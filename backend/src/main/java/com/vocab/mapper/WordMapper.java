package com.vocab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vocab.entity.Word;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
