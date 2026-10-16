package com.vocab.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一分页结构。
 *
 * <p>契约约束（见《接口详细设计》§0）：所有列表接口必须分页，
 * 请求参数 {@code page}（默认 1）/ {@code size}（默认 20），
 * 响应统一为：
 *
 * <pre>
 * { "list": [], "total": 0, "page": 1, "size": 20 }
 * </pre>
 */
public class PageResult<T> implements Serializable {

    private List<T> list;
    private long total;
    private long page;
    private long size;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, long page, long size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /** 直接由 MyBatis-Plus 的 IPage 转换（实体即返回值）。 */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 由 IPage 转换并把实体映射为 DTO。
     *
     * <p>禁止对外暴露实体（会泄露 password_hash / deleted），故 Service 层
     * 一律用本方法在分页边界完成实体 → DTO 转换。
     */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        List<T> list = page.getRecords() == null
                ? Collections.emptyList()
                : page.getRecords().stream().map(mapper).collect(Collectors.toList());
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 空分页结果（无数据时避免返回 null，前端可直接渲染）。 */
    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(Collections.emptyList(), 0L, page, size);
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
