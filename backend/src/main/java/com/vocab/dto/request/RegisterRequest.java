package com.vocab.dto.request;

import com.vocab.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（《接口详细设计》§1.1）。
 *
 * <p>规则（需求清单 §1.1）：
 * <ul>
 *   <li>用户名 4–20 位，仅字母/数字/下划线</li>
 *   <li>密码 ≥6 位，且必须同时包含字母与数字</li>
 *   <li>昵称可选，≤20 字；缺省取 username</li>
 * </ul>
 *
 * <p>校验失败由 {@code GlobalExceptionHandler} 统一转为 HTTP 200 + 1005
 * （取第一个字段错误的中文提示），Controller 无需写判断。
 */
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,20}$", message = "用户名需为 4-20 位字母、数字或下划线")
    private String username;

    /**
     * 明文密码。
     *
     * <p>仅在本对象生命周期内存在，落库前一律 BCrypt 哈希；
     * 绝不写入日志（logging-guidelines.md 敏感信息红线）。
     */
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{6,}$",
            message = "密码至少 6 位且必须同时包含字母和数字")
    private String password;

    @Size(max = Constants.NICKNAME_MAX_LENGTH, message = "昵称最多 20 字")
    private String nickname;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
