package com.vocab.controller;

import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.request.StudyRecordRequest;
import com.vocab.dto.response.CheckInResponse;
import com.vocab.dto.response.StreakResponse;
import com.vocab.dto.response.StudyPlanResponse;
import com.vocab.dto.response.StudyRecordResponse;
import com.vocab.service.StudyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端学习接口（《接口详细设计》§3）。
 *
 * <p>Controller 只做参数接收 + 单次 Service 调用 + {@link Result} 包装，
 * 无任何业务分支（quality-guidelines.md「Controller 不写业务逻辑」）。
 *
 * <p>当前用户 id 一律取 {@link UserContext}（来自 token），
 * <b>绝不接受请求参数传入 userId</b>。
 */
@RestController
@RequestMapping("/api/client/study")
public class StudyController {

    private final StudyService studyService;

    public StudyController(StudyService studyService) {
        this.studyService = studyService;
    }

    /** §3.1 今日学习计划。 */
    @GetMapping("/plan")
    public Result<StudyPlanResponse> plan() {
        return Result.ok(studyService.plan(UserContext.getUserId()));
    }

    /** §3.2 逐词学习上报（known=false 自动收录错题）。 */
    @PostMapping("/record")
    public Result<StudyRecordResponse> record(@Valid @RequestBody StudyRecordRequest request) {
        return Result.ok(studyService.record(
                UserContext.getUserId(),
                request.getWordId(),
                request.getBookId(),
                Boolean.TRUE.equals(request.getKnown())));
    }

    /** §3.3 打卡（无请求体）。 */
    @PostMapping("/checkin")
    public Result<CheckInResponse> checkIn() {
        return Result.ok(studyService.checkIn(UserContext.getUserId()));
    }

    /** §3.4 连续打卡与累计统计。 */
    @GetMapping("/streak")
    public Result<StreakResponse> streak() {
        return Result.ok(studyService.streak(UserContext.getUserId()));
    }
}
