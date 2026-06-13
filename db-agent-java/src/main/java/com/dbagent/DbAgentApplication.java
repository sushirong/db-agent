package com.dbagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class DbAgentApplication {

    public static void main(String[] args) {
        log.info("数据库智能查询 Java 服务启动中");
        SpringApplication.run(DbAgentApplication.class, args);
        log.info("数据库智能查询 Java 服务启动完成");
    }
}
