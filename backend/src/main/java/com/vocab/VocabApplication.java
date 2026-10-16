package com.vocab;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务后端启动类。
 *
 * <p>架构定位（S3 §3.1）：本服务是全系统<b>唯一</b>连接 MySQL 的服务；
 * AI 微服务无状态、不连库；两个前端只与本服务通信。
 */
@SpringBootApplication
@MapperScan("com.vocab.mapper")
public class VocabApplication {

    public static void main(String[] args) {
        SpringApplication.run(VocabApplication.class, args);
    }
}
