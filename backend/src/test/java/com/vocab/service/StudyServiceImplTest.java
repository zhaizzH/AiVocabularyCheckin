package com.vocab.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.dto.response.CheckInResponse;
import com.vocab.dto.response.StreakResponse;
import com.vocab.dto.response.StudyPlanResponse;
import com.vocab.dto.response.StudyRecordResponse;
import com.vocab.dto.response.StudyWordResponse;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.User;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.entity.WordProgress;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.mapper.WordProgressMapper;
import com.vocab.testsupport.FixedClock;
import com.vocab.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学习服务集成测试（阶段 7 闸口：核心闭环）。
 *
 * <p>用真实 H2 + 真实 SQL（含 {@code WordMapper.xml} 的 NOT EXISTS、分页插件），
 * 只把「时钟」换成可拨动的 {@link FixedClock}——这样「跨天连续打卡」
 * 「昨天到期复习」这类用例才可稳定复现。
 *
 * <p>覆盖：
 * <ul>
 *   <li>§3.1 计划：未选词书空计划 / 每本 20 新词 / 复习词在前 / 已上报标 learned</li>
 *   <li>§3.2 上报：幂等 / 不认识进错题本 / 首次 stage=1 且次日到期</li>
 *   <li>§3.3 打卡：3002 / 3003 / 3001 幂等 / streak +1 / 断签重置</li>
 *   <li>§3.4 streak：今天/昨天/更早 三种口径</li>
 * </ul>
 *
 * <p><b>计划窗口不漂移</b>是本阶段最关键的回归项：见
 * {@link #planSnapshotIsStableWhileReporting()}——它锁定了「计划生成后
 * finished 能追上 total」这一前置条件，否则永远无法打卡。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class StudyServiceImplTest {

    @Autowired
    private StudyService studyService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WordBookMapper wordBookMapper;

    @Autowired
    private WordMapper wordMapper;

    @Autowired
    private WordProgressMapper wordProgressMapper;

    @Autowired
    private StudyRecordMapper studyRecordMapper;

    @Autowired
    private MistakeEntryMapper mistakeEntryMapper;

    private static final LocalDate TODAY = FixedClock.DEFAULT_DATE;

    private User user;
    private WordBook book;

    @BeforeEach
    void setUp() {
        FixedClock.reset();
        user = newUser();
        book = newBook("四级核心词汇", "CET4");
    }

    @AfterEach
    void tearDown() {
        FixedClock.reset();
    }

    // ---------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------

    private User newUser() {
        User u = new User();
        u.setUsername("st_" + System.nanoTime());
        u.setPasswordHash("$2a$10$fakeHash");
        u.setNickname("学习测试");
        u.setAvatar(Constants.DEFAULT_AVATAR);
        u.setRole(Constants.ROLE_USER);
        u.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(u);
        return u;
    }

    private WordBook newBook(String name, String category) {
        WordBook b = new WordBook();
        b.setName(name);
        b.setCategory(category);
        b.setWordCount(0);
        wordBookMapper.insert(b);
        return b;
    }

    /** 造 {@code count} 个单词（id 升序即造词顺序）。 */
    private List<Word> newWords(Long bookId, int count, String prefix) {
        List<Word> words = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Word word = new Word();
            word.setBookId(bookId);
            word.setWord(prefix + i);
            word.setPhonetic("p" + i);
            word.setTranslation("释义" + i);
            word.setExample("example " + i);
            wordMapper.insert(word);
            words.add(word);
        }
        return words;
    }

    private void selectBook(Long userId, Long bookId) {
        com.vocab.entity.UserWordBook relation = new com.vocab.entity.UserWordBook();
        relation.setUserId(userId);
        relation.setBookId(bookId);
        // 用 mapper 顺带验证 MetaObjectHandler 对物理删除表的填充
        userWordBookMapper.insert(relation);
    }

    @Autowired
    private com.vocab.mapper.UserWordBookMapper userWordBookMapper;

    /**
     * 造一条「历史上已学」的进度行。
     *
     * <p><b>{@code learnedDate} 必须显式回填成「过去某天」</b>，否则
     * {@code create_time} 会被 {@code MetaObjectHandler} 填成今天，
     * 计划生成逻辑会把它当作「今天刚选中的词」仍留在新词候选集里——
     * 与用例意图（历史上学过，因此不再作为新词）相反。
     *
     * <p>实体已显式赋值时 {@code strictInsertFill} 不会覆盖，故直接 set 即可。
     *
     * @param learnedDate  首次进入学习计划的日期（写入 {@code create_time}）
     * @param nextReviewDate 下次复习日期
     */
    private WordProgress learnedProgress(Long userId, Long bookId, Long wordId,
                                         LocalDate learnedDate, LocalDate nextReviewDate) {
        WordProgress progress = new WordProgress();
        progress.setUserId(userId);
        progress.setBookId(bookId);
        progress.setWordId(wordId);
        progress.setStage(Constants.MIN_STAGE);
        progress.setNextReviewDate(nextReviewDate);
        progress.setLearned(1);
        progress.setWrongCount(0);
        progress.setCreateTime(learnedDate.atStartOfDay());
        progress.setUpdateTime(learnedDate.atStartOfDay());
        wordProgressMapper.insert(progress);
        return progress;
    }

    /**
     * 直接播下「过去某天已打卡」的事实（不经过 record/checkin）。
     *
     * <p>连续天数是打卡时写入的<b>快照</b>（{@code study_record.streak_days}），
     * 故这里直接造一条当天已打卡的流水即可精确构造任意历史 streak。
     * 比起「逐日模拟学习+打卡」，这样能把 streak 用例与「计划是否完成」解耦——
     * 后者是打卡的前置校验，混进来会让用例失败原因不唯一。
     *
     * <p>内部自建一个单词（该词不会被今天计划选中：{@code create_time} 回填到
     * 历史日期，且已 {@code learned=1}）。
     *
     * @return 该历史打卡日的连续天数快照（即 {@code streakDays}）
     */
    private int seedPastCheckIn(Long userId, Long bookId, LocalDate date, int streakDays) {
        Word word = newWords(bookId, 1, "past").get(0);

        WordProgress progress = new WordProgress();
        progress.setUserId(userId);
        progress.setBookId(bookId);
        progress.setWordId(word.getId());
        progress.setStage(Constants.MIN_STAGE);
        progress.setLearned(1);
        progress.setWrongCount(0);
        progress.setNextReviewDate(date.plusDays(30));
        progress.setCreateTime(date.atStartOfDay());
        progress.setUpdateTime(date.atStartOfDay());
        wordProgressMapper.insert(progress);

        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(bookId);
        record.setWordId(word.getId());
        record.setStudyType(Constants.STUDY_TYPE_STUDY);
        record.setStudyDate(date);
        record.setCheckInDate(date);
        record.setStreakDays(streakDays);
        record.setCorrect(1);
        studyRecordMapper.insert(record);
        return streakDays;
    }

    // ---------------------------------------------------------------
    // §3.1 今日学习计划
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§3.1 未选词书 → total=0、finished=0、words=[]（前端引导选词书）")
    void planWithoutSelectedBookIsEmpty() {
        StudyPlanResponse plan = studyService.plan(user.getId());

        assertEquals(TODAY, plan.getDate());
        assertEquals(0, plan.getTotal());
        assertEquals(0, plan.getFinished());
        assertTrue(plan.getWords().isEmpty());
        assertFalse(plan.getCheckedIn());
    }

    @Test
    @DisplayName("§3.1 每本已选词书给最多 20 个新词（第 21 个及以后不进计划）")
    void planCapsNewWordsPerBookAtDailyLimit() {
        newWords(book.getId(), 25, "w");
        selectBook(user.getId(), book.getId());

        StudyPlanResponse plan = studyService.plan(user.getId());

        assertEquals(Constants.DAILY_NEW_WORDS, plan.getTotal());
        assertEquals(20, plan.getWords().size());
        // 严格按 id 升序取前 20 个
        for (StudyWordResponse item : plan.getWords()) {
            assertFalse(item.getIsReview());
            assertFalse(item.getLearned());
            assertNotNull(item.getWord());
        }
    }

    @Test
    @DisplayName("§3.1 多本词书 → total 是各本额度之和（20 × 本书数）")
    void planAggregatesAcrossBooks() {
        WordBook book2 = newBook("六级核心词汇", "CET6");
        newWords(book.getId(), 30, "a");
        newWords(book2.getId(), 30, "b");
        selectBook(user.getId(), book.getId());
        selectBook(user.getId(), book2.getId());

        StudyPlanResponse plan = studyService.plan(user.getId());

        assertEquals(40, plan.getTotal());
        assertEquals(2, plan.getWords().stream().map(StudyWordResponse::getBookId).distinct().count());
    }

    @Test
    @DisplayName("§3.1 到期复习词排在新词之前，且 isReview=true")
    void planPutsReviewWordsFirst() {
        List<Word> words = newWords(book.getId(), 3, "r");
        selectBook(user.getId(), book.getId());
        // 第一个词昨天学的、昨天到期（今天待复习）；其余两个未学 → 新词
        learnedProgress(user.getId(), book.getId(), words.get(0).getId(),
                TODAY.minusDays(1), TODAY.minusDays(1));

        StudyPlanResponse plan = studyService.plan(user.getId());

        // 3 个词全部入计划：1 个复习 + 2 个新词
        assertEquals(3, plan.getTotal());
        StudyWordResponse first = plan.getWords().get(0);
        assertTrue(first.getIsReview());
        assertEquals(words.get(0).getId(), first.getWordId());
        // 复习词在前，新词在后
        assertFalse(plan.getWords().get(1).getIsReview());
    }

    @Test
    @DisplayName("§3.1 明天才到期的词不出现在今天的复习队列里")
    void planExcludesNotYetDueReviewWords() {
        List<Word> words = newWords(book.getId(), 1, "n");
        selectBook(user.getId(), book.getId());
        learnedProgress(user.getId(), book.getId(), words.get(0).getId(),
                TODAY.minusDays(1), TODAY.plusDays(1));

        StudyPlanResponse plan = studyService.plan(user.getId());

        // 该词已学（不可能是新词）且未到期（不进复习）→ 计划里没有它
        assertTrue(plan.getWords().stream()
                .noneMatch(item -> item.getWordId().equals(words.get(0).getId())));
    }

    @Test
    @DisplayName("§3.1+§3.2 计划窗口不漂移：上报后 total 不变、finished 递增，直至可打卡")
    void planSnapshotIsStableWhileReporting() {
        List<Word> words = newWords(book.getId(), 25, "s");
        selectBook(user.getId(), book.getId());

        StudyPlanResponse first = studyService.plan(user.getId());
        int total = first.getTotal();
        assertEquals(20, total);
        List<Long> plannedIds = first.getWords().stream().map(StudyWordResponse::getWordId).toList();

        // 逐词上报（全部「认识」），每一步都重新取计划
        for (int i = 0; i < total; i++) {
            studyService.record(user.getId(), plannedIds.get(i), book.getId(), true);

            StudyPlanResponse step = studyService.plan(user.getId());
            // 关键断言：total 恒定（不因上报而补新词进来）
            assertEquals(total, step.getTotal(), "上报 " + (i + 1) + " 个词后 total 漂移了");
            assertEquals(i + 1, step.getFinished());
            // 计划中的词 id 集合也完全稳定
            assertEquals(plannedIds,
                    step.getWords().stream().map(StudyWordResponse::getWordId).toList());
        }

        // 全部完成后 finished == total → 可以打卡（不再是 3003）
        StudyPlanResponse done = studyService.plan(user.getId());
        assertEquals(done.getTotal(), done.getFinished());

        CheckInResponse checkIn = studyService.checkIn(user.getId());
        assertEquals(TODAY, checkIn.getCheckInDate());
        assertEquals(1, checkIn.getStreakDays());
        assertEquals(20, checkIn.getTodayLearned());
    }

    @Test
    @DisplayName("§3.1 词书全部学完 → 新词为空（仅剩到期的复习词）")
    void planIsEmptyWhenBookFullyLearned() {
        List<Word> words = newWords(book.getId(), 2, "f");
        selectBook(user.getId(), book.getId());
        // 两个词都是「昨天学的、明天才到期」→ 既非新词也未到期
        learnedProgress(user.getId(), book.getId(), words.get(0).getId(),
                TODAY.minusDays(1), TODAY.plusDays(1));
        learnedProgress(user.getId(), book.getId(), words.get(1).getId(),
                TODAY.minusDays(1), TODAY.plusDays(1));

        StudyPlanResponse plan = studyService.plan(user.getId());

        assertEquals(0, plan.getTotal());
        assertTrue(plan.getWords().isEmpty());
    }

    // ---------------------------------------------------------------
    // §3.2 学习上报
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§3.2 首次上报（认识）→ 建进度 stage=1、次日到期、learned=true、不进错题本")
    void recordFirstTimeKnown() {
        Word word = newWords(book.getId(), 1, "k").get(0);

        StudyRecordResponse response = studyService.record(user.getId(), word.getId(), book.getId(), true);

        assertEquals(word.getId(), response.getWordId());
        assertTrue(response.getLearned());
        assertFalse(response.getAddedToMistakes());

        WordProgress progress = selectProgress(user.getId(), word.getId());
        assertEquals(Constants.MIN_STAGE, progress.getStage());
        assertEquals(TODAY.plusDays(1), progress.getNextReviewDate());
        assertEquals(1, progress.getLearned());
        assertEquals(0, progress.getWrongCount());

        // 写了一条 STUDY 流水，但尚未打卡（check_in_date 为空）
        StudyRecord record = selectStudyRecord(user.getId(), word.getId(), Constants.STUDY_TYPE_STUDY);
        assertNotNull(record);
        assertEquals(TODAY, record.getStudyDate());
        assertNull(record.getCheckInDate());
        assertEquals(0, countMistakes(user.getId(), word.getId()));
    }

    @Test
    @DisplayName("§3.2 首次上报（不认识）→ addedToMistakes=true 且错题本有记录 wrong_count=1")
    void recordFirstTimeUnknownAddsMistake() {
        Word word = newWords(book.getId(), 1, "u").get(0);

        StudyRecordResponse response = studyService.record(user.getId(), word.getId(), book.getId(), false);

        assertTrue(response.getAddedToMistakes());
        MistakeEntry entry = selectMistake(user.getId(), word.getId());
        assertNotNull(entry);
        assertEquals(1, entry.getWrongCount());
        assertEquals(Constants.SOURCE_STUDY, entry.getSource());
        assertEquals(0, entry.getMastered());
        assertEquals(1, selectProgress(user.getId(), word.getId()).getWrongCount());
    }

    @Test
    @DisplayName("§3.2 重复上报（同一天两次认识）→ 幂等：进度一行、流水一条")
    void recordIsIdempotent() {
        Word word = newWords(book.getId(), 1, "i").get(0);

        studyService.record(user.getId(), word.getId(), book.getId(), true);
        studyService.record(user.getId(), word.getId(), book.getId(), true);

        Long progressCount = wordProgressMapper.selectCount(Wrappers.<WordProgress>lambdaQuery()
                .eq(WordProgress::getUserId, user.getId())
                .eq(WordProgress::getWordId, word.getId()));
        assertEquals(1L, progressCount);

        Long recordCount = studyRecordMapper.selectCount(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, user.getId())
                .eq(StudyRecord::getWordId, word.getId())
                .eq(StudyRecord::getStudyType, Constants.STUDY_TYPE_STUDY));
        assertEquals(1L, recordCount);
    }

    @Test
    @DisplayName("§3.2 同一天先认识后不认识 → 进度退回 stage=1、错误累计、错题本收录")
    void recordUnknownAfterKnownDegradesStage() {
        Word word = newWords(book.getId(), 1, "d").get(0);
        studyService.record(user.getId(), word.getId(), book.getId(), true);
        // 先把它推到 stage=2，验证「不认识」会退回 1
        WordProgress progress = selectProgress(user.getId(), word.getId());
        progress.setStage(2);
        wordProgressMapper.updateById(progress);

        studyService.record(user.getId(), word.getId(), book.getId(), false);

        WordProgress after = selectProgress(user.getId(), word.getId());
        assertEquals(Constants.MIN_STAGE, after.getStage());
        assertEquals(TODAY.plusDays(1), after.getNextReviewDate());
        assertEquals(1, after.getWrongCount());
        assertNotNull(selectMistake(user.getId(), word.getId()));
    }

    @Test
    @DisplayName("§3.2 单词不属于该词书 → 1005（防止 word_progress.book_id 与 word.book_id 不一致）")
    void recordRejectsMismatchedBook() {
        WordBook other = newBook("考研核心词汇", "KAOYAN");
        Word word = newWords(book.getId(), 1, "m").get(0);

        BizException e = assertThrows(BizException.class,
                () -> studyService.record(user.getId(), word.getId(), other.getId(), true));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§3.2 单词不存在 → 1005")
    void recordRejectsUnknownWord() {
        BizException e = assertThrows(BizException.class,
                () -> studyService.record(user.getId(), 999999L, book.getId(), true));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    // ---------------------------------------------------------------
    // §3.3 打卡
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§3.3 未选词书 → 3002 今日无学习计划")
    void checkInWithoutPlanThrows3002() {
        BizException e = assertThrows(BizException.class, () -> studyService.checkIn(user.getId()));
        assertEquals(ErrorCode.NO_STUDY_PLAN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§3.3 计划未完成 → 3003 不可打卡")
    void checkInBeforeFinishingPlanThrows3003() {
        List<Word> words = newWords(book.getId(), 3, "p");
        selectBook(user.getId(), book.getId());
        studyService.record(user.getId(), words.get(0).getId(), book.getId(), true);

        BizException e = assertThrows(BizException.class, () -> studyService.checkIn(user.getId()));
        assertEquals(ErrorCode.PLAN_NOT_FINISHED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§3.3 同日重复打卡 → 3001（幂等，且不写第二条打卡流水）")
    void checkInTwiceThrows3001() {
        Word word = newWords(book.getId(), 1, "c").get(0);
        selectBook(user.getId(), book.getId());
        studyService.record(user.getId(), word.getId(), book.getId(), true);
        studyService.checkIn(user.getId());

        BizException e = assertThrows(BizException.class, () -> studyService.checkIn(user.getId()));
        assertEquals(ErrorCode.ALREADY_CHECKED_IN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§3.3 打卡把今日流水标记 check_in_date 与 streak_days")
    void checkInStampsTodayRecords() {
        List<Word> words = newWords(book.getId(), 2, "t");
        selectBook(user.getId(), book.getId());
        for (Word word : words) {
            studyService.record(user.getId(), word.getId(), book.getId(), false);
        }

        CheckInResponse response = studyService.checkIn(user.getId());

        assertEquals(TODAY, response.getCheckInDate());
        assertEquals(1, response.getStreakDays());
        assertEquals(2, response.getTodayLearned());
        // 两个词都进了错题本
        assertEquals(2, response.getTodayMistakes());

        for (Word word : words) {
            StudyRecord record = selectStudyRecord(user.getId(), word.getId(), Constants.STUDY_TYPE_STUDY);
            assertEquals(TODAY, record.getCheckInDate());
            assertEquals(1, record.getStreakDays());
        }
    }

    // ---------------------------------------------------------------
    // §3.4 连续天数
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§3.4 从未打卡 → streakDays=0、lastCheckInDate=null、totalLearned 仍正确")
    void streakWithoutCheckIn() {
        Word word = newWords(book.getId(), 1, "z").get(0);
        studyService.record(user.getId(), word.getId(), book.getId(), true);

        StreakResponse streak = studyService.streak(user.getId());

        assertEquals(0, streak.getStreakDays());
        assertNull(streak.getLastCheckInDate());
        assertEquals(1, streak.getTotalLearned());
    }

    @Test
    @DisplayName("§3.3/§3.4 昨天打过卡、今天再打 → streak+1（连续延续）")
    void streakIncrementsWhenYesterdayCheckedIn() {
        // 直接播下「昨天已打卡、streak=1」的事实，把本用例与「今日计划是否完成」解耦
        seedPastCheckIn(user.getId(), book.getId(), TODAY, 1);

        // 今天：学一个新词并打卡
        FixedClock.setDate(TODAY.plusDays(1));
        Word today = newWords(book.getId(), 1, "y2").get(0);
        selectBook(user.getId(), book.getId());
        studyService.record(user.getId(), today.getId(), book.getId(), true);
        CheckInResponse response = studyService.checkIn(user.getId());

        assertEquals(TODAY.plusDays(1), response.getCheckInDate());
        assertEquals(2, response.getStreakDays());

        StreakResponse streak = studyService.streak(user.getId());
        assertEquals(2, streak.getStreakDays());
        assertEquals(TODAY.plusDays(1), streak.getLastCheckInDate());
    }

    @Test
    @DisplayName("§3.3/§3.4 跨天连续打卡端到端：次日计划含昨日词作为复习，复习+打卡后 streak=2")
    void crossDayEndToEndStreakContinues() {
        // ---- 第一天：学一个词并打卡 ----
        Word day1Word = newWords(book.getId(), 1, "cd").get(0);
        selectBook(user.getId(), book.getId());
        studyService.record(user.getId(), day1Word.getId(), book.getId(), true);
        assertEquals(1, studyService.checkIn(user.getId()).getStreakDays());

        // ---- 拨到第二天 ----
        FixedClock.setDate(TODAY.plusDays(1));
        StudyPlanResponse plan = studyService.plan(user.getId());

        // 昨天学的词今天到期 → 它必须作为复习词出现在今天计划里
        StudyWordResponse reviewItem = plan.getWords().stream()
                .filter(item -> item.getWordId().equals(day1Word.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("昨日词未进入今日复习队列"));
        assertTrue(reviewItem.getIsReview());

        // 计划未过完时不能打卡
        assertThrows(BizException.class, () -> studyService.checkIn(user.getId()));

        // 把今天计划里的所有词都过完：复习词走 review、新词走 study
        for (StudyWordResponse item : studyService.plan(user.getId()).getWords()) {
            if (item.getIsReview()) {
                reviewService.record(user.getId(), item.getWordId(), true);
            } else {
                studyService.record(user.getId(), item.getWordId(), item.getBookId(), true);
            }
        }

        // ---- 第二天打卡：连续天数衔接 ----
        CheckInResponse secondDay = studyService.checkIn(user.getId());
        assertEquals(TODAY.plusDays(1), secondDay.getCheckInDate());
        assertEquals(2, secondDay.getStreakDays());
    }

    @Test
    @DisplayName("§3.4 最后一次打卡早于昨天 → 已断签，streakDays 归 0（不返回旧快照）")
    void streakResetsAfterGap() {
        Word day1 = newWords(book.getId(), 1, "g1").get(0);
        selectBook(user.getId(), book.getId());
        studyService.record(user.getId(), day1.getId(), book.getId(), true);
        studyService.checkIn(user.getId());

        // 隔了两天才来看（最后一次打卡是前天）
        FixedClock.setDate(TODAY.plusDays(2));
        StreakResponse streak = studyService.streak(user.getId());

        assertEquals(0, streak.getStreakDays(), "断签后不应返回历史快照");
        assertEquals(TODAY, streak.getLastCheckInDate(), "但最后一次打卡日期仍要如实返回");
    }

    @Test
    @DisplayName("§3.4 昨天打卡、今天还没打 → streak 仍显示昨天那个值（连续尚未中断）")
    void streakKeepsValueWhenYesterdayCheckedInButTodayNot() {
        Word day1 = newWords(book.getId(), 1, "k1").get(0);
        selectBook(user.getId(), book.getId());
        studyService.record(user.getId(), day1.getId(), book.getId(), true);
        studyService.checkIn(user.getId()); // streak=1

        // 第二天还没打卡，但昨天打过 → 连续尚未中断
        FixedClock.setDate(TODAY.plusDays(1));
        StreakResponse streak = studyService.streak(user.getId());

        assertEquals(1, streak.getStreakDays());
        assertEquals(TODAY, streak.getLastCheckInDate());
    }

    @Test
    @DisplayName("§3.4 断签后再打卡 → streak 重置为 1（不是续上历史值）")
    void streakRestartsAfterGapOnNewCheckIn() {
        // 前天打过卡（streak=1），中间断了一天
        seedPastCheckIn(user.getId(), book.getId(), TODAY, 1);

        // 隔两天后回来：学一个词再打卡 → 重置为 1
        FixedClock.setDate(TODAY.plusDays(2));
        Word today = newWords(book.getId(), 1, "r3").get(0);
        selectBook(user.getId(), book.getId());
        studyService.record(user.getId(), today.getId(), book.getId(), true);
        CheckInResponse response = studyService.checkIn(user.getId());

        assertEquals(1, response.getStreakDays(), "断签后应重置为 1");
    }

    @Test
    @DisplayName("§3.4 todayReviewCount 反映今天到期待复习数（§4.1 同源）")
    void streakCountsDueReviews() {
        List<Word> words = newWords(book.getId(), 2, "v");
        selectBook(user.getId(), book.getId());
        learnedProgress(user.getId(), book.getId(), words.get(0).getId(),
                TODAY.minusDays(2), TODAY.minusDays(1));
        learnedProgress(user.getId(), book.getId(), words.get(1).getId(),
                TODAY.minusDays(2), TODAY);

        StreakResponse streak = studyService.streak(user.getId());

        assertEquals(2, streak.getTodayReviewCount());
    }

    // ---------------------------------------------------------------
    // 查询辅助
    // ---------------------------------------------------------------

    private WordProgress selectProgress(Long userId, Long wordId) {
        return wordProgressMapper.selectOne(Wrappers.<WordProgress>lambdaQuery()
                .eq(WordProgress::getUserId, userId)
                .eq(WordProgress::getWordId, wordId)
                .last("LIMIT 1"));
    }

    private StudyRecord selectStudyRecord(Long userId, Long wordId, String studyType) {
        return studyRecordMapper.selectOne(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, userId)
                .eq(StudyRecord::getWordId, wordId)
                .eq(StudyRecord::getStudyType, studyType)
                .last("LIMIT 1"));
    }

    private MistakeEntry selectMistake(Long userId, Long wordId) {
        return mistakeEntryMapper.selectOne(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getUserId, userId)
                .eq(MistakeEntry::getWordId, wordId)
                .last("LIMIT 1"));
    }

    private long countMistakes(Long userId, Long wordId) {
        return mistakeEntryMapper.selectCount(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getUserId, userId)
                .eq(MistakeEntry::getWordId, wordId));
    }
}
