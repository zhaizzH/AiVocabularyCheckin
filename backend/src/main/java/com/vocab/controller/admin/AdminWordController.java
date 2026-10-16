package com.vocab.controller.admin;

import com.vocab.common.PageResult;
import com.vocab.common.Result;
import com.vocab.dto.request.WordUpsertRequest;
import com.vocab.dto.response.WordDeleteResponse;
import com.vocab.dto.response.WordResponse;
import com.vocab.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端单词接口（《接口详细设计》§8.2 – §8.5）。
 *
 * <p><b>鉴权由拦截器统一完成</b>：{@code /api/admin/**} 在
 * {@code JwtInterceptor} 中校验 token + 账号状态 + ADMIN 角色
 * （非管理员 → HTTP 403 + 1007）。本 Controller <b>不写任何角色判断</b>：
 * 拦截器是唯一入口，重复判断既冗余又可能与拦截器口径漂移
 * （quality-guidelines.md「Controller 只做参数接收 + 单次 Service 调用 + 包装」）。
 */
@RestController
@RequestMapping("/api/admin/words")
public class AdminWordController {

    private final AdminService adminService;

    public AdminWordController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** §8.2 单词分页查询（bookId / keyword 均可选）。 */
    @GetMapping
    public Result<PageResult<WordResponse>> list(
            @RequestParam(required = false) Long bookId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(adminService.listWords(bookId, keyword, page, size));
    }

    /** §8.3 新增单词（成功后词书 wordCount+1）。 */
    @PostMapping
    public Result<WordResponse> create(@Valid @RequestBody WordUpsertRequest request) {
        return Result.ok(adminService.createWord(request));
    }

    /** §8.4 修改单词（字段均可选）。 */
    @PutMapping("/{wordId}")
    public Result<WordResponse> update(@PathVariable Long wordId,
                                       @Valid @RequestBody WordUpsertRequest request) {
        return Result.ok(adminService.updateWord(wordId, request));
    }

    /** §8.5 删除单词（逻辑删除，词书 wordCount−1）。 */
    @DeleteMapping("/{wordId}")
    public Result<WordDeleteResponse> delete(@PathVariable Long wordId) {
        return Result.ok(adminService.deleteWord(wordId));
    }
}
