package com.vocab.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vocab.ai.AiCallException;
import com.vocab.ai.AiMetrics;
import com.vocab.ai.AiServiceClient;
import com.vocab.ai.dto.AiExampleCallRequest;
import com.vocab.ai.dto.AiExampleCallResponse;
import com.vocab.ai.dto.AiExplainCallRequest;
import com.vocab.ai.dto.AiExplainCallResponse;
import com.vocab.ai.dto.AiReportCallRequest;
import com.vocab.ai.dto.AiReportCallResponse;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.dto.response.AiExampleResponse;
import com.vocab.dto.response.AiExplainResponse;
import com.vocab.dto.response.AiReportContent;
import com.vocab.dto.response.AiReportResponse;
import com.vocab.entity.MistakeEntry;
import com.vocab.entity.Word;
import com.vocab.entity.WordBook;
import com.vocab.mapper.MistakeEntryMapper;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.WordBookMapper;
import com.vocab.mapper.WordMapper;
import com.vocab.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 中转服务实现（契约 §6 + design.md §6）。
 *
 * <p><b>三个接口的降级行为不同，逐一说明为什么</b>：
 *
 * <h3>1. {@code example}：降级不抛异常（本模块最关键的一条）</h3>
 * 契约 §6.1 明确规定：AI 不可用时 HTTP 仍 200、{@code code:"0"}、
 * {@code degraded:true}、{@code example} 填<b>词库内置例句</b>、{@code mnemonic} 为空。
 * 前端据此显示黄色提示条，而<b>不是</b>当作错误。
 *
 * <p>这一点极易被写成「降级 = 抛 5001/5002」——那就违反了前端契约：
 * 前端会弹错误对话框，而用户其实拿到了一份可用的例句。
 * 因此本方法把 {@link AiCallException} <b>捕获在方法内部</b>并转为
 * {@code degraded:true} 的正常返回，<b>不做任何异常透传</b>。
 *
 * <h3>2. {@code explain}：降级抛 5001/5002（无兜底内容）</h3>
 * 讲解是针对「这个词你为什么记错」的生成内容，没有可靠的本地替代品；
 * 返回一份捏造的讲解比报错更糟（会误导学习）。故如实报错，
 * 前端展示「AI 讲解暂时不可用」（契约 §6.2）。
 *
 * <h3>3. {@code report}：先本地校验，再调 AI</h3>
 * 学习天数 &lt; 3 时<b>不发起 HTTP 调用</b>，直接 5003（契约 §6.3）。
 * 这个顺序很重要：既省掉一次注定无意义的 AI 调用（也省 token 与等待），
 * 也保证「数据不足」这个业务判断不依赖 AI 服务的可用性。
 */
