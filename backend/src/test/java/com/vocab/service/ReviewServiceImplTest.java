package com.vocab.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.dto.response.ReviewRecordResponse;
import com.vocab.dto.response.ReviewTodayResponse;
import com.vocab.dto.response.ReviewWordResponse;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.UserWordBook;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.entity.WordProgress;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserWordBookMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复习服务集成测试（阶段 7 闸口）。
 *
 * <p>覆盖契约 §4：
 * <ul>
 *   <li>§4.1 待复习列表：只含到期词、带 stage 与错题本权威 wrong_count</li>
 *   <li>§4.2 提交结果：stage 1→4 递进与封顶、答错退回 1、日期 +1/3/7/15</li>
 *   <li>答错强制收录错题本（与学习上报共用同一去重原语）</li>
 *   <li>1005：对没有学习记录的词提交复习</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestClockConfig.class)
class ReviewServiceImplTest {

    @Autowired
    private ReviewService reviewService;

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

    @Autowired
    private UserWordBookMapper userWordBookMapper;

    @Autowired
    private com.vocab.mapper.UserMapper userMapper;

    private static final LocalDate TODAY = FixedClock.DEFAULT_DATE;

    private com.vocab.entity.User user;
    private WordBook book;

    @BeforeEach
    void setUp() {
        FixedClock.reset();
        user = new com.vocab.entity.User();
        user.setUsername("rv_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHash");
        user.setNickname("复习测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);

        book = new WordBook();
        book.setName("四级核心词汇");
        book.setCategory("CET4");
        book.setWordCount(0);
        wordBookMapper.insert(book);

        UserWordBook relation = new UserWordBook();
        relation.setUserId(user.getId());
        relation.setBookId(book.getId());
        userWordBookMapper.insert(relation);
    }

    @AfterEach
    void tearDown() {
        FixedClock.reset();
    }

    // ---------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------

    private Word newWord(String text) {
        Word word = new Word();
        word.setBookId(book.getId());
        word.setWord(text);
        word.setPhonetic("p-" + text);
        word.setTranslation("释义-" + text);
        word.setExample("example " + text);
        wordMapper.insert(word);
        return word;
    }

    /**
     * 造一条到期复习进度。
     *
     * @param stage         当前复习阶段
     * @param nextReviewDate 下次复习日期（<= 今天才会进待复习列表）
     */
    private WordProgress dueProgress(Long wordId, int stage, LocalDate nextReviewDate) {
        WordProgress progress = new WordProgress();
        progress.setUserId(user.getId());
        progress.setBookId(book.getId());
        progress.setWordId(wordId);
        progress.setStage(stage);
        progress.setNextReviewDate(nextReviewDate);
        progress.setLearned(1);
        progress.setWrongCount(0);
        wordProgressMapper.insert(progress);
        return progress;
    }

    // ---------------------------------------------------------------
    // §4.1 今日待复习
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§4.1 无到期词 → total=0、words=[]")
    void todayEmptyWhenNothingDue() {
        ReviewTodayResponse response = reviewService.today(user.getId());

        assertEquals(0, response.getTotal());
        assertTrue(response.getWords().isEmpty());
    }

    @Test
    @DisplayName("§4.1 只返回到期词（今天或逾期），未到期的词不出现")
    void todayReturnsOnlyDueWords() {
        Word overdue = newWord("overdue");
        Word dueToday = newWord("dueToday");
        Word future = newWord("future");
        dueProgress(overdue.getId(), 1, TODAY.minusDays(5));
        dueProgress(dueToday.getId(), 2, TODAY);
        dueProgress(future.getId(), 1, TODAY.plusDays(1));

        ReviewTodayResponse response = reviewService.today(user.getId());

        assertEquals(2, response.getTotal());
        List<Long> ids = response.getWords().stream().map(ReviewWordResponse::getWordId).toList();
        assertTrue(ids.contains(overdue.getId()));
        assertTrue(ids.contains(dueToday.getId()));
        assertFalse(ids.contains(future.getId()));
    }

    @Test
    @DisplayName("§4.1 逾期最久的排最前（ORDER BY next_review_date ASC）")
    void todayOrdersByOverdueFirst() {
        Word recent = newWord("recent");
        Word old = newWord("old");
        dueProgress(recent.getId(), 1, TODAY);
        dueProgress(old.getId(), 1, TODAY.minusDays(10));

        ReviewTodayResponse response = reviewService.today(user.getId());

        assertEquals(old.getId(), response.getWords().get(0).getWordId());
        assertEquals(recent.getId(), response.getWords().get(1).getWordId());
    }

    @Test
    @DisplayName("§4.1 带出单词文本 + stage + 错题本权威 wrong_count")
    void todayCarriesWordDetailAndWrongCount() {
        Word word = newWord("detail");
        dueProgress(word.getId(), 3, TODAY);
        // 错题本里该词的 wrong_count = 2（权威来源），进度表的 wrong_count 是 0
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(user.getId());
        entry.setWordId(word.getId());
        entry.setSource(Constants.SOURCE_STUDY);
        entry.setWrongCount(2);
        entry.setMastered(0);
        mistakeEntryMapper.insert(entry);

        ReviewTodayResponse response = reviewService.today(user.getId());

        ReviewWordResponse item = response.getWords().get(0);
        assertEquals("detail", item.getWord());
        assertEquals("释义-detail", item.getTranslation());
        assertEquals(3, item.getStage());
        assertEquals(2, item.getWrongCount());
    }

    @Test
    @DisplayName("§4.1 无错题记录的词 wrong_count 补 0（不是 null）")
    void todayDefaultsMissingWrongCountToZero() {
        Word word = newWord("nocount");
        dueProgress(word.getId(), 1, TODAY);

        ReviewTodayResponse response = reviewService.today(user.getId());

        assertEquals(0, response.getWords().get(0).getWrongCount());
    }

    @Test
    @DisplayName("§4.1 已移除（deleted=1）的错题不计入 wrong_count")
    void todayIgnoresRemovedMistakes() {
        Word word = newWord("removed");
        dueProgress(word.getId(), 1, TODAY);
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(user.getId());
        entry.setWordId(word.getId());
        entry.setSource(Constants.SOURCE_STUDY);
        entry.setWrongCount(5);
        entry.setMastered(0);
        mistakeEntryMapper.insert(entry);
        mistakeEntryMapper.deleteById(entry.getId()); // 逻辑删除

        ReviewTodayResponse response = reviewService.today(user.getId());

        assertEquals(0, response.getWords().get(0).getWrongCount());
    }

    @Test
    @DisplayName("§4.1 未选词书（无 user_word_book 行）→ 不给复习词（即使进度到期）")
    void todayRequiresSelectedBook() {
        Word word = newWord("orphan");
        dueProgress(word.getId(), 1, TODAY.minusDays(1));
        // 移出词书（物理删除中间行）
        userWordBookMapper.delete(Wrappers.<UserWordBook>lambdaQuery()
                .eq(UserWordBook::getUserId, user.getId())
                .eq(UserWordBook::getBookId, book.getId()));

        ReviewTodayResponse response = reviewService.today(user.getId());

        assertEquals(0, response.getTotal());
    }

    // ---------------------------------------------------------------
    // §4.2 提交复习结果
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§4.2 答对：stage 1→2，下次复习 +3 天")
    void recordCorrectAdvancesStage() {
        Word word = newWord("advance");
        dueProgress(word.getId(), 1, TODAY);

        ReviewRecordResponse response = reviewService.record(user.getId(), word.getId(), true);

        assertEquals(word.getId(), response.getWordId());
        assertEquals(2, response.getStage());
        assertEquals(TODAY.plusDays(3), response.getNextReviewDate());

        WordProgress progress = selectProgress(word.getId());
        assertEquals(2, progress.getStage());
        assertEquals(TODAY.plusDays(3), progress.getNextReviewDate());
        // 答对不计错题
        assertNull(selectMistake(word.getId()));
    }

    @Test
    @DisplayName("§4.2 答对连续三次：stage 2→3→4→4（封顶），日期 +7/+15/+15")
    void recordCorrectCapsAtMaxStage() {
        Word word = newWord("cap");
        dueProgress(word.getId(), 1, TODAY);

        assertEquals(2, reviewService.record(user.getId(), word.getId(), true).getStage());
        assertEquals(3, reviewService.record(user.getId(), word.getId(), true).getStage());

        ReviewRecordResponse fourth = reviewService.record(user.getId(), word.getId(), true);
        assertEquals(4, fourth.getStage());
        assertEquals(TODAY.plusDays(15), fourth.getNextReviewDate());

        // 已是上限，再答对仍停在 4
        ReviewRecordResponse fifth = reviewService.record(user.getId(), word.getId(), true);
        assertEquals(4, fifth.getStage());
        assertEquals(TODAY.plusDays(15), fifth.getNextReviewDate());
    }

    @Test
    @DisplayName("§4.2 答错：stage 退回 1、下次 +1 天、progress.wrong_count+1、收录错题本")
    void recordWrongResetsStageAndAddsMistake() {
        Word word = newWord("wrong");
        dueProgress(word.getId(), 3, TODAY);

        ReviewRecordResponse response = reviewService.record(user.getId(), word.getId(), false);

        assertEquals(1, response.getStage());
        assertEquals(TODAY.plusDays(1), response.getNextReviewDate());
        WordProgress progress = selectProgress(word.getId());
        assertEquals(1, progress.getStage());
        assertEquals(1, progress.getWrongCount());

        MistakeEntry entry = selectMistake(word.getId());
        assertNotNull(entry);
        assertEquals(1, entry.getWrongCount());
        assertEquals(Constants.SOURCE_REVIEW, entry.getSource());
        assertEquals(0, entry.getMastered());
    }

    @Test
    @DisplayName("§4.2 同一词多次答错 → 错题本累加 wrong_count，不重复建记录")
    void recordWrongAccumulatesSingleMistakeEntry() {
        Word word = newWord("accum");
        dueProgress(word.getId(), 2, TODAY);

        reviewService.record(user.getId(), word.getId(), false);
        reviewService.record(user.getId(), word.getId(), false);
        reviewService.record(user.getId(), word.getId(), false);

        Long count = mistakeEntryMapper.selectCount(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getUserId, user.getId())
                .eq(MistakeEntry::getWordId, word.getId()));
        assertEquals(1L, count);
        assertEquals(3, selectMistake(word.getId()).getWrongCount());
    }

