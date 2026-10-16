package com.vocab.service;

import com.vocab.common.PageResult;
import com.vocab.dto.request.UserStatusRequest;
import com.vocab.dto.request.WordUpsertRequest;
import com.vocab.dto.response.AdminUserResponse;
import com.vocab.dto.response.AiStatsResponse;
import com.vocab.dto.response.UserStatusResponse;
import com.vocab.dto.response.WordDeleteResponse;
import com.vocab.dto.response.WordResponse;

/**
 * 管理端服务（《接口详细设计》§8 中除「词书列表 / 导入 / 删除」之外的部分）。
 *
 * <p><b>为什么词书相关接口不在这里</b>：{@code GET /api/admin/books}、
 * {@code POST /api/admin/books/import}、{@code DELETE /api/admin/books/{bookId}}
 * 早已作为 {@link WordBookService#adminListBooks()} /
 * {@link WordBookService#importBook} / {@link WordBookService#deleteBook}
 * 存在（阶段 6 落地，词书导入/业务属词库域）。本接口只承载
 * 阶段 10 新增的<b>单词</b>、<b>用户</b>、<b>监控</b>三类管理能力，
 * 避免把词书逻辑复制一份造成两份漂移。
 *
 * <p><b>鉴权不在本层</b>：{@code /api/admin/**} 的 ADMIN 角色校验由
 * {@code JwtInterceptor} 统一完成（HTTP 403 + 1007），Service 不做角色判断
 * ——拦截器是唯一入口，Service 再判一次既冗余又容易与拦截器口径不一。
 * Service 只接收「操作者 id」用于写操作日志（契约 §8.7 要求记录操作者）。
 */
public interface AdminService {

    // ---------------- 单词管理（§8.2 / §8.3 / §8.4 / §8.5） ----------------

    /**
     * 单词分页查询（契约 §8.2）。
     *
     * @param bookId  按词书过滤；为 {@code null} 时不过滤（全库单词）
     * @param keyword 按单词原文模糊搜索；空白时不过滤
     * @param page    页码（从 1 开始，非法值回落默认）
     * @param size    每页条数（非法值回落默认，上限由分页插件截断）
     */
    PageResult<WordResponse> listWords(Long bookId, String keyword, long page, long size);

    /**
     * 新增单词（契约 §8.3）：成功后所属词书 {@code wordCount + 1}。
     *
     * @throws com.vocab.common.BizException 1005 参数非法；2001 词书不存在
     */
    WordResponse createWord(WordUpsertRequest request);

    /**
     * 修改单词（契约 §8.4）：字段均可选，只更新传入的字段；不存在的单词 → 1005。
     *
     * @throws com.vocab.common.BizException 1005 参数非法 / 单词不存在；2001 目标词书不存在
     */
    WordResponse updateWord(Long wordId, WordUpsertRequest request);

    /**
     * 删除单词（契约 §8.5）：逻辑删除，所属词书 {@code wordCount − 1}。
     *
     * @throws com.vocab.common.BizException 1005 单词不存在
     */
    WordDeleteResponse deleteWord(Long wordId);

    // ---------------- 用户管理（§8.6 / §8.7） ----------------

    /**
     * 用户分页查询（契约 §8.6）。
     *
     * @param keyword 按用户名模糊搜索（可选）
     * @param page    页码
     * @param size    每页条数
     */
    PageResult<AdminUserResponse> listUsers(String keyword, long page, long size);

    /**
     * 禁用 / 启用用户（契约 §8.7）：禁用后该用户 token 下次校验即 401（1003）。
     *
     * @param operatorId 操作者（当前 ADMIN）id，仅用于 INFO 操作日志
     * @throws com.vocab.common.BizException 1005 参数非法 / 目标用户不存在 / 目标是 ADMIN
     */
    UserStatusResponse updateUserStatus(Long operatorId, Long userId, UserStatusRequest request);

    // ---------------- 监控（§8.10） ----------------

    /**
     * AI 调用监控（契约 §8.10）：内存计数器 + 注册用户数。
     *
     * <p>计数为「服务启动以来」的累计值，重启清零。
     */
    AiStatsResponse aiStats();
}
