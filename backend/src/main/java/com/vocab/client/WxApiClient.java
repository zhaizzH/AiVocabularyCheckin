package com.vocab.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.BizException;
import com.vocab.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 微信小程序服务端接口客户端（当前只有 {@code code2Session}）。
 *
 * <p>契约约束（《接口详细设计》§1.3）：{@code code → code2Session 换 openid}；
 * 任何微信侧异常（网络失败、{@code errcode != 0}、response 缺 openid）→ 1004。
 *
 * <p><b>安全</b>（logging-guidelines.md 红线）：
 * <ul>
 *   <li>{@code appid} / {@code secret} 只从配置（环境变量）读取，绝不硬编码</li>
 *   <li>绝不记录完整请求 URL——中间拼接了 secret</li>
 *   <li>{@code code} 是一次性凭证，同样不记入日志</li>
 * </ul>
 *
 * <p>超时由专用 {@code wxRestTemplate} 提供（5s，见 {@code RestTemplateConfig}）：
 * 本调用位于登录主链路上，不能让用户在页面上干等。
 */
@Component
public class WxApiClient {

    private static final Logger log = LoggerFactory.getLogger(WxApiClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String appid;
    private final String secret;
    private final String code2SessionUrl;

    public WxApiClient(@Qualifier("wxRestTemplate") RestTemplate restTemplate,
                       ObjectMapper objectMapper,
                       @Value("${wx.appid:}") String appid,
                       @Value("${wx.secret:}") String secret,
                       @Value("${wx.code2session-url}") String code2SessionUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.appid = appid;
        this.secret = secret;
        this.code2SessionUrl = code2SessionUrl;
    }

    /**
     * 用一次性凭证换取 openid。
     *
     * @param code 小程序 {@code wx.login()} 返回的临时凭证
     * @return openid（非空）
     * @throws BizException 1004：未配置 AppID / 微信侧返回错误 / 网络异常 / 响应缺 openid
     */
    public String code2Session(String code) {
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(secret)) {
            // 开发期尚未配置凭据时的明确提示。学生端主入口仍可用账号密码登录（契约 §1.2）。
            log.warn("微信登录不可用：未配置 wx.appid / wx.secret（WX_APPID / WX_SECRET）");
            throw new BizException(ErrorCode.WX_LOGIN_FAILED);
        }

        JsonNode body = requestOpenId(code);
        if (body == null) {
            throw new BizException(ErrorCode.WX_LOGIN_FAILED);
        }

        // 微信在失败时返回 errcode/errmsg（HTTP 仍为 200），必须先判 errcode。
        int errcode = body.path("errcode").asInt(0);
        if (errcode != 0) {
            // errmsg 是微信侧的稳定错误短串，不含敏感数据，可记录以辅助联调。
            log.warn("微信 code2Session 失败 errcode={} errmsg={}",
                    errcode, body.path("errmsg").asText(""));
            throw new BizException(ErrorCode.WX_LOGIN_FAILED);
        }

        String openid = body.path("openid").asText(null);
        if (!StringUtils.hasText(openid)) {
            log.warn("微信 code2Session 成功但响应缺少 openid");
            throw new BizException(ErrorCode.WX_LOGIN_FAILED);
        }
        return openid;
    }

    private JsonNode requestOpenId(String code) {
        // 参数化拼接（build(true) 保持 appid/secret 原样不被编码）。
        URI uri = UriComponentsBuilder.fromHttpUrl(code2SessionUrl)
                .queryParam("appid", appid)
                .queryParam("secret", secret)
                .queryParam("js_code", code)
                .queryParam("grant_type", "authorization_code")
                .build(true)
                .toUri();

        String raw;
        try {
            raw = restTemplate.getForObject(uri, String.class);
        } catch (RestClientException e) {
            // 超时/连接失败。绝不记录 uri（含 secret），只记异常类别。
            log.warn("调用微信 code2Session 失败: {}", e.getClass().getSimpleName());
            return null;
        }

        if (!StringUtils.hasText(raw)) {
            log.warn("微信 code2Session 返回空响应");
            return null;
        }

        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            // 微信侧返回了非 JSON 内容（如网关 HTML 错误页）。
            log.warn("微信 code2Session 响应不是合法 JSON: ", e);
            return null;
        }
    }
}