    @Test
    @DisplayName("§4.2 已标记「已掌握」的词再答错 → 重置为未掌握")
    void recordWrongResetsMasteredFlag() {
        Word word = newWord("reopen");
        dueProgress(word.getId(), 1, TODAY);
        MistakeEntry entry = new MistakeEntry();
        entry.setUserId(user.getId());
        entry.setWordId(word.getId());
        entry.setSource(Constants.SOURCE_REVIEW);
        entry.setWrongCount(1);
        entry.setMastered(1);
        mistakeEntryMapper.insert(entry);

        reviewService.record(user.getId(), word.getId(), false);

        assertEquals(0, selectMistake(word.getId()).getMastered());
        assertEquals(2, selectMistake(word.getId()).getWrongCount());
    }

    @Test
    @DisplayName("§4.2 写一条 REVIEW 流水（book_id 取自进度行），且同日重复不重复写")
    void recordWritesIdempotentReviewRecord() {
        Word word = newWord("flow");
        dueProgress(word.getId(), 1, TODAY);

        reviewService.record(user.getId(), word.getId(), true);
        reviewService.record(user.getId(), word.getId(), true);

        List<StudyRecord> records = studyRecordMapper.selectList(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, user.getId())
                .eq(StudyRecord::getWordId, word.getId())
                .eq(StudyRecord::getStudyType, Constants.STUDY_TYPE_REVIEW));
        assertEquals(1, records.size());
        assertEquals(book.getId(), records.get(0).getBookId());
        assertEquals(TODAY, records.get(0).getStudyDate());
        assertEquals(1, records.get(0).getCorrect());
    }

    @Test
    @DisplayName("§4.2 复习后该词不再出现在今日待复习（next_review_date 已推到未来）")
    void recordRemovesWordFromDueList() {
        Word word = newWord("leave");
        dueProgress(word.getId(), 1, TODAY);

        assertEquals(1, reviewService.today(user.getId()).getTotal());
        reviewService.record(user.getId(), word.getId(), true);
        assertEquals(0, reviewService.today(user.getId()).getTotal());
    }

    @Test
    @DisplayName("§4.2 没有学习记录的词 → 1005（不存在复习上下文）")
    void recordUnknownWordThrows1005() {
        Word word = newWord("nolearn");

        BizException e = assertThrows(BizException.class,
                () -> reviewService.record(user.getId(), word.getId(), true));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("§4.2 wordId=null → 1005（不抛 NPE）")
    void recordNullWordIdThrows1005() {
        BizException e = assertThrows(BizException.class,
                () -> reviewService.record(user.getId(), null, true));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    // ---------------------------------------------------------------
    // 查询辅助
    // ---------------------------------------------------------------

    private WordProgress selectProgress(Long wordId) {
        return wordProgressMapper.selectOne(Wrappers.<WordProgress>lambdaQuery()
                .eq(WordProgress::getUserId, user.getId())
                .eq(WordProgress::getWordId, wordId)
                .last("LIMIT 1"));
    }

    private MistakeEntry selectMistake(Long wordId) {
        return mistakeEntryMapper.selectOne(Wrappers.<MistakeEntry>lambdaQuery()
                .eq(MistakeEntry::getUserId, user.getId())
                .eq(MistakeEntry::getWordId, wordId)
                .last("LIMIT 1"));
    }
}
