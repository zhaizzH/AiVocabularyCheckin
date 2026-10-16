package com.vocab.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.dto.response.ReviewRecordResponse;
import com.vocab.dto.response.ReviewTodayResponse;
import com.vocab.dto.response.ReviewWordResponse;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.WordProgress;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.WordProgressMapper;
import com.vocab.service.MistakeService;
import com.vocab.service.ReviewSchedule;
import com.vocab.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 复习服务实现（契约 §4 + design.md §4.3）。
 *
 * <p>调度算法全部委托给 {@link ReviewSchedule}（纯函数，单独可测）——
 * 本类只负责「取列表」「更新进度」「写流水」「收录错题」四件事。
 */
@Service
public class ReviewServiceImpl implements ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewServiceImpl.class);

    private final WordProgressMapper wordProgressMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final MistakeService mistakeService;
    private final Clock clock;

    public ReviewServiceImpl(WordProgressMapper wordProgressMapper,
                             StudyRecordMapper studyRecordMapper,
                             MistakeService mistakeService,
                             Clock clock) {
        this.wordProgressMapper = wordProgressMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.mistakeService = mistakeService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------
    // §4.1 今日待复习
    // ---------------------------------------------------------------

    @Override
    public ReviewTodayResponse today(Long userId) {
        List<ReviewWordResponse> words =
                wordProgressMapper.selectDueReviewWords(userId, LocalDate.now(clock));
        return new ReviewTodayResponse(words.size(), words);
    }

    // ---------------------------------------------------------------
    // §4.2 提交复习结果
    // ---------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewRecordResponse record(Long userId, Long wordId, boolean correct) {
        LocalDate today = LocalDate.now(clock);

        WordProgress progress = wordId == null ? null : wordProgressMapper.selectOne(
                Wrappers.<WordProgress>lambdaQuery()
                        .eq(WordProgress::getUserId, userId)
                        .eq(WordProgress::getWordId, wordId)
                        .last("LIMIT 1"));
        if (progress == null) {
            // 没学过就不存在复习上下文——契约 §4.2 只定义了 wordId 与 correct，
            // 未定义错误码。按参数非法（1005）处理，与「词不属于该词书」一致。
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "该单词没有学习记录，无法复习");
        }

        int currentStage = progress.getStage() == null ? Constants.MIN_STAGE : progress.getStage();
        int newStage;
        if (correct) {
            newStage = ReviewSchedule.nextStage(currentStage);
        } else {
            // 答错退回第 1 阶段（需求清单 §3.2），1 天后重来。
            newStage = Constants.MIN_STAGE;
            progress.setWrongCount(safeInt(progress.getWrongCount()) + 1);
        }
        LocalDate nextReviewDate = ReviewSchedule.nextReviewDate(newStage, today);

        progress.setStage(newStage);
        progress.setNextReviewDate(nextReviewDate);
        progress.setLearned(1);
        wordProgressMapper.updateById(progress);

        if (!correct) {
            // 答错 → 确保收录错题本（同词已存在则累加，不重复建记录）。
            mistakeService.addOrIncrement(userId, wordId, Constants.SOURCE_REVIEW);
        }

        insertReviewRecordIfAbsent(userId, progress.getBookId(), wordId, today, correct);
        log.info("复习上报 userId={} wordId={} correct={} stage={} nextReviewDate={}",
                userId, wordId, correct, newStage, nextReviewDate);
        return new ReviewRecordResponse(wordId, newStage, nextReviewDate);
    }

    /**
     * 写复习流水（幂等：同词同日同类型只一条）。
     *
     * <p>{@code book_id} 取自进度行——契约 §4.2 刻意不带 bookId，
     * 词书维度从已有进度读出即可。
     */
    private void insertReviewRecordIfAbsent(Long userId, Long bookId, Long wordId,
                                            LocalDate today, boolean correct) {
        Long existing = studyRecordMapper.selectCount(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getUserId, userId)
                .eq(StudyRecord::getWordId, wordId)
                .eq(StudyRecord::getStudyDate, today)
                .eq(StudyRecord::getStudyType, Constants.STUDY_TYPE_REVIEW));
        if (existing != null && existing > 0) {
            return;
        }
        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(bookId);
        record.setWordId(wordId);
        record.setStudyType(Constants.STUDY_TYPE_REVIEW);
        record.setStudyDate(today);
        record.setStreakDays(0);
        record.setCorrect(correct ? 1 : 0);
        try {
            studyRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.debug("复习流水已存在，跳过 userId={} wordId={}", userId, wordId);
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
