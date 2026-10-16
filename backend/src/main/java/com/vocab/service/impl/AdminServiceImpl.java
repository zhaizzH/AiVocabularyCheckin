package com.vocab.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vocab.ai.AiMetrics;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.common.PageResult;
import com.vocab.common.Pages;
import com.vocab.common.SuccessRate;
import com.vocab.dto.request.UserStatusRequest;
import com.vocab.dto.request.WordUpsertRequest;
import com.vocab.dto.response.AdminUserResponse;
import com.vocab.dto.response.AiFeatureStat;
import com.vocab.dto.response.AiStatsResponse;
import com.vocab.dto.response.DegradeEventView;
import com.vocab.dto.response.UserCheckInCount;
import com.vocab.dto.response.UserStatusResponse;
import com.vocab.dto.response.WordDeleteResponse;
import com.vocab.dto.response.WordResponse;
import com.vocab.entity.User;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.service.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端服务实现。
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>绝不返回实体</b>——所有出口都是 DTO（{@code AdminUserResponse}
 *       不含 {@code passwordHash}/{@code deleted}）。</li>
 *   <li><b>列表必分页</b>——{@code listWords} / {@code listUsers} 都走
 *       MyBatis-Plus {@code Page}（database-guidelines.md：
 *       「管理端接口也不例外」）。</li>
 *   <li><b>聚合在 SQL 内完成</b>——用户列表的 {@code totalCheckInDays}
 *       用一次 {@code GROUP BY user_id} 批量取，不在 Java 里逐用户 COUNT。</li>
 * </ol>
 *
 * <p><b>词书计数一致性</b>：{@code word_count} 是<b>冗余列</b>
 * （真值可由 {@code COUNT(*)} 推出）。增删单词时用
 * {@code word_count = word_count ± 1} 的 SQL 原子自增/自减，而不是
 * 「先 SELECT 再写回」——后者在并发下会丢更新（两个管理员同时删词，
 * 各自读到旧值 100，各写回 99，实际应删了两个词=98）。
 * 副作用是计数可能与真实行数漂移，故导入路径末尾用
 * {@code correctWordCount} 以 COUNT 校准（见 {@code WordImportServiceImpl}）。
 */
