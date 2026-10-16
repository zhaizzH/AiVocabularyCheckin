package com.vocab.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一响应结构与错误码的单测。
 *
 * <p>这是前端契约的地基：只要 {@code code} 成功值或错误码分段被改坏，
 * 整个小程序的错误处理就会静默失效，因此必须锁死。
 */
class ResultAndErrorCodeTest {

    @Test
    @DisplayName("成功码必须是字符串 \"0\"（前端用 code !== \"0\" 判断失败）")
    void successCodeIsStringZero() {
        assertEquals("0", Result.SUCCESS_CODE);
        assertEquals("0", Result.ok().getCode());
        assertEquals("ok", Result.ok().getMessage());
        assertNull(Result.ok().getData());
    }

    @Test
    @DisplayName("ok(data) 必须原样带回数据")
    void okCarriesData() {
        Result<String> result = Result.ok("hello");
        assertEquals("0", result.getCode());
        assertEquals("hello", result.getData());
    }

    @Test
    @DisplayName("fail(ErrorCode) 的 code 与 message 必须取自枚举")
    void failUsesErrorCode() {
        Result<Void> result = Result.fail(ErrorCode.ALREADY_CHECKED_IN);
        assertEquals("3001", result.getCode());
        assertEquals("今日已打卡", result.getMessage());
        assertNull(result.getData(), "失败响应不得携带 data");
    }

    @Test
    @DisplayName("错误码必须按模块分段且全局唯一")
    void errorCodesAreUniqueAndSegmented() {
        ErrorCode[] values = ErrorCode.values();
        long distinct = Arrays.stream(values).map(ErrorCode::getCode).distinct().count();
        assertEquals(values.length, distinct, "存在重复错误码");

        for (ErrorCode ec : values) {
            assertNotNull(ec.getMessage(), "错误码 " + ec.getCode() + " 缺少文案");
            assertTrue(ec.getMessage().length() > 0, "错误码 " + ec.getCode() + " 文案为空");

            int first = ec.getCode().charAt(0) - '0';
            switch (ec) {
                case USERNAME_TAKEN, LOGIN_FAILED, USER_DISABLED, WX_LOGIN_FAILED,
                     PARAM_INVALID, TOKEN_INVALID, FORBIDDEN -> assertEquals(1, first, "认证类错误码应为 1xxx");
                case BOOK_NOT_FOUND, BOOK_NOT_SELECTED, BOOK_ALREADY_SELECTED,
                     IMPORT_FILE_INVALID -> assertEquals(2, first, "词库类错误码应为 2xxx");
                case ALREADY_CHECKED_IN, NO_STUDY_PLAN, PLAN_NOT_FINISHED ->
                        assertEquals(3, first, "学习类错误码应为 3xxx");
                case MISTAKE_NOT_FOUND, MISTAKE_ALREADY_MASTERED ->
                        assertEquals(4, first, "错题类错误码应为 4xxx");
                case AI_TIMEOUT, AI_ERROR, AI_DATA_INSUFFICIENT ->
                        assertEquals(5, first, "AI 类错误码应为 5xxx");
                case SYSTEM_BUSY, NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE ->
                        assertEquals(9, first, "系统类错误码应为 9xxx");
            }
        }
    }

    @Test
    @DisplayName("fromCode 能反查，未知码返回 null")
    void fromCodeLookup() {
        assertEquals(ErrorCode.BOOK_NOT_FOUND, ErrorCode.fromCode("2001"));
        assertNull(ErrorCode.fromCode("0000"));
        assertNull(ErrorCode.fromCode(null));
    }

    @Test
    @DisplayName("BizException 必须携带错误码且消息可读")
    void bizExceptionCarriesCode() {
        BizException e = new BizException(ErrorCode.MISTAKE_NOT_FOUND);
        assertEquals("4001", e.getCode());
        assertEquals("错题不存在", e.getMessage());

        BizException custom = new BizException("9999", "自定义");
        assertEquals("9999", custom.getCode());
        assertEquals("自定义", custom.getMessage());
    }

    @Test
    @DisplayName("BizException 是可捕获的受检异常替代品（RuntimeException）")
    void bizExceptionIsRuntime() {
        assertThrows(BizException.class, () -> {
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        });
    }

    @Test
    @DisplayName("PageResult.empty 返回空列表而非 null（前端可直接渲染）")
    void pageResultEmpty() {
        PageResult<String> empty = PageResult.empty(1, 20);
        assertNotNull(empty.getList());
        assertTrue(empty.getList().isEmpty());
        assertEquals(0L, empty.getTotal());
        assertEquals(1L, empty.getPage());
        assertEquals(20L, empty.getSize());
    }

    @Test
    @DisplayName("算法常量必须与需求清单一致")
    void algorithmConstants() {
        assertEquals(20, Constants.DAILY_NEW_WORDS, "每日新词数应为 20");
        assertEquals(4, Constants.MAX_STAGE, "复习阶段上限应为 4");
        assertArrayEqualsInt(new int[]{1, 3, 7, 15}, Constants.REVIEW_INTERVALS, "复习间隔应为 1/3/7/15 天");
    }

    @Test
    @DisplayName("头像白名单仅 avatar_1~avatar_6")
    void avatarWhitelist() {
        assertEquals(6, Constants.AVATARS.size());
        assertTrue(Constants.isValidAvatar("avatar_1"));
        assertTrue(Constants.isValidAvatar("avatar_6"));
        assertTrue(!Constants.isValidAvatar("avatar_7"), "白名单外头像必须被拒绝");
        assertTrue(!Constants.isValidAvatar("../../etc/passwd"), "路径式输入必须被拒绝");
        assertTrue(!Constants.isValidAvatar(null));
    }

    @Test
    @DisplayName("头像白名单不可被外部修改")
    void avatarWhitelistImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> Constants.AVATARS.add("avatar_99"));
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], message + " (索引 " + i + ")");
        }
    }
}
