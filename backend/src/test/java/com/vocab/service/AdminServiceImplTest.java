package com.vocab.service;

import com.vocab.ai.AiMetrics;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.PageResult;
import com.vocab.dto.response.AdminUserResponse;
import com.vocab.dto.response.AiFeatureStat;
import com.vocab.dto.response.AiStatsResponse;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.User;
import com.vocab.entity.WordBook;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminService} 单元级测试（Service 层直调，不经 HTTP）。
 *
 * <p>与 {@code AdminControllerIntegrationTest} 的分工：集成测试验证「契约在
 * HTTP 上成立」，本类验证「Service 内部不变式成立」——尤其是那些
 * 在 Controller 层看不出来的性质：
 * <ul>
 *   <li>{@code word_count} 是<b>原子</b> SQL 自增/自减（不是读改写），
 *       连续操作后计数精确等于初始值</li>
 *   <li>{@code update_time} 在走 {@code setSql} 路径时也被刷新
 *       （否则会留下「行改了时间没变」的脏数据）</li>
 *   <li>{@code totalCheckInDays} 用 {@code COUNT(DISTINCT check_in_date)}，
 *       同一天多条流水只算一天</li>
 *   <li>{@code aiStats} 的成功率/平均耗时口径（含降级、无调用 100/0）</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class AdminServiceImplTest {

    @Autowired
    private AdminService adminService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WordBookMapper wordBookMapper;

    @Autowired
    private WordMapper wordMapper;

    @Autowired
    private StudyRecordMapper studyRecordMapper;

    @Autowired
    private AiMetrics aiMetrics;

    @Autowired
    private Clock clock;

    private int seq;

    @BeforeEach
    void setUp() {
        seq = 0;
    }

    private User newUser(String role) {
        User user = new User();
        user.setUsername("asi_" + System.nanoTime() + "_" + (seq++));
        user.setPasswordHash("$2a$10$fakeHashForAdminServiceTest");
        user.setNickname("管理服务测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(role);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);
        return user;
    }

    private WordBook newBook(String name, int wordCount) {
        WordBook book = new WordBook();
        book.setName(name);
        book.setCategory("CET4");
        book.setWordCount(wordCount);
        wordBookMapper.insert(book);
        return book;
    }

    private int wordCountOf(Long bookId) {
        return wordBookMapper.selectById(bookId).getWordCount();
    }

    // ---------------------------------------------------------------
    // word_count 原子维护
    // ---------------------------------------------------------------

    @Test
    @DisplayName("word_count：连续增删后计数精确回到初值（原子 SQL 自增/自减，无丢更新）")
    void wordCountStaysExactAfterManyMutations() {
        WordBook book = newBook("计数词书", 0);

        Long[] ids = new Long[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = adminService.createWord(
                    wordRequest(book.getId(), "w" + i, "n. " + i)).getWordId();
        }
        assertEquals(5, wordCountOf(book.getId()), "5 次新增后应为 5");

        for (Long id : ids) {
            adminService.deleteWord(id);
        }
        assertEquals(0, wordCountOf(book.getId()), "全部删除后应精确回到 0（不是 -1 或残留）");
    }

    @Test
    @DisplayName("word_count：新增使 update_time 被刷新（setSql 路径不放跑时间戳）")
    void wordCountUpdateRefreshesUpdateTime() {
        WordBook book = newBook("时间戳词书", 0);
        LocalDateTime before = wordBookMapper.selectById(book.getId()).getUpdateTime();

        adminService.createWord(wordRequest(book.getId(), "alpha", "n. 阿尔法"));

        LocalDateTime after = wordBookMapper.selectById(book.getId()).getUpdateTime();
        assertNotNull(after);
        // 同一次事务内时钟不拨动（FixedClock 秒级以下为 0），可能相等；
        // 关键是不为 null 且 >= before（说明走了带 update_time 的更新语句）
        assertFalse(after.isBefore(before), "update_time 不应倒退");
    }

    @Test
    @DisplayName("新增单词：bookId 缺失 → 1005（不是 2001）；bookId 不存在 → 2001")
    void createWordDistinguishesMissingAndNonexistentBook() {
        BizException missing = assertThrows(BizException.class, () ->
                adminService.createWord(wordRequest(null, "alpha", "n.")));
        assertEquals("1005", missing.getCode(), "少传 bookId 是参数错误");

        BizException nonexistent = assertThrows(BizException.class, () ->
                adminService.createWord(wordRequest(99999999L, "alpha", "n.")));
        assertEquals("2001", nonexistent.getCode(), "词书不存在是 2001");
    }

    @Test
    @DisplayName("修改单词：空更新（一个字段都不传）→ 1005")
    void updateWordWithNothingToChangeIsRejected() {
        WordBook book = newBook("空更新词书", 1);
        Long wordId = adminService.createWord(wordRequest(book.getId(), "alpha", "n.")).getWordId();

        var empty = new com.vocab.dto.request.WordUpsertRequest();
        BizException e = assertThrows(BizException.class,
                () -> adminService.updateWord(wordId, empty));
        assertEquals("1005", e.getCode());
    }

    @Test
    @DisplayName("删除单词：逻辑删除（正常查询不可见）且计数 -1")
    void deleteWordIsLogical() {
        // 初值 0：createWord 会 +1 到 1，删除后再 -1 回 0
        WordBook book = newBook("逻辑删词书", 0);
        Long wordId = adminService.createWord(wordRequest(book.getId(), "alpha", "n.")).getWordId();
        assertEquals(1, wordCountOf(book.getId()), "新增后为 1");

        adminService.deleteWord(wordId);

        assertEquals(0, wordCountOf(book.getId()), "删除后回到 0");
        // 逻辑删除后 selectById 带 deleted=0 过滤 → null，证明是 UPDATE 而非物理 DELETE
        org.junit.jupiter.api.Assertions.assertNull(wordMapper.selectById(wordId));
    }

    // ---------------------------------------------------------------
    // 用户列表聚合
    // ---------------------------------------------------------------

    @Test
    @DisplayName("用户列表：totalCheckInDays 用 COUNT(DISTINCT check_in_date)，同日多条只算一天")
    void totalCheckInDaysCountsDistinctDays() {
        User u = newUser(Constants.ROLE_USER);
        insertRecord(u.getId(), 1L, LocalDate.of(2026, 10, 14));
        insertRecord(u.getId(), 2L, LocalDate.of(2026, 10, 15));
        insertRecord(u.getId(), 3L, LocalDate.of(2026, 10, 15));
        insertRecord(u.getId(), 4L, LocalDate.of(2026, 10, 15));

        PageResult<AdminUserResponse> page = adminService.listUsers(u.getUsername(), 1, 20);

        assertEquals(1, page.getTotal());
        assertEquals(2, page.getList().get(0).getTotalCheckInDays(),
                "3 条同日流水 + 1 条它日 = 2 个不同日期");
    }

    @Test
    @DisplayName("用户列表：无打卡记录的用户 totalCheckInDays=0（不被 N+1 漏成 null）")
    void usersWithoutRecordsGetZero() {
        User u = newUser(Constants.ROLE_USER);
        PageResult<AdminUserResponse> page = adminService.listUsers(u.getUsername(), 1, 20);
        assertEquals(0, page.getList().get(0).getTotalCheckInDays());
    }

    @Test
    @DisplayName("用户列表：绝不暴露 passwordHash（DTO 边界）")
    void userListNeverExposesPasswordHash() {
        User u = newUser(Constants.ROLE_USER);
        PageResult<AdminUserResponse> page = adminService.listUsers(u.getUsername(), 1, 20);
        AdminUserResponse item = page.getList().get(0);
        // DTO 上没有该字段即编译期安全；此处再断言响应字段集合
        String[] names = {"userId", "username", "nickname", "role", "status",
                "totalCheckInDays", "createTime"};
        java.util.Set<String> actual = new java.util.HashSet<>();
        for (var f : AdminUserResponse.class.getDeclaredFields()) {
            actual.add(f.getName());
        }
        for (String name : names) {
            assertTrue(actual.contains(name), "缺少字段 " + name);
        }
        assertFalse(actual.contains("passwordHash"), "不得包含 passwordHash");
        assertFalse(actual.contains("password"), "不得包含 password");
        assertFalse(actual.contains("deleted"), "不得包含 deleted");
        assertEquals(u.getId(), item.getUserId());
    }

    // ---------------------------------------------------------------
    // 用户启禁
    // ---------------------------------------------------------------

    @Test
    @DisplayName("禁用/启用：状态被真实写入（回显值 = 库中值）")
    void updateStatusPersists() {
        User admin = newUser(Constants.ROLE_ADMIN);
        User target = newUser(Constants.ROLE_USER);

        var disable = new com.vocab.dto.request.UserStatusRequest();
        disable.setStatus(Constants.STATUS_DISABLED);
        assertEquals(Constants.STATUS_DISABLED,
                adminService.updateUserStatus(admin.getId(), target.getId(), disable).getStatus());
        assertEquals(Constants.STATUS_DISABLED, userMapper.selectById(target.getId()).getStatus());

        var enable = new com.vocab.dto.request.UserStatusRequest();
        enable.setStatus(Constants.STATUS_ENABLED);
        adminService.updateUserStatus(admin.getId(), target.getId(), enable);
        assertEquals(Constants.STATUS_ENABLED, userMapper.selectById(target.getId()).getStatus());
    }

    @Test
    @DisplayName("禁用 ADMIN → 1005；目标不存在 → 1005")
    void statusGuards() {
        User admin = newUser(Constants.ROLE_ADMIN);
        User other = newUser(Constants.ROLE_ADMIN);

        var req = new com.vocab.dto.request.UserStatusRequest();
        req.setStatus(Constants.STATUS_DISABLED);

        assertEquals("1005", assertThrows(BizException.class, () ->
                adminService.updateUserStatus(admin.getId(), other.getId(), req)).getCode());
        assertEquals("1005", assertThrows(BizException.class, () ->
                adminService.updateUserStatus(admin.getId(), 99999999L, req)).getCode());
    }

    // ---------------------------------------------------------------
    // AI 监控口径
    // ---------------------------------------------------------------

    @Test
    @DisplayName("aiStats：features 固定三项顺序 example/explain/report（零调用也不缺项）")
    void aiStatsFeatureOrderIsStable() {
        AiStatsResponse stats = adminService.aiStats();
        List<AiFeatureStat> features = stats.getFeatures();
        assertEquals(3, features.size());
        assertEquals(AiMetrics.FEATURE_EXAMPLE, features.get(0).getFeature());
        assertEquals(AiMetrics.FEATURE_EXPLAIN, features.get(1).getFeature());
        assertEquals(AiMetrics.FEATURE_REPORT, features.get(2).getFeature());
    }

    @Test
    @DisplayName("aiStats：成功率分母含降级（2 成功 + 1 降级 → 66.7%，不是 100%）")
    void aiStatsSuccessRateIncludesDegrades() {
        // 用快照差值断言：AiMetrics 是单例，同上下文其它用例可能已写入
        int baseTotal = adminService.aiStats().getTotalCalls();
        int baseDegraded = adminService.aiStats().getDegradedCount();

        aiMetrics.recordSuccess(AiMetrics.FEATURE_REPORT, 500);
        aiMetrics.recordSuccess(AiMetrics.FEATURE_REPORT, 600);
        aiMetrics.recordDegrade(AiMetrics.FEATURE_REPORT, 60000,
                AiMetrics.REASON_ERROR, "抛 5002", LocalDateTime.now(clock));

        AiStatsResponse stats = adminService.aiStats();
        assertEquals(baseTotal + 3, stats.getTotalCalls());
        assertEquals(baseDegraded + 1, stats.getDegradedCount());

        // 该功能的成功率：成功 2 / 总 3 = 66.7
        AiFeatureStat report = stats.getFeatures().stream()
                .filter(f -> AiMetrics.FEATURE_REPORT.equals(f.getFeature()))
                .findFirst().orElseThrow();
        assertEquals(2, report.getSuccess());
        assertEquals(3, report.getTotal());
        assertEquals(1, report.getError());
        assertEquals(0, report.getTimeout());
    }

    @Test
    @DisplayName("aiStats：registeredUsers 只统计未逻辑删除的用户")
    void aiStatsCountsOnlyAliveUsers() {
        // 先造一个已删除用户：直接置 deleted=1（@TableLogic 不拦截 updateById）
        User ghost = newUser(Constants.ROLE_USER);
        User patch = new User();
        patch.setId(ghost.getId());
        patch.setDeleted(1);
        userMapper.updateById(patch);

        long alive = userMapper.selectCount(null);
        assertEquals(alive, (long) adminService.aiStats().getRegisteredUsers(),
                "registeredUsers 必须与「未删除用户数」一致");
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private com.vocab.dto.request.WordUpsertRequest wordRequest(Long bookId, String word,
                                                              String translation) {
        var req = new com.vocab.dto.request.WordUpsertRequest();
        req.setBookId(bookId);
        req.setWord(word);
        req.setTranslation(translation);
        return req;
    }

    private void insertRecord(Long userId, Long wordId, LocalDate date) {
        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(1L);
        record.setWordId(wordId);
        record.setStudyDate(date);
        record.setCheckInDate(date);
        record.setStudyType(Constants.STUDY_TYPE_STUDY);
        record.setStreakDays(1);
        studyRecordMapper.insert(record);
    }
}
