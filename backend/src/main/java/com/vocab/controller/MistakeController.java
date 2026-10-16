package com.vocab.controller;

import com.vocab.common.PageResult;
import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.response.MistakeMasteredResponse;
import com.vocab.dto.response.MistakeRemoveResponse;
import com.vocab.dto.response.MistakeResponse;
import com.vocab.service.MistakeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端错题接口（《接口详细设计》§5）。
 *
 * <p>Controller 只做参数接收 + 单次 Service 调用 + {@link Result} 包装。
 */
@RestController
@RequestMapping("/api/client/mistakes")
public class MistakeController {

    private final MistakeService mistakeService;

    public MistakeController(MistakeService mistakeService) {
        this.mistakeService = mistakeService;
    }

    /** §5.1 错题列表（mastered 可选，缺省 0 未掌握）。 */
    @GetMapping
    public Result<PageResult<MistakeResponse>> list(
            @RequestParam(required = false) Integer mastered,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(mistakeService.list(UserContext.getUserId(), mastered, page, size));
    }

    /** §5.2 标记已掌握。 */
    @PostMapping("/{mistakeId}/mastered")
    public Result<MistakeMasteredResponse> markMastered(@PathVariable Long mistakeId) {
        return Result.ok(mistakeService.markMastered(UserContext.getUserId(), mistakeId));
    }

    /** §5.3 移除错题（逻辑删除）。 */
    @DeleteMapping("/{mistakeId}")
    public Result<MistakeRemoveResponse> remove(@PathVariable Long mistakeId) {
        return Result.ok(mistakeService.remove(UserContext.getUserId(), mistakeId));
    }
}
