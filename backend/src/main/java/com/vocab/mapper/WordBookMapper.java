package com.vocab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vocab.entity.WordBook;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link WordBook} 的 MyBatis-Plus Mapper。
 *
 * <p>动态条件一律使用 {@code LambdaQueryWrapper}（避免重构时字符串列名静默失效）；
 * 逻辑删除由 {@code @TableLogic} 自动附加 {@code deleted = 0} 条件；
 * 列表查询一律走 {@code Page} 分页。
 *
 * <p>仅在 Wrapper 无法表达（带聚合的多表联查）时才写 XML——
 * 见 {@link StatsMapper}。
 */
@Mapper
public interface WordBookMapper extends BaseMapper<WordBook> {
}
