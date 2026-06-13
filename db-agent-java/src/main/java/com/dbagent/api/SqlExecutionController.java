package com.dbagent.api;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dbagent.model.SqlExecutionRequest;
import com.dbagent.model.SqlExecutionResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL 执行沙箱 API
 * 提供安全的 SQL 执行环境，仅允许 SELECT 查询
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/db")
public class SqlExecutionController {

    private final JdbcTemplate readonlyJdbcTemplate;

    // 危险 SQL 关键字正则匹配 (不区分大小写)
    private static final Pattern DANGEROUS_SQL_PATTERN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|REPLACE|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public SqlExecutionController(@Qualifier("readonlyJdbcTemplate") JdbcTemplate readonlyJdbcTemplate) {
        this.readonlyJdbcTemplate = readonlyJdbcTemplate;
    }

    /**
     * 执行 SQL 语句
     * 安全限制：仅允许 SELECT 查询，拦截所有写操作
     *
     * @param request SQL 执行请求
     * @return 执行结果
     */
    @PostMapping("/execute")
    public ResponseEntity<SqlExecutionResponse> executeSql(@RequestBody SqlExecutionRequest request) {
        long startTime = System.currentTimeMillis();
        log.info(
                "收到 SQL 执行请求 databaseName={}, requestPresent={}",
                request == null ? null : request.getDatabaseName(),
                request != null
        );

        if (request == null) {
            log.warn("SQL 执行请求体为空");
            return ResponseEntity.badRequest()
                    .body(SqlExecutionResponse.error("请求体不能为空"));
        }

        String sql = request.getSql();

        if (sql == null || sql.trim().isEmpty()) {
            log.warn("SQL 执行请求被拒绝，SQL 为空 databaseName={}", request.getDatabaseName());
            return ResponseEntity.badRequest()
                    .body(SqlExecutionResponse.error("SQL 语句不能为空"));
        }

        log.info(
                "SQL 执行请求参数 databaseName={}, sqlLength={}, sqlPreview={}",
                request.getDatabaseName(),
                sql.length(),
                abbreviate(sql, 500)
        );

        String securityCheckResult = checkSqlSecurity(sql);
        if (securityCheckResult != null) {
            log.warn(
                    "SQL 安全校验未通过 databaseName={}, reason={}, sqlPreview={}",
                    request.getDatabaseName(),
                    securityCheckResult,
                    abbreviate(sql, 500)
            );
            return ResponseEntity.status(403)
                    .body(SqlExecutionResponse.error(securityCheckResult));
        }

        log.info("SQL 安全校验通过，开始执行 databaseName={}", request.getDatabaseName());

        try {
            List<Map<String, Object>> results = readonlyJdbcTemplate.queryForList(sql);
            log.info(
                    "SQL 执行成功 databaseName={}, rowCount={}, costMs={}",
                    request.getDatabaseName(),
                    results.size(),
                    System.currentTimeMillis() - startTime
            );
            return ResponseEntity.ok(SqlExecutionResponse.success(results));

        } catch (Exception e) {
            log.error(
                    "SQL 执行异常 databaseName={}, costMs={}, sqlPreview={}",
                    request.getDatabaseName(),
                    System.currentTimeMillis() - startTime,
                    abbreviate(sql, 500),
                    e
            );
            return ResponseEntity.ok(SqlExecutionResponse.error(
                    String.format("SQL 执行错误 : %s", e.getMessage())
            ));
        }
    }

    /**
     * SQL 安全检查
     *
     * @param sql SQL 语句
     * @return 如果安全返回 null，否则返回错误信息
     */
    private String checkSqlSecurity(String sql) {
        String cleanedSql = sql.replaceAll("--.*$", "").replaceAll("/\\*.*?\\*/", "").trim();
        log.debug("SQL 清理完成 cleanedSqlPreview={}", abbreviate(cleanedSql, 500));

        if (DANGEROUS_SQL_PATTERN.matcher(cleanedSql).find()) {
            return "权限错误: 仅允许 SELECT 查询操作，禁止 INSERT/UPDATE/DELETE/DROP/TRUNCATE 等写操作";
        }

        String upperSql = cleanedSql.toUpperCase();
        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
            return "权限错误: 仅允许 SELECT 或 WITH...SELECT 查询语句";
        }

        return null;
    }

    /**
     * 截断超长日志文本
     * SQL 内容过长时仅记录前缀，避免日志体积过大
     */
    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        if (normalizedText.length() <= maxLength) {
            return normalizedText;
        }
        return normalizedText.substring(0, maxLength) + "...";
    }
}
