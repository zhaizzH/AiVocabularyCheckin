package com.vocab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vocab.dto.response.ReviewWordResponse;
import com.vocab.dto.response.StudyWordResponse;
import com.vocab.entity.WordProgress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link WordProgress} 的 MyBatis-Plus Mapper。
 *
 * <p>动态条件一律使用 {@code LambdaQueryWrapper}（避免重构时字符串列名静默失效）；
 * 逻辑删除由 {@code @TableLogic} 自动附加 {@code deleted = 0} 条件；
 * 列表查询一律走 {@code Page} 分页。
 *
 * <p>仅在 Wrapper 无法表达（多表联查、带聚合）时才写 XML——
 * 见 {@code resources/mapper/WordProgressMapper.xml}。
 */
@Mapper
public interface WordProgressMapper extends BaseMapper<WordProgress> {

    /**
     * 今日计划里的复习词（契约 §3.1，design.md §4.1 步骤 3a）。
     *
     * <p>范围刻意限定在<b>已选词书</b>内（JOIN {@code user_word_book}）：
     * design.md §4.1 的算法是「对每本已选词书取复习词」，移出词书的历史进度
     * 仍在库中（需求清单 §2.4），但不再出现在当天的学习计划里。
     *
     * <p><b>不排除「今天已上报过」的词</b>：已上报的词继续占据计划名额并带
     * {@code learned=true} 返回，前端跳过即可；若把它们从计划里剔除，
     * {@code total} 会随上报不断缩小，进度条语义就崩了。
     *
     * @param userId 用户 id
     * @param today  今天（由 Service 用 Clock 计算，不用 {@code CURDATE()}）
     */
    List<StudyWordResponse> selectPlanReviewWords(@Param("userId") Long userId,
                                                  @Param("today") LocalDate today);

    /**
     * 今日待复习列表（契约 §4.1）。
     *
     * <p>与 {@link #selectPlanReviewWords} 同一筛选条件，只是多带
     * {@code stage} 与 {@code wrongCount}（后者取 {@code mistake_entry.wrong_count}，
     * 因为它是展示口径的权威值——{@code word_progress.wrong_count} 仅作调度参考）。
     * 按到期日升序：最早到期的先复习。
     *
     * @param userId 用户 id
     * @param today  今天
     */
    List<ReviewWordResponse> selectDueReviewWords(@Param("userId") Long userId,
                                                  @Param("today") LocalDate today);
}
