package com.dbagent.api;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.dbagent.model.SchemaSyncEvent;
import com.dbagent.model.TableSchema;
import com.dbagent.service.DatabaseSchemaService;
import com.dbagent.service.SchemaSyncService;
import com.dbagent.service.SchemaSyncService.SyncResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final DatabaseSchemaService schemaService;
    private final SchemaSyncService syncService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public SchemaController(DatabaseSchemaService schemaService, SchemaSyncService syncService) {
        this.schemaService = schemaService;
        this.syncService = syncService;
    }

    @GetMapping("/{databaseName}")
    public ResponseEntity<List<TableSchema>> getAllSchemas(@PathVariable String databaseName) {
        return ResponseEntity.ok(schemaService.getAllTableSchemas(databaseName));
    }

    @PostMapping("/{databaseName}/sync")
    public SseEmitter syncSchemasToAI(
            @PathVariable String databaseName,
            @RequestParam(defaultValue = "false") boolean force) {
        // 同步大量表结构可能耗时很长，设置 30 分钟超时
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L);
        AtomicBoolean emitterClosed = new AtomicBoolean(false);

        Runnable closeEmitter = () -> {
            if (emitterClosed.compareAndSet(false, true)) {
                emitter.complete();
            }
        };

        executor.execute(() -> {
            try {
                SyncResult result = force
                        ? syncService.syncAllSchemas(databaseName, progressCallback(emitter))
                        : syncService.incrementalSync(databaseName, progressCallback(emitter));

                sendEvent(emitter, SchemaSyncEvent.complete(
                        result.getTotal(), result.getSuccess(), result.getFail(), result.getMessage()));
                log.info("表结构同步完成 databaseName={}, total={}, success={}, fail={}",
                        databaseName, result.getTotal(), result.getSuccess(), result.getFail());

            } catch (Exception e) {
                log.error("表结构同步流程异常 databaseName={}", databaseName, e);
                sendEvent(emitter, SchemaSyncEvent.error(e.getMessage()));
            } finally {
                closeEmitter.run();
            }
        });

        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时 databaseName={}", databaseName);
            closeEmitter.run();
        });
        emitter.onError(t -> log.warn("SSE 连接异常 databaseName={}", databaseName, t));

        return emitter;
    }

    private SchemaSyncService.ProgressCallback progressCallback(SseEmitter emitter) {
        return (current, total, tableName, status, message) -> {
            if ("start".equals(status)) {
                log.info("同步开始 total={}", total);
                sendEvent(emitter, SchemaSyncEvent.start(total));
            } else {
                sendEvent(emitter, SchemaSyncEvent.progress(current, total, tableName, status, message));
            }
        };
    }

    private void sendEvent(SseEmitter emitter, SchemaSyncEvent event) {
        try {
            emitter.send(SseEmitter.event().name("sync").data(event));
        } catch (IllegalStateException e) {
            log.debug("SSE 连接已关闭，忽略事件 type={}", event.getType());
        } catch (Exception e) {
            log.warn("SSE 推送事件失败 type={}", event.getType(), e);
        }
    }
}
