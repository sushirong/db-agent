package com.dbagent.api;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.dbagent.model.TableSchema;
import com.dbagent.service.DatabaseSchemaService;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 表结构管理 API
 * 负责提取数据库表结构并推送到 Python AI 服务
 */
@Slf4j
@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final DatabaseSchemaService schemaService;
    private final RestTemplate restTemplate;

    @Value("${app.ai-service-url:http://localhost:8000}")
    private String aiServiceUrl;

    public SchemaController(DatabaseSchemaService schemaService) {
        this.schemaService = schemaService;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 获取指定数据库的所有表结构
     */
    @GetMapping("/{databaseName}")
    public ResponseEntity<List<TableSchema>> getAllSchemas(@PathVariable String databaseName) {
        long startTime = System.currentTimeMillis();
        log.info("收到表结构查询请求 databaseName={}", databaseName);
        List<TableSchema> schemas = schemaService.getAllTableSchemas(databaseName);
        log.info(
                "表结构查询完成 databaseName={}, tableCount={}, costMs={}",
                databaseName,
                schemas.size(),
                System.currentTimeMillis() - startTime
        );
        return ResponseEntity.ok(schemas);
    }

    /**
     * 将表结构同步到 Python AI 服务进行向量化
     *
     * @param databaseName 数据库名称
     * @return 同步结果
     */
    @PostMapping("/{databaseName}/sync")
    public ResponseEntity<SyncResponse> syncSchemasToAI(@PathVariable String databaseName) {
        long startTime = System.currentTimeMillis();
        log.info("收到表结构同步请求 databaseName={}, aiServiceUrl={}", databaseName, aiServiceUrl);

        List<TableSchema> schemas = schemaService.getAllTableSchemas(databaseName);
        log.info("待同步表结构加载完成 databaseName={}, tableCount={}", databaseName, schemas.size());
        int successCount = 0;
        int failCount = 0;

        for (TableSchema schema : schemas) {
            long tableStartTime = System.currentTimeMillis();
            try {
                SchemaSyncRequest request = new SchemaSyncRequest();
                request.setTableName(schema.getTableName());
                request.setTableComment(schema.getTableComment());
                request.setSchemaText(schema.toFormattedString());

                log.info(
                        "开始同步单表结构 tableName={}, tableComment={}, columnCount={}, schemaTextLength={}",
                        schema.getTableName(),
                        schema.getTableComment(),
                        schema.getColumns() == null ? 0 : schema.getColumns().size(),
                        request.getSchemaText() == null ? 0 : request.getSchemaText().length()
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<SchemaSyncRequest> entity = new HttpEntity<>(request, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(
                        aiServiceUrl + "/ai/schema/sync",
                        entity,
                        String.class
                );

                if (response.getStatusCode().is2xxSuccessful()) {
                    successCount++;
                    log.info(
                            "单表结构同步成功 tableName={}, statusCode={}, costMs={}, responseBody={}",
                            schema.getTableName(),
                            response.getStatusCode(),
                            System.currentTimeMillis() - tableStartTime,
                            response.getBody()
                    );
                } else {
                    failCount++;
                    log.warn(
                            "单表结构同步失败 tableName={}, statusCode={}, costMs={}, responseBody={}",
                            schema.getTableName(),
                            response.getStatusCode(),
                            System.currentTimeMillis() - tableStartTime,
                            response.getBody()
                    );
                }
            } catch (Exception e) {
                failCount++;
                log.error(
                        "单表结构同步异常 tableName={}, costMs={}",
                        schema.getTableName(),
                        System.currentTimeMillis() - tableStartTime,
                        e
                );
            }
        }

        SyncResponse response = new SyncResponse();
        response.setTotalTables(schemas.size());
        response.setSuccessCount(successCount);
        response.setFailCount(failCount);
        response.setMessage(String.format("同步完成: 成功 %d 张, 失败 %d 张", successCount, failCount));

        log.info(
                "表结构同步完成 databaseName={}, totalTables={}, successCount={}, failCount={}, costMs={}",
                databaseName,
                schemas.size(),
                successCount,
                failCount,
                System.currentTimeMillis() - startTime
        );
        return ResponseEntity.ok(response);
    }

    @Data
    static class SchemaSyncRequest {
        private String tableName;
        private String tableComment;
        private String schemaText;
    }

    @Data
    static class SyncResponse {
        private int totalTables;
        private int successCount;
        private int failCount;
        private String message;
    }
}
