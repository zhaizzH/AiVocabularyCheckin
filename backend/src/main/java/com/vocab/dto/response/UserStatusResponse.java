package com.vocab.dto.response;

/**
 * 禁用 / 启用用户响应（《接口详细设计》§8.7）。
 *
 * <pre>
 * { "userId": 1001, "status": 0 }
 * </pre>
 *
 * <p>{@code status} 回显的是<b>操作后</b>的状态（0 禁用 / 1 启用），
 * 不是「是否成功」——失败会以 1005 / 1003 错误码返回，不会走到这里。
 * 管理端表格据此就地刷新该行状态，无需重新拉整页列表。
 */
public class UserStatusResponse {

    private Long userId;
    private Integer status;

    public UserStatusResponse() {
    }

    public UserStatusResponse(Long userId, Integer status) {
        this.userId = userId;
        this.status = status;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
