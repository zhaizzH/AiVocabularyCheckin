package com.vocab.dto.request;

import com.vocab.common.Constants;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改个人信息请求（《接口详细设计》§1.5）。
 *
 * <p>两个字段<b>均可选，但至少要传一项</b>：「至少一项」属于跨字段的整体约束，
 * 用 Bean Validation 表达需要在类上写自定义约束、收益不抵复杂度，
 * 故交由 {@code AuthService} 判断并抛 1005。
 *
 * <p>字段级约束（长度、头像白名单）留在本 DTO：越靠近入口越早失败，
 * 且错误文案由 {@code GlobalExceptionHandler} 统一包装为 HTTP 200 + 1005。
 */
public class UpdateProfileRequest {

    @Size(max = Constants.NICKNAME_MAX_LENGTH, message = "昵称最多 20 字")
    private String nickname;

    /**
     * 头像：一期只允许 6 个内置值（需求清单 §1.3「不做文件上传」）。
     *
     * <p>用正则而非 {@code @Pattern} 引用常量列表：Bean Validation 的注解值
     * 必须是编译期常量，无法引用 {@code Constants.AVATARS}。
     * 内联正则与 {@code Constants.AVATARS} 的同步由
     * {@code AuthServiceTest} 断言锁定（避免两处漂移）。
     */
    @Pattern(regexp = "^avatar_[1-6]$", message = "头像仅限 avatar_1 ~ avatar_6")
    private String avatar;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
