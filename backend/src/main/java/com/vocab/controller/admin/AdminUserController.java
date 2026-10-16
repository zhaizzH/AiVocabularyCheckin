package com.vocab.controller.admin;

import com.vocab.common.PageResult;
import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.request.UserStatusRequest;
import com.vocab.dto.response.AdminUserResponse;
import com.vocab.dto.response.UserStatusResponse;
import com.vocab.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户接口（《接口详细设计》§8.6 / §8.7）。
 *
 * <p>ADMIN 角色校验由 {@code JwtInterceptor} 完成（403 + 1007），此处不重复。
 *
 * <p>操作者 id 取 {@link UserContext}（来自 token）而非请求参数：
 * 操作日志必须记录<b>真实操作者</b>，若允许前端传 operatorId，
 * 任何人都能在日志里伪造成别人操作。这与学生端「userId 一律取 token」同一条原则。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminService adminService;

    public AdminUserController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** §8.6 用户分页查询（keyword 按用户名搜索）。 */
    @GetMapping
    public Result<PageResult<AdminUserResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(adminService.listUsers(keyword, page, size));
    }

    /** §8.7 禁用 / 启用用户（不允许禁用 ADMIN → 1005）。 */
    @PutMapping("/{userId}/status")
    public Result<UserStatusResponse> updateStatus(@PathVariable Long userId,
                                                   @Valid @RequestBody UserStatusRequest request) {
        return Result.ok(adminService.updateUserStatus(
                UserContext.getUserId(), userId, request));
    }
}
