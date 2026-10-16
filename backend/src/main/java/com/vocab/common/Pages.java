package com.vocab.common;

/**
 * 分页参数归一化。
 *
 * <p>契约 §0：{@code page} 默认 1、{@code size} 默认 20。前端可能传 0 或负数
 * （尤其是初始化状态），统一在此回落默认值，而不是在各 Service 里各写一遍。
 *
 * <p><b>上限不在这里管</b>：单页最大条数由分页插件的
 * {@code PaginationInnerInterceptor.setMaxLimit(500)} 在 SQL 层截断——
 * 那才是真正不可绕过的地方（即便有人漏调本工具类也不会拖垮数据库）。
 * 本类只负责「非法值回落」。
 */
public final class Pages {

    private Pages() {
    }

    /** {@code page < 1} → {@link Constants#DEFAULT_PAGE}。 */
    public static long normalizePage(long page) {
        return page < 1 ? Constants.DEFAULT_PAGE : page;
    }

    /** {@code size < 1} → {@link Constants#DEFAULT_SIZE}。 */
    public static long normalizeSize(long size) {
        return size < 1 ? Constants.DEFAULT_SIZE : size;
    }
}
