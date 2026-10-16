package com.vocab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vocab.entity.UserWordBook;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link UserWordBook} 的 MyBatis-Plus Mapper。
 *
 * <p><b>本表是物理删除，没有 deleted 列</b>（全项目唯一）：
 * 移出词书即 {@code DELETE} 本行，因此<b>不存在 {@code @TableLogic} 自动附加条件</b>，
 * 查询也不会过滤任何「已删除」数据。删除语义详见 {@link com.vocab.entity.UserWordBook}。
 *
 * <p>动态条件一律使用 {@code LambdaQueryWrapper}（避免重构时字符串列名静默失效）；
 * 列表查询一律走 {@code Page} 分页。
 *
 * <p>仅在 Wrapper 无法表达（带聚合的多表联查）时才写 XML——
 * 见 {@link StatsMapper}。
 */
@Mapper
public interface UserWordBookMapper extends BaseMapper<UserWordBook> {
}
