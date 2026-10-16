package com.vocab.service;

import com.vocab.common.PageResult;
import com.vocab.dto.response.MistakeMasteredResponse;
import com.vocab.dto.response.MistakeRemoveResponse;
import com.vocab.dto.response.MistakeResponse;

/**
 * 错题服务（契约 §5 + 需求清单 §3.1）。
 *
 * <p>向外暴露三个学生端用例，向内暴露一个<b>被学习/复习模块复用的收录原语</b>
 * （{@link #addOrIncrement}）——收录去重规则（design.md §4.4）只能有一份实现，
 * 否则「学习中不认识」与「复习答错」两条路径的去重行为迟早漂移。
 */
public interface MistakeService {

    // ---------------- 内部复用的收录原语 ----------------

    /**
     * 收录错题：同一 {@code (userId, wordId)} 已存在则 {@code wrong_count + 1}、
     * 刷新 {@code source} 并重置 {@code mastered = 0}；否则新建（{@code wrong_count = 1}）。
     *
     * <p>由 {@code StudyService}/{@code ReviewService} 调用，不对外暴露成 HTTP 接口。
     *
     * <p><b>「已掌握再答错 → 重置为未掌握」</b>是合理推断（design.md §4.4），
     * 需求清单 §3.1 只写了「重复错 → wrong_count+1 不重复建记录」。
     *
     * @param source {@link com.vocab.common.Constants#SOURCE_STUDY} 或
     *               {@link com.vocab.common.Constants#SOURCE_REVIEW}
     */
    void addOrIncrement(Long userId, Long wordId, String source);

    // ---------------- 学生端 ----------------

    /**
     * 错题列表（契约 §5.1）。
     *
     * @param mastered 0 未掌握 / 1 已掌握（缺省 0）
     * @throws com.vocab.common.BizException 1005 参数非法（mastered 非 0/1）
     */
    PageResult<MistakeResponse> list(Long userId, Integer mastered, long page, long size);

    /**
     * 标记错题已掌握（契约 §5.2）。
     *
     * @throws com.vocab.common.BizException 4001 错题不存在；4002 错题已掌握
     */
    MistakeMasteredResponse markMastered(Long userId, Long mistakeId);

    /**
     * 移除错题（契约 §5.3）：<b>逻辑删除</b>，保留历史行。
     *
     * @throws com.vocab.common.BizException 4001 错题不存在
     */
    MistakeRemoveResponse remove(Long userId, Long mistakeId);
}
