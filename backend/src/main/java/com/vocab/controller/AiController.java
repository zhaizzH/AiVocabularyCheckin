package com.vocab.controller;

import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.request.AiExampleRequest;
import com.vocab.dto.request.AiExplainRequest;
import com.vocab.dto.response.AiExampleResponse;
import com.vocab.dto.response.AiExplainResponse;
import com.vocab.dto.response.AiReportResponse;
import com.vocab.service.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端 AI 接口（《接口详细设计》§6）。
 *
 * <p>三个接口全部经本服务<b>中转</b>（前端不直连 ai-service），
 * 降级行为见 {@link AiService}。
 *
 * <p>{@code report} 无请求体——后端自己汇总统计
 * （契约 §6.3「后端汇总统计传给 AI」）。
 */
@RestController
@RequestMapping("/api/client/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    /** §6.1 AI 例句（降级时仍返回 code:"0" + degraded:true）。 */
    @PostMapping("/example")
    public Result<AiExampleResponse> example(@Valid @RequestBody AiExampleRequest request) {
        return Result.ok(aiService.example(UserContext.getUserId(), request.getWordId()));
    }

    /** §6.2 AI 错题讲解（失败 → 5001/5002）。 */
    @PostMapping("/explain")
    public Result<AiExplainResponse> explain(@Valid @RequestBody AiExplainRequest request) {
        return Result.ok(aiService.explain(UserContext.getUserId(), request.getMistakeId()));
    }

    /** §6.3 AI 学习报告（学习天数 < 3 → 5003；失败 → 5001/5002）。 */
    @PostMapping("/report")
    public Result<AiReportResponse> report() {
        return Result.ok(aiService.report(UserContext.getUserId()));
    }
}
