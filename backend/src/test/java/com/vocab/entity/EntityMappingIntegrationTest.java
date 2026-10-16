package com.vocab.entity;

import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.UserWordBookMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.mapper.WordProgressMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体 ↔ 表结构映射验证（阶段 4 闸口）。
 *
 * <p>本测试是阶段 3 发现的 {@code 1364 Field 'create_time' doesn't have a default value}
 * 风险的**回归防线**：数据库层不带时间默认值，时间戳完全依赖实体上的
 * {@code @TableField(fill = ...)} 标注。若某实体漏标 fill，本测试的插入会立刻失败。
 *
 * <p>覆盖内容：
 * <ol>
 *   <li>7 个实体都能对空表执行 {@code selectCount(null)}（验证 {@code @TableName} 列名正确）</li>
 *   <li>7 个实体都能插入 + 读回（验证反向映射与字段类型）</li>
 *   <li>自动填充生效：不显式赋值也能拿到非空 {@code createTime}/{@code updateTime}</li>
 *   <li>逻辑删除语义：{@code @TableLogic} 过滤 + {@code user_word_book} 物理删除</li>
 *   <li>唯一索引冲突能被识别（供阶段 7/8 转化为业务错误码）</li>
 *   <li>统计 XML 聚合 SQL 语法正确、可执行</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntityMappingIntegrationTest {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WordBookMapper wordBookMapper;
    @Autowired
    private WordMapper wordMapper;
    @Autowired
    private UserWordBookMapper userWordBookMapper;
    @Autowired
    private StudyRecordMapper studyRecordMapper;
    @Autowired
    private MistakeEntryMapper mistakeEntryMapper;
    @Autowired
    private WordProgressMapper wordProgressMapper;
    @Autowired
    private StatsMapper statsMapper;

    /** 直连 JDBC，用于绕过 MyBatis-Plus 的逻辑删除拦截、观察物理行。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------
    // 1. 空表查询：验证 @TableName 与列名映射
    // ---------------------------------------------------------------

    @Test
    @DisplayName("7 张表均可对空表执行 selectCount，无 SQL 语法/列名错误")
    void allTablesSelectable() {
        assertEquals(0L, userMapper.selectCount(null));
        assertEquals(0L, wordBookMapper.selectCount(null));
        assertEquals(0L, wordMapper.selectCount(null));
        assertEquals(0L, userWordBookMapper.selectCount(null));
        assertEquals(0L, studyRecordMapper.selectCount(null));
        assertEquals(0L, mistakeEntryMapper.selectCount(null));
        assertEquals(0L, wordProgressMapper.selectCount(null));
    }

    // ---------------------------------------------------------------
    // 2 + 3. 插入读回 + 自动填充
    // ---------------------------------------------------------------

    @Test
    @DisplayName("User 插入读回：自动填充 createTime/updateTime，且字段完整往返")
    void userInsertAndReadBack() {
        User user = new User();
        user.setUsername("zhangsan");
        user.setPasswordHash("$2a$10$fakeBCryptHashValueForMappingTest");
        user.setNickname("张三");
        user.setAvatar("avatar_1");
        user.setRole("USER");
        user.setStatus(1);
        // 刻意不设 createTime / updateTime —— 由 MetaObjectHandler 填充。
        // 若实体漏标 @TableField(fill=...)，此处会抛 1364（create_time 无默认值）。

        assertEquals(1, userMapper.insert(user));
        assertNotNull(user.getId(), "自增主键必须回填");
        assertNotNull(user.getCreateTime(), "createTime 必须被自动填充（fill=INSERT）");
        assertNotNull(user.getUpdateTime(), "updateTime 必须被自动填充（fill=INSERT_UPDATE）");

        User loaded = userMapper.selectById(user.getId());
        assertEquals("zhangsan", loaded.getUsername());
        assertEquals("$2a$10$fakeBCryptHashValueForMappingTest", loaded.getPasswordHash());
        assertEquals("张三", loaded.getNickname());
        assertEquals("avatar_1", loaded.getAvatar());
        assertEquals("USER", loaded.getRole());
        assertEquals(1, loaded.getStatus());
        assertEquals(0, loaded.getDeleted(), "逻辑删除标志默认 0");
        assertNull(loaded.getOpenid(), "账号密码用户的 openid 为空");
    }

    @Test
    @DisplayName("形如 user 表支持多行 username=NULL（微信用户场景）")
    void multipleNullUsernamesAllowed() {
        User first = newUserWithOpenid("openid-a");
        User second = newUserWithOpenid("openid-b");
        assertEquals(1, userMapper.insert(first));
        assertEquals(1, userMapper.insert(second));
        assertNull(first.getUsername());
        assertNull(second.getUsername());
    }

    @Test
    @DisplayName("WordBook 插入读回：wordCount 与 category 往返正确")
    void wordBookInsertAndReadBack() {
        WordBook book = new WordBook();
        book.setName("四级核心词汇");
        book.setDescription("CET4 核心大纲词汇");
        book.setCategory("CET4");
        book.setWordCount(7508);

        assertEquals(1, wordBookMapper.insert(book));
        assertNotNull(book.getCreateTime());

        WordBook loaded = wordBookMapper.selectById(book.getId());
        assertEquals("四级核心词汇", loaded.getName());
        assertEquals("CET4", loaded.getCategory());
        assertEquals(7508, loaded.getWordCount());
    }

    @Test
    @DisplayName("Word 插入读回：bookId 关联与外键列写入正确")
    void wordInsertAndReadBack() {
        Long bookId = insertBook("考研核心词汇", "KAOYAN");

        Word word = new Word();
        word.setBookId(bookId);
        word.setWord("abandon");
        word.setPhonetic("əˈbændən");
        word.setTranslation("v. 放弃；抛弃");
        word.setExample("He decided to abandon the plan.");
        // definition / audioUrl 一期留空，显式为 null

        assertEquals(1, wordMapper.insert(word));
        assertNotNull(word.getCreateTime());

        Word loaded = wordMapper.selectById(word.getId());
        assertEquals(bookId, loaded.getBookId());
        assertEquals("abandon", loaded.getWord());
        assertEquals("əˈbændən", loaded.getPhonetic());
        assertEquals("v. 放弃；抛弃", loaded.getTranslation());
        assertEquals("He decided to abandon the plan.", loaded.getExample());
        assertNull(loaded.getDefinition());
        assertNull(loaded.getAudioUrl());
    }

    @Test
    @DisplayName("UserWordBook 插入读回：中间表无 deleted、适用物理删除")
    void userWordBookInsertAndReadBack() {
        Long userId = insertUser("selectbook-user");
        Long bookId = insertBook("六级核心词汇", "CET6");

        UserWordBook relation = new UserWordBook();
        relation.setUserId(userId);
        relation.setBookId(bookId);

        assertEquals(1, userWordBookMapper.insert(relation));
        assertNotNull(relation.getCreateTime(), "中间表同样由 MetaObjectHandler 填充时间");

        UserWordBook loaded = userWordBookMapper.selectById(relation.getId());
        assertEquals(userId, loaded.getUserId());
        assertEquals(bookId, loaded.getBookId());
    }

    @Test
    @DisplayName("StudyRecord 插入读回：DATE 列与 checkInDate 的 null 语义正确")
    void studyRecordInsertAndReadBack() {
        Long userId = insertUser("study-user");
        Long bookId = insertBook("四级核心词汇", "CET4");

        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(bookId);
        record.setWordId(101L);
        record.setStudyType("STUDY");
        record.setStudyDate(LocalDate.of(2026, 10, 16));
        record.setStreakDays(1);
        // checkInDate / correct 尚未打卡/未复习，保持 null

        assertEquals(1, studyRecordMapper.insert(record));

        StudyRecord loaded = studyRecordMapper.selectById(record.getId());
        assertEquals(LocalDate.of(2026, 10, 16), loaded.getStudyDate());
        assertNull(loaded.getCheckInDate(), "未打卡时 checkInDate 必须为 null");
        assertNull(loaded.getCorrect(), "学习（非复习）时 correct 必须为 null");
        assertEquals(1, loaded.getStreakDays());
    }

    @Test
    @DisplayName("MistakeEntry 插入读回：wrongCount/mastered 往返正确")
    void mistakeEntryInsertAndReadBack() {
        Long userId = insertUser("mistake-user");

        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(userId);
        entry.setWordId(101L);
        entry.setSource("STUDY");
        entry.setWrongCount(3);
        entry.setMastered(0);

        assertEquals(1, mistakeEntryMapper.insert(entry));

        MistakeEntry loaded = mistakeEntryMapper.selectById(entry.getId());
        assertEquals("STUDY", loaded.getSource());
        assertEquals(3, loaded.getWrongCount());
        assertEquals(0, loaded.getMastered());
    }

    @Test
    @DisplayName("WordProgress 插入读回：stage/nextReviewDate/learned 往返正确")
    void wordProgressInsertAndReadBack() {
        Long userId = insertUser("progress-user");
        Long bookId = insertBook("四级核心词汇", "CET4");

        WordProgress progress = new WordProgress();
        progress.setUserId(userId);
        progress.setBookId(bookId);
        progress.setWordId(101L);
        progress.setStage(1);
        progress.setNextReviewDate(LocalDate.of(2026, 10, 17));
        progress.setLearned(1);

        assertEquals(1, wordProgressMapper.insert(progress));

        WordProgress loaded = wordProgressMapper.selectById(progress.getId());
        assertEquals(1, loaded.getStage());
        assertEquals(LocalDate.of(2026, 10, 17), loaded.getNextReviewDate());
        assertEquals(1, loaded.getLearned());
        assertEquals(0, loaded.getWrongCount());
    }

    // ---------------------------------------------------------------
    // 4. 逻辑删除 vs 物理删除
    // ---------------------------------------------------------------

    @Test
    @DisplayName("User 逻辑删除：deleteById 后 selectById 返回 null，但物理行仍在")
    void userLogicalDeleteKeepsRow() {
        Long userId = insertUser("softdelete-user");

        assertEquals(1, userMapper.deleteById(userId));
        assertNull(userMapper.selectById(userId), "@TableLogic 必须过滤掉已删除行");
        assertEquals(0L, userMapper.selectCount(null), "已删除行不应出现在正常查询中");

        // 用直连 JDBC 绕过 MyBatis-Plus 的逻辑删除拦截，确认物理行仍在（deleted=1）
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM `user` WHERE id = ?", Integer.class, userId);
        assertNotNull(deletedFlag, "逻辑删除的行必须仍物理存在于表中");
        assertEquals(1, deletedFlag, "逻辑删除标志应为 1");
    }

    @Test
    @DisplayName("UserWordBook 物理删除：deleteById 后行被真正移除（无 deleted 列）")
    void userWordBookIsPhysicallyDeleted() {
        Long userId = insertUser("physicaldelete-user");
        Long bookId = insertBook("四级核心词汇", "CET4");

        UserWordBook relation = new UserWordBook();
        relation.setUserId(userId);
        relation.setBookId(bookId);
        assertEquals(1, userWordBookMapper.insert(relation));

        assertEquals(1, userWordBookMapper.deleteById(relation.getId()));
        assertNull(userWordBookMapper.selectById(relation.getId()),
                "中间表为物理删除，行必须真正消失");
    }

    // ---------------------------------------------------------------
    // 5. 唯一索引冲突可被识别（阶段 7/8 据此转换为业务错误码）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("重复 username 触发唯一索引冲突（阶段 5 据此返回 1001）")
    void duplicateUsernameRejected() {
        insertUser("dup-user");
        assertThrows(Exception.class, () -> insertUser("dup-user"),
                "uk_username 必须拒绝重复用户名");
    }

    @Test
    @DisplayName("重复选同一词书触发唯一索引冲突（阶段 6 据此返回 2003）")
    void duplicateBookSelectionRejected() {
        Long userId = insertUser("dup-book-user");
        Long bookId = insertBook("四级核心词汇", "CET4");

        UserWordBook first = new UserWordBook();
        first.setUserId(userId);
        first.setBookId(bookId);
        assertEquals(1, userWordBookMapper.insert(first));

        UserWordBook second = new UserWordBook();
        second.setUserId(userId);
        second.setBookId(bookId);
        assertThrows(Exception.class, () -> userWordBookMapper.insert(second),
                "uk_user_word_book_user_book 必须拒绝重复选书");
    }

    @Test
    @DisplayName("同一用户同词同日同类型重复学习记录被唯一索引拦截（并发打卡防线）")
    void duplicateStudyRecordRejected() {
        Long userId = insertUser("dup-record-user");
        Long bookId = insertBook("四级核心词汇", "CET4");
        LocalDate day = LocalDate.of(2026, 10, 16);

        assertEquals(1, studyRecordMapper.insert(studyRecord(userId, bookId, 101L, day)));

        StudyRecord duplicate = studyRecord(userId, bookId, 101L, day);
        assertThrows(Exception.class, () -> studyRecordMapper.insert(duplicate),
                "uk_study_record_user_word_date_type 必须拦截重复打卡行");
    }

    // ---------------------------------------------------------------
    // 6. 统计 XML 聚合 SQL 可执行（验证 XML 语法与列名，非结果正确性）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("StatsMapper 聚合查询可执行，且逻辑删除条件已手工补齐")
    void statsAggregationsExecutable() {
        Long userId = insertUser("stats-user");
        Long bookId = insertBook("四级核心词汇", "CET4");

        StudyRecord study = studyRecord(userId, bookId, 101L, LocalDate.now());
        study.setCheckInDate(LocalDate.now());
        study.setStreakDays(1);
        assertEquals(1, studyRecordMapper.insert(study));

        WordProgress progress = new WordProgress();
        progress.setUserId(userId);
        progress.setBookId(bookId);
        progress.setWordId(101L);
        progress.setStage(1);
        progress.setLearned(1);
        assertEquals(1, wordProgressMapper.insert(progress));

        MistakeEntry mistake = new MistakeEntry();
        mistake.setUserId(userId);
        mistake.setWordId(101L);
        mistake.setSource("STUDY");
        mistake.setWrongCount(1);
        mistake.setMastered(0);
        assertEquals(1, mistakeEntryMapper.insert(mistake));

        // 以下 8 个查询若 XML 有列名/语法/参数错误，会在此抛异常
        assertEquals(1, statsMapper.selectHeatmap(userId, LocalDate.now().minusDays(84)).size());
        assertEquals(1, statsMapper.selectHeatmap(userId, LocalDate.now().minusDays(84)).get(0).getWordCount(),
                "当日仅 1 条打卡学习记录");
        assertEquals(1, statsMapper.selectMasteryDaily(userId, LocalDate.now().minusDays(30)).size());
        assertEquals(0, statsMapper.countMasteredMistakes(userId));
        assertEquals(1, statsMapper.countTotalMistakes(userId));
        assertEquals(1, statsMapper.countCheckInDays(userId));
        assertEquals(1, statsMapper.countLearnedWords(userId));
        assertEquals(1, statsMapper.countLearnedWordsInBook(userId, bookId));
    }

    @Test
    @DisplayName("统计聚合在无数据时必须返回空集而非报错（空态由 Service 补零）")
    void statsAggregationsOnEmptyData() {
        Long userId = insertUser("empty-stats-user");
        assertTrue(statsMapper.selectHeatmap(userId, LocalDate.now().minusDays(84)).isEmpty());
        assertTrue(statsMapper.selectMasteryDaily(userId, LocalDate.now().minusDays(30)).isEmpty());
        assertEquals(0, statsMapper.countMasteredMistakes(userId));
        assertEquals(0, statsMapper.countTotalMistakes(userId));
        assertEquals(0, statsMapper.countCheckInDays(userId));
        assertEquals(0, statsMapper.countLearnedWords(userId));
    }

    @Test
    @DisplayName("统计聚合必须排除逻辑删除的记录（XML 手工 deleted=0 生效）")
    void statsAggregationsRespectLogicalDelete() {
        Long userId = insertUser("stats-deleted-user");
        Long bookId = insertBook("四级核心词汇", "CET4");

        StudyRecord study = studyRecord(userId, bookId, 101L, LocalDate.now());
        study.setCheckInDate(LocalDate.now());
        assertEquals(1, studyRecordMapper.insert(study));
        assertEquals(1, statsMapper.countCheckInDays(userId));

        studyRecordMapper.deleteById(study.getId());
        assertEquals(0, statsMapper.countCheckInDays(userId),
                "手写 SQL 的 deleted=0 条件必须生效，否则统计会计入已删除记录");
    }

    // ---------------------------------------------------------------
    // 6b. 掌握度聚合的 mistakeCount 关联值正确性
    //     （仅断言 size() 无法发现 JOIN 写错——必须断言到具体数值）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("掌握度曲线：同日学习+复习+错题应正确汇总到同一行，且 mistakeCount 取自错题表关联")
    void masteryDailyAggregatesCountsCorrectly() {
        Long userId = insertUser("mastery-user");
        Long bookId = insertBook("考研词汇", "KAOYAN");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // 今天：2 条 STUDY + 1 条 REVIEW（3 个不同单词，避开唯一索引）
        assertEquals(1, studyRecordMapper.insert(studyRecord(userId, bookId, 201L, today)));
        assertEquals(1, studyRecordMapper.insert(studyRecord(userId, bookId, 202L, today)));
        StudyRecord review = studyRecord(userId, bookId, 203L, today);
        review.setStudyType("REVIEW");
        assertEquals(1, studyRecordMapper.insert(review));

        // 昨天：1 条 STUDY
        assertEquals(1, studyRecordMapper.insert(studyRecord(userId, bookId, 204L, yesterday)));

        // 今天新增 2 条错题（create_time 由 MetaObjectHandler 填为今天）
        for (Long wordId : new Long[]{201L, 202L}) {
            MistakeEntry mistake = new MistakeEntry();
            mistake.setUserId(userId);
            mistake.setWordId(wordId);
            mistake.setSource("STUDY");
            mistake.setWrongCount(1);
            mistake.setMastered(0);
            assertEquals(1, mistakeEntryMapper.insert(mistake));
        }

        var daily = statsMapper.selectMasteryDaily(userId, today.minusDays(30));
        assertEquals(2, daily.size(), "两天的数据应聚合成 2 行（每天一行，不因 JOIN 分裂）");

        // 昨天：只有 1 条 STUDY，无复习、无错题
        assertEquals(yesterday, daily.get(0).getDate());
        assertEquals(1, daily.get(0).getLearnedCount(), "昨天 learnedCount");
        assertEquals(0, daily.get(0).getReviewCount(), "昨天 reviewCount");
        assertEquals(0, daily.get(0).getMistakeCount(), "昨天无错题，LEFT JOIN 应补 0 而非 NULL");

        // 今天：STUDY/REVIEW 分别计数，错题数来自 mistake_entry 关联
        assertEquals(today, daily.get(1).getDate());
        assertEquals(2, daily.get(1).getLearnedCount(), "今天 learnedCount");
        assertEquals(1, daily.get(1).getReviewCount(), "今天 reviewCount");
        assertEquals(2, daily.get(1).getMistakeCount(), "今天 mistakeCount 应等于当日错题数 2");
    }

    @Test
    @DisplayName("掌握度曲线：仅 REVIEW 无 STUDY 的日期也必须出现在结果中（单日可同时含两类）")
    void masteryDailyCountsReviewOnlyDay() {
        Long userId = insertUser("review-only-user");
        Long bookId = insertBook("六级词汇", "CET6");
        LocalDate today = LocalDate.now();

        StudyRecord review = studyRecord(userId, bookId, 301L, today);
        review.setStudyType("REVIEW");
        assertEquals(1, studyRecordMapper.insert(review));

        var daily = statsMapper.selectMasteryDaily(userId, today.minusDays(30));
        assertEquals(1, daily.size());
        assertEquals(0, daily.get(0).getLearnedCount(), "仅复习日 learnedCount 应为 0");
        assertEquals(1, daily.get(0).getReviewCount());
        assertEquals(0, daily.get(0).getMistakeCount());
    }

    // ---------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------

    private User newUserWithOpenid(String openid) {
        User user = new User();
        user.setOpenid(openid);
        user.setNickname("微信用户");
        user.setAvatar("avatar_1");
        user.setRole("USER");
        user.setStatus(1);
        return user;
    }

    private Long insertUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash("$2a$10$fakeBCryptHashValueForMappingTest");
        user.setNickname(username);
        user.setAvatar("avatar_1");
        user.setRole("USER");
        user.setStatus(1);
        assertEquals(1, userMapper.insert(user));
        return user.getId();
    }

    private Long insertBook(String name, String category) {
        WordBook book = new WordBook();
        book.setName(name);
        book.setCategory(category);
        book.setWordCount(0);
        assertEquals(1, wordBookMapper.insert(book));
        return book.getId();
    }

    private StudyRecord studyRecord(Long userId, Long bookId, Long wordId, LocalDate day) {
        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(bookId);
        record.setWordId(wordId);
        record.setStudyType("STUDY");
        record.setStudyDate(day);
        record.setStreakDays(1);
        return record;
    }
}
