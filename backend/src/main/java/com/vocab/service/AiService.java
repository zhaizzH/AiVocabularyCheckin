package com.vocab.service;

import com.vocab.dto.response.AiExampleResponse;
import com.vocab.dto.response.AiExplainResponse;
import com.vocab.dto.response.AiReportResponse;

/**
 * AI 中转服务（契约 §6 + 需求清单 §4）。
 *
 * <p>三个用例的降级行为<b>刻意不同</b>，这是本模块最容易写错的地方：
 * <ul>
 *   <li>{@link #example} —— <b>降级不抛异常</b>，返回 {@code code:"0"} +
 *       {@code degraded:true} + 库内例句兜底。前端按 {@code degraded} 显示黄条，
 *       不当作错误（契约 §6.1 明示）。</li>
 *   <li>{@link #explain} —— 无兜底内容，降级抛 5001/5002（契约 §6.2）。</li>
 *   <li>{@link #report} —— 先校验学习天数，不足 3 天抛 5003 <b>且不发起 HTTP</b>；
 *       调用失败抛 5001/5002（契约 §6.3）。</li>
 * </ul>
 */
public interface AiService {

    /**
     * AI 例句（契约 §6.1）。
     *
     * <p><b>本方法绝不因 AI 不可用而抛异常</b>——见类注释。
     *
     * @throws com.vocab.common.BizException 1005 单词不存在
     */
    AiExampleResponse example(Long userId, Long wordId);

    /**
     * AI 错题讲解（契约 §6.2）。
     *
     * @throws com.vocab.common.BizException 4001 错题不存在或不属于该用户；
     *                                       5001 AI 超时；5002 AI 异常
     */
    AiExplainResponse explain(Long userId, Long mistakeId);

    /**
     * AI 学习报告（契约 §6.3）。
     *
     * @throws com.vocab.common.BizException 5003 学习天数不足（不发起 HTTP）；
     *                                       5001 AI 超时；5002 AI 异常
     */
    AiReportResponse report(Long userId);
}
