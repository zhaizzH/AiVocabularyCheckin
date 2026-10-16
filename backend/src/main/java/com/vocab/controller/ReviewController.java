package com.vocab.controller;

import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.request.ReviewRecordRequest;
import com.vocab.dto.response.ReviewRecordResponse;
import com.vocab.dto.response.ReviewTodayResponse;
import com.vocab.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端复习接口（《接口详细设计》§4）。
 *
 * <p>Controller 只做参数接收 + 单次 Service 调用 + {@link Result} 包装。
 */
@RestController
@RequestMapping("/api/client/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** §4.1 今日待复习列表。 */
    @GetMapping("/today")
    public Result<ReviewTodayResponse> today() {
        return Result.ok(reviewService.today(UserContext.getUserId()));
    }

    /** §4.2 提交复习结果（答对进下一阶段，答错退回第 1 阶段）。 */
    @PostMapping("/record")
    public Result<ReviewRecordResponse> record(@Valid @RequestBody ReviewRecordRequest request) {
        return Result.ok(reviewService.record(
                UserContext.getUserId(),
                request.getWordId(),
                Boolean.TRUE.equals(request.getCorrect())));
    }
}
