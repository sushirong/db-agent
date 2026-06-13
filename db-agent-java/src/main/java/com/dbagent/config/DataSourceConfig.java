package com.dbagent.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * 双数据源配置
 * - primary: 主业务数据源
 * - readonly: 只读沙箱数据源，专供 AI Agent 使用
 */
@Slf4j
@Configuration
public class DataSourceConfig {

    @Primary
    @Bean(name = "primaryDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.primary")
    public DataSource primaryDataSource() {
        log.info("初始化主业务数据源 beanName=primaryDataSource, propertyPrefix=spring.datasource.primary");
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "readonlyDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.readonly")
    public DataSource readonlyDataSource() {
        log.info("初始化只读沙箱数据源 beanName=readonlyDataSource, propertyPrefix=spring.datasource.readonly");
        return DataSourceBuilder.create().build();
    }

    @Primary
    @Bean(name = "primaryJdbcTemplate")
    public JdbcTemplate primaryJdbcTemplate(@Qualifier("primaryDataSource") DataSource dataSource) {
        log.info("初始化主业务 JdbcTemplate beanName=primaryJdbcTemplate");
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "readonlyJdbcTemplate")
    public JdbcTemplate readonlyJdbcTemplate(@Qualifier("readonlyDataSource") DataSource dataSource) {
        log.info("初始化只读沙箱 JdbcTemplate beanName=readonlyJdbcTemplate");
        return new JdbcTemplate(dataSource);
    }
}
