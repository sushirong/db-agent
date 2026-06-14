package com.dbagent.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.dbagent.model.TableSchema;
import com.dbagent.service.DatabaseSchemaService;
import com.dbagent.service.SchemaSyncService;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SchemaSyncServiceImpl implements SchemaSyncService {

    private final DatabaseSchemaService schemaService;
    private final RestTemplate restTemplate;

    @Value("${app.ai-service-url:http://localhost:8000}")
    private String aiServiceUrl;

    public SchemaSyncServiceImpl(DatabaseSchemaService schemaService) {
        this.schemaService = schemaService;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public SyncResult syncAllSchemas(String databaseName, ProgressCallback callback) {
        long startTime = System.currentTimeMillis();
        log.info("开始同步表结构 databaseName={}, aiServiceUrl={}", databaseName, aiServiceUrl);

        List<TableSchema> schemas = schemaService.getAllTableSchemas(databaseName);
        log.info("待同步表结构加载完成 databaseName={}, tableCount={}", databaseName, schemas.size());

        if (callback != null) {
            callback.onProgress(0, schemas.size(), null, "start", null);
        }

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < schemas.size(); i++) {
            TableSchema schema = schemas.get(i);
            int current = i + 1;
            long tableStartTime = System.currentTimeMillis();

            try {
                SyncRequest request = new SyncRequest();
                request.setTableName(schema.getTableName());
                request.setTableComment(schema.getTableComment());
                request.setSchemaText(schema.toFormattedString());

                log.info(
                        "开始同步单表结构 tableName={}, tableComment={}, columnCount={}, schemaTextLength={}",
                        schema.getTableName(), schema.getTableComment(),
                        schema.getColumns() == null ? 0 : schema.getColumns().size(),
                        request.getSchemaText() == null ? 0 : request.getSchemaText().length()
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<SyncRequest> entity = new HttpEntity<>(request, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(
                        aiServiceUrl + "/ai/schema/sync", entity, String.class
                );

                if (response.getStatusCode().is2xxSuccessful()) {
                    successCount++;
                    log.info("单表结构同步成功 tableName={}, statusCode={}, costMs={}",
                            schema.getTableName(), response.getStatusCode(), System.currentTimeMillis() - tableStartTime);
                    if (callback != null) {
                        callback.onProgress(current, schemas.size(), schema.getTableName(), "success", null);
                    }
                } else {
                    failCount++;
                    log.warn("单表结构同步失败 tableName={}, statusCode={}, costMs={}",
                            schema.getTableName(), response.getStatusCode(), System.currentTimeMillis() - tableStartTime);
                    if (callback != null) {
                        callback.onProgress(current, schemas.size(), schema.getTableName(), "fail",
                                "HTTP " + response.getStatusCode());
                    }
                }
            } catch (Exception e) {
                failCount++;
                log.error("单表结构同步异常 tableName={}, costMs={}", schema.getTableName(),
                        System.currentTimeMillis() - tableStartTime, e);
                if (callback != null) {
                    callback.onProgress(current, schemas.size(), schema.getTableName(), "fail", e.getMessage());
                }
            }
        }

        String message = String.format("同步完成: 成功 %d 张, 失败 %d 张", successCount, failCount);
        log.info(
                "表结构同步完成 databaseName={}, total={}, success={}, fail={}, costMs={}",
                databaseName, schemas.size(), successCount, failCount, System.currentTimeMillis() - startTime
        );

        return new SyncResult(schemas.size(), successCount, failCount, message);
    }

    @Override
    public SyncResult incrementalSync(String databaseName, ProgressCallback callback) {
        long startTime = System.currentTimeMillis();
        log.info("开始增量同步表结构 databaseName={}", databaseName);

        // 1. 获取当前数据库所有表结构
        List<TableSchema> currentSchemas = schemaService.getAllTableSchemas(databaseName);
        log.info("当前数据库表结构加载完成 databaseName={}, tableCount={}", databaseName, currentSchemas.size());

        // 2. 获取 Python 端已存储的表元数据
        Map<String, String> storedHashes = fetchStoredMetadata();
        log.info("已存储表元数据获取完成 storedCount={}", storedHashes.size());

        // 3. 计算差异
        Set<String> currentTableNames = currentSchemas.stream()
                .map(TableSchema::getTableName)
                .collect(Collectors.toSet());

        // 需要同步的表：新增 + 结构变更
        List<TableSchema> toSync = new ArrayList<>();
        for (TableSchema schema : currentSchemas) {
            String currentHash = md5(schema.toFormattedString());
            String storedHash = storedHashes.get(schema.getTableName());
            if (storedHash == null) {
                log.info("检测到新增表 tableName={}", schema.getTableName());
                toSync.add(schema);
            } else if (!storedHash.equals(currentHash)) {
                log.info("检测到结构变更 tableName={}", schema.getTableName());
                toSync.add(schema);
            }
        }

        // 需要删除的表：已存储但当前数据库不存在
        List<String> toDelete = storedHashes.keySet().stream()
                .filter(name -> !currentTableNames.contains(name))
                .toList();

        int skippedCount = currentSchemas.size() - toSync.size();
        log.info(
                "增量同步差异计算完成 toSync={}, toDelete={}, skipped={}",
                toSync.size(), toDelete.size(), skippedCount
        );

        if (callback != null) {
            int total = toSync.size() + toDelete.size();
            callback.onProgress(0, total, null, "start",
                    String.format("需同步 %d 张, 需删除 %d 张, 跳过 %d 张", toSync.size(), toDelete.size(), skippedCount));
        }

        // 4. 同步变更的表
        int successCount = 0;
        int failCount = 0;
        int current = 0;
        int total = toSync.size() + toDelete.size();

        for (TableSchema schema : toSync) {
            current++;
            long tableStartTime = System.currentTimeMillis();
            try {
                SyncRequest request = new SyncRequest();
                request.setTableName(schema.getTableName());
                request.setTableComment(schema.getTableComment());
                request.setSchemaText(schema.toFormattedString());

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<SyncRequest> entity = new HttpEntity<>(request, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(
                        aiServiceUrl + "/ai/schema/sync", entity, String.class
                );

                if (response.getStatusCode().is2xxSuccessful()) {
                    successCount++;
                    log.info("增量同步成功 tableName={}, costMs={}", schema.getTableName(), System.currentTimeMillis() - tableStartTime);
                    if (callback != null) {
                        callback.onProgress(current, total, schema.getTableName(), "success", null);
                    }
                } else {
                    failCount++;
                    log.warn("增量同步失败 tableName={}, statusCode={}", schema.getTableName(), response.getStatusCode());
                    if (callback != null) {
                        callback.onProgress(current, total, schema.getTableName(), "fail", "HTTP " + response.getStatusCode());
                    }
                }
            } catch (Exception e) {
                failCount++;
                log.error("增量同步异常 tableName={}", schema.getTableName(), e);
                if (callback != null) {
                    callback.onProgress(current, total, schema.getTableName(), "fail", e.getMessage());
                }
            }
        }

        // 5. 删除不存在的表
        for (String tableName : toDelete) {
            current++;
            try {
                restTemplate.delete(aiServiceUrl + "/ai/schema/" + tableName);
                log.info("增量删除表成功 tableName={}", tableName);
                if (callback != null) {
                    callback.onProgress(current, total, tableName, "deleted", null);
                }
            } catch (Exception e) {
                log.warn("增量删除表失败 tableName={}", tableName, e);
                if (callback != null) {
                    callback.onProgress(current, total, tableName, "fail", "删除失败: " + e.getMessage());
                }
            }
        }

        String message = String.format("增量同步完成: 同步 %d 张, 删除 %d 张, 跳过 %d 张",
                successCount, toDelete.size(), skippedCount);
        log.info(
                "增量同步完成 databaseName={}, synced={}, deleted={}, skipped={}, costMs={}",
                databaseName, successCount, toDelete.size(), skippedCount, System.currentTimeMillis() - startTime
        );

        return new SyncResult(total, successCount, failCount, message);
    }

    /**
     * 从 Python 端获取已存储表的元数据（表名 -> schemaText 哈希）
     */
    private Map<String, String> fetchStoredMetadata() {
        try {
            ResponseEntity<List<StoredTableMeta>> response = restTemplate.exchange(
                    aiServiceUrl + "/ai/schema/metadata",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );
            List<StoredTableMeta> metadata = response.getBody();
            if (metadata == null) {
                return Map.of();
            }
            return metadata.stream()
                    .collect(Collectors.toMap(StoredTableMeta::getTableName, StoredTableMeta::getSchemaHash));
        } catch (Exception e) {
            log.warn("获取已存储表元数据失败，将执行全量同步", e);
            return Map.of();
        }
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Data
    private static class SyncRequest {
        private String tableName;
        private String tableComment;
        private String schemaText;
    }

    @Data
    private static class StoredTableMeta {
        private String tableName;
        private String schemaHash;
    }
}
