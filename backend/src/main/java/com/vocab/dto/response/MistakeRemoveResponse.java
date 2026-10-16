package com.vocab.dto.response;

/**
 * 移除错题（《接口详细设计》§5.3）。
 *
 * <pre>
 * { "mistakeId": 501, "removed": true }
 * </pre>
 *
 * <p>逻辑删除（{@code deleted=1}），不是物理 DELETE——
 * 用户「移除后重新收录」时新行不会与历史行冲突（design.md §4.4）。
 */
public class MistakeRemoveResponse {

    private Long mistakeId;
    private Boolean removed;

    public MistakeRemoveResponse() {
    }

    public MistakeRemoveResponse(Long mistakeId, Boolean removed) {
        this.mistakeId = mistakeId;
        this.removed = removed;
    }

    public Long getMistakeId() {
        return mistakeId;
    }

    public void setMistakeId(Long mistakeId) {
        this.mistakeId = mistakeId;
    }

    public Boolean getRemoved() {
        return removed;
    }

    public void setRemoved(Boolean removed) {
        this.removed = removed;
    }
}
