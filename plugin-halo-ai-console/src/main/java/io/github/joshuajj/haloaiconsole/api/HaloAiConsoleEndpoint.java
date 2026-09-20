package io.github.joshuajj.haloaiconsole.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.Principal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import reactor.core.Disposable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.security.core.Authentication;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.joshuajj.haloaiconsole.service.AiFoundationModelInvoker;
import io.github.joshuajj.haloaiconsole.service.JobEventRegistry;
import io.github.joshuajj.haloaiconsole.audit.AuditRecordFactory;
import io.github.joshuajj.haloaiconsole.policy.ConversationRequestPolicy;
import io.github.joshuajj.haloaiconsole.policy.JobLifecyclePolicy;
import io.github.joshuajj.haloaiconsole.policy.QuotaPolicy;
import io.github.joshuajj.haloaiconsole.security.OwnerAccessPolicy;
import io.github.joshuajj.haloaiconsole.security.ImageUploadPolicy;
import io.github.joshuajj.haloaiconsole.security.KubernetesNamePolicy;
import io.github.joshuajj.haloaiconsole.security.RemoteImageContentPolicy;
import io.github.joshuajj.haloaiconsole.extension.AiChatCallLog;
import io.github.joshuajj.haloaiconsole.extension.AiChatImageCache;
import io.github.joshuajj.haloaiconsole.extension.AiChatMessage;
import io.github.joshuajj.haloaiconsole.extension.AiChatSession;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class HaloAiConsoleEndpoint implements CustomEndpoint, DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(HaloAiConsoleEndpoint.class);
  private static final int MAX_TITLE_LENGTH = 120;
  private static final int MAX_MEMORY_LENGTH = 20000;
  private static final int MAX_CONTENT_LENGTH = 200000;
  private static final int MAX_REASONING_LENGTH = 200000;
  private static final int MAX_MESSAGES_PER_SESSION = 300;
  private static final int MAX_REQUEST_MESSAGES = 30;
  private static final int MAX_REQUEST_CHARS = 80_000;
  private static final int MAX_REQUEST_IMAGES = 12;
  private static final int MAX_REQUEST_ATTACHMENTS = 20;
  private static final int MAX_STREAM_TEXT_LENGTH = 200_000;
  private static final int MAX_SESSION_JSON_LENGTH = 900_000;
  private static final int MAX_ATTACHMENTS_PER_MESSAGE = 20;
  private static final int MAX_IMAGES_PER_MESSAGE = 20;
  private static final int MAX_TAGS_PER_SESSION = 20;
  // Kept below the JSON request envelope and ConfigMap payload budget.
  private static final int MAX_DATA_URL_LENGTH = 700_000;
  private static final int MAX_JSON_REQUEST_BYTES = 1_000_000;
  private static final int MAX_EXPORT_LOGS = 10_000;
  private static final int MAX_EXPORT_JOBS = 10_000;
  private static final int MAX_IMAGE_RESULTS_PER_REQUEST = 4;
  private static final int MAX_IMAGE_DIMENSION = 2_048;
  private static final long MAX_IMAGE_PIXELS = 4_194_304L;
  private static final long HARD_MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
  private static final Duration GENERATED_IMAGE_DOWNLOAD_TIMEOUT = Duration.ofSeconds(20);
  private static final Semaphore IMAGE_UPLOAD_PERMITS = new Semaphore(2, true);
  private static final Set<String> ROLES = Set.of("user", "assistant");
  private static final Set<String> AUXILIARY_OPERATIONS = Set.of(
    "title-generation", "conversation-summary", "context-compression"
  );
  private static final Set<String> ADMIN_AUTHORITIES = Set.of(
    "plugin:halo-ai-console:admin",
    "plugin:halo-ai-console:call-log-all",
    "role-template-halo-ai-console-admin",
    "ROLE_role-template-halo-ai-console-admin",
    "super-role",
    "ROLE_super-role"
  );
  private static final String STORE_CONFIG_MAP_PREFIX = "halo-ai-console-store-";
  private static final String SESSION_CONFIG_MAP_PREFIX = "halo-ai-console-session-";
  private static final String JOB_CONFIG_MAP_PREFIX = "halo-ai-console-job-";
  private static final String LOG_CONFIG_MAP_PREFIX = "halo-ai-console-log-";
  private static final String USAGE_CONFIG_MAP_PREFIX = "halo-ai-console-usage-";
  private static final String INSTANCE_CONFIG_MAP_PREFIX = "halo-ai-console-instance-";
  private static final long INSTANCE_HEARTBEAT_TTL_MS = 35_000L;
  private static final long JOB_STALE_AFTER_MS = 30_000L;
  // Keep a small deletion marker long enough to reject delayed browser writes.
  private static final long SESSION_TOMBSTONE_RETENTION_MS = Duration.ofDays(30).toMillis();
  private static final String SESSION_KEY_PREFIX = "session:";
  private static final String LOG_KEY_PREFIX = "log:";
  private static final String IMAGE_KEY_PREFIX = "image:";
  private static final String JOB_KEY_PREFIX = "job:";
  private static final String LEGACY_MIGRATION_KEY = "migration:legacy";
  private static final String SETTINGS_KEY = "settings";
  private static final String GLOBAL_CONFIG_MAP = "halo-ai-console-config";
  private static final String GLOBAL_CONFIG_GROUP = "basic";

  private final ReactiveExtensionClient client;
  private final AiFoundationModelInvoker modelInvoker;
  private final AttachmentService attachmentService;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String instanceId = safeName("instance", UUID.randomUUID().toString());
  private final Map<String, Disposable> runningJobs = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> runningJobStates = new ConcurrentHashMap<>();
  private final Map<String, UserUsageState> usageStates = new ConcurrentHashMap<>();
  private final JobEventRegistry jobEvents = new JobEventRegistry();
  private final List<Disposable> lifecycleDisposables = new CopyOnWriteArrayList<>();
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

  public HaloAiConsoleEndpoint(ReactiveExtensionClient client,
    AiFoundationModelInvoker modelInvoker, AttachmentService attachmentService) {
    this.client = client;
    this.modelInvoker = modelInvoker;
    this.attachmentService = attachmentService;
    lifecycleDisposables.add(Flux.interval(Duration.ZERO, Duration.ofSeconds(10))
      .flatMap(tick -> heartbeatInstance().onErrorResume(error -> Mono.empty()))
      .subscribe());
    lifecycleDisposables.add(Mono.delay(Duration.ofSeconds(15))
      .then(markInterruptedJobsV2())
      .onErrorResume(error -> Mono.empty())
      .subscribe());
    lifecycleDisposables.add(Flux.interval(Duration.ofMinutes(5), Duration.ofHours(6))
      .flatMap(tick -> cleanupExpiredRecords().onErrorResume(error -> Mono.empty()))
      .subscribe());
  }

  @Override
  public void destroy() {
    if (!shuttingDown.compareAndSet(false, true)) {
      return;
    }
    lifecycleDisposables.forEach(Disposable::dispose);
    lifecycleDisposables.clear();
    var shutdownWrites = new ArrayList<Mono<Void>>();
    runningJobs.forEach((key, disposable) -> {
      var state = runningJobStates.get(key);
      var splitAt = key.lastIndexOf('/');
      if (state != null && splitAt > 0) {
        var owner = key.substring(0, splitAt);
        var jobId = key.substring(splitAt + 1);
        var reservationDay = stringValue(state.get("_usageDay"));
        if (reservationDay.isBlank()) {
          reservationDay = dayKey(System.currentTimeMillis());
        }
        var reservation = new UsageReservation(owner, reservationDay,
          intValue(state.get("_promptTokens")), intValue(state.get("_reservedTokens")), jobId);
        shutdownWrites.add(updateJobAndSession(owner, jobId, state, "interrupted",
            "插件已停用或卸载，任务已中断。")
          .then(releaseUsageReservation(reservation)));
        emitJobEvent(owner, jobId, transientJobEvent(jobId,
          intValue(state.get("_promptTokens")), state, "interrupted", "插件已停用或卸载，任务已中断。"));
      }
      disposable.dispose();
    });
    if (!shutdownWrites.isEmpty()) {
      try {
        Flux.<Void>concatDelayError(Flux.fromIterable(shutdownWrites))
          .then()
          .timeout(Duration.ofSeconds(8))
          .block();
      } catch (RuntimeException error) {
        log.warn("Failed to persist interrupted AI jobs during plugin shutdown.", error);
      }
    }
    runningJobs.clear();
    runningJobStates.clear();
    jobEvents.completeAll();
    usageStates.clear();
  }

  private static final class UserUsageState {
    private final Deque<Long> requestTimes = new ArrayDeque<>();
    private String day = "";
    private int running;
    private int reservedTokens;
  }

  private record PersistedGeneratedImage(Attachment attachment, String permalink) {
  }

  private static final class UsageReservation {
    private final String owner;
    private final String day;
    private final int promptTokens;
    private final int reservedTokens;
    private final String jobId;

    private UsageReservation(String owner, String day, int promptTokens, Integer reservedTokens, String jobId) {
      this.owner = owner;
      this.day = day;
      this.promptTokens = Math.max(0, promptTokens);
      this.reservedTokens = Math.max(this.promptTokens,
        reservedTokens == null ? this.promptTokens : reservedTokens);
      this.jobId = jobId;
    }
  }

  private static final class JobRecord {
    private final ConfigMap configMap;
    private final Map<String, Object> job;

    private JobRecord(ConfigMap configMap, Map<String, Object> job) {
      this.configMap = configMap;
      this.job = job;
    }
  }

  @Override
  public GroupVersion groupVersion() {
    return new GroupVersion("console.api.halo-ai-console.halo.run", "v1alpha1");
  }

  @Override
  public RouterFunction<ServerResponse> endpoint() {
    return RouterFunctions.route()
      .GET("/sessions-with-messages", this::listSessionsWithMessages)
      .PUT("/sessions/{name}/snapshot", this::saveSessionSnapshot)
      .DELETE("/sessions/{name}", this::deleteSession)
      .GET("/me/identity", this::currentIdentity)
      .GET("/me/export", this::exportOwnData)
      .DELETE("/me/data", this::deleteOwnData)
      .GET("/call-logs", this::listCallLogs)
      .GET("/call-logs/all", this::listAllCallLogs)
      .POST("/call-logs", this::createCallLog)
      .GET("/audit-logs", this::listCallLogs)
      .GET("/audit-logs/all", this::listAllCallLogs)
      .GET("/global-settings", this::getGlobalSettings)
      .GET("/assets/dompurify.min.js", this::domPurifyAsset)
      .GET("/settings", this::getSettings)
      .PUT("/settings", this::saveSettings)
      .POST("/attachments/upload", this::uploadAttachment)
      .POST("/jobs/chat", this::createChatJob)
      .POST("/jobs/image", this::createImageJob)
      .POST("/models/{name}/generate-text", this::generateText)
      .GET("/jobs/recoverable", this::listRecoverableJobs)
      .GET("/jobs/{name}", this::getJob)
      .GET("/jobs/{name}/events", this::jobEvents)
      .POST("/jobs/{name}/cancel", this::cancelJob)
      .GET("/image-caches/{name}", this::getImageCache)
      .POST("/image-caches", this::createImageCache)
      .GET("/migration/legacy/status", this::legacyMigrationStatus)
      .POST("/migration/legacy", this::migrateLegacyStorage)
      .build();
  }

  private Mono<ServerResponse> listSessionsWithMessages(ServerRequest request) {
    return owner(request).flatMap(owner -> Mono.zip(
        fetchStore(owner).map(store -> store.getData().entrySet().stream()
          .filter(entry -> entry.getKey().startsWith(SESSION_KEY_PREFIX))
          .map(entry -> readMapValue(entry.getValue()))
          .collect(Collectors.toList())),
        sessionStoreSessions(owner).collectList()
      )
      .map(tuple -> {
        var merged = new LinkedHashMap<String, Map<String, Object>>();
        tuple.getT1().forEach(session -> merged.put(stringValue(session.get("id")), session));
        tuple.getT2().forEach(session -> merged.put(stringValue(session.get("id")), session));
        return merged.values().stream()
          .sorted((left, right) -> Long.compare(nullToZero(longValue(right.get("updatedAt"))), nullToZero(longValue(left.get("updatedAt")))))
          .collect(Collectors.toList());
      })
      .flatMap(items -> ServerResponse.ok().bodyValue(items)));
  }

  private Mono<ServerResponse> saveSessionSnapshot(ServerRequest request) {
    var name = safeName("chat", request.pathVariable("name"));
    return Mono.zip(owner(request), requestBodyMap(request))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var body = tuple.getT2();
        var expectedOwner = stringValue(body.get("_expectedOwner"));
        if (!expectedOwner.isBlank() && !expectedOwner.equals(owner)) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,
            "当前登录用户已变化，已拒绝保存其他用户的会话快照。");
        }
        var suppliedVersion = longValue(body.get("_baseVersion"));
        if (suppliedVersion == null) {
          throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED,
            "客户端版本过旧，请刷新 Halo Console 后再保存会话。");
        }
        if (suppliedVersion < 0) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,
            "会话版本无效，请刷新页面后重试。");
        }
        return settingsFor(owner).flatMap(settings -> {
          var maxImageBytes = maxImageBytes(settings);
          var messages = listOfMaps(body.get("messages"));
          if (messages.size() > MAX_MESSAGES_PER_SESSION) {
            throw badRequest("单个会话中的消息数量超过限制。");
          }
          var session = sessionFromMap(name, owner, body);
          var savedMessages = messages.stream()
            .map(message -> messageToMap(messageFromMap(name, owner, message, maxImageBytes)))
            .collect(Collectors.toList());
          var snapshot = sessionToMap(session, savedMessages);
          enforceSessionSize(snapshot);
          return updateSessionStore(owner, name, data -> {
            var storedSession = readMapValue(data.get("session"));
            var storedVersion = sessionVersion(storedSession);
            var tombstoneVersion = nullToZero(longValue(data.get("tombstoneVersion")));
            var currentVersion = Math.max(storedVersion, tombstoneVersion);
            if (suppliedVersion != null && suppliedVersion != currentVersion) {
              throw new ResponseStatusException(HttpStatus.CONFLICT,
                "会话已在其他页面更新或删除，请刷新后重试。");
            }
            var storedCreatedAt = longValue(storedSession.get("createdAt"));
            if (storedCreatedAt != null && storedCreatedAt > 0) {
              snapshot.put("createdAt", storedCreatedAt);
            }
            snapshot.put("updatedAt", System.currentTimeMillis());
            snapshot.put("_version", currentVersion + 1);
            enforceSessionSize(snapshot);
            data.remove("tombstoneVersion");
            data.remove("deletedAt");
            data.put("session", writeMapValue(snapshot));
          })
            .then(ServerResponse.ok().bodyValue(snapshot));
        });
      });
  }

  private Mono<ServerResponse> deleteSession(ServerRequest request) {
    var name = safeName("chat", request.pathVariable("name"));
    return owner(request)
      .flatMap(owner -> deleteSessionStore(owner, name)
        .then(updateStore(owner, data -> data.remove(sessionKey(name)))))
      .then(ServerResponse.noContent().build());
  }

  private Mono<ServerResponse> exportOwnData(ServerRequest request) {
    return owner(request).flatMap(owner -> Mono.zip(
        sessionsForExport(owner),
        settingsFor(owner),
        logFluxFor(owner).take(MAX_EXPORT_LOGS).collectList(),
        jobRecordsForOwner(owner).take(MAX_EXPORT_JOBS).map(record -> record.job).collectList()
      )
      .map(tuple -> {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "1");
        result.put("exportedAt", System.currentTimeMillis());
        result.put("owner", owner);
        result.put("sessions", tuple.getT1());
        result.put("settings", tuple.getT2());
        result.put("logs", tuple.getT3());
        result.put("jobs", tuple.getT4());
        result.put("attachments", attachmentReferences(tuple.getT1()));
        return result;
      })
      .flatMap(result -> ServerResponse.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(result)));
  }

  private Mono<ServerResponse> currentIdentity(ServerRequest request) {
    return owner(request).zipWith(request.principal()
        .map(principal -> principal instanceof Authentication authentication && hasAdminPermission(authentication))
        .defaultIfEmpty(false))
      .flatMap(tuple -> {
        var result = new LinkedHashMap<String, Object>();
        result.put("owner", tuple.getT1());
        result.put("canViewAllLogs", tuple.getT2());
        return ServerResponse.ok().bodyValue(result);
      });
  }

  private Mono<ServerResponse> deleteOwnData(ServerRequest request) {
    return Mono.zip(owner(request), globalSettings()).flatMap(tuple -> {
      var owner = tuple.getT1();
      var deleteAuditLogs = Boolean.TRUE.equals(booleanValue(tuple.getT2().get("allowUserAuditLogDeletion")));
      return cancelOwnerJobs(owner)
        .then(Flux.concat(
          deletionStep("sessions", deleteOwnedConfigMaps(ownerConfigMapPrefixes(SESSION_CONFIG_MAP_PREFIX, owner))),
          deletionStep("jobs", deleteOwnedConfigMaps(ownerConfigMapPrefixes(JOB_CONFIG_MAP_PREFIX, owner))),
          deletionStep("usage", deleteOwnedConfigMaps(ownerConfigMapPrefixes(USAGE_CONFIG_MAP_PREFIX, owner))),
          deletionStep("auditLogs", deleteOwnedLogMaps(owner, deleteAuditLogs)),
          deletionStep("personalStore", clearOwnStore(owner, deleteAuditLogs))
        ).collectList())
        .map(steps -> {
          var failures = steps.stream()
            .filter(step -> !Boolean.TRUE.equals(step.get("success")))
            .collect(Collectors.toList());
          var deleted = steps.stream()
            .mapToInt(step -> intValue(step.get("deleted")) == null ? 0 : intValue(step.get("deleted")))
            .sum();
          var result = new LinkedHashMap<String, Object>();
          result.put("success", failures.isEmpty());
          result.put("partialFailure", !failures.isEmpty() && failures.size() < steps.size());
          result.put("sessionsDeleted", deletedCount(steps, "sessions"));
          result.put("jobsDeleted", deletedCount(steps, "jobs"));
          result.put("usageRecordsDeleted", deletedCount(steps, "usage"));
          result.put("auditLogsDeleted", deletedCount(steps, "auditLogs") + deletedCount(steps, "personalStore"));
          result.put("auditLogsRetained", !deleteAuditLogs);
          result.put("attachmentsDeleted", 0);
          result.put("deletedCount", deleted);
          result.put("steps", steps);
          result.put("failures", failures);
          result.put("message", failures.isEmpty()
            ? (deleteAuditLogs
              ? "个人聊天数据和审计日志已删除。Halo 附件未被删除，需要单独处理。"
              : "个人聊天数据已删除。根据管理员策略，审计日志已保留。Halo 附件未被删除，需要单独处理。")
            : "部分个人数据未能删除。请查看失败明细，解决问题后重试。Halo 附件未被删除。");
          return result;
        })
        .flatMap(result -> ServerResponse.ok().bodyValue(result));
    });
  }

  private Mono<ServerResponse> listCallLogs(ServerRequest request) {
    return owner(request).flatMap(owner -> logsFor(owner)
      .flatMap(items -> ServerResponse.ok().bodyValue(items)));
  }

  private Mono<ServerResponse> listAllCallLogs(ServerRequest request) {
    var logs = client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(LOG_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("log")));
    var legacyLogs = client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(STORE_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .flatMap(configMap -> Flux.fromIterable(configMap.getData() == null ? List.<Map.Entry<String, String>>of() : configMap.getData().entrySet())
        .filter(entry -> entry.getKey().startsWith(LOG_KEY_PREFIX))
        .map(entry -> readMapValue(entry.getValue())));
    return requireAdminPermission(request).thenMany(logs.concatWith(legacyLogs))
      .sort((left, right) -> Long.compare(nullToZero(longValue(right.get("time"))), nullToZero(longValue(left.get("time")))))
      .take(300)
      .collectList()
      .flatMap(items -> ServerResponse.ok().bodyValue(items));
  }

  private Mono<ServerResponse> createCallLog(ServerRequest request) {
    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
      "调用日志仅由服务器在模型任务完成后写入，不能由浏览器创建。"));
  }

  private Mono<ServerResponse> getImageCache(ServerRequest request) {
    var name = safeName("img", request.pathVariable("name"));
    return owner(request).flatMap(owner -> fetchStore(owner)
      .map(store -> store.getData().get(IMAGE_KEY_PREFIX + name))
      .filter(value -> value != null && !value.isBlank())
      .map(this::readMapValue)
      .flatMap(saved -> ServerResponse.ok().bodyValue(saved))
      .switchIfEmpty(ServerResponse.notFound().build()));
  }

  private Mono<ServerResponse> createImageCache(ServerRequest request) {
    return Mono.zip(owner(request), requestBodyMap(request))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        return settingsFor(owner).flatMap(settings -> {
          var cache = imageCacheFromMap(owner, tuple.getT2(), maxImageBytes(settings));
          var map = imageCacheToMap(cache);
          return updateStore(owner, data -> data.put(IMAGE_KEY_PREFIX + idOf(cache), writeMapValue(map)))
            .then(ServerResponse.ok().bodyValue(map));
        });
      });
  }

  private Mono<ServerResponse> getSettings(ServerRequest request) {
    return owner(request).flatMap(owner -> settingsFor(owner)
      .flatMap(settings -> ServerResponse.ok().bodyValue(settings)));
  }

  private Mono<ServerResponse> getGlobalSettings(ServerRequest request) {
    return requireAdminPermission(request)
      .then(globalSettings())
      .flatMap(settings -> ServerResponse.ok().bodyValue(settings));
  }

  private Mono<ServerResponse> domPurifyAsset(ServerRequest request) {
    try (var input = getClass().getClassLoader().getResourceAsStream("assets/dompurify.min.js")) {
      if (input == null) {
        return ServerResponse.notFound().build();
      }
      return ServerResponse.ok()
        .contentType(MediaType.valueOf("application/javascript"))
        .bodyValue(input.readAllBytes());
    } catch (IOException e) {
      return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取 DOMPurify 资源。"));
    }
  }

  private Mono<ServerResponse> saveSettings(ServerRequest request) {
    return Mono.zip(owner(request), requestBodyMap(request))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var settings = validateSettings(tuple.getT2());
        return updateStore(owner, data -> data.put(SETTINGS_KEY, writeMapValue(settings)))
          .then(ServerResponse.ok().bodyValue(settings));
      });
  }

  private Mono<Map<String, Object>> requestBodyMap(ServerRequest request) {
    return DataBufferUtils.join(request.bodyToFlux(DataBuffer.class), MAX_JSON_REQUEST_BYTES)
      .onErrorMap(DataBufferLimitException.class,
        error -> badRequest("JSON 请求体超过 1 MiB 限制。"))
      .flatMap(buffer -> {
        try {
          var bytes = new byte[buffer.readableByteCount()];
          buffer.read(bytes);
          if (bytes.length == 0) {
            return Mono.error(badRequest("JSON 请求体不能为空。"));
          }
          return Mono.just(castMap(objectMapper.readValue(bytes,
            new TypeReference<Map<String, Object>>() {})));
        } catch (IOException | IllegalArgumentException error) {
          return Mono.error(badRequest("JSON 请求体格式无效。"));
        } finally {
          DataBufferUtils.release(buffer);
        }
      });
  }

  private Mono<ServerResponse> generateText(ServerRequest request) {
    return ensureActive().then(Mono.zip(owner(request), requestBodyMap(request), globalSettings()))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var body = tuple.getT2();
        var globalSettings = tuple.getT3();
        var model = limitString(request.pathVariable("name"), 253);
        if (model.isBlank()) {
          throw badRequest("必须选择模型。");
        }
        enforceAllowedModel(model, globalSettings);
        var messages = listOfMaps(body.get("messages"));
        if (messages.isEmpty()) {
          throw badRequest("消息不能为空。");
        }
        validateAiRequestMessages(messages, globalSettings);
        return canonicalizeMessageAttachmentUrls(owner, messages).flatMap(canonicalMessages -> {
          var maxOutputTokens = clampInt(body.get("maxOutputTokens"), 1, 8192, 1200);
          var startedAt = System.currentTimeMillis();
          var promptTokens = estimateRequestTokens(canonicalMessages);
          var operation = auxiliaryOperation(body.get("operation"));
          var jobId = nextJobId();
          return reserveUsage(owner, globalSettings, promptTokens, maxOutputTokens, jobId)
            .flatMap(reservation -> modelInvoker.generateText(model, canonicalMessages, maxOutputTokens)
          .flatMap(result -> {
            var response = new LinkedHashMap<String, Object>();
            response.put("text", limitString(result.text(), MAX_STREAM_TEXT_LENGTH));
            response.put("reasoning", limitString(result.reasoning(), MAX_STREAM_TEXT_LENGTH));
            response.put("inputTokens", result.inputTokens());
            response.put("outputTokens", result.outputTokens());
            response.put("totalTokens", result.totalTokens());
            return saveAuthoritativeLog(owner, "", "", "text", operation, model, "success", "",
                startedAt, promptTokens, result.inputTokens(), result.outputTokens(), result.totalTokens(), request)
              .then(ServerResponse.ok().bodyValue(response));
          })
          .onErrorResume(error -> saveAuthoritativeLog(owner, "", "", "text", operation, model, "error",
              limitString(cleanAiFoundationError(error), 4000), startedAt, promptTokens, null, null, null, request)
            .then(Mono.error(error)))
          .doFinally(signal -> releaseUsageReservation(reservation).subscribe()));
        });
      });
  }

  private Mono<ServerResponse> createChatJob(ServerRequest request) {
    return ensureActive().then(Mono.zip(owner(request), requestBodyMap(request), globalSettings()))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var body = tuple.getT2();
        var globalSettings = tuple.getT3();
        return settingsFor(owner).flatMap(settings -> {
          var sessionBody = castMapValue(body.get("session"));
          var sessionId = safeName("chat", stringValue(sessionBody.get("id")));
          if (sessionId.isBlank()) {
            throw badRequest("缺少会话标识。");
          }
          var assistant = castMapValue(body.get("assistant"));
          var assistantId = stringValue(assistant.get("id"));
          if (assistantId.isBlank()) {
            throw badRequest("缺少 AI 回复消息标识。");
          }
          var model = stringValue(body.get("model"));
          if (model.isBlank()) {
            throw badRequest("必须选择模型。");
          }
          enforceAllowedModel(model, globalSettings);
          var requestMessages = listOfMaps(body.get("requestMessages"));
          if (requestMessages.isEmpty()) {
            throw badRequest("请求消息不能为空。");
          }
          validateAiRequestMessages(requestMessages, globalSettings);
          return canonicalizeMessageAttachmentUrls(owner, requestMessages).flatMap(canonicalMessages -> {
            var normalizedSession = normalizeSessionSnapshot(sessionId, owner, sessionBody, maxImageBytes(settings));
            var jobId = nextJobId();
            var promptTokens = estimateRequestTokens(canonicalMessages);
            var now = System.currentTimeMillis();
            var job = new LinkedHashMap<String, Object>();
            job.put("id", jobId);
            job.put("type", "chat");
            job.put("owner", owner);
            job.put("sessionId", sessionId);
            job.put("assistantId", assistantId);
            job.put("model", model);
            job.put("status", "running");
            job.put("createdAt", now);
            job.put("updatedAt", now);
            job.put("promptTokens", promptTokens);
            job.put("completionTokens", 0);
            job.put("totalTokens", promptTokens);
            job.put("content", "");
            job.put("reasoning", "");
            job.put("reasoningOpen", true);
            job.put("instanceId", instanceId);
            job.put("heartbeatAt", now);
            job.putAll(requestAuditMeta(request));
            return reserveUsage(owner, globalSettings, promptTokens, 4096, jobId)
            .flatMap(reservation -> saveSessionForJob(owner, sessionId, normalizedSession)
            .flatMap(savedSession -> {
              job.put("reservedTokens", reservation.reservedTokens);
              job.put("sessionVersion", sessionVersion(savedSession));
              return saveJob(owner, jobId, job)
                .doOnSuccess(ignored -> runChatJob(owner, sessionId, assistantId, jobId, model,
                  canonicalMessages, promptTokens, globalSettings, reservation))
                .then(ServerResponse.ok().bodyValue(job));
            })
            .onErrorResume(error -> releaseUsageReservation(reservation).then(Mono.error(error))));
          });
        });
      });
  }

  private Mono<ServerResponse> createImageJob(ServerRequest request) {
    return ensureActive().then(Mono.zip(owner(request), requestBodyMap(request), globalSettings()))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var body = tuple.getT2();
        var globalSettings = tuple.getT3();
        var sessionBody = castMapValue(body.get("session"));
        var sessionId = safeName("chat", stringValue(sessionBody.get("id")));
        var assistant = castMapValue(body.get("assistant"));
        var assistantId = stringValue(assistant.get("id"));
        var model = stringValue(body.get("model"));
        var prompt = limitString(stringValue(body.get("prompt")), 12_000);
        var payload = castMapValue(body.get("payload"));
        if (sessionId.isBlank() || assistantId.isBlank() || model.isBlank() || prompt.isBlank()) {
            throw badRequest("图像生成任务缺少会话标识、AI 回复标识、模型或提示词。");
        }
        enforceAllowedModel(model, globalSettings);
        validateImagePayload(payload, globalSettings);
        return canonicalizeImagePayload(owner, payload).flatMap(canonicalPayload -> {
          var normalizedSession = normalizeSessionSnapshot(sessionId, owner, sessionBody, maxImageBytes(globalSettings));
          var jobId = nextJobId();
          var now = System.currentTimeMillis();
          var promptTokens = estimateImagePromptTokens(prompt, canonicalPayload);
          var job = new LinkedHashMap<String, Object>();
          job.put("id", jobId);
          job.put("type", "image");
          job.put("owner", owner);
          job.put("sessionId", sessionId);
          job.put("assistantId", assistantId);
          job.put("model", model);
          job.put("status", "running");
          job.put("createdAt", now);
          job.put("updatedAt", now);
          job.put("promptTokens", promptTokens);
          job.put("completionTokens", 0);
          job.put("totalTokens", promptTokens);
          job.put("content", "正在生成图像...");
          job.put("images", List.of());
          job.put("instanceId", instanceId);
          job.put("heartbeatAt", now);
          job.putAll(requestAuditMeta(request));
          var aiPayload = toAiFoundationImagePayload(canonicalPayload);
          return reserveUsage(owner, globalSettings, promptTokens, 4096, jobId)
          .flatMap(reservation -> saveSessionForJob(owner, sessionId, normalizedSession)
          .flatMap(savedSession -> {
            job.put("reservedTokens", reservation.reservedTokens);
            job.put("sessionVersion", sessionVersion(savedSession));
            return saveJob(owner, jobId, job)
              .doOnSuccess(ignored -> runImageJobV2(owner, sessionId, assistantId, jobId, model, aiPayload,
                promptTokens, globalSettings, reservation))
                .then(ServerResponse.ok().bodyValue(job));
          })
          .onErrorResume(error -> releaseUsageReservation(reservation).then(Mono.error(error))));
        });
      });
  }

  private Mono<ServerResponse> getJob(ServerRequest request) {
    var jobId = safeName("job", request.pathVariable("name"));
    return owner(request).flatMap(owner -> fetchJob(owner, jobId)
      .flatMap(job -> ServerResponse.ok().bodyValue(job))
      .switchIfEmpty(ServerResponse.notFound().build()));
  }

  /**
   * Returns the persisted Job state for the current owner after a browser reconnect. The Job
   * record, rather than an SSE connection, is the source of truth for recovery.
   */
  private Mono<ServerResponse> listRecoverableJobs(ServerRequest request) {
    return owner(request).flatMap(owner -> jobRecordsForOwner(owner)
      .map(record -> record.job)
      .filter(job -> JobLifecyclePolicy.isActive(job.get("status"))
        || jobUpdatedAt(job) >= System.currentTimeMillis() - Duration.ofMinutes(10).toMillis())
      .sort((left, right) -> Long.compare(jobUpdatedAt(right), jobUpdatedAt(left)))
      .take(100)
      .collectList()
      .flatMap(jobs -> ServerResponse.ok().bodyValue(jobs)));
  }

  private Mono<ServerResponse> jobEvents(ServerRequest request) {
    var jobId = safeName("job", request.pathVariable("name"));
    return owner(request).flatMap(owner -> fetchJob(owner, jobId).flatMap(initial -> {
      var key = runningJobKey(owner, jobId);
      var events = jobEvents.reconnectingStream(key, initial, () -> fetchJob(owner, jobId))
        .map(this::toJobSse);
      return ServerResponse.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(events, new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {});
    }).switchIfEmpty(ServerResponse.notFound().build()));
  }

  private ServerSentEvent<Map<String, Object>> toJobSse(Map<String, Object> job) {
    return ServerSentEvent.builder(job)
      .event("job")
      .id(stringValue(job.get("id")))
      .build();
  }

  private void emitJobEvent(String owner, String jobId, Map<String, Object> job) {
    var key = runningJobKey(owner, jobId);
    jobEvents.emit(key, job);
  }

  private Map<String, Object> transientJobEvent(String jobId, Integer promptTokens, Map<String, Object> state,
    String status, String error) {
    var effectivePromptTokens = tokenValue(state.get("_actualInputTokens"), promptTokens);
    var completionTokens = tokenValue(state.get("_actualOutputTokens"),
      estimateTokens(stringValue(state.get("reasoning")) + "\n" + stringValue(state.get("content"))));
    var totalTokens = tokenValue(state.get("_actualTotalTokens"),
      effectivePromptTokens + completionTokens);
    var job = new LinkedHashMap<String, Object>();
    job.put("id", jobId);
    job.put("status", status);
    job.put("error", error);
    job.put("updatedAt", System.currentTimeMillis());
    job.put("content", state.get("content"));
    job.put("reasoning", state.get("reasoning"));
    job.put("reasoningOpen", state.get("reasoningOpen"));
    job.put("images", listOfStrings(state.get("images")));
    job.put("promptTokens", effectivePromptTokens);
    job.put("completionTokens", completionTokens);
    job.put("totalTokens", totalTokens);
    return job;
  }

  private boolean shouldPersistRunningState(Map<String, Object> state) {
    var now = System.currentTimeMillis();
    var last = nullToZero(longValue(state.get("_lastPersistAt")));
    if (last > 0 && now - last < 1500L) {
      return false;
    }
    state.put("_lastPersistAt", now);
    return true;
  }

  private Mono<ServerResponse> cancelJob(ServerRequest request) {
    var jobId = safeName("job", request.pathVariable("name"));
    return owner(request).flatMap(owner -> fetchJob(owner, jobId).flatMap(job -> {
      if (!JobLifecyclePolicy.isActive(job.get("status"))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已经结束，不能取消。");
      }
      var key = runningJobKey(owner, jobId);
      var disposable = runningJobs.remove(key);
      var localExecution = disposable != null;
      if (disposable != null && !disposable.isDisposed()) {
        disposable.dispose();
      }
      var state = runningJobStates.remove(key);
      if (state == null) {
        state = cancellationState(job);
      }
      var reservation = new UsageReservation(owner,
        dayKey(nullToZero(longValue(job.get("createdAt"))) > 0
          ? nullToZero(longValue(job.get("createdAt")))
          : System.currentTimeMillis()),
        intValue(job.get("promptTokens")) == null ? 0 : intValue(job.get("promptTokens")),
        intValue(job.get("reservedTokens")),
        jobId);
      return updateJobAndSession(owner, jobId, state, "cancelled", "Cancelled by user.")
        // A local subscription releases its in-memory reservation in doFinally. A job running
        // on another instance has no local subscription, so release only its persistent marker.
        .then(localExecution ? Mono.empty() : releasePersistentUsageReservation(reservation))
        .then(fetchJob(owner, jobId))
        .flatMap(saved -> ServerResponse.ok().bodyValue(saved));
    }).switchIfEmpty(ServerResponse.notFound().build()));
  }

  private Map<String, Object> cancellationState(Map<String, Object> job) {
    var state = new LinkedHashMap<String, Object>();
    state.put("_type", stringValue(job.get("type")));
    state.put("_model", stringValue(job.get("model")));
    state.put("_sessionId", stringValue(job.get("sessionId")));
    state.put("_assistantId", stringValue(job.get("assistantId")));
    state.put("_jobId", stringValue(job.get("id")));
    state.put("_promptTokens", intValue(job.get("promptTokens")));
    state.put("_reservedTokens", intValue(job.get("reservedTokens")));
    state.put("content", job.get("content"));
    state.put("reasoning", job.get("reasoning"));
    state.put("reasoningOpen", false);
    state.put("images", listOfStrings(job.get("images")));
    return state;
  }

  private Mono<Void> markInterruptedJobs() {
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(JOB_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .flatMap(configMap -> {
        var job = readMapValue(configMap.getData() == null ? null : configMap.getData().get("job"));
        var status = stringValue(job.get("status"));
        if (!JobLifecyclePolicy.isActive(status)) {
          return Mono.<Void>empty();
        }
        var owner = stringValue(job.get("owner"));
        var jobId = stringValue(job.get("id"));
        if (owner.isBlank() || jobId.isBlank()) {
          return Mono.<Void>empty();
        }
        var state = new LinkedHashMap<String, Object>();
        state.put("_type", stringValue(job.get("type")));
        state.put("_model", stringValue(job.get("model")));
        state.put("_sessionId", stringValue(job.get("sessionId")));
        state.put("_assistantId", stringValue(job.get("assistantId")));
        state.put("_jobId", jobId);
        state.put("_promptTokens", intValue(job.get("promptTokens")));
        state.put("_reservedTokens", intValue(job.get("reservedTokens")));
        state.put("content", job.get("content"));
        state.put("reasoning", job.get("reasoning"));
        state.put("reasoningOpen", false);
        state.put("images", listOfStrings(job.get("images")));
        return updateJobAndSession(owner, jobId, state, "interrupted", "Halo 重启或任务执行中断，请重新生成。")
          .onErrorResume(error -> updateJob(owner, jobId, savedJob -> {
            savedJob.put("status", "interrupted");
            savedJob.put("error", "Halo 重启或任务执行中断，请重新生成。");
            savedJob.put("updatedAt", System.currentTimeMillis());
          }));
      })
      .then();
  }

  private Mono<Void> markInterruptedJobsV2() {
    var now = System.currentTimeMillis();
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(JOB_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .flatMap(configMap -> {
        var job = readMapValue(configMap.getData() == null ? null : configMap.getData().get("job"));
        var status = stringValue(job.get("status"));
        if (!JobLifecyclePolicy.isActive(status)) {
          return Mono.<Void>empty();
        }
        var owner = stringValue(job.get("owner"));
        var jobId = stringValue(job.get("id"));
        if (owner.isBlank() || jobId.isBlank()) {
          return Mono.<Void>empty();
        }
        var jobInstanceId = stringValue(job.get("instanceId"));
        var heartbeatAt = nullToZero(longValue(job.get("heartbeatAt")));
        return isInstanceAlive(jobInstanceId).flatMap(alive -> {
          if (!JobLifecyclePolicy.shouldInterrupt(alive, now, heartbeatAt,
            Duration.ofMinutes(10).toMillis())) {
            return Mono.<Void>empty();
          }
          var state = new LinkedHashMap<String, Object>();
          state.put("_type", stringValue(job.get("type")));
          state.put("_model", stringValue(job.get("model")));
          state.put("_sessionId", stringValue(job.get("sessionId")));
          state.put("_assistantId", stringValue(job.get("assistantId")));
          state.put("_jobId", jobId);
          state.put("_promptTokens", intValue(job.get("promptTokens")));
          state.put("_reservedTokens", intValue(job.get("reservedTokens")));
          state.put("content", job.get("content"));
          state.put("reasoning", job.get("reasoning"));
          state.put("reasoningOpen", false);
          state.put("images", listOfStrings(job.get("images")));
          var message = "Halo 重启或任务执行中断，请重新生成。";
          var promptTokens = intValue(job.get("promptTokens"));
          var createdAt = nullToZero(longValue(job.get("createdAt")));
          var day = dayKey(createdAt > 0 ? createdAt : now);
          return updateJobAndSession(owner, jobId, state, "interrupted", message)
            .onErrorResume(error -> updateJob(owner, jobId, savedJob -> {
              savedJob.put("status", "interrupted");
              savedJob.put("error", message);
              savedJob.put("updatedAt", System.currentTimeMillis());
            }))
            .then(releasePersistentUsageReservation(new UsageReservation(owner, day,
              promptTokens == null ? 0 : promptTokens, intValue(job.get("reservedTokens")), jobId)));
        });
      })
      .then();
  }

  private Mono<Void> heartbeatInstance() {
    var name = instanceStoreName(instanceId);
    return client.fetch(ConfigMap.class, name)
      .switchIfEmpty(Mono.defer(() -> {
        var configMap = new ConfigMap();
        configMap.setMetadata(metadata(name));
        configMap.setData(new LinkedHashMap<>());
        return client.create(configMap);
      }))
      .flatMap(configMap -> {
        var data = configMap.getData() == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(configMap.getData());
        data.put("instanceId", instanceId);
        data.put("heartbeatAt", String.valueOf(System.currentTimeMillis()));
        configMap.setData(data);
        return client.update(configMap);
      })
      .retryWhen(Retry.backoff(4, Duration.ofMillis(60)).filter(this::isOptimisticLockConflict))
      .then();
  }

  private Mono<Boolean> isInstanceAlive(String checkedInstanceId) {
    if (checkedInstanceId == null || checkedInstanceId.isBlank()) {
      return Mono.just(false);
    }
    return client.fetch(ConfigMap.class, instanceStoreName(checkedInstanceId))
      .map(configMap -> {
        var data = configMap.getData();
        var heartbeatAt = data == null ? 0L : nullToZero(longValue(data.get("heartbeatAt")));
        return System.currentTimeMillis() - heartbeatAt <= INSTANCE_HEARTBEAT_TTL_MS;
      })
      .defaultIfEmpty(false);
  }

  private Mono<ServerResponse> uploadAttachment(ServerRequest request) {
    return owner(request).flatMap(owner -> settingsFor(owner)
      .flatMap(settings -> request.multipartData().flatMap(parts -> {
        var file = firstFile(parts);
        var maxImageBytes = maxImageBytes(settings);
        if (file == null) {
          throw badRequest("请选择要上传的图片。");
        }
        var declaredLength = file.headers().getContentLength();
        if (declaredLength > maxImageBytes || declaredLength > HARD_MAX_IMAGE_BYTES) {
          throw badRequest("图片大小超过管理员配置的上限。");
        }
        return throttleImageUpload(DataBufferUtils.join(file.content(), (int) Math.min(maxImageBytes, HARD_MAX_IMAGE_BYTES) + 1)
          .onErrorMap(DataBufferLimitException.class, e -> badRequest("图片大小超过管理员配置的上限。"))
          .flatMap(buffer -> {
            try {
              var bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              if (bytes.length > maxImageBytes || bytes.length > HARD_MAX_IMAGE_BYTES) {
                throw badRequest("图片大小超过管理员配置的上限。");
              }
              return Mono.fromCallable(() -> ImageUploadPolicy.sanitize(bytes, maxImageBytes))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(ImageUploadPolicy.InvalidImageException.class,
                  error -> badRequest(error.getMessage()))
                .flatMap(image -> forwardAttachmentUpload(file, image.bytes(),
                  MediaType.parseMediaType(image.mediaType())));
            } finally {
              DataBufferUtils.release(buffer);
            }
          }));
      })));
  }

  private Mono<ServerResponse> throttleImageUpload(Mono<ServerResponse> operation) {
    return Mono.defer(() -> {
      if (!IMAGE_UPLOAD_PERMITS.tryAcquire()) {
        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "图片上传繁忙，请稍后重试。"));
      }
      return operation.doFinally(signal -> IMAGE_UPLOAD_PERMITS.release());
    });
  }

  private Mono<ServerResponse> forwardAttachmentUpload(FilePart file, byte[] bytes, MediaType mediaType) {
    var filename = limitString(file.filename(), 255);
    return attachmentService.upload(null, null, filename,
        Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)), mediaType)
      .flatMap(attachment -> attachmentService.getPermalink(attachment)
        .flatMap(permalink -> {
          var response = objectMapper.convertValue(attachment, new TypeReference<Map<String, Object>>() {});
          var status = castMapValue(response.get("status"));
          status.put("permalink", permalink.toString());
          response.put("status", status);
          return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(response);
        }))
      .onErrorMap(error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
        "Halo 附件服务无法保存该图片，请检查附件存储策略后重试。", error));
  }

  private Mono<ServerResponse> legacyMigrationStatus(ServerRequest request) {
    return requireAdminPermission(request).then(owner(request)).flatMap(owner -> fetchStore(owner)
      .flatMap(store -> {
        var result = new LinkedHashMap<String, Object>();
        var migration = store.getData().get(LEGACY_MIGRATION_KEY);
        if (migration != null && !migration.isBlank()) {
          result.put("sessions", 0);
          result.put("messages", 0);
          result.put("callLogs", 0);
          result.put("imageCaches", 0);
          result.put("total", 0);
          result.put("migrated", true);
          result.put("migration", readMapValue(migration));
          return ServerResponse.ok().bodyValue(result);
        }
        return Mono.zip(
            legacyRestItems(request, "halo-ai-sessions").map(items -> legacyItemsForOwner(items, owner).size()).onErrorReturn(0),
            legacyRestItems(request, "halo-ai-messages").map(items -> legacyItemsForOwner(items, owner).size()).onErrorReturn(0),
            legacyRestItems(request, "halo-ai-call-logs").map(items -> legacyItemsForOwner(items, owner).size()).onErrorReturn(0),
            legacyRestItems(request, "halo-ai-image-caches").map(items -> legacyItemsForOwner(items, owner).size()).onErrorReturn(0)
          )
          .flatMap(tuple -> {
            result.put("sessions", tuple.getT1());
            result.put("messages", tuple.getT2());
            result.put("callLogs", tuple.getT3());
            result.put("imageCaches", tuple.getT4());
            result.put("total", tuple.getT1() + tuple.getT2() + tuple.getT3() + tuple.getT4());
            result.put("migrated", false);
            return ServerResponse.ok().bodyValue(result);
          });
      }));
  }

  private Mono<ServerResponse> migrateLegacyStorage(ServerRequest request) {
    return requireAdminPermission(request).then(owner(request)).flatMap(owner -> {
      var result = new LinkedHashMap<String, Object>();
      var warnings = new ArrayList<String>();
      result.put("deleteWarnings", warnings);
      result.put("legacyDeleteSkipped", true);
      warnings.add("Legacy extension objects were copied but not deleted because this Halo runtime reports missing indices for the old AI chat extension types.");
      return Mono.zip(
          legacyRestItems(request, "halo-ai-sessions").map(items -> legacyItemsForOwner(items, owner)),
          legacyRestItems(request, "halo-ai-messages").map(items -> legacyItemsForOwner(items, owner)),
          legacyRestItems(request, "halo-ai-call-logs").map(items -> legacyItemsForOwner(items, owner)),
          legacyRestItems(request, "halo-ai-image-caches").map(items -> legacyItemsForOwner(items, owner))
        )
        .flatMap(tuple -> {
          var messages = tuple.getT2().stream()
            .map(this::legacyMessageToMap)
            .sorted((left, right) -> Long.compare(nullToZero(longValue(left.get("createdAt"))), nullToZero(longValue(right.get("createdAt")))))
            .collect(Collectors.toList());
          var sessions = tuple.getT1().stream()
            .map(session -> legacySessionToMap(session, messages.stream()
              .filter(message -> metadataName(session).equals(stringValue(message.get("sessionId"))))
              .collect(Collectors.toList())))
            .sorted((left, right) -> Long.compare(nullToZero(longValue(left.get("createdAt"))), nullToZero(longValue(right.get("createdAt")))))
            .collect(Collectors.toList());
          var callLogs = tuple.getT3().stream().map(this::legacySpecToMap).collect(Collectors.toList());
          var imageCaches = tuple.getT4().stream().map(this::legacySpecToMap).collect(Collectors.toList());
          result.put("sessions", sessions.size());
          result.put("messages", messages.size());
          result.put("callLogs", callLogs.size());
          result.put("imageCaches", imageCaches.size());
          var marker = new LinkedHashMap<String, Object>();
          marker.put("completedAt", System.currentTimeMillis());
          marker.put("sessions", result.get("sessions"));
          marker.put("messages", result.get("messages"));
          marker.put("callLogs", result.get("callLogs"));
          marker.put("imageCaches", result.get("imageCaches"));
          marker.put("legacyDeleteSkipped", result.get("legacyDeleteSkipped"));
          marker.put("deleteWarnings", result.get("deleteWarnings"));
          return updateStore(owner, data -> {
              for (var session : sessions) {
                data.put(sessionKey(stringValue(session.get("id"))), writeMapValue(session));
              }
              for (var log : callLogs) {
                data.put(LOG_KEY_PREFIX + safeName("log",
                  stringValue(log.get("time")) + "-" + stringValue(log.get("model"))), writeMapValue(log));
              }
              for (var image : imageCaches) {
                data.put(IMAGE_KEY_PREFIX + safeName("img",
                  stringValue(image.get("messageId")) + "-" + stringValue(image.get("sourceUrl"))), writeMapValue(image));
              }
              data.put(LEGACY_MIGRATION_KEY, writeMapValue(marker));
            })
            .thenReturn(result);
        })
        .flatMap(ignored -> ServerResponse.ok().bodyValue(result));
    });
  }

  private Mono<String> owner(ServerRequest request) {
    return request.principal()
      .map(Principal::getName)
      .map(String::trim)
      .filter(name -> !name.isBlank())
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
        "请先登录后再使用 AI 聊天。")));
  }

  private Mono<List<Map<String, Object>>> legacyRestItems(ServerRequest request, String plural) {
    return Mono.error(new ResponseStatusException(HttpStatus.GONE,
      "旧版扩展数据无法在当前 Halo 运行时安全读取。请使用备份或在旧版插件环境中导出后再迁移。"));
  }

  private Mono<List<Map<String, Object>>> legacyRestItemsPage(ServerRequest request, String plural, int page,
    List<Map<String, Object>> accumulated) {
    return legacyRestItems(request, plural);
  }

  private List<Map<String, Object>> legacyItemsForOwner(List<Map<String, Object>> items, String owner) {
    return items.stream()
      .filter(item -> owner.equals(stringValue(legacySpecToMap(item).get("owner"))))
      .collect(Collectors.toList());
  }

  private Map<String, Object> legacySessionToMap(Map<String, Object> item, List<Map<String, Object>> messages) {
    var spec = legacySpecToMap(item);
    var session = new LinkedHashMap<String, Object>();
    session.put("id", metadataName(item));
    session.put("title", limitString(stringValue(spec.get("title")), MAX_TITLE_LENGTH));
    session.put("memory", limitString(stringValue(spec.get("memory")), MAX_MEMORY_LENGTH));
    session.put("tags", cleanTags(listOfStrings(spec.get("tags"))));
    session.put("contextClearedAt", longValue(spec.get("contextClearedAt")));
    session.put("createdAt", longOrNow(spec.get("createdAt")));
    session.put("updatedAt", longOrNow(spec.get("updatedAt")));
    session.put("messages", messages);
    return session;
  }

  private Map<String, Object> legacyMessageToMap(Map<String, Object> item) {
    var spec = legacySpecToMap(item);
    var message = new LinkedHashMap<String, Object>();
    message.put("id", emptyToDefault(stringValue(spec.get("id")), metadataName(item)));
    message.put("sessionId", stringValue(spec.get("sessionId")));
    message.put("role", stringValue(spec.get("role")));
    message.put("favorite", booleanValue(spec.get("favorite")));
    message.put("content", limitString(stringValue(spec.get("content")), MAX_CONTENT_LENGTH));
    message.put("reasoning", limitString(stringValue(spec.get("reasoning")), MAX_REASONING_LENGTH));
    message.put("reasoningOpen", booleanValue(spec.get("reasoningOpen")));
    message.put("createdAt", longOrNow(spec.get("createdAt")));
    message.put("updatedAt", longValue(spec.get("updatedAt")));
    message.put("promptTokens", intValue(spec.get("promptTokens")));
    message.put("completionTokens", intValue(spec.get("completionTokens")));
    message.put("totalTokens", intValue(spec.get("totalTokens")));
    message.put("files", listOfMaps(spec.get("files")));
    message.put("images", listOfStrings(spec.get("images")));
    return message;
  }

  private Map<String, Object> legacySpecToMap(Map<String, Object> item) {
    return castMapValue(item.get("spec"));
  }

  private String metadataName(Map<String, Object> item) {
    return stringValue(castMapValue(item.get("metadata")).get("name"));
  }

  private Flux<AiChatSession> legacySessions(String owner) {
    return client.listAll(AiChatSession.class, new ListOptions(), Sort.unsorted())
      .filter(session -> session.getSpec() != null && owner.equals(session.getSpec().getOwner()))
      .sort(Comparator.comparing(session -> nullToZero(session.getSpec().getCreatedAt())));
  }

  private Flux<Map<String, Object>> legacyMessages(String owner, String sessionId) {
    return client.listAll(AiChatMessage.class, new ListOptions(), Sort.unsorted())
      .filter(message -> message.getSpec() != null && owner.equals(message.getSpec().getOwner()))
      .filter(message -> sessionId == null || sessionId.isBlank()
        || sessionId.equals(message.getSpec().getSessionId()))
      .sort(Comparator.comparing(message -> nullToZero(message.getSpec().getCreatedAt())))
      .map(this::messageToMap);
  }

  private Flux<AiChatMessage> legacyMessageObjects(String owner, String sessionId) {
    return client.listAll(AiChatMessage.class, new ListOptions(), Sort.unsorted())
      .filter(message -> message.getSpec() != null && owner.equals(message.getSpec().getOwner()))
      .filter(message -> sessionId == null || sessionId.isBlank()
        || sessionId.equals(message.getSpec().getSessionId()))
      .sort(Comparator.comparing(message -> nullToZero(message.getSpec().getCreatedAt())));
  }

  private Flux<AiChatCallLog> legacyCallLogs(String owner) {
    return client.listAll(AiChatCallLog.class, new ListOptions(), Sort.unsorted())
      .filter(log -> log.getSpec() != null && owner.equals(log.getSpec().getOwner()))
      .sort(Comparator.comparing(log -> nullToZero(log.getSpec().getTime())));
  }

  private Flux<AiChatImageCache> legacyImageCaches(String owner) {
    return client.listAll(AiChatImageCache.class, new ListOptions(), Sort.unsorted())
      .filter(image -> image.getSpec() != null && owner.equals(image.getSpec().getOwner()))
      .sort(Comparator.comparing(image -> nullToZero(image.getSpec().getCreatedAt())));
  }

  private Mono<List<Map<String, Object>>> messagesFor(String owner, String sessionId) {
    return legacyMessages(owner, sessionId).collectList();
  }

  private Mono<Void> deleteStaleMessages(String owner, String sessionId, Set<String> keepNames) {
    return client.list(
        AiChatMessage.class,
        message -> owner.equals(message.getSpec().getOwner())
          && sessionId.equals(message.getSpec().getSessionId()),
        Comparator.comparing(message -> nullToZero(message.getSpec().getCreatedAt()))
      )
      .filter(message -> !keepNames.contains(idOf(message)))
      .flatMap(client::delete)
      .then();
  }

  private Mono<Void> deleteLegacyMessages(String owner, String sessionId) {
    return legacyMessageObjects(owner, sessionId)
      .flatMap(client::delete)
      .then();
  }

  private Mono<Map<String, Object>> migrateLegacyCallLogs(String owner, Map<String, Object> result) {
    var warnings = migrationWarnings(result);
    return legacyCallLogs(owner)
      .flatMap(log -> {
        var map = callLogToMap(log);
        return updateStore(owner, data -> data.put(LOG_KEY_PREFIX + safeName("log",
            stringValue(map.get("time")) + "-" + idOf(log)), writeMapValue(map)))
          .thenReturn(1);
      })
      .reduce(0, Integer::sum)
      .map(count -> {
        result.put("callLogs", count);
        return result;
      });
  }

  private Mono<Map<String, Object>> migrateLegacyImageCaches(String owner, Map<String, Object> result) {
    var warnings = migrationWarnings(result);
    return legacyImageCaches(owner)
      .flatMap(image -> {
        var map = imageCacheToMap(image);
        return updateStore(owner, data -> data.put(IMAGE_KEY_PREFIX + idOf(image), writeMapValue(map)))
          .thenReturn(1);
      })
      .reduce(0, Integer::sum)
      .map(count -> {
        result.put("imageCaches", count);
        return result;
      });
  }

  @SuppressWarnings("unchecked")
  private List<String> migrationWarnings(Map<String, Object> result) {
    var warnings = result.get("deleteWarnings");
    if (warnings instanceof List<?> list) {
      return (List<String>) list;
    }
    var next = new ArrayList<String>();
    result.put("deleteWarnings", next);
    return next;
  }

  private void runChatJob(String owner, String sessionId, String assistantId, String jobId, String model,
    List<Map<String, Object>> requestMessages, Integer promptTokens, Map<String, Object> globalSettings,
    UsageReservation reservation) {
    var state = new LinkedHashMap<String, Object>();
    state.put("content", "");
    state.put("reasoning", "");
    state.put("reasoningOpen", true);
    state.put("maxOutputCharacters", clampInt(globalSettings.get("maxOutputCharacters"), 4000, MAX_STREAM_TEXT_LENGTH, MAX_STREAM_TEXT_LENGTH));
    state.put("_type", "chat");
    state.put("_model", model);
    state.put("_sessionId", sessionId);
    state.put("_assistantId", assistantId);
    state.put("_jobId", jobId);
    state.put("_promptTokens", promptTokens);
    state.put("_reservedTokens", reservation.reservedTokens);
    state.put("_usageDay", reservation.day);
    var key = runningJobKey(owner, jobId);
    runningJobStates.put(key, state);
    var disposable = modelInvoker.streamText(model, requestMessages, 4096)
      .flatMap(stream -> stream.deltas()
        .concatMap(delta -> applyChatDelta(owner, sessionId, assistantId, jobId, promptTokens, state, delta))
        .then(stream.result().doOnNext(result -> applyChatResult(state, result)).then()))
      .then(Mono.defer(() -> markChatJobFinished(owner, sessionId, assistantId, jobId, promptTokens, state, "success", "")))
      .onErrorResume(error -> markChatJobFinished(owner, sessionId, assistantId, jobId, promptTokens, state,
        "error", limitString(cleanAiFoundationError(error), 4000)))
      .doFinally(signal -> {
        runningJobs.remove(key);
        runningJobStates.remove(key);
        if (!shuttingDown.get()) {
          releaseUsageReservation(reservation).subscribe();
        }
      })
      .subscribe();
    runningJobs.put(key, disposable);
    if (disposable.isDisposed()) {
      runningJobs.remove(key, disposable);
      runningJobStates.remove(key);
    }
  }

  private void runImageJobV2(String owner, String sessionId, String assistantId, String jobId, String model,
    Map<String, Object> payload, Integer promptTokens, Map<String, Object> globalSettings,
    UsageReservation reservation) {
    var state = new LinkedHashMap<String, Object>();
    state.put("content", "正在生成图像...");
    state.put("images", new ArrayList<String>());
    state.put("_type", "image");
    state.put("_model", model);
    state.put("_sessionId", sessionId);
    state.put("_assistantId", assistantId);
    state.put("_jobId", jobId);
    state.put("_promptTokens", promptTokens);
    state.put("_reservedTokens", reservation.reservedTokens);
    state.put("_usageDay", reservation.day);
    var key = runningJobKey(owner, jobId);
    runningJobStates.put(key, state);
    var maxImageBytes = maxImageBytes(globalSettings);
    var persistedImages = new CopyOnWriteArrayList<PersistedGeneratedImage>();
    var resultCommitted = new AtomicBoolean(false);
    var rollbackStarted = new AtomicBoolean(false);
    var stream = updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "running", "")
      .then(modelInvoker.generateImage(model, payload))
      .flatMap(result -> {
        return Flux.fromIterable(result.images())
          .concatMap(image -> persistGeneratedImage(image, maxImageBytes)
            .doOnNext(persistedImages::add))
          .collectList()
          .flatMap(images -> {
            mergeImages(state, images.stream().map(PersistedGeneratedImage::permalink).toList(), maxImageBytes);
            state.put("_actualInputTokens", result.inputTokens());
            state.put("_actualOutputTokens", result.outputTokens());
            state.put("_actualTotalTokens", result.totalTokens());
            state.put("content", images.isEmpty() ? "图像模型完成了请求，但没有返回图像。" : "已生成图像：");
            return updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "success", "")
              .doOnSuccess(ignored -> resultCommitted.set(true));
          })
          .onErrorResume(error -> rollbackGeneratedImagesOnce(persistedImages, rollbackStarted)
            .then(Mono.error(error)));
      });
    var disposable = stream
      .onErrorResume(error -> updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state,
        "error", limitString(cleanAiFoundationError(error), 4000)))
      .doFinally(signal -> {
        if (!resultCommitted.get()) {
          rollbackGeneratedImagesOnce(persistedImages, rollbackStarted).subscribe();
        }
        runningJobs.remove(key);
        runningJobStates.remove(key);
        if (!shuttingDown.get()) {
          releaseUsageReservation(reservation).subscribe();
        }
      })
      .subscribe();
    runningJobs.put(key, disposable);
    if (disposable.isDisposed()) {
      runningJobs.remove(key, disposable);
      runningJobStates.remove(key);
    }
  }

  private Mono<Void> applyChatDelta(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state,
    AiFoundationModelInvoker.TextDelta delta) {
    if ("error".equals(delta.type())) {
      return Mono.error(new IllegalStateException(delta.error().isBlank()
        ? "AI Foundation 流式生成失败。"
        : delta.error()));
    }
    var maxOutput = clampInt(state.get("maxOutputCharacters"), 4000,
      MAX_STREAM_TEXT_LENGTH, MAX_STREAM_TEXT_LENGTH);
    if ("reasoning".equals(delta.type())) {
      appendOutputLimited(state, "reasoning", delta.text(), maxOutput,
        "AI 输出超过最大长度限制。");
    } else if ("text".equals(delta.type())) {
      if (!stringValue(state.get("reasoning")).isBlank()) {
        state.put("reasoningOpen", false);
      }
      appendOutputLimited(state, "content", delta.text(), maxOutput,
        "AI 输出超过最大长度限制。");
    } else {
      return Mono.empty();
    }
    emitJobEvent(owner, jobId, transientJobEvent(jobId, promptTokens, state, "running", ""));
    if (!shouldPersistRunningState(state)) {
      return Mono.empty();
    }
    return updateChatJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "running", "");
  }

  private void applyChatResult(Map<String, Object> state,
    AiFoundationModelInvoker.TextResult result) {
    var reasoning = stringValue(result.reasoning());
    var content = stringValue(result.text());
    var maxOutput = clampInt(state.get("maxOutputCharacters"), 4000,
      MAX_STREAM_TEXT_LENGTH, MAX_STREAM_TEXT_LENGTH);
    if (reasoning.length() + content.length() > maxOutput) {
      throw new IllegalStateException("AI 输出超过最大长度限制。");
    }
    if (!reasoning.isBlank()) {
      state.put("reasoning", reasoning);
    }
    if (!content.isBlank()) {
      state.put("content", content);
    }
    state.put("_actualInputTokens", result.inputTokens());
    state.put("_actualOutputTokens", result.outputTokens());
    state.put("_actualTotalTokens", result.totalTokens());
  }

  private String generatedImageReference(AiFoundationModelInvoker.GeneratedImage image) {
    if (image == null) {
      return "";
    }
    if (!stringValue(image.url()).isBlank()) {
      return image.url();
    }
    var base64 = stringValue(image.base64());
    if (base64.isBlank() || base64.startsWith("data:")) {
      return base64;
    }
    var mediaType = stringValue(image.mediaType());
    return "data:" + (mediaType.isBlank() ? "image/png" : mediaType) + ";base64," + base64;
  }

  private Mono<PersistedGeneratedImage> persistGeneratedImage(AiFoundationModelInvoker.GeneratedImage image,
    long maxImageBytes) {
    return Mono.fromCallable(() -> generatedImageBytes(image, maxImageBytes))
      .subscribeOn(Schedulers.boundedElastic())
      .map(bytes -> ImageUploadPolicy.sanitize(bytes, maxImageBytes))
      .flatMap(sanitized -> attachmentService.upload(null, null, "ai-generated.png",
          Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(sanitized.bytes())),
          MediaType.parseMediaType(sanitized.mediaType()))
        .flatMap(attachment -> attachmentService.getPermalink(attachment)
          .map(permalink -> new PersistedGeneratedImage(attachment, permalink.toString()))
          .onErrorResume(error -> attachmentService.delete(attachment).then(Mono.error(error)))))
      .onErrorMap(error -> new IllegalStateException("无法将生成图片保存到 Halo 附件库。", error));
  }

  private Mono<Void> rollbackGeneratedImages(List<PersistedGeneratedImage> images) {
    return Flux.fromIterable(images)
      .concatMap(image -> attachmentService.delete(image.attachment())
        .onErrorResume(error -> {
          log.warn("Unable to roll back generated attachment {}", image.attachment().getMetadata().getName(), error);
          return Mono.empty();
        }))
      .then();
  }

  private Mono<Void> rollbackGeneratedImagesOnce(List<PersistedGeneratedImage> images,
    AtomicBoolean rollbackStarted) {
    if (images.isEmpty() || !rollbackStarted.compareAndSet(false, true)) {
      return Mono.empty();
    }
    return rollbackGeneratedImages(images);
  }

  private byte[] generatedImageBytes(AiFoundationModelInvoker.GeneratedImage image, long maxImageBytes) throws IOException {
    var base64 = stringValue(image == null ? null : image.base64());
    if (!base64.isBlank()) {
      var value = base64.startsWith("data:") ? base64.substring(base64.indexOf(',') + 1) : base64;
      return RemoteImageContentPolicy.decodeBase64(value, Math.min(maxImageBytes, HARD_MAX_IMAGE_BYTES));
    }
    var value = stringValue(image == null ? null : image.url());
    var uri = RemoteImageContentPolicy.requirePublicHttpsUri(value);
    var connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
    connection.setConnectTimeout((int) GENERATED_IMAGE_DOWNLOAD_TIMEOUT.toMillis());
    connection.setReadTimeout((int) GENERATED_IMAGE_DOWNLOAD_TIMEOUT.toMillis());
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty(HttpHeaders.ACCEPT, "image/png,image/jpeg");
    var length = connection.getContentLengthLong();
    if (length > maxImageBytes || length > HARD_MAX_IMAGE_BYTES) {
      throw new IOException("生成图片超过大小限制。");
    }
    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
      throw new IOException("生成图片下载失败，HTTP " + connection.getResponseCode());
    }
    try (InputStream input = connection.getInputStream(); var output = new java.io.ByteArrayOutputStream()) {
      input.transferTo(new java.io.FilterOutputStream(output) {
        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
          if (output.size() + length > maxImageBytes || output.size() + length > HARD_MAX_IMAGE_BYTES) {
            throw new IOException("生成图片超过大小限制。");
          }
          super.write(buffer, offset, length);
        }
      });
      return output.toByteArray();
    } finally {
      connection.disconnect();
    }
  }

  private Mono<Void> markChatJobFinished(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state, String status, String error) {
    if ("success".equals(status)
      && stringValue(state.get("content")).isBlank()
      && !stringValue(state.get("reasoning")).isBlank()) {
      state.put("content", "模型仅返回了思考过程，未返回最终文本。");
    }
    if ("success".equals(status) && stringValue(state.get("content")).isBlank()) {
      state.put("content", "模型没有返回文本内容。");
    }
    state.put("reasoningOpen", false);
    return updateChatJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, status, error);
  }

  private Mono<Void> updateJobAndSession(String owner, String jobId, Map<String, Object> state, String status,
    String error) {
    var type = stringValue(state.get("_type"));
    var sessionId = stringValue(state.get("_sessionId"));
    var assistantId = stringValue(state.get("_assistantId"));
    var promptTokens = intValue(state.get("_promptTokens"));
    if ("image".equals(type)) {
      return updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, status, error);
    }
    return updateChatJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, status, error);
  }

  private Mono<Void> updateChatJobAndSession(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state, String status, String error) {
    return fetchSessionSnapshot(owner, sessionId).flatMap(session -> fetchJob(owner, jobId).flatMap(job -> {
      var now = System.currentTimeMillis();
      var effectivePromptTokens = tokenValue(state.get("_actualInputTokens"), promptTokens);
      var completionTokens = tokenValue(state.get("_actualOutputTokens"),
        estimateTokens(stringValue(state.get("reasoning")) + "\n" + stringValue(state.get("content"))));
      var totalTokens = tokenValue(state.get("_actualTotalTokens"),
        effectivePromptTokens + completionTokens);
      job.put("id", jobId);
      job.put("sessionId", sessionId);
      job.put("assistantId", assistantId);
      job.put("status", status);
      job.put("error", error);
      job.put("updatedAt", now);
      job.put("instanceId", instanceId);
      job.put("heartbeatAt", now);
      job.put("content", state.get("content"));
      job.put("reasoning", state.get("reasoning"));
      job.put("reasoningOpen", state.get("reasoningOpen"));
      job.put("promptTokens", effectivePromptTokens);
      job.put("completionTokens", completionTokens);
      job.put("totalTokens", totalTokens);
      Mono<Void> saveLog = Mono.empty();
      if (!"running".equals(status) && !Boolean.TRUE.equals(job.get("logged"))) {
        job.put("logged", true);
        var log = AuditRecordFactory.create(owner, sessionId, stringValue(session.get("title")),
          "chat", "chat", job.get("model"), status, error,
          nullToZero(longValue(job.get("createdAt"))), now, effectivePromptTokens,
          completionTokens, totalTokens, job);
        saveLog = saveLog(owner, log).then(incrementDailyUsage(owner, dayKey(now), totalTokens));
      }
      var savedJob = new LinkedHashMap<String, Object>(job);
      var logWrite = saveLog;
      return updateSessionStore(owner, sessionId, data -> mergeChatJobResult(data, assistantId, state, status,
          now, effectivePromptTokens, completionTokens, totalTokens))
        .map(updated -> sessionVersion(readMapValue(updated.getData().get("session"))))
        .flatMap(version -> {
          savedJob.put("sessionVersion", version);
          return saveJob(owner, jobId, savedJob).then(logWrite)
            .then(Mono.fromRunnable(() -> emitJobEvent(owner, jobId, savedJob)));
        });
    }));
  }

  private Mono<Void> updateImageJobAndSession(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state, String status, String error) {
    return fetchSessionSnapshot(owner, sessionId).flatMap(session -> fetchJob(owner, jobId).flatMap(job -> {
      var now = System.currentTimeMillis();
      var images = listOfStrings(state.get("images")).stream().limit(MAX_IMAGES_PER_MESSAGE).collect(Collectors.toList());
      var effectivePromptTokens = tokenValue(state.get("_actualInputTokens"), promptTokens);
      var completionTokens = tokenValue(state.get("_actualOutputTokens"), 0);
      var totalTokens = tokenValue(state.get("_actualTotalTokens"),
        effectivePromptTokens + completionTokens);
      job.put("id", jobId);
      job.put("sessionId", sessionId);
      job.put("assistantId", assistantId);
      job.put("status", status);
      job.put("error", error);
      job.put("updatedAt", now);
      job.put("instanceId", instanceId);
      job.put("heartbeatAt", now);
      job.put("content", limitString(stringValue(state.get("content")), MAX_CONTENT_LENGTH));
      job.put("images", images);
      job.put("promptTokens", effectivePromptTokens);
      job.put("completionTokens", completionTokens);
      job.put("totalTokens", totalTokens);
      Mono<Void> saveLog = Mono.empty();
      if (!"running".equals(status) && !Boolean.TRUE.equals(job.get("logged"))) {
        job.put("logged", true);
        var log = AuditRecordFactory.create(owner, sessionId, stringValue(session.get("title")),
          "image", "image", job.get("model"), status, error,
          nullToZero(longValue(job.get("createdAt"))), now, effectivePromptTokens,
          completionTokens, totalTokens, job);
        saveLog = saveLog(owner, log)
          .then(incrementDailyUsage(owner, dayKey(now), totalTokens));
      }
      var savedJob = new LinkedHashMap<String, Object>(job);
      var logWrite = saveLog;
      return updateSessionStore(owner, sessionId, data -> mergeImageJobResult(data, assistantId, state,
          images, status, now, effectivePromptTokens, completionTokens, totalTokens))
        .map(updated -> sessionVersion(readMapValue(updated.getData().get("session"))))
        .flatMap(version -> {
          savedJob.put("sessionVersion", version);
          return saveJob(owner, jobId, savedJob).then(logWrite)
            .then(Mono.fromRunnable(() -> emitJobEvent(owner, jobId, savedJob)));
        });
    }));
  }

  private Mono<AiChatSession> upsertSession(AiChatSession session) {
    return client.fetch(AiChatSession.class, idOf(session))
      .flatMap(existing -> {
        existing.setSpec(session.getSpec());
        existing.setStatus(session.getStatus());
        return client.update(existing);
      })
      .switchIfEmpty(client.create(session));
  }

  private Mono<AiChatMessage> upsertMessage(AiChatMessage message) {
    return client.fetch(AiChatMessage.class, idOf(message))
      .flatMap(existing -> {
        existing.setSpec(message.getSpec());
        return client.update(existing);
      })
      .switchIfEmpty(client.create(message));
  }

  private AiChatSession sessionFromMap(String name, String owner, Map<String, Object> body) {
    var session = new AiChatSession();
    session.setMetadata(metadata(name));
    var spec = new AiChatSession.SessionSpec();
    spec.setOwner(owner);
    spec.setTitle(limitString(stringValue(body.get("title")), MAX_TITLE_LENGTH));
    spec.setMemory(limitString(stringValue(body.get("memory")), MAX_MEMORY_LENGTH));
    spec.setTags(cleanTags(listOfStrings(body.get("tags"))));
    spec.setContextClearedAt(longValue(body.get("contextClearedAt")));
    // Cache retention must use a server-authoritative creation time.
    spec.setCreatedAt(System.currentTimeMillis());
    spec.setUpdatedAt(System.currentTimeMillis());
    session.setSpec(spec);
    return session;
  }

  private AiChatMessage messageFromMap(String sessionId, String owner, Map<String, Object> body, long maxImageBytes) {
    var id = stringValue(body.get("id"));
    if (id.isBlank()) {
      throw badRequest("缺少消息标识。");
    }
    var role = stringValue(body.get("role"));
    if (!ROLES.contains(role)) {
      throw badRequest("不支持该消息角色。");
    }
    var message = new AiChatMessage();
    message.setMetadata(metadata(messageName(sessionId, id)));
    var spec = new AiChatMessage.MessageSpec();
    spec.setId(id);
    spec.setOwner(owner);
    spec.setSessionId(sessionId);
    spec.setRole(role);
    var generation = castMapValue(body.get("generation"));
    spec.setGenerationType(limitString(stringValue(generation.get("type")), 32));
    spec.setGenerationModel(limitString(stringValue(generation.get("model")), 253));
    spec.setFavorite(booleanValue(body.get("favorite")));
    spec.setContent(limitString(stringValue(body.get("content")), MAX_CONTENT_LENGTH));
    spec.setReasoning(limitString(stringValue(body.get("reasoning")), MAX_REASONING_LENGTH));
    spec.setReasoningOpen(booleanValue(body.get("reasoningOpen")));
    spec.setCreatedAt(longOrNow(body.get("createdAt")));
    spec.setUpdatedAt(longValue(body.get("updatedAt")));
    spec.setPromptTokens(intValue(body.get("promptTokens")));
    spec.setCompletionTokens(intValue(body.get("completionTokens")));
    spec.setTotalTokens(intValue(body.get("totalTokens")));
    spec.setFiles(validateAttachments(objectMapper.convertValue(body.getOrDefault("files", List.of()),
      new TypeReference<List<AiChatMessage.Attachment>>() {}), maxImageBytes));
    spec.setImages(validateImages(objectMapper.convertValue(body.getOrDefault("images", List.of()),
      new TypeReference<List<String>>() {}), maxImageBytes));
    message.setSpec(spec);
    return message;
  }

  private AiChatCallLog callLogFromMap(String owner, Map<String, Object> body) {
    var log = new AiChatCallLog();
    log.setMetadata(metadata(safeName("log", String.valueOf(System.currentTimeMillis()) + "-" + Math.random())));
    var spec = new AiChatCallLog.CallLogSpec();
    spec.setOwner(owner);
    spec.setSessionId(limitString(stringValue(body.get("sessionId")), 120));
    spec.setSessionTitle(limitString(stringValue(body.get("sessionTitle")), MAX_TITLE_LENGTH));
    spec.setType(limitString(stringValue(body.get("type")), 32));
    spec.setOperation(limitString(emptyToDefault(stringValue(body.get("operation")), stringValue(body.get("type"))), 40));
    spec.setModel(limitString(stringValue(body.get("model")), 160));
    spec.setStatus(limitString(stringValue(body.get("status")), 32));
    spec.setError(limitString(stringValue(body.get("error")), 4000));
    spec.setIpAddress(limitString(stringValue(body.get("ipAddress")), 128));
    spec.setUserAgent(limitString(stringValue(body.get("userAgent")), 500));
    spec.setBrowser(limitString(stringValue(body.get("browser")), 80));
    spec.setOperatingSystem(limitString(stringValue(body.get("operatingSystem")), 80));
    spec.setTime(longOrNow(body.get("time")));
    spec.setDurationMs(longValue(body.get("durationMs")));
    spec.setPromptTokens(intValue(body.get("promptTokens")));
    spec.setCompletionTokens(intValue(body.get("completionTokens")));
    spec.setTotalTokens(intValue(body.get("totalTokens")));
    log.setSpec(spec);
    return log;
  }

  private AiChatImageCache imageCacheFromMap(String owner, Map<String, Object> body, long maxImageBytes) {
    var sourceUrl = stringValue(body.get("sourceUrl"));
    var messageId = stringValue(body.get("messageId"));
    var cache = new AiChatImageCache();
    cache.setMetadata(metadata(safeName("img", messageId + "-" + sourceUrl)));
    var spec = new AiChatImageCache.ImageCacheSpec();
    spec.setOwner(owner);
    spec.setSessionId(limitString(stringValue(body.get("sessionId")), 120));
    spec.setMessageId(limitString(messageId, 120));
    spec.setSourceUrl(limitString(sourceUrl, 2048));
    var dataUrl = stringValue(body.get("dataUrl"));
    validateDataUrlSize(dataUrl, maxImageBytes);
    spec.setDataUrl(limitString(dataUrl, MAX_DATA_URL_LENGTH));
    spec.setMediaType(validateMediaType(stringValue(body.get("mediaType"))));
    spec.setCreatedAt(System.currentTimeMillis());
    cache.setSpec(spec);
    return cache;
  }

  private Map<String, Object> sessionToMap(AiChatSession session, List<Map<String, Object>> messages) {
    var spec = session.getSpec();
    var item = new LinkedHashMap<String, Object>();
    item.put("id", idOf(session));
    item.put("title", spec.getTitle());
    item.put("memory", spec.getMemory());
    item.put("tags", cleanTags(spec.getTags()));
    item.put("contextClearedAt", spec.getContextClearedAt());
    item.put("createdAt", spec.getCreatedAt());
    item.put("updatedAt", spec.getUpdatedAt());
    item.put("messages", messages);
    return item;
  }

  private Map<String, Object> messageToMap(AiChatMessage message) {
    var spec = message.getSpec();
    var item = new LinkedHashMap<String, Object>();
    item.put("id", emptyToDefault(spec.getId(), idOf(message)));
    item.put("role", spec.getRole());
    if (!stringValue(spec.getGenerationType()).isBlank() && !stringValue(spec.getGenerationModel()).isBlank()) {
      item.put("generation", Map.of("type", spec.getGenerationType(), "model", spec.getGenerationModel()));
    }
    item.put("favorite", Boolean.TRUE.equals(spec.getFavorite()));
    item.put("content", spec.getContent());
    item.put("reasoning", spec.getReasoning());
    item.put("reasoningOpen", spec.getReasoningOpen());
    item.put("createdAt", spec.getCreatedAt());
    item.put("updatedAt", spec.getUpdatedAt());
    item.put("promptTokens", spec.getPromptTokens());
    item.put("completionTokens", spec.getCompletionTokens());
    item.put("totalTokens", spec.getTotalTokens());
    item.put("files", spec.getFiles());
    item.put("images", spec.getImages());
    return item;
  }

  private Map<String, Object> callLogToMap(AiChatCallLog log) {
    return objectMapper.convertValue(log.getSpec(), new TypeReference<Map<String, Object>>() {});
  }

  private Map<String, Object> imageCacheToMap(AiChatImageCache cache) {
    return objectMapper.convertValue(cache.getSpec(), new TypeReference<Map<String, Object>>() {});
  }

  private Mono<ConfigMap> fetchStore(String owner) {
    return fetchCompatibleConfigMap(storeName(owner), legacyStoreName(owner))
      .switchIfEmpty(Mono.defer(() -> {
        var configMap = new ConfigMap();
        configMap.setMetadata(metadata(storeName(owner)));
        configMap.setData(new LinkedHashMap<>());
        return client.create(configMap);
      }))
      .map(configMap -> {
        if (configMap.getData() == null) {
          configMap.setData(new LinkedHashMap<>());
        }
        return configMap;
      });
  }

  private Mono<ConfigMap> fetchSessionStore(String owner, String sessionId) {
    return fetchCompatibleConfigMap(sessionStoreName(owner, sessionId), legacySessionStoreName(owner, sessionId))
      .switchIfEmpty(Mono.defer(() -> {
        var configMap = new ConfigMap();
        configMap.setMetadata(metadata(sessionStoreName(owner, sessionId)));
        configMap.setData(new LinkedHashMap<>());
        return client.create(configMap);
      }))
      .map(configMap -> {
        if (configMap.getData() == null) {
          configMap.setData(new LinkedHashMap<>());
        }
        return configMap;
      });
  }

  private Mono<Map<String, Object>> fetchSessionSnapshot(String owner, String sessionId) {
    return fetchCompatibleConfigMap(sessionStoreName(owner, sessionId), legacySessionStoreName(owner, sessionId))
      .map(configMap -> {
        var data = configMap.getData();
        if (isSessionTombstoned(data)) {
          var deleted = new LinkedHashMap<String, Object>();
          deleted.put("id", sessionId);
          deleted.put("_deleted", true);
          deleted.put("_version", nullToZero(longValue(data.get("tombstoneVersion"))));
          return deleted;
        }
        return readMapValue(data == null ? null : data.get("session"));
      })
      .filter(session -> !stringValue(session.get("id")).isBlank())
      .switchIfEmpty(fetchStore(owner)
        .map(store -> readMapValue(store.getData().get(sessionKey(sessionId))))
        .filter(session -> !stringValue(session.get("id")).isBlank()))
      .switchIfEmpty(Mono.defer(() -> {
        var session = new LinkedHashMap<String, Object>();
        session.put("id", sessionId);
        session.put("_version", 0L);
        session.put("title", "Halo AI");
        session.put("memory", "");
        session.put("createdAt", System.currentTimeMillis());
        session.put("updatedAt", System.currentTimeMillis());
        session.put("messages", new ArrayList<Map<String, Object>>());
        return Mono.just(session);
      }));
  }

  private Mono<ConfigMap> updateStore(String owner, Consumer<Map<String, String>> mutator) {
    return fetchStore(owner).flatMap(configMap -> {
      var data = new LinkedHashMap<>(configMap.getData());
      mutator.accept(data);
      configMap.setData(data);
      return client.update(configMap);
    }).retryWhen(Retry.backoff(4, Duration.ofMillis(60)).filter(this::isOptimisticLockConflict));
  }

  private Mono<ConfigMap> updateSessionStore(String owner, String sessionId, Consumer<Map<String, String>> mutator) {
    return fetchSessionStore(owner, sessionId).flatMap(configMap -> {
      var data = new LinkedHashMap<>(configMap.getData());
      mutator.accept(data);
      configMap.setData(data);
      return client.update(configMap);
    }).retryWhen(Retry.backoff(4, Duration.ofMillis(60)).filter(this::isOptimisticLockConflict));
  }

  private Mono<Map<String, Object>> saveSessionForJob(String owner, String sessionId, Map<String, Object> session) {
    return updateSessionStore(owner, sessionId, data -> {
      if (isSessionTombstoned(data)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
          "会话已删除，不能继续创建任务或写入回复。");
      }
      var existing = readMapValue(data.get("session"));
      var merged = mergeSessionForJob(existing, session);
      merged.put("_version", sessionVersion(existing) + 1);
      enforceSessionSize(merged);
      data.put("session", writeMapValue(merged));
    }).map(updated -> readMapValue(updated.getData().get("session")));
  }

  private boolean isSessionTombstoned(Map<String, String> data) {
    return data != null && !data.containsKey("session")
      && nullToZero(longValue(data.get("tombstoneVersion"))) > 0;
  }

  private Map<String, Object> mergeSessionForJob(Map<String, Object> existing,
    Map<String, Object> incoming) {
    if (stringValue(existing.get("id")).isBlank()) {
      return new LinkedHashMap<>(incoming);
    }
    var merged = new LinkedHashMap<String, Object>(existing);
    var messages = new ArrayList<Map<String, Object>>(listOfMaps(existing.get("messages")));
    var knownMessageIds = messages.stream()
      .map(message -> stringValue(message.get("id")))
      .filter(id -> !id.isBlank())
      .collect(Collectors.toSet());
    for (var message : listOfMaps(incoming.get("messages"))) {
      var messageId = stringValue(message.get("id"));
      if (!messageId.isBlank() && knownMessageIds.add(messageId)) {
        messages.add(new LinkedHashMap<>(message));
      }
    }
    merged.put("messages", messages);
    if (messages.size() > listOfMaps(existing.get("messages")).size()) {
      merged.put("updatedAt", Math.max(
        nullToZero(longValue(existing.get("updatedAt"))),
        nullToZero(longValue(incoming.get("updatedAt")))));
    }
    return merged;
  }

  private void mergeChatJobResult(Map<String, String> data, String assistantId,
    Map<String, Object> state, String status, long now, Integer promptTokens, Integer completionTokens,
    Integer totalTokens) {
    if (isSessionTombstoned(data)) {
      return;
    }
    var session = readMapValue(data.get("session"));
    if (stringValue(session.get("id")).isBlank()) {
      return;
    }
    var messages = listOfMaps(session.get("messages"));
    Map<String, Object> assistant = null;
    for (var message : messages) {
      if (assistantId.equals(stringValue(message.get("id")))) {
        assistant = message;
        break;
      }
    }
    if (assistant == null) {
      assistant = new LinkedHashMap<>();
      assistant.put("id", assistantId);
      assistant.put("role", "assistant");
      assistant.put("createdAt", now);
      messages.add(assistant);
    }
    assistant.put("content", limitString(stringValue(state.get("content")), MAX_CONTENT_LENGTH));
    assistant.put("reasoning", limitString(stringValue(state.get("reasoning")), MAX_REASONING_LENGTH));
    assistant.put("reasoningOpen", state.get("reasoningOpen"));
    assistant.put("streaming", "running".equals(status));
    assistant.put("updatedAt", now);
    assistant.put("promptTokens", promptTokens);
    assistant.put("completionTokens", completionTokens);
    assistant.put("totalTokens", totalTokens);
    if (!stringValue(state.get("_type")).isBlank() && !stringValue(state.get("_model")).isBlank()) {
      assistant.put("generation", Map.of("type", stringValue(state.get("_type")), "model", stringValue(state.get("_model"))));
    }
    session.put("messages", messages);
    session.put("_version", sessionVersion(session) + 1);
    enforceSessionSize(session);
    data.put("session", writeMapValue(session));
  }

  private void mergeImageJobResult(Map<String, String> data, String assistantId,
    Map<String, Object> state, List<String> images, String status, long now,
    Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    if (isSessionTombstoned(data)) {
      return;
    }
    var session = readMapValue(data.get("session"));
    if (stringValue(session.get("id")).isBlank()) {
      return;
    }
    var messages = listOfMaps(session.get("messages"));
    Map<String, Object> assistant = null;
    for (var message : messages) {
      if (assistantId.equals(stringValue(message.get("id")))) {
        assistant = message;
        break;
      }
    }
    if (assistant == null) {
      assistant = new LinkedHashMap<>();
      assistant.put("id", assistantId);
      assistant.put("role", "assistant");
      assistant.put("createdAt", now);
      messages.add(assistant);
    }
    assistant.put("content", limitString(stringValue(state.get("content")), MAX_CONTENT_LENGTH));
    assistant.put("images", images);
    assistant.put("streaming", "running".equals(status));
    assistant.put("updatedAt", now);
    assistant.put("promptTokens", promptTokens);
    assistant.put("completionTokens", completionTokens);
    assistant.put("totalTokens", totalTokens);
    if (!stringValue(state.get("_type")).isBlank() && !stringValue(state.get("_model")).isBlank()) {
      assistant.put("generation", Map.of("type", stringValue(state.get("_type")), "model", stringValue(state.get("_model"))));
    }
    session.put("messages", messages);
    session.put("_version", sessionVersion(session) + 1);
    enforceSessionSize(session);
    data.put("session", writeMapValue(session));
  }

  private Mono<Void> saveJob(String owner, String jobId, Map<String, Object> job) {
    var name = jobStoreName(owner, jobId);
    return fetchCompatibleConfigMap(name, legacyJobStoreName(owner, jobId))
      .switchIfEmpty(Mono.defer(() -> {
        var configMap = new ConfigMap();
        configMap.setMetadata(metadata(name));
        configMap.setData(new LinkedHashMap<>());
        return client.create(configMap);
      }))
      .flatMap(configMap -> {
        var data = configMap.getData() == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(configMap.getData());
        var serialized = writeMapValue(job);
        if (serialized.length() > MAX_SESSION_JSON_LENGTH) {
        throw new IllegalStateException("Job 快照过大，无法安全保存。");
        }
        data.put("job", serialized);
        configMap.setData(data);
        return client.update(configMap);
      })
      .retryWhen(Retry.backoff(4, Duration.ofMillis(60)).filter(this::isOptimisticLockConflict))
      .then();
  }

  private Mono<Void> updateJob(String owner, String jobId, Consumer<Map<String, Object>> mutator) {
    return fetchJob(owner, jobId)
      .flatMap(job -> {
        mutator.accept(job);
        return saveJob(owner, jobId, job);
      });
  }

  private Mono<Map<String, Object>> fetchJob(String owner, String jobId) {
    return fetchCompatibleConfigMap(jobStoreName(owner, jobId), legacyJobStoreName(owner, jobId))
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("job")))
      .filter(job -> !stringValue(job.get("id")).isBlank()
        && OwnerAccessPolicy.owns(owner, job.get("owner")))
      .switchIfEmpty(fetchStore(owner)
        .map(store -> readMapValue(store.getData().get(jobKey(jobId))))
        .filter(job -> !stringValue(job.get("id")).isBlank()
          && OwnerAccessPolicy.owns(owner, job.get("owner"))));
  }

  private Mono<Void> saveLog(String owner, Map<String, Object> log) {
    var time = nullToZero(longValue(log.get("time")));
    var name = LOG_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("log", time + "-" + UUID.randomUUID());
    var configMap = new ConfigMap();
    configMap.setMetadata(metadata(name));
    configMap.setData(new LinkedHashMap<>(Map.of("log", writeMapValue(log))));
    return client.create(configMap).then();
  }

  private Mono<Void> saveAuthoritativeLog(String owner, String sessionId, String sessionTitle, String type,
    String operation, String model, String status, String error, long startedAt, int estimatedPromptTokens,
    Integer actualInputTokens, Integer actualOutputTokens, Integer actualTotalTokens, ServerRequest request) {
    var finishedAt = System.currentTimeMillis();
    var promptTokens = tokenValue(actualInputTokens, estimatedPromptTokens);
    var completionTokens = tokenValue(actualOutputTokens, 0);
    var totalTokens = tokenValue(actualTotalTokens, promptTokens + completionTokens);
    var log = AuditRecordFactory.create(owner, sessionId, sessionTitle, type, operation, model,
      status, error, startedAt, finishedAt, promptTokens, completionTokens, totalTokens,
      requestAuditMeta(request));
    return saveLog(owner, log).then(incrementDailyUsage(owner, dayKey(finishedAt), totalTokens));
  }

  private Mono<Void> cleanupExpiredRecords() {
    return globalSettings().flatMap(settings -> {
      var now = System.currentTimeMillis();
      var jobRetentionDays = clampInt(settings.get("jobRetentionDays"), 1, 365, 7);
      var logRetentionDays = clampInt(settings.get("logRetentionDays"), 7, 3650, 90);
      var imageCacheRetentionDays = clampInt(settings.get("imageCacheRetentionDays"), 1, 3650, 30);
      var maxJobsPerUser = clampInt(settings.get("maxJobsPerUser"), 50, 10000, 500);
      var jobCutoff = now - Duration.ofDays(jobRetentionDays).toMillis();
      var logCutoff = now - Duration.ofDays(logRetentionDays).toMillis();
      var imageCacheCutoff = now - Duration.ofDays(imageCacheRetentionDays).toMillis();
      return deleteExpiredJobs(jobCutoff)
        .then(deleteExcessJobs(maxJobsPerUser))
        .then(deleteExpiredLogs(logCutoff))
        .then(deleteExpiredImageCaches(imageCacheCutoff))
        .then(deleteExpiredSessionTombstones(now - SESSION_TOMBSTONE_RETENTION_MS))
        .then(deleteExpiredUsage(logCutoff));
    });
  }

  private Mono<Void> deleteExpiredJobs(long cutoff) {
    return jobRecords()
      .filter(record -> isTerminalJobStatus(record.job.get("status")))
      .filter(record -> jobUpdatedAt(record.job) < cutoff)
      .flatMap(record -> client.delete(record.configMap).then())
      .then();
  }

  private Mono<Void> deleteExcessJobs(int maxJobsPerUser) {
    return jobRecords()
      .filter(record -> isTerminalJobStatus(record.job.get("status")))
      .collectList()
      .flatMapMany(records -> {
        var grouped = records.stream().collect(Collectors.groupingBy(record -> stringValue(record.job.get("owner"))));
        var toDelete = new ArrayList<JobRecord>();
        grouped.values().forEach(items -> {
          items.sort((left, right) -> Long.compare(jobUpdatedAt(right.job), jobUpdatedAt(left.job)));
          if (items.size() > maxJobsPerUser) {
            toDelete.addAll(items.subList(maxJobsPerUser, items.size()));
          }
        });
        return Flux.fromIterable(toDelete);
      })
      .flatMap(record -> client.delete(record.configMap).then())
      .then();
  }

  private Mono<Void> deleteExpiredLogs(long cutoff) {
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(LOG_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .filter(configMap -> {
        var log = readMapValue(configMap.getData() == null ? null : configMap.getData().get("log"));
        return nullToZero(longValue(log.get("time"))) < cutoff;
      })
      .flatMap(configMap -> client.delete(configMap).then())
      .then();
  }

  private Mono<Void> deleteExpiredUsage(long cutoff) {
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(USAGE_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .filter(configMap -> {
        var data = configMap.getData();
        if (data == null) {
          return true;
        }
        var updatedAt = nullToZero(longValue(data.get("updatedAt")));
        return updatedAt > 0 && updatedAt < cutoff;
      })
      .flatMap(configMap -> client.delete(configMap).then())
      .then();
  }

  private Mono<Void> deleteExpiredImageCaches(long cutoff) {
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(STORE_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .flatMap(configMap -> {
        var data = configMap.getData() == null
          ? new LinkedHashMap<String, String>()
          : new LinkedHashMap<>(configMap.getData());
        var changed = data.entrySet().removeIf(entry -> {
          if (!entry.getKey().startsWith(IMAGE_KEY_PREFIX)) {
            return false;
          }
          var image = readMapValue(entry.getValue());
          var createdAt = longValue(image.get("createdAt"));
          return createdAt != null && createdAt < cutoff;
        });
        if (!changed) {
          return Mono.<Void>empty();
        }
        configMap.setData(data);
        return client.update(configMap)
          .retryWhen(Retry.backoff(4, Duration.ofMillis(60)).filter(this::isOptimisticLockConflict))
          .then();
      })
      .then();
  }

  private Mono<Void> deleteExpiredSessionTombstones(long cutoff) {
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(SESSION_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .filter(configMap -> {
        var data = configMap.getData();
        if (data == null || data.containsKey("session")) {
          return false;
        }
        var deletedAt = longValue(data.get("deletedAt"));
        return deletedAt != null && deletedAt < cutoff;
      })
      .flatMap(configMap -> client.delete(configMap).then())
      .then();
  }

  private Flux<JobRecord> jobRecords() {
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(JOB_CONFIG_MAP_PREFIX),
        Comparator.comparing(this::idOf)
      )
      .map(configMap -> new JobRecord(configMap,
        readMapValue(configMap.getData() == null ? null : configMap.getData().get("job"))))
      .filter(record -> !stringValue(record.job.get("id")).isBlank());
  }

  private boolean isTerminalJobStatus(Object status) {
    return JobLifecyclePolicy.isTerminal(status);
  }

  private long jobUpdatedAt(Map<String, Object> job) {
    var updatedAt = nullToZero(longValue(job.get("updatedAt")));
    if (updatedAt > 0) {
      return updatedAt;
    }
    return nullToZero(longValue(job.get("createdAt")));
  }

  private boolean isOptimisticLockConflict(Throwable error) {
    var message = error == null ? "" : String.valueOf(error.getMessage());
    return message.contains("versioned entity")
      || message.contains("updated or deleted concurrently")
      || message.contains("OptimisticLock")
      || message.contains("AlreadyExists")
      || message.contains("already exists")
      || message.contains("version");
  }

  private Mono<Void> deleteSessionStore(String owner, String sessionId) {
    return updateSessionStore(owner, sessionId, data -> {
      var storedSession = readMapValue(data.get("session"));
      var nextVersion = Math.max(sessionVersion(storedSession),
        nullToZero(longValue(data.get("tombstoneVersion")))) + 1;
      data.remove("session");
      data.put("tombstoneVersion", String.valueOf(nextVersion));
      data.put("deletedAt", String.valueOf(System.currentTimeMillis()));
    }).then();
  }

  private long sessionVersion(Map<String, Object> session) {
    return Math.max(0L, nullToZero(longValue(session.get("_version"))));
  }

  private Mono<List<Map<String, Object>>> sessionsForExport(String owner) {
    return Mono.zip(
        fetchStore(owner).map(store -> store.getData().entrySet().stream()
          .filter(entry -> entry.getKey().startsWith(SESSION_KEY_PREFIX))
          .map(entry -> readMapValue(entry.getValue()))
          .collect(Collectors.toList())),
        sessionStoreSessions(owner).collectList()
      )
      .map(tuple -> {
        var sessions = new LinkedHashMap<String, Map<String, Object>>();
        tuple.getT1().forEach(session -> sessions.put(stringValue(session.get("id")), session));
        tuple.getT2().forEach(session -> sessions.put(stringValue(session.get("id")), session));
        return sessions.values().stream()
          .sorted((left, right) -> Long.compare(
            nullToZero(longValue(right.get("updatedAt"))),
            nullToZero(longValue(left.get("updatedAt")))))
          .collect(Collectors.toList());
      });
  }

  private Flux<JobRecord> jobRecordsForOwner(String owner) {
    return jobRecords().filter(record -> OwnerAccessPolicy.owns(owner, record.job.get("owner")));
  }

  private List<Map<String, Object>> attachmentReferences(List<Map<String, Object>> sessions) {
    var references = new ArrayList<Map<String, Object>>();
    for (var session : sessions) {
      var sessionId = stringValue(session.get("id"));
      for (var message : listOfMaps(session.get("messages"))) {
        for (var file : listOfMaps(message.get("files"))) {
          var reference = new LinkedHashMap<String, Object>();
          reference.put("sessionId", sessionId);
          reference.put("messageId", stringValue(message.get("id")));
          file.forEach((key, value) -> {
            if ("data".equals(key) || "dataUrl".equals(key)) {
              reference.put(key + "Length", stringValue(value).length());
            } else {
              reference.put(key, value);
            }
          });
          references.add(reference);
        }
        for (var image : listOfStrings(message.get("images"))) {
          var reference = new LinkedHashMap<String, Object>();
          reference.put("sessionId", sessionId);
          reference.put("messageId", stringValue(message.get("id")));
          if (image.startsWith("data:")) {
            reference.put("dataUrlLength", image.length());
          } else {
            reference.put("url", image);
          }
          references.add(reference);
        }
      }
    }
    return references;
  }

  private Mono<Void> cancelOwnerJobs(String owner) {
    var ownerPrefix = safeName("owner", owner) + "/";
    runningJobs.entrySet().removeIf(entry -> {
      if (!entry.getKey().startsWith(ownerPrefix)) {
        return false;
      }
      var disposable = entry.getValue();
      if (disposable != null && !disposable.isDisposed()) {
        disposable.dispose();
      }
      return true;
    });
    runningJobStates.keySet().removeIf(key -> key.startsWith(ownerPrefix));
    jobEvents.completeMatching(key -> key.startsWith(ownerPrefix));
    return Mono.empty();
  }

  private Mono<Integer> deleteOwnedConfigMaps(String... prefixes) {
    return Flux.fromArray(prefixes)
      .distinct()
      .flatMap(prefix -> client.list(
          ConfigMap.class,
          item -> item.getMetadata() != null && idOf(item).startsWith(prefix),
          Comparator.comparing(this::idOf)
        ))
      .flatMap(configMap -> client.delete(configMap).thenReturn(1))
      .reduce(0, Integer::sum);
  }

  private Mono<Map<String, Object>> deletionStep(String resource, Mono<Integer> deletion) {
    return deletion
      .<Map<String, Object>>map(count -> {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resource", resource);
        result.put("success", true);
        result.put("deleted", count);
        return result;
      })
      .onErrorResume(error -> {
        var result = new LinkedHashMap<String, Object>();
        result.put("resource", resource);
        result.put("success", false);
        result.put("deleted", 0);
        result.put("errorType", error.getClass().getSimpleName());
        result.put("error", limitString(error.getMessage(), 500));
        return Mono.just(result);
      });
  }

  private int deletedCount(List<Map<String, Object>> steps, String resource) {
    return steps.stream()
      .filter(step -> resource.equals(step.get("resource")))
      .mapToInt(step -> intValue(step.get("deleted")) == null ? 0 : intValue(step.get("deleted")))
      .sum();
  }

  private Mono<Integer> deleteOwnedLogMaps(String owner, boolean deleteAuditLogs) {
    if (!deleteAuditLogs) {
      return Mono.just(0);
    }
    return deleteOwnedConfigMaps(ownerConfigMapPrefixes(LOG_CONFIG_MAP_PREFIX, owner));
  }

  private Mono<Integer> clearOwnStore(String owner, boolean deleteAuditLogs) {
    return Flux.just(storeName(owner), legacyStoreName(owner))
      .distinct()
      .concatMap(name -> clearOwnStoreConfigMap(name, deleteAuditLogs))
      .reduce(0, Integer::sum);
  }

  private Mono<Integer> clearOwnStoreConfigMap(String name, boolean deleteAuditLogs) {
    return client.fetch(ConfigMap.class, name)
      .flatMap(configMap -> {
        var data = configMap.getData() == null
          ? new LinkedHashMap<String, String>()
          : new LinkedHashMap<>(configMap.getData());
        var legacyLogCount = (int) data.keySet().stream()
          .filter(key -> key.startsWith(LOG_KEY_PREFIX))
          .count();
        if (deleteAuditLogs) {
          return client.delete(configMap).thenReturn(legacyLogCount);
        }
        data.entrySet().removeIf(entry -> !entry.getKey().startsWith(LOG_KEY_PREFIX));
        configMap.setData(data);
        return client.update(configMap).thenReturn(0);
      })
      .defaultIfEmpty(0)
      .retryWhen(Retry.backoff(4, Duration.ofMillis(60)).filter(this::isOptimisticLockConflict));
  }

  private Flux<Map<String, Object>> sessionStoreSessions(String owner) {
    var prefixes = ownerConfigMapPrefixes(SESSION_CONFIG_MAP_PREFIX, owner);
    return listCompatibleConfigMaps(prefixes)
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("session")))
      .filter(session -> !stringValue(session.get("id")).isBlank());
  }

  private String storeName(String owner) {
    return STORE_CONFIG_MAP_PREFIX + safeName("owner", owner);
  }

  private String legacyStoreName(String owner) {
    return STORE_CONFIG_MAP_PREFIX + legacySafeName("owner", owner);
  }

  private String sessionStoreName(String owner, String sessionId) {
    return SESSION_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("chat", sessionId);
  }

  private String legacySessionStoreName(String owner, String sessionId) {
    return SESSION_CONFIG_MAP_PREFIX + legacySafeName("owner", owner) + "-" + legacySafeName("chat", sessionId);
  }

  private String jobStoreName(String owner, String jobId) {
    return JOB_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("job", jobId);
  }

  private String legacyJobStoreName(String owner, String jobId) {
    return JOB_CONFIG_MAP_PREFIX + legacySafeName("owner", owner) + "-" + legacySafeName("job", jobId);
  }

  private String usageStoreName(String owner, String day) {
    return USAGE_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("day", day);
  }

  private String legacyUsageStoreName(String owner, String day) {
    return USAGE_CONFIG_MAP_PREFIX + legacySafeName("owner", owner) + "-" + legacySafeName("day", day);
  }

  private String instanceStoreName(String checkedInstanceId) {
    return INSTANCE_CONFIG_MAP_PREFIX + safeName("instance", checkedInstanceId);
  }

  private String sessionKey(String sessionId) {
    return SESSION_KEY_PREFIX + safeName("chat", sessionId);
  }

  private String jobKey(String jobId) {
    return JOB_KEY_PREFIX + safeName("job", jobId);
  }

  private String runningJobKey(String owner, String jobId) {
    return safeName("owner", owner) + "/" + safeName("job", jobId);
  }

  private String dayKey(long time) {
    return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString();
  }

  private Mono<List<Map<String, Object>>> logsFor(String owner) {
    return logFluxFor(owner)
      .sort((left, right) -> Long.compare(nullToZero(longValue(right.get("time"))), nullToZero(longValue(left.get("time")))))
      .take(300)
      .collectList();
  }

  private Flux<Map<String, Object>> logFluxFor(String owner) {
    var currentLogs = listCompatibleConfigMaps(ownerConfigMapPrefixes(LOG_CONFIG_MAP_PREFIX, owner))
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("log")));
    var legacyLogs = fetchStore(owner)
      .flatMapMany(store -> Flux.fromIterable(store.getData().entrySet()))
      .filter(entry -> entry.getKey().startsWith(LOG_KEY_PREFIX))
      .map(entry -> readMapValue(entry.getValue()));
    return currentLogs.concatWith(legacyLogs);
  }

  private Mono<Integer> currentDailyUsage(String owner, String day) {
    return fetchCompatibleConfigMap(usageStoreName(owner, day), legacyUsageStoreName(owner, day))
      .map(configMap -> {
        var tokens = intValue(configMap.getData() == null ? null : configMap.getData().get("tokens"));
        return tokens == null ? 0 : tokens;
      })
      .switchIfEmpty(sumDailyTokensFromLogs(owner, day));
  }

  private Mono<Integer> sumDailyTokensFromLogs(String owner, String day) {
    var start = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    var end = LocalDate.parse(day).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    return logFluxFor(owner)
      .filter(log -> {
        var time = nullToZero(longValue(log.get("time")));
        return time >= start && time < end;
      })
      .map(log -> intValue(log.get("totalTokens")) == null ? 0 : intValue(log.get("totalTokens")))
      .reduce(0, Integer::sum);
  }

  private Mono<Void> incrementDailyUsage(String owner, String day, int tokens) {
    if (tokens <= 0) {
      return Mono.empty();
    }
    return updateUsageRecord(owner, day, data -> {
      var current = intValue(data.get("tokens"));
      data.put("owner", owner);
      data.put("day", day);
      data.put("tokens", String.valueOf((current == null ? 0 : current) + tokens));
      data.put("updatedAt", String.valueOf(System.currentTimeMillis()));
    }).then();
  }

  private Mono<ConfigMap> updateUsageRecord(String owner, String day, Consumer<Map<String, String>> mutator) {
    var name = usageStoreName(owner, day);
    return fetchCompatibleConfigMap(name, legacyUsageStoreName(owner, day))
      .switchIfEmpty(Mono.defer(() -> {
        var configMap = new ConfigMap();
        configMap.setMetadata(metadata(name));
        configMap.setData(new LinkedHashMap<>());
        return client.create(configMap);
      }))
      .flatMap(configMap -> {
        var data = configMap.getData() == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(configMap.getData());
        data.putIfAbsent("tokens", "0");
        data.putIfAbsent("requestTimes", "[]");
        data.putIfAbsent("reservations", "{}");
        mutator.accept(data);
        configMap.setData(data);
        return client.update(configMap);
      })
      .retryWhen(Retry.backoff(6, Duration.ofMillis(50)).filter(this::isOptimisticLockConflict));
  }

  private Mono<List<Map<String, Object>>> legacyLogsFor(String owner) {
    return fetchStore(owner)
      .map(store -> store.getData().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(LOG_KEY_PREFIX))
        .map(entry -> readMapValue(entry.getValue()))
        .sorted((left, right) -> Long.compare(nullToZero(longValue(right.get("time"))), nullToZero(longValue(left.get("time")))))
        .limit(300)
        .collect(Collectors.toList()));
  }

  private Mono<Map<String, Object>> settingsFor(String owner) {
    return Mono.zip(fetchStore(owner), globalSettings())
      .map(tuple -> {
        var settings = validateSettings(readMapValue(tuple.getT1().getData().get(SETTINGS_KEY)));
        var policy = userModelPolicy(tuple.getT2());
        settings.put("modelPolicy", policy);
        settings.put("imageMaxSizeMb", policy.get("imageMaxSizeMb"));
        return settings;
      });
  }

  private Map<String, Object> userModelPolicy(Map<String, Object> globalSettings) {
    var policy = new LinkedHashMap<String, Object>();
    for (var key : List.of("defaultLanguageModelMode", "defaultLanguageModel",
      "defaultMultimodalModelMode", "defaultMultimodalModel", "defaultImageModelMode",
      "defaultImageModel", "allowedModels")) {
      policy.put(key, globalSettings.get(key));
    }
    policy.put("imageMaxSizeMb", clampInt(globalSettings.get("imageMaxSizeMb"), 1, 10, 8));
    return policy;
  }

  private Mono<Map<String, Object>> globalSettings() {
    return client.fetch(ConfigMap.class, GLOBAL_CONFIG_MAP)
      .map(configMap -> {
        var data = configMap.getData();
        if (data == null) {
          return validateGlobalSettings(new LinkedHashMap<>());
        }
        if (data.containsKey(GLOBAL_CONFIG_GROUP)) {
          var grouped = readRequiredGlobalSettings(data.get(GLOBAL_CONFIG_GROUP));
          return validateGlobalSettings(grouped);
        }
        var flat = new LinkedHashMap<String, Object>();
        data.forEach(flat::put);
        return validateGlobalSettings(flat);
      })
      .switchIfEmpty(Mono.fromSupplier(() -> validateGlobalSettings(new LinkedHashMap<>())));
  }

  private Map<String, Object> readRequiredGlobalSettings(String value) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "全局插件设置为空或损坏，请由管理员检查并重新保存设置。" );
    }
    try {
      var parsed = objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
      if (parsed == null) {
        throw new IOException("empty settings map");
      }
      return parsed;
    } catch (IOException | IllegalArgumentException error) {
      log.error("Global Halo AI Console settings are invalid", error);
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "全局插件设置损坏，请由管理员检查并重新保存设置。", error);
    }
  }

  private Map<String, Object> validateGlobalSettings(Map<String, Object> source) {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("defaultLanguageModelMode", normalizeModelMode(source.get("defaultLanguageModelMode")));
    settings.put("defaultLanguageModel", limitString(stringValue(source.get("defaultLanguageModel")), 160));
    settings.put("defaultMultimodalModelMode", normalizeModelMode(source.get("defaultMultimodalModelMode")));
    settings.put("defaultMultimodalModel", limitString(stringValue(source.get("defaultMultimodalModel")), 160));
    settings.put("defaultImageModelMode", normalizeModelMode(source.get("defaultImageModelMode")));
    settings.put("defaultImageModel", limitString(stringValue(source.get("defaultImageModel")), 160));
    settings.put("allowedModels", cleanModelNames(source.get("allowedModels")));
    settings.put("imageMaxSizeMb", clampInt(source.get("imageMaxSizeMb"), 1, 10, 8));
    settings.put("maxConcurrentJobs", clampInt(source.get("maxConcurrentJobs"), 1, 20, 2));
    settings.put("requestsPerMinute", clampInt(source.get("requestsPerMinute"), 1, 300, 12));
    settings.put("dailyTokenLimit", clampInt(source.get("dailyTokenLimit"), 1000, 10_000_000, 200_000));
    settings.put("maxContextMessages", clampInt(source.get("maxContextMessages"), 4, 100, MAX_REQUEST_MESSAGES));
    settings.put("maxContextCharacters", clampInt(source.get("maxContextCharacters"), 4000, 200_000, MAX_REQUEST_CHARS));
    settings.put("maxOutputCharacters", clampInt(source.get("maxOutputCharacters"), 4000, MAX_STREAM_TEXT_LENGTH, MAX_STREAM_TEXT_LENGTH));
    settings.put("maxImagesPerRequest", clampInt(source.get("maxImagesPerRequest"), 0, 50, MAX_REQUEST_IMAGES));
    settings.put("jobRetentionDays", clampInt(source.get("jobRetentionDays"), 1, 365, 7));
    settings.put("logRetentionDays", clampInt(source.get("logRetentionDays"), 7, 3650, 90));
    settings.put("imageCacheRetentionDays", clampInt(source.get("imageCacheRetentionDays"), 1, 3650, 30));
    settings.put("maxJobsPerUser", clampInt(source.get("maxJobsPerUser"), 50, 10000, 500));
    settings.put("allowUserAuditLogDeletion", Boolean.TRUE.equals(booleanValue(source.get("allowUserAuditLogDeletion"))));
    return settings;
  }

  private String normalizeModelMode(Object value) {
    return "custom".equals(stringValue(value)) ? "custom" : "default";
  }

  private List<String> cleanModelNames(Object value) {
    var names = new ArrayList<String>();
    if (value instanceof List<?> list) {
      list.forEach(item -> names.add(stringValue(item)));
    } else {
      for (var item : stringValue(value).split("[,\\r\\n]+")) {
        names.add(item);
      }
    }
    return names.stream()
      .map(name -> limitString(name.trim(), 160))
      .filter(name -> !name.isBlank())
      .distinct()
      .limit(200)
      .collect(Collectors.toList());
  }

  private void enforceAllowedModel(String model, Map<String, Object> globalSettings) {
    var allowedModels = listOfStrings(globalSettings.get("allowedModels"));
    if (!allowedModels.isEmpty() && !allowedModels.contains(model)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "插件设置不允许使用该模型，请联系管理员。");
    }
  }

  private Mono<UsageReservation> reserveUsage(String owner, Map<String, Object> globalSettings, Integer promptTokens,
    int maxOutputTokens, String jobId) {
    var maxConcurrent = clampInt(globalSettings.get("maxConcurrentJobs"), 1, 20, 2);
    var perMinute = clampInt(globalSettings.get("requestsPerMinute"), 1, 300, 12);
    var dailyLimit = clampInt(globalSettings.get("dailyTokenLimit"), 1000, 10_000_000, 200_000);
    var prompt = Math.max(0, promptTokens == null ? 0 : promptTokens);
    var reserved = saturatedTokenSum(prompt, maxOutputTokens);
    var now = System.currentTimeMillis();
    var day = dayKey(now);
    var reservation = new UsageReservation(owner, day, prompt, reserved, jobId);
    return currentDailyUsage(owner, day).flatMap(consumed -> updateUsageRecord(owner, day, data -> {
      var recordedTokens = intValue(data.get("tokens"));
      if ((recordedTokens == null || recordedTokens == 0) && consumed > 0) {
        data.put("tokens", String.valueOf(consumed));
      }
      var requestTimes = readLongList(data.get("requestTimes"));
      var minuteStart = now - 60_000L;
      requestTimes = requestTimes.stream().filter(time -> time >= minuteStart).collect(Collectors.toCollection(ArrayList::new));
      var reservations = readMapValue(data.get("reservations"));
      reservations.entrySet().removeIf(entry -> {
        var item = castMapValue(entry.getValue());
        return now - nullToZero(longValue(item.get("createdAt"))) > Duration.ofHours(6).toMillis();
      });
      var running = reservations.size();
      var reservedTokens = reservations.values().stream()
        .map(this::castMapValue)
        .mapToInt(item -> intValue(item.get("tokens")) == null ? 0 : intValue(item.get("tokens")))
        .sum();
      try {
        QuotaPolicy.validate(
          new QuotaPolicy.Snapshot(running, requestTimes.size(), consumed, reservedTokens, reserved),
          new QuotaPolicy.Limits(maxConcurrent, perMinute, dailyLimit));
      } catch (QuotaPolicy.Exceeded exceeded) {
        throw tooManyRequests(exceeded.getMessage());
      }
      var item = new LinkedHashMap<String, Object>();
      item.put("tokens", reserved);
      item.put("createdAt", now);
      item.put("instanceId", instanceId);
      reservations.put(safeName("job", jobId), item);
      requestTimes.add(now);
      data.put("owner", owner);
      data.put("day", day);
      data.put("requestTimes", writeJsonValue(requestTimes));
      data.put("reservations", writeMapValue(reservations));
      data.put("updatedAt", String.valueOf(now));
    }).thenReturn(reservation).doOnSuccess(ignored -> {
      var state = usageStates.computeIfAbsent(owner, unusedOwner -> new UserUsageState());
      synchronized (state) {
        if (!day.equals(state.day)) {
          state.day = day;
          state.reservedTokens = 0;
          state.requestTimes.clear();
        }
        var minuteStart = now - 60_000L;
        while (!state.requestTimes.isEmpty() && state.requestTimes.peekFirst() < minuteStart) {
          state.requestTimes.removeFirst();
        }
        state.running++;
        state.reservedTokens = saturatedTokenSum(state.reservedTokens, reserved);
        state.requestTimes.addLast(now);
      }
    }));
  }

  private Mono<Void> releaseUsageReservation(UsageReservation reservation) {
    if (reservation == null) {
      return Mono.empty();
    }
    var state = usageStates.get(reservation.owner);
    if (state == null) {
      return releasePersistentUsageReservation(reservation);
    }
    synchronized (state) {
      if (state.running > 0) {
        state.running--;
      }
      if (reservation.day.equals(state.day)) {
        state.reservedTokens = Math.max(0, state.reservedTokens - reservation.reservedTokens);
      }
    }
    return releasePersistentUsageReservation(reservation);
  }

  private Mono<Void> releasePersistentUsageReservation(UsageReservation reservation) {
    return fetchCompatibleConfigMap(usageStoreName(reservation.owner, reservation.day),
        legacyUsageStoreName(reservation.owner, reservation.day))
      .flatMap(configMap -> {
        var data = configMap.getData() == null
          ? new LinkedHashMap<String, String>()
          : new LinkedHashMap<>(configMap.getData());
        var reservations = readMapValue(data.get("reservations"));
        reservations.remove(safeName("job", reservation.jobId));
        data.put("reservations", writeMapValue(reservations));
        data.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        configMap.setData(data);
        return client.update(configMap);
      })
      .retryWhen(Retry.backoff(6, Duration.ofMillis(50)).filter(this::isOptimisticLockConflict))
      .then()
      .onErrorResume(error -> Mono.empty());
  }

  private Mono<Void> requireAdminPermission(ServerRequest request) {
    return request.principal()
      .filter(principal -> principal instanceof Authentication authentication && hasAdminPermission(authentication))
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "该操作需要 AI 聊天管理权限。")))
      .then();
  }

  private boolean hasAdminPermission(Authentication authentication) {
    if (authentication == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
      .map(authority -> authority.getAuthority())
      .anyMatch(ADMIN_AUTHORITIES::contains);
  }

  private ResponseStatusException tooManyRequests(String message) {
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
  }

  private String nextJobId() {
    return safeName("job", "job-" + UUID.randomUUID());
  }

  private String auxiliaryOperation(Object value) {
    var operation = stringValue(value);
    if (!AUXILIARY_OPERATIONS.contains(operation)) {
      throw badRequest("不支持的辅助调用类型。");
    }
    return operation;
  }

  private Map<String, Object> normalizeSessionSnapshot(String sessionId, String owner, Map<String, Object> body,
    long maxImageBytes) {
    var session = sessionFromMap(sessionId, owner, body);
    var savedMessages = listOfMaps(body.get("messages")).stream()
      .limit(MAX_MESSAGES_PER_SESSION)
      .map(message -> messageToMap(messageFromMap(sessionId, owner, message, maxImageBytes)))
      .collect(Collectors.toList());
    var snapshot = sessionToMap(session, savedMessages);
    enforceSessionSize(snapshot);
    return snapshot;
  }

  private Map<String, Object> requestAuditMeta(ServerRequest request) {
    var meta = new LinkedHashMap<String, Object>();
    var userAgent = stringValue(request.headers().firstHeader(HttpHeaders.USER_AGENT));
    meta.put("ipAddress", clientIp(request));
    meta.put("userAgent", userAgent);
    meta.put("browser", browserName(userAgent));
    meta.put("operatingSystem", operatingSystem(userAgent));
    return meta;
  }

  private String clientIp(ServerRequest request) {
    return request.remoteAddress().map(address -> address.getAddress().getHostAddress()).orElse("");
  }

  private String browserName(String userAgent) {
    var ua = stringValue(userAgent);
    if (ua.contains("Edg/")) return "Edge";
    if (ua.contains("Chrome/")) return "Chrome";
    if (ua.contains("Firefox/")) return "Firefox";
    if (ua.contains("Safari/") && !ua.contains("Chrome/")) return "Safari";
    return ua.isBlank() ? "" : "Other";
  }

  private String operatingSystem(String userAgent) {
    var ua = stringValue(userAgent);
    if (ua.contains("Windows")) return "Windows";
    if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
    if (ua.contains("Android")) return "Android";
    if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
    if (ua.contains("Linux")) return "Linux";
    return ua.isBlank() ? "" : "Other";
  }

  private String cleanAiFoundationError(Throwable error) {
    var message = error == null ? "" : stringValue(error.getMessage());
    var parsed = readMapValue(message);
    var detail = stringValue(firstNonBlank(parsed.get("detail"), parsed.get("title"), parsed.get("message")));
    var source = stringValue(firstNonBlank(detail, message)).toLowerCase();
    if (source.contains("no static resource") || source.contains("not found")) {
      return "当前 AI Foundation 版本没有提供该接口，或所选模型不支持此能力。";
    }
    if (source.contains("timeout") || source.contains("timed out")) {
      return "模型响应超时，请稍后重试或选择其他模型。";
    }
    if (source.contains("unauthorized") || source.contains("forbidden")
      || source.contains("authentication") || source.contains("api key")) {
      return "模型服务认证失败，请联系站点管理员检查 AI Foundation 配置。";
    }
    if (source.contains("rate limit") || source.contains("too many requests") || source.contains(" 429")) {
      return "模型服务当前限流，请稍后重试。";
    }
    if (source.contains("unavailable") || source.contains("bad gateway")
      || source.contains("service unavailable") || source.contains(" 502") || source.contains(" 503")) {
      return "模型服务暂时不可用，请稍后重试。";
    }
    return "AI Foundation 请求失败，请检查模型配置后重试。";
  }

  private void appendOutputLimited(Map<String, Object> state, String field, String delta, int maxLength, String error) {
    var reasoning = stringValue(state.get("reasoning"));
    var content = stringValue(state.get("content"));
    var text = stringValue(delta);
    if (reasoning.length() + content.length() + text.length() > maxLength) {
      throw new IllegalStateException(error);
    }
    if ("reasoning".equals(field)) {
      state.put("reasoning", reasoning + text);
    } else {
      state.put("content", content + text);
    }
  }

  private String normalizeGeneratedImageReference(String value, long maxImageBytes) {
    var text = value == null ? "" : value.trim();
    if (text.isBlank()) {
      return "";
    }
    if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("/")) {
      if (text.length() > 2048) {
        throw new IllegalStateException("生成图片的访问地址超过最大长度限制。");
      }
      return text;
    }
    if (text.startsWith("data:image/")) {
      if (text.length() > MAX_DATA_URL_LENGTH) {
        throw new IllegalStateException("生成图片的数据超过最大长度限制。");
      }
      validateDataUrlSize(text, maxImageBytes);
      return text;
    }
    if (text.length() <= 200) {
      return "";
    }
    if (text.length() > MAX_DATA_URL_LENGTH) {
      throw new IllegalStateException("生成图片的数据超过最大长度限制。");
    }
    if (!text.matches("^[A-Za-z0-9+/=\\r\\n]+$")) {
      return "";
    }
    var payloadLength = text.codePoints().filter(code -> !Character.isWhitespace(code)).count();
    if (payloadLength * 3L / 4L > maxImageBytes || payloadLength * 3L / 4L > HARD_MAX_IMAGE_BYTES) {
      throw new IllegalStateException("生成图片的数据超过管理员配置的大小上限。");
    }
    return text;
  }

  private void mergeImages(Map<String, Object> state, List<String> images) {
    mergeImages(state, images, HARD_MAX_IMAGE_BYTES);
  }

  private void mergeImages(Map<String, Object> state, List<String> images, long maxImageBytes) {
    if (images.isEmpty()) {
      return;
    }
    var merged = new ArrayList<>(listOfStrings(state.get("images")));
    images.forEach(image -> {
      var reference = normalizeGeneratedImageReference(image, maxImageBytes);
      if (!reference.isBlank()) {
        merged.add(reference);
      }
    });
    state.put("images", merged.stream().filter(item -> !item.isBlank()).distinct().limit(MAX_IMAGES_PER_MESSAGE).collect(Collectors.toList()));
  }

  private Object firstNonBlank(Object... values) {
    for (var value : values) {
      if (value != null && !String.valueOf(value).isBlank()) {
        return value;
      }
    }
    return "";
  }

  private int estimateTokens(String value) {
    var text = stringValue(value);
    var cjk = text.codePoints().filter(code -> code >= 0x3400 && code <= 0x9fff).count();
    var asciiWords = java.util.regex.Pattern.compile("[A-Za-z0-9_]+").matcher(text.replaceAll("[\\u3400-\\u9fff]", " "));
    var words = 0;
    while (asciiWords.find()) {
      words++;
    }
    return Math.max(0, (int) Math.ceil(cjk * 0.75 + words * 1.25 + text.length() / 12.0));
  }

  private int tokenValue(Object actual, Integer fallback) {
    var value = intValue(actual);
    return value == null
      ? Math.max(0, fallback == null ? 0 : fallback)
      : Math.max(0, value);
  }

  private int estimateRequestTokens(List<Map<String, Object>> messages) {
    var total = 0;
    for (var message : messages) {
      total += 4;
      total += estimateTokens(stringValue(message.get("role")));
      for (var part : listOfMaps(message.get("parts"))) {
        var type = stringValue(part.get("type"));
        total += estimateTokens(stringValue(firstNonBlank(part.get("text"), part.get("title"), part.get("name"))));
        if (part.containsKey("data") || part.containsKey("url")) {
          total += stringValue(part.get("mediaType")).startsWith("image/") ? 1024 : 128;
        }
        if ("file".equals(type) || "image".equals(type)) {
          total += 128;
        }
      }
    }
    return Math.max(0, total);
  }

  private int estimateImagePromptTokens(String prompt, Map<String, Object> payload) {
    var total = estimateTokens(prompt) + 16;
    var imageSource = payload.containsKey("images") ? payload.get("images") : payload.get("inputImages");
    for (var image : listOfMaps(imageSource)) {
      total += stringValue(image.get("mediaType")).startsWith("image/") ? 1024 : 128;
      total += estimateTokens(stringValue(firstNonBlank(image.get("filename"), image.get("name"))));
    }
    return Math.max(0, total);
  }

  private FilePart firstFile(MultiValueMap<String, Part> parts) {
    var named = parts.getFirst("file");
    if (named instanceof FilePart filePart) {
      return filePart;
    }
    return parts.values().stream()
      .flatMap(List::stream)
      .filter(FilePart.class::isInstance)
      .map(FilePart.class::cast)
      .findFirst()
      .orElse(null);
  }

  private Mono<Void> ensureActive() {
    if (shuttingDown.get()) {
      return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "插件正在停止，暂时不能创建新的 AI 任务。"));
    }
    return Mono.empty();
  }

  private Map<String, Object> validateSettings(Map<String, Object> source) {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("lazyBatchSize", clampInt(source.get("lazyBatchSize"), 20, 200, 60));
    settings.put("olderBatchSize", clampInt(source.get("olderBatchSize"), 10, 100, 40));
    settings.put("autoCompressPercent", clampInt(source.get("autoCompressPercent"), 50, 98, 85));
    settings.put("memoryText", limitString(stringValue(source.get("memoryText")), 20000));
    return settings;
  }

  /**
   * Replaces a browser-provided media URL with a permalink resolved from an attachment owned by
   * the current user. The boolean marker is internal only and is required by the SDK adapter.
   */
  private Mono<List<Map<String, Object>>> canonicalizeMessageAttachmentUrls(String owner,
    List<Map<String, Object>> messages) {
    return Flux.fromIterable(messages)
      .concatMap(message -> {
        var canonicalMessage = new LinkedHashMap<String, Object>(message);
        return Flux.fromIterable(listOfMaps(message.get("parts")))
          .concatMap(part -> canonicalizeAttachmentReference(owner, part))
          .collectList()
          .map(parts -> {
            canonicalMessage.put("parts", parts);
            return (Map<String, Object>) canonicalMessage;
          });
      })
      .collectList();
  }

  private Mono<Map<String, Object>> canonicalizeImagePayload(String owner, Map<String, Object> payload) {
    var canonical = new LinkedHashMap<String, Object>(payload);
    var imagesKey = payload.containsKey("images") ? "images" : "inputImages";
    return Flux.fromIterable(listOfMaps(payload.get(imagesKey)))
      .concatMap(image -> canonicalizeAttachmentReference(owner, image))
      .collectList()
      .flatMap(images -> {
        canonical.put(imagesKey, images);
        var mask = castMapValue(payload.get("mask"));
        if (mask.isEmpty()) {
          return Mono.just(canonical);
        }
        return canonicalizeAttachmentReference(owner, mask)
          .map(canonicalMask -> {
            canonical.put("mask", canonicalMask);
            return canonical;
          });
      });
  }

  private Mono<Map<String, Object>> canonicalizeAttachmentReference(String owner,
    Map<String, Object> source) {
    var canonical = new LinkedHashMap<String, Object>(source);
    canonical.remove("_trustedAttachmentUrl");
    if (!stringValue(canonical.get("data")).isBlank()) {
      // Data is validated separately and takes precedence in the AI Foundation SDK.
      canonical.remove("url");
      return Mono.just(canonical);
    }
    if (stringValue(canonical.get("url")).isBlank()) {
      return Mono.just(canonical);
    }
    var attachmentName = limitString(stringValue(canonical.get("attachmentName")), 120);
    if (attachmentName.isBlank()) {
      return Mono.error(badRequest("媒体地址必须引用当前用户已上传的 Halo 附件。"));
    }
    return client.fetch(Attachment.class, attachmentName)
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
        "引用的 Halo 附件不存在或已被删除。")))
      .flatMap(attachment -> {
        var spec = attachment.getSpec();
        if (spec == null || !owner.equals(spec.getOwnerName())) {
          return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
            "不能使用其他用户的 Halo 附件。"));
        }
        var requestedMediaType = stringValue(canonical.get("mediaType"));
        var attachmentMediaType = spec.getMediaType();
        if (!requestedMediaType.isBlank() && attachmentMediaType != null
          && !attachmentMediaType.isBlank()
          && !requestedMediaType.equalsIgnoreCase(attachmentMediaType)) {
          return Mono.error(badRequest("附件媒体类型与上传记录不一致。"));
        }
        return attachmentService.getPermalink(attachment)
          .map(permalink -> {
            canonical.put("url", permalink.toString());
            canonical.put("_trustedAttachmentUrl", true);
            if (requestedMediaType.isBlank() && attachmentMediaType != null) {
              canonical.put("mediaType", attachmentMediaType);
            }
            return canonical;
          });
      });
  }

  private void validateAiRequestMessages(List<Map<String, Object>> messages, Map<String, Object> globalSettings) {
    var maxMessages = clampInt(globalSettings.get("maxContextMessages"), 4, 100, MAX_REQUEST_MESSAGES);
    var maxChars = clampInt(globalSettings.get("maxContextCharacters"), 4000, 200_000, MAX_REQUEST_CHARS);
    var maxImages = clampInt(globalSettings.get("maxImagesPerRequest"), 0, 50, MAX_REQUEST_IMAGES);
    var maxImageBytes = maxImageBytes(globalSettings);
    try {
      ConversationRequestPolicy.validate(messages, new ConversationRequestPolicy.Limits(
        maxMessages, maxChars, maxImages, MAX_REQUEST_ATTACHMENTS, MAX_CONTENT_LENGTH, 2048,
        MAX_DATA_URL_LENGTH));
    } catch (ConversationRequestPolicy.Violation violation) {
      throw badRequest(violation.getMessage());
    }
    for (var message : messages) {
      for (var part : listOfMaps(message.get("parts"))) {
        validateDataUrlSize(stringValue(part.get("data")), maxImageBytes);
      }
    }
  }

  private void validateImagePayload(Map<String, Object> payload, Map<String, Object> globalSettings) {
    var maxImages = clampInt(globalSettings.get("maxImagesPerRequest"), 0, 50, MAX_REQUEST_IMAGES);
    var maxImageBytes = maxImageBytes(globalSettings);
    var prompt = stringValue(payload.get("prompt"));
    if (prompt.length() > 12_000) {
      throw badRequest("图像生成提示词超过长度限制。");
    }
    var inputImages = listOfMaps(payload.containsKey("images") ? payload.get("images") : payload.get("inputImages"));
    if (inputImages.size() > maxImages) {
      throw badRequest("输入图片数量超过限制。");
    }
    for (var image : inputImages) {
      validateImageInput(image, maxImageBytes);
    }
    var mask = castMapValue(payload.get("mask"));
    if (!mask.isEmpty()) {
      validateImageInput(mask, maxImageBytes);
    }
    for (var forbidden : List.of("headers", "providerOptions", "maxRetries", "maxParallelCalls")) {
      if (payload.containsKey(forbidden)) {
        throw badRequest("图像生成不允许客户端设置 " + forbidden + "。");
      }
    }
    var n = intValue(payload.get("n"));
    if (n != null && (n < 1 || n > MAX_IMAGE_RESULTS_PER_REQUEST)) {
      throw badRequest("单次图像生成数量必须在 1 到 " + MAX_IMAGE_RESULTS_PER_REQUEST + " 之间。");
    }
    var size = stringValue(payload.get("size"));
    if (!size.isBlank()) {
      validateImageSize(size);
    }
    var width = intValue(payload.get("width"));
    var height = intValue(payload.get("height"));
    if ((width == null) != (height == null)) {
      throw badRequest("图像宽度和高度必须同时提供。");
    }
    if (width != null) {
      validateImageDimensions(width, height);
    }
    var responseFormat = stringValue(payload.get("responseFormat"));
    if (!responseFormat.isBlank() && !"URL".equalsIgnoreCase(responseFormat)
      && !"BASE64".equalsIgnoreCase(responseFormat)) {
      throw badRequest("图像响应格式只能是 URL 或 BASE64。");
    }
  }

  private void validateImageInput(Map<String, Object> image, long maxImageBytes) {
    if (stringValue(image.get("url")).length() > 2048) {
        throw badRequest("输入图片的访问地址超过最大长度限制。");
    }
    if (stringValue(image.get("data")).length() > MAX_DATA_URL_LENGTH) {
        throw badRequest("输入图片数据超过最大长度限制。");
    }
    validateDataUrlSize(stringValue(image.get("data")), maxImageBytes);
    if (!stringValue(image.get("url")).isBlank() && stringValue(image.get("attachmentName")).isBlank()) {
      throw badRequest("输入图片地址必须引用当前用户已上传的 Halo 附件。");
    }
  }

  private void validateImageSize(String size) {
    if (!size.matches("\\d{2,4}x\\d{2,4}")) {
      throw badRequest("图像尺寸必须采用 宽x高 格式。");
    }
    var parts = size.split("x", 2);
    validateImageDimensions(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
  }

  private void validateImageDimensions(int width, int height) {
    if (width < 64 || height < 64 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
      || (long) width * height > MAX_IMAGE_PIXELS) {
      throw badRequest("图像尺寸超出允许范围。");
    }
  }

  private Map<String, Object> toAiFoundationImagePayload(Map<String, Object> payload) {
    var normalized = new LinkedHashMap<String, Object>();
    normalized.put("prompt", limitString(stringValue(payload.get("prompt")), 12_000));
    var imageSource = payload.containsKey("images") ? payload.get("images") : payload.get("inputImages");
    var images = listOfMaps(imageSource).stream()
      .map(image -> {
        var item = new LinkedHashMap<String, Object>();
        putIfNotBlank(item, "url", stringValue(image.get("url")));
        putIfNotBlank(item, "data", stringValue(image.get("data")));
        putIfNotBlank(item, "mediaType", stringValue(image.get("mediaType")));
        putIfNotBlank(item, "filename", stringValue(firstNonBlank(image.get("filename"), image.get("name"))));
        if (Boolean.TRUE.equals(image.get("_trustedAttachmentUrl"))) {
          item.put("_trustedAttachmentUrl", true);
        }
        return item;
      })
      .filter(image -> !stringValue(image.get("url")).isBlank() || !stringValue(image.get("data")).isBlank())
      .limit(MAX_IMAGES_PER_MESSAGE)
      .collect(Collectors.toList());
    normalized.put("images", images);
    var mask = castMapValue(payload.get("mask"));
    if (!mask.isEmpty()) {
      var normalizedMask = new LinkedHashMap<String, Object>();
      putIfNotBlank(normalizedMask, "url", stringValue(mask.get("url")));
      putIfNotBlank(normalizedMask, "data", stringValue(mask.get("data")));
      putIfNotBlank(normalizedMask, "mediaType", stringValue(mask.get("mediaType")));
      putIfNotBlank(normalizedMask, "filename", stringValue(firstNonBlank(mask.get("filename"), mask.get("name"))));
      if (Boolean.TRUE.equals(mask.get("_trustedAttachmentUrl"))) {
        normalizedMask.put("_trustedAttachmentUrl", true);
      }
      normalized.put("mask", normalizedMask);
    }
    for (var key : List.of("n", "size", "width", "height", "aspectRatio", "seed", "responseFormat")) {
      if (payload.containsKey(key) && payload.get(key) != null) {
        normalized.put(key, payload.get(key));
      }
    }
    if (!normalized.containsKey("responseFormat")) {
      normalized.put("responseFormat", "URL");
    }
    return normalized;
  }

  private void putIfNotBlank(Map<String, Object> target, String key, String value) {
    if (value != null && !value.isBlank()) {
      target.put(key, value);
    }
  }

  private void enforceSessionSize(Map<String, Object> session) {
    var messages = listOfMaps(session.get("messages"));
    if (messages.size() > MAX_MESSAGES_PER_SESSION) {
      throw badRequest("单个会话中的消息数量超过限制。");
    }
    session.put("memory", limitString(stringValue(session.get("memory")), MAX_MEMORY_LENGTH));
    var serialized = writeMapValue(session);
    if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_SESSION_JSON_LENGTH) {
      throw badRequest("会话数据过大，无法安全保存。");
    }
  }

  private int clampInt(Object value, int min, int max, int fallback) {
    var parsed = intValue(value);
    var number = parsed == null ? fallback : parsed;
    return Math.max(min, Math.min(max, number));
  }

  private int saturatedTokenSum(int left, int right) {
    var total = (long) Math.max(0, left) + Math.max(0, right);
    return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
  }

  private long maxImageBytes(Map<String, Object> settings) {
    var mb = clampInt(settings.get("imageMaxSizeMb"), 1, 10, 8);
    return Math.min(HARD_MAX_IMAGE_BYTES, mb * 1024L * 1024L);
  }

  private Map<String, Object> readMapValue(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  private List<Long> readLongList(String value) {
    try {
      if (value == null || value.isBlank()) {
        return new ArrayList<>();
      }
      return objectMapper.convertValue(objectMapper.readValue(value, List.class), new TypeReference<List<Long>>() {});
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  private String writeJsonValue(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw badRequest("数据序列化失败，无法保存。");
    }
  }

  private String writeMapValue(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw badRequest("会话快照序列化失败，无法保存。");
    }
  }

  private Metadata metadata(String name) {
    var metadata = new Metadata();
    metadata.setName(name);
    return metadata;
  }

  private String idOf(run.halo.app.extension.Extension extension) {
    return extension.getMetadata().getName();
  }

  private String messageName(String sessionId, String id) {
    return safeName("msg", sessionId + "-" + emptyToDefault(id, "message"));
  }

  private String safeName(String prefix, String raw) {
    return KubernetesNamePolicy.canonicalName(prefix, raw);
  }

  private String legacySafeName(String prefix, String raw) {
    return KubernetesNamePolicy.legacyName(prefix, raw);
  }

  private String[] ownerConfigMapPrefixes(String resourcePrefix, String owner) {
    return new String[] {
      resourcePrefix + safeName("owner", owner) + "-",
      resourcePrefix + legacySafeName("owner", owner) + "-"
    };
  }

  private Mono<ConfigMap> fetchCompatibleConfigMap(String canonicalName, String legacyName) {
    var canonical = client.fetch(ConfigMap.class, canonicalName);
    if (canonicalName.equals(legacyName)) {
      return canonical;
    }
    return canonical.switchIfEmpty(client.fetch(ConfigMap.class, legacyName));
  }

  private Flux<ConfigMap> listCompatibleConfigMaps(String... prefixes) {
    return Flux.fromArray(prefixes)
      .distinct()
      .flatMap(prefix -> client.list(
          ConfigMap.class,
          item -> item.getMetadata() != null && idOf(item).startsWith(prefix),
          Comparator.comparing(this::idOf)
        ))
      .distinct(this::idOf);
  }

  private List<Map<String, Object>> listOfMaps(Object value) {
    if (value == null) {
      return new ArrayList<>();
    }
    return objectMapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() {});
  }

  private List<String> listOfStrings(Object value) {
    if (value == null) {
      return new ArrayList<>();
    }
    return objectMapper.convertValue(value, new TypeReference<List<String>>() {});
  }

  private List<String> cleanTags(List<String> tags) {
    return tags.stream()
      .map(tag -> limitString(tag, 40).trim())
      .filter(tag -> !tag.isBlank())
      .distinct()
      .limit(MAX_TAGS_PER_SESSION)
      .collect(Collectors.toList());
  }

  private Map<String, Object> castMap(Map<?, ?> value) {
    var result = new LinkedHashMap<String, Object>();
    if (value == null) {
      return result;
    }
    value.forEach((key, val) -> result.put(String.valueOf(key), val));
    return result;
  }

  private Map<String, Object> castMapValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return castMap(map);
    }
    if (value == null) {
      return new LinkedHashMap<>();
    }
    return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
  }

  private List<AiChatMessage.Attachment> validateAttachments(List<AiChatMessage.Attachment> files, long maxImageBytes) {
    if (files == null) {
      return List.of();
    }
    if (files.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
      throw badRequest("单条消息中的附件数量超过限制。");
    }
    files.forEach(file -> {
      file.setName(limitString(file.getName(), 240));
      file.setMediaType(validateMediaType(file.getMediaType()));
      file.setUrl(limitString(file.getUrl(), 2048));
      file.setAttachmentName(limitString(file.getAttachmentName(), 120));
      if (file.getSize() != null && (file.getSize() > maxImageBytes || file.getSize() > HARD_MAX_IMAGE_BYTES)) {
        throw badRequest("图片大小超过管理员配置的上限。");
      }
      validateDataUrlSize(file.getData(), maxImageBytes);
      file.setData(limitString(file.getData(), MAX_DATA_URL_LENGTH));
    });
    return files;
  }

  private List<String> validateImages(List<String> images, long maxImageBytes) {
    if (images == null) {
      return List.of();
    }
    if (images.size() > MAX_IMAGES_PER_MESSAGE) {
      throw badRequest("单条消息中的图片数量超过限制。");
    }
    return images.stream()
      .map(image -> {
        validateDataUrlSize(image, maxImageBytes);
        return limitString(image, MAX_DATA_URL_LENGTH);
      })
      .collect(Collectors.toList());
  }

  private void validateDataUrlSize(String value, long maxImageBytes) {
    if (value == null || value.isBlank() || !value.startsWith("data:")) {
      return;
    }
    var comma = value.indexOf(',');
    var payloadLength = comma >= 0 ? value.length() - comma - 1 : value.length();
    var estimatedBytes = value.contains(";base64,") ? (payloadLength * 3L / 4L) : payloadLength;
    if (estimatedBytes > maxImageBytes || estimatedBytes > HARD_MAX_IMAGE_BYTES) {
      throw badRequest("图片数据超过管理员配置的大小上限。");
    }
  }

  private String validateMediaType(String mediaType) {
    var value = limitString(mediaType, 120);
    if (!value.isBlank() && !value.startsWith("image/") && !"application/octet-stream".equals(value)) {
      throw badRequest("不支持该媒体类型。");
    }
    return value;
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String limitString(String value, int maxLength) {
    var text = value == null ? "" : value;
    if (text.length() > maxLength) {
      return text.substring(0, maxLength);
    }
    return text;
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private String emptyToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private Long longOrNow(Object value) {
    var parsed = longValue(value);
    return parsed == null ? System.currentTimeMillis() : parsed;
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private long nullToZero(Long value) {
    return value == null ? 0L : value;
  }

  private Integer intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Boolean booleanValue(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value == null) {
      return null;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }
}
