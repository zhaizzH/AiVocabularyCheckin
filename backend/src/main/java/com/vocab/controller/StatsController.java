package com.vocab.controller;

import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.response.HeatmapResponse;
import com.vocab.dto.response.MasteryResponse;
import com.vocab.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端统计接口（《接口详细设计》§7）。
 *
 * <p>Controller 只做参数接收 + 单次 Service 调用 + {@link Result} 包装。
 * 补零与分档都在 {@code StatsService}，SQL 聚合在 {@code StatsMapper}。
 */
@RestController
@RequestMapping("/api/client/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * §7.1 打卡热力图。
     *
     * <p>{@code weeks} 缺省 12（契约 §7.1）；非法值（0/负数/非数字）
     * 由 Service 回落默认值、由全局处理器处理非数字。
     * 返回的 {@code days} 长度恒为 {@code weeks × 7}。
     */
    @GetMapping("/heatmap")
    public Result<HeatmapResponse> heatmap(
            @RequestParam(required = false) Integer weeks) {
        return Result.ok(statsService.heatmap(UserContext.getUserId(), weeks));
    }

    /**
     * §7.2 掌握度曲线。
     *
     * <p>{@code days} 缺省 30（契约 §7.2）；返回的 {@code points}
     * 长度恒为 {@code days}。
     */
    @GetMapping("/mastery")
    public Result<MasteryResponse> mastery(
            @RequestParam(required = false) Integer days) {
        return Result.ok(statsService.mastery(UserContext.getUserId(), days));
    }
}
