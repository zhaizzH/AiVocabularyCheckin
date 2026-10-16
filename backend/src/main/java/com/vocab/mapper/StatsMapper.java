package com.vocab.mapper;

import com.vocab.dto.response.BookLearnedCount;
import com.vocab.dto.response.HeatmapDay;
import com.vocab.dto.response.MasteryPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计聚合 Mapper（SQL 见 {@code resources/mapper/StatsMapper.xml}）。
 *
 * <p><b>为什么用 XML 而不是 Wrapper</b>（spec/database-guidelines.md）：
 * 「按天聚合」「按打卡日期分组」这类 <b>GROUP BY 聚合</b>用 Wrapper 无法表达，
 * 属于规范明确允许写 SQL 的例外情形
 * （原文举例：「带聚合的多表联查——打卡热力图统计」）。
 *
 * <p><b>规范红线</b>（database-guidelines.md「常见错误」）：
 * 手写 XML 不会自动附加逻辑删除条件，<b>必须手动补 {@code deleted = 0}</b>，
 * 已在每个查询中显式写出。
 *
 * <p><b>规范红线</b>（需求清单 §5.1）：聚合在 SQL 内完成，
 * 绝不把原始记录加载到 Java 内存再计算——本接口所有方法的返回值都是
 * 「按天聚合后的小结果集」，不是明细行。
 *
 * <p><b>时间边界为何由 Java 传入</b>：起始日期用 {@code since} 参数而非
 * SQL 侧的 {@code DATE_SUB(CURDATE(), INTERVAL ? DAY)}。这样 SQL 不依赖
 * MySQL 专有日期函数——既让 H2（MySQL 模式）能跑同一份 SQL 做测试，
 * 也把「回溯多少天」这条业务规则显式留在 Service 层，便于单测注入固定日期。
 */
@Mapper
public interface StatsMapper {

    /**
     * 打卡热力图：按 {@code check_in_date} 聚合某用户 {@code since} 起每日学习词数。
     *
     * <p>依据 S3 §7.5「打卡热力图按 {@code check_in_date} 聚合」与需求清单 §5.1。
     * 只统计已打卡的日子（未打卡时 {@code check_in_date} 为 NULL，不参与分组）。
     *
     * @param userId 用户 id
     * @param since  起始日期（含），由 Service 按「今天 − (weeks × 7) 天」计算
     */
    List<HeatmapDay> selectHeatmap(@Param("userId") Long userId, @Param("since") LocalDate since);

    /**
     * 掌握度曲线：按 {@code study_date} 聚合 {@code since} 起每日学习/复习/新增错题数。
     *
     * <p>依据 S3 §5.6「按日期聚合每日学习/复习单词量与错题量」。
     * 与热力图刻意使用不同日期列：热力图刻画「打卡」行为（{@code check_in_date}），
     * 掌握度刻画「学习活动」（{@code study_date}）——复习不打卡，但属于学习活动。
     *
     * @param userId 用户 id
     * @param since  起始日期（含），由 Service 计算
     */
    List<MasteryPoint> selectMasteryDaily(@Param("userId") Long userId, @Param("since") LocalDate since);

    /** 已掌握错题数（掌握度分子）。 */
    Integer countMasteredMistakes(@Param("userId") Long userId);

    /** 错题总数（掌握度分母，含已掌握）。 */
    Integer countTotalMistakes(@Param("userId") Long userId);

    /**
     * 累计打卡天数：不同 {@code check_in_date} 的天数。
     *
     * <p>同时用于：契约 §1.4 profile 的 {@code totalCheckInDays}、
     * AI 学习报告前置校验（学习天数 &lt; 3 → 5003，需求清单 §4.3）。
     */
    Integer countCheckInDays(@Param("userId") Long userId);

    /**
     * 累计已学单词数：{@code word_progress} 中 {@code learned = 1} 的单词数。
     *
     * <p>唯一键是 {@code (user_id, word_id)}，故跨词书天然去重——
     * 同一单词出现在两本词书中只算一个。
     */
    Integer countLearnedWords(@Param("userId") Long userId);

    /**
     * 指定词书内该用户已学单词数（契约 §2.1 的 {@code learnedCount}）。
     *
     * @param userId 用户 id
     * @param bookId 词书 id
     */
    Integer countLearnedWordsInBook(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /**
     * 一次性取「该用户在每本词书内已学词数」——契约 §2.1 列表页的 {@code learnedCount}。
     *
     * <p><b>为什么不做成 {@link #countLearnedWordsInBook} 的循环调用</b>：
     * 列表页有 N 本词书就该发出 N 次 COUNT（典型 N+1）。
     * 一次 {@code GROUP BY book_id} 拿到全部，SQL 次数恒为 1。
     * 结果由 Service 转成 {@code bookId → count} 的 Map 填充。
     *
     * <p>只返回「有已学词」的词书——无记录的词书在 Map 中查不到，
     * Service 侧 {@code getOrDefault(bookId, 0)} 兜底为 0。
     *
     * @param userId 用户 id
     */
    List<BookLearnedCount> selectLearnedCountByBook(@Param("userId") Long userId);
}
