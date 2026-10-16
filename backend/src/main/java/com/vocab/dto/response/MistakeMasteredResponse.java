package com.vocab.dto.response;

/**
 * 标记错题已掌握（《接口详细设计》§5.2）。
 *
 * <pre>
 * { "mistakeId": 501, "mastered": true }
 * </pre>
 *
 * <p>与 {@link MistakeRemoveResponse} 同为 `{id, 布尔}` 形状但字段名不同
 * （{@code mastered} vs {@code removed}），故不强行共用一个类——
 * 字段名本身就是契约的一部分。
 */
public class MistakeMasteredResponse {

    private Long mistakeId;
    private Boolean mastered;

    public MistakeMasteredResponse() {
    }

    public MistakeMasteredResponse(Long mistakeId, Boolean mastered) {
        this.mistakeId = mistakeId;
        this.mastered = mastered;
    }

    public Long getMistakeId() {
        return mistakeId;
    }

    public void setMistakeId(Long mistakeId) {
        this.mistakeId = mistakeId;
    }

    public Boolean getMastered() {
        return mastered;
    }

    public void setMastered(Boolean mastered) {
        this.mastered = mastered;
    }
}
