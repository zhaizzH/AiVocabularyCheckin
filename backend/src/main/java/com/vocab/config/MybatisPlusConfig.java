package com.vocab.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置：分页插件 + 公共字段自动填充。
 *
 * <p>规范约束（spec/database-guidelines.md）：
 * <ul>
 *   <li>所有列表接口必须使用 {@code Page} 分页，绝不返回无上限列表</li>
 *   <li>每张表含 {@code id} / {@code create_time} / {@code update_time}，
 *       后两者由本类的 {@link MetaObjectHandler} 自动填充</li>
 * </ul>
 */
@Configuration
public class MybatisPlusConfig {

    /** 分页插件。不注册则 {@code Page} 查询不会真正分页（会全量返回）。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限，防御性设置：即使前端传 size=100000 也不会拖垮数据库
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /** 公共字段自动填充：create_time / update_time。 */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {

            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                // 用 strictInsertFill：字段本身已有值时不覆盖（便于导入历史数据时保留原始时间）
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                // update 只刷新 updateTime，createTime 保持不变
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
