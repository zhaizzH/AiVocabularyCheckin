package com.vocab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vocab.entity.Word;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link Word} 的 MyBatis-Plus Mapper。
 *
 * <p>动态条件一律使用 {@code LambdaQueryWrapper}（避免重构时字符串列名静默失效）；
 * 逻辑删除由 {@code @TableLogic} 自动附加 {@code deleted = 0} 条件；
 * 列表查询一律走 {@code Page} 分页。
 *
 * <p>仅在 Wrapper 无法表达（带聚合的多表联查、多行 VALUES 批量插入）时才写 XML——
 * 见 {@link StatsMapper} 与 {@link #insertBatch(List)}。
 */
@Mapper
public interface WordMapper extends BaseMapper<Word> {

    /**
     * 多行 VALUES 批量插入（词库导入专用）。
     *
     * <p><b>为什么手写 XML 而非 `saveBatch`/`Db.saveBatch`</b>：
     * 那两者的语义都是「循环调用单条 insert」，只是被包在一个循环里——
     * 对 22761 行的词库导入意味着 22761 次往返。多行 VALUES 一条 SQL 插 1000 行，
     * 是真正的一次往返（database-guidelines.md「批量操作使用 saveBatch /
     * removeByIds；禁止循环单条插入」的实质要求）。
     *
     * <p><b>时间戳</b>：{@code create_time} / {@code update_time} 无 DB 默认值
     * （G2 决策），故 XML 写操作必须显式带上 {@code NOW()} —— 这正是
     * 「任何绕过 MyBatis-Plus 的写库必须手工赋值时间列」约束的兑现点
     * （design.md §3.5）。
     *
     * @param list 待插入单词列表（调用方负责分批，建议 1000 条/批）
     * @return 实际插入行数
     */
    int insertBatch(@Param("list") List<Word> list);

    /**
     * 取某词书内「按 id 升序未学过」的新词 id，最多 {@code limit} 条。
     *
     * <p><b>为什么 NOT EXISTS 的条件是「历史上已学过」而不是「有没有进度行」</b>：
     * 若按契约字面写 {@code id NOT IN (SELECT word_id FROM word_progress WHERE learned=1)}
     * ，一旦用户中途上报几个词，这些词的 {@code word_progress} 行会立刻从候选集消失，
     * 而 {@code LIMIT 20} 会再补进 20 个<b>新的</b>词——计划窗口不断向后滑动，
     * 用户永远追不上 {@code total}（每上报一个词，候选集就补一个），
     * 结果 {@code finished < total} 恒成立、永远无法打卡。
     *
     * <p>故排除条件收紧为「{@code create_time < 今天 00:00} 且 {@code learned = 1}」
     * （即昨天及更早学过的词）。这样：
     * <ol>
     *   <li>同一天内计划快照稳定——今天上报过的词（{@code create_time} 在今天）
     *       仍留在候选集里，{@code total} 不漂移；</li>
     *   <li>昨天及更早学过的词不再作为新词出现，而是走 §4.1 的待复习队列；</li>
     *   <li>从未学过的词正常入选，满足「取该词书内未学单词按 id 升序」。</li>
     * </ol>
     *
     * <p><b>为什么用 {@code create_time} 而不是 {@code update_time}</b>：
     * {@code update_time} 会在每次复习/重学后被刷新。若用它作判据，一个词今天
     * 被复习后 {@code update_time} 变成今天，就又满足了「今天学过」而被排除；
     * 反过来用「{@code update_time < 今天}」也会让当天复习过的词重新变成「新词」。
     * {@code create_time} 只在首次进入学习计划时写入、之后不变，才是「历史上是否
     * 学过」的正确判据。
     *
     * <p>{@code dayStart} 由 Service 层用 {@link java.time.Clock} 计算后传入
     * （不依赖 DB 的 {@code CURDATE()}），保证 H2 与 MySQL 行为一致且可单测注入固定日期。
     *
     * @param bookId   词书 id
     * @param userId   用户 id
     * @param dayStart 今天的 00:00:00（用于判定「历史上已学过」）
     * @param limit    最多返回条数（{@link com.vocab.common.Constants#DAILY_NEW_WORDS}）
     * @return 新词 id 列表（可能不足 limit 条，词书学完时为空）
     */
    List<Long> selectUnlearnedNewWordIds(@Param("bookId") Long bookId,
                                         @Param("userId") Long userId,
                                         @Param("dayStart") LocalDateTime dayStart,
                                         @Param("limit") int limit);
}
