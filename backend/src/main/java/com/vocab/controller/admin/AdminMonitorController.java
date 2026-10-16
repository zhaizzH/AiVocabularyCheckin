package com.vocab.controller.admin;

import com.vocab.common.Result;
import com.vocab.dto.response.AiStatsResponse;
import com.vocab.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端系统监控接口（《接口详细设计》§8.10）。
 *
 * <p>ADMIN 角色校验由 {@code JwtInterceptor} 完成（403 + 1007），此处不重复。
 *
 * <p><b>注意</b>：本接口没有 query 参数，因为统计口径固定为「服务启动以来」
 * 的累计值——{@code AiMetrics} 是内存计数器，重启即清零，没有任何
 * 「按日期筛选」的能力（需求清单 §6.3 明确一期不做完整监控平台）。
 * 若前端真要「今日调用次数」，需要的是持久化埋点，属二期范围。
 */
@RestController
@RequestMapping("/api/admin/monitor")
public class AdminMonitorController {

    private final AdminService adminService;

    public AdminMonitorController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** §8.10 AI 调用监控（内存计数器 + 注册用户数）。 */
    @GetMapping("/ai-stats")
    public Result<AiStatsResponse> aiStats() {
        return Result.ok(adminService.aiStats());
    }
}
