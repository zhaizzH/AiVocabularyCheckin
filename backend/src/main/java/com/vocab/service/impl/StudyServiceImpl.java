package com.vocab.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.UserWordBook;
import com.vocab.entity.Word;
import com.vocab.entity.WordProgress;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserWordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.mapper.WordProgressMapper;
import com.vocab.dto.response.CheckInResponse;
import com.vocab.dto.response.StreakResponse;
import com.vocab.dto.response.StudyPlanResponse;
import com.vocab.dto.response.StudyRecordResponse;
import com.vocab.dto.response.StudyWordResponse;
import com.vocab.service.MistakeService;
import com.vocab.service.ReviewSchedule;
import com.vocab.service.StudyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习服务实现（契约 §3 + design.md §4.1/§4.2）。
 *
 * <p><b>时间一律来自注入的 {@link Clock}</b>，不调用 {@code LocalDate.now()}：
 * 「今天」是本模块的核心变量（今日计划、今日打卡、今日已上报词数），
 * 直接从系统时钟取值会让「跨天连续打卡」「昨天/今天边界」这类用例
 * 只能靠改系统时间或直接改库来验证。注入固定 Clock 后这些用例都是
 * 毫秒级的普通单测。
 *
 * <p><b>打卡为什么能做到「同一事务内既不重复写、又能返回正确统计」</b>：
 * 三道校验（3002 无计划 / 3003 未完成 / 3001 已打卡）全部在写库前完成，
 * 写库只有一次 {@code insert}（顺序幂等，撞唯一索引即并发重复打卡），
 * 之后返回的统计值都是「写完之后再查」的真实结果，而不是内存推算值。
 */
@Service
public class StudyServiceImpl implements StudyService {

    private static final Logger log = LoggerFactory.getLogger(StudyServiceImpl.class);

    private final UserWordBookMapper userWordBookMapper;
    private final WordProgressMapper wordProgressMapper;
    private final WordMapper wordMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final StatsMapper statsMapper;
    private final MistakeService mistakeService;
    private final Clock clock;