@Service
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final WordMapper wordMapper;
    private final WordBookMapper wordBookMapper;
    private final MistakeEntryMapper mistakeEntryMapper;
    private final StatsMapper statsMapper;
    private final AiServiceClient aiServiceClient;
    private final AiMetrics aiMetrics;
    private final Clock clock;

    public AiServiceImpl(WordMapper wordMapper,
                         WordBookMapper wordBookMapper,
                         MistakeEntryMapper mistakeEntryMapper,
                         StatsMapper statsMapper,
                         AiServiceClient aiServiceClient,
                         AiMetrics aiMetrics,
                         Clock clock) {
        this.wordMapper = wordMapper;
        this.wordBookMapper = wordBookMapper;
        this.mistakeEntryMapper = mistakeEntryMapper;
        this.statsMapper = statsMapper;
        this.aiServiceClient = aiServiceClient;
        this.aiMetrics = aiMetrics;
        this.clock = clock;
    }

    // ---------------------------------------------------------------
    // §6.1 AI 例句（降级不抛异常）
    // ---------------------------------------------------------------

    @Override
    public AiExampleResponse example(Long userId, Long wordId) {
        Word word = wordId == null ? null : wordMapper.selectById(wordId);
        if (word == null) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "单词不存在");
        }

        // 词书分类作为 AI 的难度上下文（CET4/CET6/KAOYAN）。
        String context = resolveCategory(word.getBookId());
        AiExampleCallRequest request = new AiExampleCallRequest(
                word.getWord(), word.getTranslation(), context);

        long start = System.currentTimeMillis();
        try {
            AiExampleCallResponse callResponse = aiServiceClient.callExample(request);
            long cost = System.currentTimeMillis() - start;
            aiMetrics.recordSuccess(AiMetrics.FEATURE_EXAMPLE, cost);

            return new AiExampleResponse(
                    wordId,
                    // AI 未给例句时仍回退到库内例句，而不是把空串交给前端。
                    firstNonBlank(callResponse.getExample(), word.getExample()),
                    emptyIfNull(callResponse.getExampleTranslation()),
                    emptyIfNull(callResponse.getMnemonic()),
                    false);
        } catch (AiCallException e) {
            // ============================================================
            //  此处【刻意】吞掉异常，不向外抛 5001/5002。
            //  契约 §6.1：降级必须走「HTTP 200 + code:"0" + degraded:true」，
            //  前端按 degraded 显示黄条而非错误弹窗。
            //  若改成 throw new BizException(AI_TIMEOUT/AI_ERROR)，
            //  就把一次「可用的降级结果」变成了「用户可见的失败」——
            //  这是本模块最容易被误改的一行。
            // ============================================================
            long cost = System.currentTimeMillis() - start;
            aiMetrics.recordDegrade(
                    AiMetrics.FEATURE_EXAMPLE, cost,
                    e.isTimeout() ? AiMetrics.REASON_TIMEOUT : AiMetrics.REASON_ERROR,
                    "返回库内例句",
                    LocalDateTime.now(clock));

            log.info("AI 例句降级 userId={} wordId={} timeout={}",
                    userId, wordId, e.isTimeout());

            // 兜底：example 用词库内置例句；mnemonic 留空（契约 §6.1）。
            return new AiExampleResponse(
                    wordId,
                    emptyIfNull(word.getExample()),
                    "",
                    "",
                    true);
        }
    }

    // ---------------------------------------------------------------
    // §6.2 AI 错题讲解（降级抛 5001/5002）
    // ---------------------------------------------------------------

    @Override
    public AiExplainResponse explain(Long userId, Long mistakeId) {
        MistakeEntry entry = mistakeId == null ? null : mistakeEntryMapper.selectById(mistakeId);
        if (entry == null || !entry.getUserId().equals(userId)) {
            // 用 4001 而非 1007：对调用者而言「不是你的错题」与「不存在」不可区分，
            // 返回统一结果还能避免通过 id 枚举探测他人错题。
            throw new BizException(ErrorCode.MISTAKE_NOT_FOUND);
        }

        Word word = wordMapper.selectById(entry.getWordId());
        if (word == null) {
            // 词被删除但错题记录还在（逻辑删除的历史行）——无法生成讲解。
            throw new BizException(ErrorCode.MISTAKE_NOT_FOUND);
        }

        // 本项目不记录用户当时选错的选项（无选项数据），故用「来源 + 错误次数」
        // 构造错题上下文，让 AI 至少能围绕「这个词反复出错」给出讲解。
        String wrongContext = buildWrongContext(entry);
        AiExplainCallRequest request = new AiExplainCallRequest(
                word.getWord(), word.getTranslation(), wrongContext);

        long start = System.currentTimeMillis();
        try {
            AiExplainCallResponse callResponse = aiServiceClient.callExplain(request);
            aiMetrics.recordSuccess(AiMetrics.FEATURE_EXPLAIN, System.currentTimeMillis() - start);

            return new AiExplainResponse(
                    mistakeId,
                    emptyIfNull(callResponse.getExplanation()),
                    emptyIfNull(callResponse.getAdvice()),
                    false);
        } catch (AiCallException e) {
            // 讲解无本地兜底内容 → 如实报错（契约 §6.2）。
            throw degradeToBizException(AiMetrics.FEATURE_EXPLAIN, e, start,
                    "抛 " + (e.isTimeout() ? "5001" : "5002"));
        }
    }

    // ---------------------------------------------------------------
    // §6.3 AI 学习报告（先校验学习天数，再调 AI）
    // ---------------------------------------------------------------

    @Override
    public AiReportResponse report(Long userId) {
        int studyDays = safeInt(statsMapper.countCheckInDays(userId));
        if (studyDays < Constants.MIN_REPORT_STUDY_DAYS) {
            // 前置校验：不发起 HTTP（契约 §6.3）。顺序不可颠倒——
            // 否则「数据不足」要等一次 AI 调用返回才知道，白白付出等待与 token。
            log.info("学习报告数据不足 userId={} studyDays={}", userId, studyDays);
            throw new BizException(ErrorCode.AI_DATA_INSUFFICIENT);
        }

        AiReportCallRequest request = new AiReportCallRequest(buildStudyStats(userId, studyDays));

        long start = System.currentTimeMillis();
        try {
            AiReportCallResponse callResponse = aiServiceClient.callReport(request);
            aiMetrics.recordSuccess(AiMetrics.FEATURE_REPORT, System.currentTimeMillis() - start);

            AiReportContent content = new AiReportContent(
                    emptyIfNull(callResponse.getOverview()),
                    emptyIfNull(callResponse.getWeakness()),
                    emptyIfNull(callResponse.getSuggestion()));
            return new AiReportResponse(content, LocalDateTime.now(clock), false);
        } catch (AiCallException e) {
            // 报告无本地兜底内容 → 如实报错（契约 §6.3，前端给重试按钮）。
            throw degradeToBizException(AiMetrics.FEATURE_REPORT, e, start,
                    "抛 " + (e.isTimeout() ? "5001" : "5002"));
        }
    }

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    /**
     * 统计「近两周」的学习数据，供 AI 写报告（契约 §9.3）。
     *
     * <p>所有数字都在 SQL 内聚合（{@code StatsMapper}），
     * 绝不把明细加载到 Java 再算（需求清单 §5.1 红线）。
     */
    private AiReportCallRequest.StudyStats buildStudyStats(Long userId, int studyDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate since = today.minusDays(Constants.REPORT_RECENT_DAYS - 1L);

        int recentCheckInDays = safeInt(statsMapper.countCheckInDaysSince(userId, since));
        // 打卡率 = 近两周打卡天数 / 近两周总天数。
        int checkInRate = (int) Math.round(recentCheckInDays * 100.0 / Constants.REPORT_RECENT_DAYS);

        int totalMistakes = safeInt(statsMapper.countTotalMistakes(userId));
        int masteredMistakes = safeInt(statsMapper.countMasteredMistakes(userId));
        // 掌握度 = 已掌握错题 / 总错题；无错题时视为 100（没有未掌握的薄弱点）。
        int masteryRate = totalMistakes == 0
                ? 100
                : (int) Math.round(masteredMistakes * 100.0 / totalMistakes);

        List<String> topMistakeWords =
                statsMapper.selectTopMistakeWords(userId, Constants.REPORT_TOP_MISTAKES);

        return new AiReportCallRequest.StudyStats(
                studyDays,
                safeInt(statsMapper.countLearnedWords(userId)),
                checkInRate,
                topMistakeWords,
                masteryRate);
    }

    /**
     * 把 AI 调用失败翻译成业务异常，并记录降级事件。
     *
     * <p>{@code explain}/{@code report} 共用——它们的降级行为一致（如实报错）。
     */
    private BizException degradeToBizException(String feature, AiCallException e,
                                               long start, String result) {
        long cost = System.currentTimeMillis() - start;
        aiMetrics.recordDegrade(feature, cost,
                e.isTimeout() ? AiMetrics.REASON_TIMEOUT : AiMetrics.REASON_ERROR,
                result, LocalDateTime.now(clock));
        return new BizException(e.isTimeout() ? ErrorCode.AI_TIMEOUT : ErrorCode.AI_ERROR);
    }

    /** 词书分类（AI 的难度上下文）；词书缺失时用分类未知的通用标识。 */
    private String resolveCategory(Long bookId) {
        if (bookId == null) {
            return "";
        }
        WordBook book = wordBookMapper.selectById(bookId);
        return book == null ? "" : emptyIfNull(book.getCategory());
    }

    private String buildWrongContext(MistakeEntry entry) {
        String source = Constants.SOURCE_REVIEW.equals(entry.getSource()) ? "复习" : "学习";
        int wrongCount = safeInt(entry.getWrongCount());
        return "该词在" + source + "中答错，累计错误 " + wrongCount + " 次";
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : emptyIfNull(fallback);
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