@Service
public class AdminServiceImpl implements AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceImpl.class);

    private final WordMapper wordMapper;
    private final WordBookMapper wordBookMapper;
    private final UserMapper userMapper;
    private final StatsMapper statsMapper;
    private final AiMetrics aiMetrics;

    /**
     * 用于在绕过 ORM 自动填充时显式写入 {@code update_time}。
     *
     * <p>{@code word_count} 的原子自增/自减走 {@code setSql}，不是
     * {@code updateById}，因此 {@code MetaObjectHandler} 的 UPDATE 填充不会触发
     * （自动填充只在 {@code entity != null} 时生效）。为不留下「更新了行但
     * {@code update_time} 未变」的脏数据，此处显式传入时间戳。
     *
     * <p>刻意不用 SQL 的 {@code NOW()}：与项目红线「时间边界由 Java 传入」
     * 一致——注入 {@link Clock} 后测试可固定时间，且 SQL 与方言无关。
     */
    private final Clock clock;

    public AdminServiceImpl(WordMapper wordMapper,
                            WordBookMapper wordBookMapper,
                            UserMapper userMapper,
                            StatsMapper statsMapper,
                            AiMetrics aiMetrics,
                            Clock clock) {
        this.wordMapper = wordMapper;
        this.wordBookMapper = wordBookMapper;
        this.userMapper = userMapper;
        this.statsMapper = statsMapper;
        this.aiMetrics = aiMetrics;
        this.clock = clock;
    }

    // ---------------------------------------------------------------
    // 单词管理
    // ---------------------------------------------------------------

    @Override
    public PageResult<WordResponse> listWords(Long bookId, String keyword, long page, long size) {
        IPage<Word> wordPage = wordMapper.selectPage(
                new Page<>(Pages.normalizePage(page), Pages.normalizeSize(size)),
                Wrappers.<Word>lambdaQuery()
                        .eq(bookId != null, Word::getBookId, bookId)
                        .like(StringUtils.hasText(keyword), Word::getWord,
                                keyword == null ? null : keyword.trim())
                        .orderByAsc(Word::getId));
        // 实体 → DTO 在分页边界完成（禁止把实体序列化出去）
        return PageResult.of(wordPage, this::toWordResponse);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WordResponse createWord(WordUpsertRequest request) {
        // 必填缺失 → 1005（参数错误）；填了但不存在 → 2001（词书不存在）。
        // 两者分开：前端据此区分「少传了字段」与「词书已被删」。
        if (request.getBookId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "bookId 不能为空");
        }
        requireBook(request.getBookId());
        String word = requireWordText(request.getWord());

        Word entity = new Word();
        entity.setBookId(request.getBookId());
        entity.setWord(word);
        entity.setPhonetic(blankToEmpty(request.getPhonetic()));
        entity.setTranslation(blankToEmpty(request.getTranslation()));
        // definition / audioUrl 一期留空（需求清单 §7.4/§7.5），与导入路径同一口径
        entity.setExample(blankToEmpty(request.getExample()));
        wordMapper.insert(entity);

        adjustWordCount(entity.getBookId(), 1);
        log.info("管理端新增单词 wordId={} bookId={} word={}",
                entity.getId(), entity.getBookId(), word);
        // 回读保证 createTime/updateTime 等由 MetaObjectHandler 填充的字段可见
        return toWordResponse(wordMapper.selectById(entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WordResponse updateWord(Long wordId, WordUpsertRequest request) {
        Word existing = wordMapper.selectById(wordId);
        if (existing == null) {
            // 「单词不存在」在本契约中走 1005（错误码表无 2005，见 §0）
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "单词不存在：" + wordId);
        }

        Word update = new Word();
        update.setId(wordId);

        // 字段均可选：只有显式传入才写（用 null 判断，区别于「传空串清空」）
        boolean anyField = false;
        if (request.getWord() != null) {
            update.setWord(requireWordText(request.getWord()));
            anyField = true;
        }
        if (request.getPhonetic() != null) {
            update.setPhonetic(blankToEmpty(request.getPhonetic()));
            anyField = true;
        }
        if (request.getTranslation() != null) {
            update.setTranslation(blankToEmpty(request.getTranslation()));
            anyField = true;
        }
        if (request.getExample() != null) {
            update.setExample(blankToEmpty(request.getExample()));
            anyField = true;
        }

        // 换书：word_count 必须从旧书搬到新书，否则两本书的计数都会错
        Long newBookId = request.getBookId();
        if (newBookId != null && !newBookId.equals(existing.getBookId())) {
            requireBook(newBookId);
            update.setBookId(newBookId);
            anyField = true;
        }

        if (!anyField) {
            // 契约 §8.4「字段均可选」不意味着「可以一个都不传」：
            // 空更新是无意义的写库，且会让 update_time 在无实际变更时被刷新。
            // 与 UpdateProfileRequest 的「至少传一项」同一套判断。
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "至少需要修改一个字段");
        }

        if (newBookId != null && !newBookId.equals(existing.getBookId())) {
            adjustWordCount(existing.getBookId(), -1);
            adjustWordCount(newBookId, 1);
        }

        wordMapper.updateById(update);
        log.info("管理端修改单词 wordId={} bookId={}", wordId, update.getBookId());
        return toWordResponse(wordMapper.selectById(wordId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WordDeleteResponse deleteWord(Long wordId) {
        Word existing = wordMapper.selectById(wordId);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "单词不存在：" + wordId);
        }
        // @TableLogic 触发 UPDATE deleted = 1，不是物理 DELETE
        wordMapper.deleteById(wordId);
        adjustWordCount(existing.getBookId(), -1);
        log.info("管理端删除单词 wordId={} bookId={} word={}",
                wordId, existing.getBookId(), existing.getWord());
        return new WordDeleteResponse(wordId, true);
    }

    // ---------------------------------------------------------------
    // 用户管理
    // ---------------------------------------------------------------

    @Override
    public PageResult<AdminUserResponse> listUsers(String keyword, long page, long size) {
        IPage<User> userPage = userMapper.selectPage(
                new Page<>(Pages.normalizePage(page), Pages.normalizeSize(size)),
                Wrappers.<User>lambdaQuery()
                        .like(StringUtils.hasText(keyword), User::getUsername,
                                keyword == null ? null : keyword.trim())
                        .orderByAsc(User::getId));

        List<User> users = userPage.getRecords();

        // 一次 GROUP BY 拿整页用户的打卡天数，避免逐用户 COUNT（N+1）。
        // 空页时短路，不发出 `IN ()`（空集合是语法错误）。
        Map<Long, Integer> checkInMap = new HashMap<>();
        if (users != null && !users.isEmpty()) {
            List<Long> userIds = new ArrayList<>(users.size());
            for (User user : users) {
                userIds.add(user.getId());
            }
            List<UserCheckInCount> counts = statsMapper.countCheckInDaysByUsers(userIds);
            if (counts != null) {
                for (UserCheckInCount count : counts) {
                    if (count.getUserId() != null) {
                        checkInMap.put(count.getUserId(), count.getCheckInDays());
                    }
                }
            }
        }

        // 实体 → DTO 在分页边界完成；无打卡记录的用户兜底为 0
        return PageResult.of(userPage,
                user -> toAdminUserResponse(user, checkInMap.getOrDefault(user.getId(), 0)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserStatusResponse updateUserStatus(Long operatorId, Long userId,
                                               UserStatusRequest request) {
        Integer status = request.getStatus();
        if (status == null
                || (status != Constants.STATUS_ENABLED && status != Constants.STATUS_DISABLED)) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "status 仅支持 0（禁用）或 1（启用）");
        }

        User target = userId == null ? null : userMapper.selectById(userId);
        if (target == null) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "用户不存在：" + userId);
        }
        if (Constants.ROLE_ADMIN.equals(target.getRole())) {
            // 契约 §8.7：不允许禁用 ADMIN 账号 → 1005。
            // 这里对「启用 ADMIN」也一并拒绝：启用一个本就启用的账号是无意义操作，
            // 而放行它会留下「管理端可改 ADMIN 状态」的假象。
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "不允许修改管理员账号状态");
        }

        User update = new User();
        update.setId(userId);
        update.setStatus(status);
        userMapper.updateById(update);

        // 契约 §8.7：操作记 INFO 日志（操作者 id、动作、目标用户）
        log.info("管理端修改用户状态 operatorId={} action={} targetUserId={} targetUsername={}",
                operatorId,
                status == Constants.STATUS_DISABLED ? "DISABLE_USER" : "ENABLE_USER",
                userId, target.getUsername());
        return new UserStatusResponse(userId, status);
    }

    // ---------------------------------------------------------------
    // 监控
    // ---------------------------------------------------------------

    @Override
    public AiStatsResponse aiStats() {
        Map<String, AiMetrics.CallStat> snapshot = aiMetrics.snapshot();

        List<AiFeatureStat> features = new ArrayList<>(snapshot.size());
        long total = 0;
        long success = 0;
        long degraded = 0;
        for (Map.Entry<String, AiMetrics.CallStat> entry : snapshot.entrySet()) {
            AiMetrics.CallStat stat = entry.getValue();
            AiFeatureStat feature = new AiFeatureStat();
            feature.setFeature(entry.getKey());
            feature.setTotal((int) stat.getTotal());
            feature.setSuccess((int) stat.getSuccess());
            feature.setDegraded((int) stat.getDegraded());
            feature.setTimeout((int) stat.getTimeout());
            feature.setError((int) stat.getError());
            feature.setAvgCostMs(stat.avgCostMs());
            features.add(feature);

            total += stat.getTotal();
            success += stat.getSuccess();
            degraded += stat.getDegraded();
        }

        AiStatsResponse response = new AiStatsResponse();
        response.setTotalCalls((int) total);
        // 成功率 = 成功 / 总调用（分母含降级），口径唯一真相源在 SuccessRate
        response.setSuccessRate(SuccessRate.percent(success, total));
        response.setDegradedCount((int) degraded);
        response.setRegisteredUsers(countRegisteredUsers());
        response.setFeatures(features);
        response.setRecentEvents(toEventViews(aiMetrics.recentEvents()));
        return response;
    }

    /** 注册用户数：{@code @TableLogic} 自动附加 {@code deleted = 0}。 */
    private int countRegisteredUsers() {
        Long count = userMapper.selectCount(null);
        return count == null ? 0 : count.intValue();
    }

    /** 内部值对象 → 对外 DTO（监控组件内部结构不得直接出网）。 */
    private List<DegradeEventView> toEventViews(List<AiMetrics.DegradeEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }
        List<DegradeEventView> views = new ArrayList<>(events.size());
        for (AiMetrics.DegradeEvent event : events) {
            views.add(new DegradeEventView(event.getTime(), event.getFeature(),
                    event.getCostMs(), event.getReason(), event.getResult()));
        }
        return views;
    }

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    /**
     * 原子调整词书 {@code word_count}（SQL 侧自增/自减，避免读改写丢更新）。
     *
     * <p><b>为什么不用「查出来改完写回」</b>：两管理员并发删同一本词书的词，
     * 各自读到 {@code word_count = 100}、各写回 99，实际删了两个词应为 98，
     * 计数偏大且不会自我纠正（这是经典的 lost update）。
     * {@code word_count = word_count ± 1} 在数据库内完成，天然并发安全。
     *
     * <p><b>update_time 必须手工带上</b>：本语句走的是
     * {@code update(null, wrapper)}（entity 为 null），
     * {@code MetaObjectHandler} 的 UPDATE 填充不会被触发——若不显式 set，
     * 会出现「行被改了但 update_time 没变」的脏数据。
     */
    private void adjustWordCount(Long bookId, int delta) {
        if (bookId == null || delta == 0) {
            return;
        }
        wordBookMapper.update(null, Wrappers.<WordBook>lambdaUpdate()
                .setSql("word_count = word_count + (" + delta + ")")
                .set(WordBook::getUpdateTime, LocalDateTime.now(clock))
                .eq(WordBook::getId, bookId));
    }

    private WordBook requireBook(Long bookId) {
        WordBook book = bookId == null ? null : wordBookMapper.selectById(bookId);
        if (book == null) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        return book;
    }

    /** 单词原文必填（新增/改词原文时）：空白视为参数错误。 */
    private String requireWordText(String word) {
        if (!StringUtils.hasText(word)) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "单词不能为空");
        }
        return word.trim();
    }

    /** 与导入路径同一口径：可选字段缺省填空串而非 null，免去前端处处判空。 */
    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private WordResponse toWordResponse(Word word) {
        WordResponse response = new WordResponse();
        response.setWordId(word.getId());
        response.setWord(word.getWord());
        response.setPhonetic(word.getPhonetic());
        response.setTranslation(word.getTranslation());
        response.setExample(word.getExample());
        return response;
    }

    private AdminUserResponse toAdminUserResponse(User user, int totalCheckInDays) {
        AdminUserResponse response = new AdminUserResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setTotalCheckInDays(totalCheckInDays);
        response.setCreateTime(user.getCreateTime());
        return response;
    }
}