    public StudyServiceImpl(UserWordBookMapper userWordBookMapper,
                            WordProgressMapper wordProgressMapper,
                            WordMapper wordMapper,
                            StudyRecordMapper studyRecordMapper,
                            StatsMapper statsMapper,
                            MistakeService mistakeService,
                            Clock clock) {
        this.userWordBookMapper = userWordBookMapper;
        this.wordProgressMapper = wordProgressMapper;
        this.wordMapper = wordMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.statsMapper = statsMapper;
        this.mistakeService = mistakeService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------
    // §3.1 今日学习计划
    // ---------------------------------------------------------------

    @Override
    public StudyPlanResponse plan(Long userId) {
        LocalDate today = today();
        LocalDateTime dayStart = startOfToday();

        StudyPlanResponse response = new StudyPlanResponse();
        response.setDate(today);
        response.setCheckedIn(hasCheckedInToday(userId, today));

        List<Long> selectedBookIds = selectedBookIds(userId);
        if (selectedBookIds.isEmpty()) {
            // 边界（需求清单 §2.2）：未选词书 → 空计划，前端引导去选词书。
            response.setTotal(0);
            response.setFinished(0);
            response.setWords(List.of());
            return response;
        }

        // 计划由三段构成，顺序即最终顺序：
        //   1) 待复习词（全部已选词书）—— 排最前
        //   2) 新词（每本各 20 个）
        //   3) 今天已经上报过的词（兜底，见下）
        // 用 LinkedHashMap 去重并保持插入顺序（第 2 段与第 3 段天然有重叠：
        // 今天学的新词既是候选，也已在今天的流水里）。
        Map<Long, StudyWordResponse> ordered = new LinkedHashMap<>();

        for (StudyWordResponse reviewWord : wordProgressMapper.selectPlanReviewWords(userId, today)) {
            reviewWord.setIsReview(true);
            ordered.put(reviewWord.getWordId(), reviewWord);
        }

        for (Long bookId : selectedBookIds) {
            List<Long> newWordIds = wordMapper.selectUnlearnedNewWordIds(
                    bookId, userId, dayStart, Constants.DAILY_NEW_WORDS);
            if (newWordIds.isEmpty()) {
                continue;
            }
            // selectBatchIds 的 IN 查询不保证返回顺序，故用 id → Word 的映射
            // 再按 newWordIds 的顺序回填，确保计划里新词严格按 id 升序。
            Map<Long, Word> wordMap = new HashMap<>();
            for (Word word : wordMapper.selectBatchIds(newWordIds)) {
                wordMap.put(word.getId(), word);
            }
            for (Long newWordId : newWordIds) {
                if (ordered.containsKey(newWordId)) {
                    continue;
                }
                Word word = wordMap.get(newWordId);
                if (word == null) {
                    continue;
                }
                ordered.put(newWordId, toStudyWord(word, false));
            }
        }

        // 3) 兜底：今天已经上报过的词必须继续留在计划里。
        //    计划是「今日任务集合」，不是「还剩多少没做」。若只由「待复习 + 新词候选」
        //    实时推导，用户把到期的复习词全部复习完之后，那些词既不再到期（next_review_date
        //    被推到未来）、也不是新词候选（早已 learned=1），就会立刻从计划里消失——
        //    计划总量一路收敛到 0，checkIn 反而触发 3002「今日无学习计划」，
        //    用户刚做完任务却无法打卡，连续天数被无辜中断。
        //    并入今天已上报的词后：total 恒定、finished 单调递增，做完即可打卡。
        Map<Long, String> reportedToday = todayReportedWordTypes(userId, today);
        for (Map.Entry<Long, String> entry : reportedToday.entrySet()) {
            if (ordered.containsKey(entry.getKey())) {
                continue;
            }
            Word word = wordMapper.selectById(entry.getKey());
            if (word == null) {
                continue;
            }
            // 复习过的词归入复习区（isReview=true），学习过的归入新词区。
            boolean isReview = Constants.STUDY_TYPE_REVIEW.equals(entry.getValue());
            ordered.put(word.getId(), toStudyWord(word, isReview));
        }

        // learned 统一在最后标记（复习词、新词、今日已上报词同一口径），
        // 避免多处各写一份、日后只改一处造成语义漂移。
        List<StudyWordResponse> wordList = new ArrayList<>(ordered.values());
        int finished = 0;
        for (StudyWordResponse item : wordList) {
            boolean learnedToday = reportedToday.containsKey(item.getWordId());
            item.setLearned(learnedToday);
            if (learnedToday) {
                finished++;
            }
        }

        response.setTotal(wordList.size());
        response.setFinished(finished);
        response.setWords(wordList);
        return response;
    }

    /** 把单词实体装配成计划/复习列表项（两个入口共用，避免字段遗漏）。 */
    private StudyWordResponse toStudyWord(Word word, boolean isReview) {
        StudyWordResponse item = new StudyWordResponse();
        item.setWordId(word.getId());
        item.setBookId(word.getBookId());
        item.setWord(word.getWord());
        item.setPhonetic(word.getPhonetic());
        item.setTranslation(word.getTranslation());
        item.setExample(word.getExample());
        item.setIsReview(isReview);
        return item;
    }

    // ---------------------------------------------------------------
    // §3.2 逐词学习上报
    // ---------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StudyRecordResponse record(Long userId, Long wordId, Long bookId, boolean known) {
        Word word = wordId == null ? null : wordMapper.selectById(wordId);
        if (word == null) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "单词不存在");
        }
        if (!word.getBookId().equals(bookId)) {
            // 防止前端传错 bookId 导致 word_progress.book_id 与 word.book_id 不一致，
            // 那会让「按词书取未学单词」与「按词书统计已学」互相矛盾。
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "单词不属于该词书");
        }

        LocalDate today = today();

        // 进度表 upsert：唯一键 (user_id, word_id) 让重复上报天然幂等。
        // 首次上报从 stage=1 开始（design.md §4.3：只要学过就进复习队列）。
        WordProgress progress = wordProgressMapper.selectOne(Wrappers.<WordProgress>lambdaQuery()
                .eq(WordProgress::getUserId, userId)
                .eq(WordProgress::getWordId, wordId)
                .last("LIMIT 1"));

        if (progress == null) {
            progress = new WordProgress();
            progress.setUserId(userId);
            progress.setBookId(bookId);
            progress.setWordId(wordId);
            progress.setStage(Constants.MIN_STAGE);
            progress.setLearned(1);
            progress.setWrongCount(known ? 0 : 1);
            progress.setNextReviewDate(ReviewSchedule.nextReviewDate(Constants.MIN_STAGE, today));
            try {
                wordProgressMapper.insert(progress);
            } catch (DuplicateKeyException e) {
                // 并发：同一词几乎同时上报两次。唯一键兜底后重查并走更新分支。
                progress = wordProgressMapper.selectOne(Wrappers.<WordProgress>lambdaQuery()
                        .eq(WordProgress::getUserId, userId)
                        .eq(WordProgress::getWordId, wordId)
                        .last("LIMIT 1"));
                applyRelearn(progress, known, today);
                wordProgressMapper.updateById(progress);
            }
        } else {
            applyRelearn(progress, known, today);
            wordProgressMapper.updateById(progress);
        }

        boolean addedToMistakes = false;
        if (!known) {
            // 「不认识」→ 立即收录错题本（需求清单 §2.3）。
            mistakeService.addOrIncrement(userId, wordId, Constants.SOURCE_STUDY);
            addedToMistakes = true;
        }

        // 写学习事件流水。唯一键 (user_id, word_id, study_date, study_type)
        // 使同一天对同一词的重复上报不会写重复流水（幂等）。
        insertStudyRecordIfAbsent(userId, bookId, wordId, Constants.STUDY_TYPE_STUDY, today, null);

        log.info("学习上报 userId={} wordId={} known={}", userId, wordId, known);
        return new StudyRecordResponse(wordId, true, addedToMistakes);
    }

    /**
     * 已学过的词再次上报（学习或复习）时的进度更新。
     *
     * <p>{@code known=true} → 进入下一阶段（封顶 4）；{@code known=false} →
     * 退回第 1 阶段并 {@code wrong_count + 1}（与复习答错同语义）。
     */
    private void applyRelearn(WordProgress progress, boolean known, LocalDate today) {
        int nextStage = known
                ? ReviewSchedule.nextStage(currentStage(progress))
                : Constants.MIN_STAGE;
        progress.setStage(nextStage);
        progress.setLearned(1);
        if (!known) {
            progress.setWrongCount(safeInt(progress.getWrongCount()) + 1);
        }
        progress.setNextReviewDate(ReviewSchedule.nextReviewDate(nextStage, today));
    }

    // ---------------------------------------------------------------
    // §3.3 打卡
    // ---------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckInResponse checkIn(Long userId) {
        LocalDate today = today();

        StudyPlanResponse plan = plan(userId);
        if (plan.getTotal() == 0) {
            throw new BizException(ErrorCode.NO_STUDY_PLAN);
        }
        if (plan.getFinished() < plan.getTotal()) {
            throw new BizException(ErrorCode.PLAN_NOT_FINISHED);
        }

        // 幂等：今日已打卡 → 3001（前端据此把按钮置为「已打卡 ✓」）。
        if (hasCheckedInToday(userId, today)) {
            throw new BizException(ErrorCode.ALREADY_CHECKED_IN);
        }

        int streakDays = computeStreak(userId, today);

        // 把今日已学的词补上打卡标记（写入 check_in_date 与 streak_days 快照）。
        // 今日已学词的流水在 §3.2 上报时就已写入（check_in_date 为 NULL），
        // 打卡是一次「把今天整批流水标记为已打卡」的动作。
        List<StudyRecord> todayRecords = studyRecordMapper.selectList(
                Wrappers.<StudyRecord>lambdaQuery()
                        .eq(StudyRecord::getUserId, userId)
                        .eq(StudyRecord::getStudyDate, today)
                        .isNull(StudyRecord::getCheckInDate));
        for (StudyRecord record : todayRecords) {
            record.setCheckInDate(today);
            record.setStreakDays(streakDays);
            studyRecordMapper.updateById(record);
        }

        int todayLearned = safeInt(statsMapper.countLearnedWordsOnDate(userId, today));
        int todayMistakes = safeInt(statsMapper.countMistakesOnDate(
                userId, startOfToday(), startOfTomorrow()));

        log.info("打卡成功 userId={} date={} streakDays={} todayLearned={}",
                userId, today, streakDays, todayLearned);
        return new CheckInResponse(today, streakDays, todayLearned, todayMistakes);
    }

    /**
     * 计算「今天打卡后」的连续天数（design.md §4.2 步骤 3）。
     *
     * <p>规则：上次打卡日期 = 昨天 → 昨天快照 + 1；否则重置为 1。
     * 注意查的是 {@code check_in_date < 今天} 的<b>最大值</b>——
     * 中间断签多少天都不影响：只要不是昨天，就从 1 重新开始。
     */
    private int computeStreak(Long userId, LocalDate today) {
        StudyRecord last = studyRecordMapper.selectOne(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, userId)
                .isNotNull(StudyRecord::getCheckInDate)
                .lt(StudyRecord::getCheckInDate, today)
                .orderByDesc(StudyRecord::getCheckInDate)
                .last("LIMIT 1"));

        if (last == null) {
            return 1;
        }
        if (today.minusDays(1).equals(last.getCheckInDate())) {
            return safeInt(last.getStreakDays()) + 1;
        }
        return 1;
    }

    // ---------------------------------------------------------------
    // §3.4 连续打卡与累计统计
    // ---------------------------------------------------------------

    @Override
    public StreakResponse streak(Long userId) {
        LocalDate today = today();

        StudyRecord latest = studyRecordMapper.selectOne(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, userId)
                .isNotNull(StudyRecord::getCheckInDate)
                .orderByDesc(StudyRecord::getCheckInDate)
                .last("LIMIT 1"));

        Integer streakDays = 0;
        LocalDate lastCheckInDate = null;
        if (latest != null) {
            lastCheckInDate = latest.getCheckInDate();
            // 「连续」必须是仍然有效的连续：今天或昨天打过卡才算延续，
            // 更早的打卡记录到今天就已中断（返回 0），否则断签一个月首页仍显示旧值。
            if (lastCheckInDate.equals(today) || lastCheckInDate.equals(today.minusDays(1))) {
                streakDays = safeInt(latest.getStreakDays());
            }
        }

        return new StreakResponse(
                streakDays,
                safeInt(statsMapper.countLearnedWords(userId)),
                safeInt(statsMapper.countDueReviewWords(userId, today)),
                lastCheckInDate);
    }

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime startOfToday() {
        return today().atStartOfDay();
    }

    private LocalDateTime startOfTomorrow() {
        return today().plusDays(1).atStartOfDay();
    }

    private boolean hasCheckedInToday(Long userId, LocalDate today) {
        Long count = studyRecordMapper.selectCount(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, userId)
                .eq(StudyRecord::getCheckInDate, today));
        return count != null && count > 0;
    }

    private List<Long> selectedBookIds(Long userId) {
        List<UserWordBook> relations = userWordBookMapper.selectList(
                Wrappers.<UserWordBook>lambdaQuery()
                        .eq(UserWordBook::getUserId, userId)
                        .orderByAsc(UserWordBook::getBookId));
        List<Long> ids = new ArrayList<>(relations.size());
        for (UserWordBook relation : relations) {
            ids.add(relation.getBookId());
        }
        return ids;
    }

    /**
     * 今日已上报的词 → 该词的流水类型（{@link Constants#STUDY_TYPE_STUDY} /
     * {@link Constants#STUDY_TYPE_REVIEW}）。
     *
     * <p>返回顺序按流水 id 升序，保证计划里「今日已上报词」的补位顺序稳定
     * （不依赖 DB 的物理返回顺序）。同一词今天既学过又复习过时，后写的那条
     * 类型更贴近当前状态，故用 {@code LinkedHashMap} 的 put 覆盖语义取最后一条。
     */
    private Map<Long, String> todayReportedWordTypes(Long userId, LocalDate today) {
        List<StudyRecord> records = studyRecordMapper.selectList(
                Wrappers.<StudyRecord>lambdaQuery()
                        .select(StudyRecord::getWordId, StudyRecord::getStudyType)
                        .eq(StudyRecord::getUserId, userId)
                        .eq(StudyRecord::getStudyDate, today)
                        .orderByAsc(StudyRecord::getId));
        Map<Long, String> types = new LinkedHashMap<>(records.size());
        for (StudyRecord record : records) {
            types.put(record.getWordId(), record.getStudyType());
        }
        return types;
    }

    /**
     * 写一条学习流水，若当天同类型同词已存在则跳过（幂等）。
     *
     * <p>不直接依赖「撞唯一索引就吞掉异常」：那样会在事务里留下一个失败语句，
     * 有些数据库/驱动组合下会污染事务状态（且日志里会反复出现 SQL 异常）。
     * 先查后插 + 唯一索引兜底，是这里更稳妥的双保险。
     */
    private void insertStudyRecordIfAbsent(Long userId, Long bookId, Long wordId,
                                           String studyType, LocalDate studyDate,
                                           Integer correct) {
        Long existing = studyRecordMapper.selectCount(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, userId)
                .eq(StudyRecord::getWordId, wordId)
                .eq(StudyRecord::getStudyDate, studyDate)
                .eq(StudyRecord::getStudyType, studyType));
        if (existing != null && existing > 0) {
            return;
        }
        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(bookId);
        record.setWordId(wordId);
        record.setStudyType(studyType);
        record.setStudyDate(studyDate);
        record.setStreakDays(0);
        record.setCorrect(correct);
        try {
            studyRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发重复上报：唯一键 uk_study_record_user_word_date_type 兜底。
            // 语义上等价于「已存在」，无需报错。
            log.debug("学习流水已存在，跳过 userId={} wordId={} type={}",
                    userId, wordId, studyType);
        }
    }

    private int currentStage(WordProgress progress) {
        return progress.getStage() == null ? Constants.MIN_STAGE : progress.getStage();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
