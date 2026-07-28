package run.halo.aichatconsole.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;
import reactor.core.Disposable;
import org.springframework.security.core.Authentication;
import run.halo.aichatconsole.extension.AiChatCallLog;
import run.halo.aichatconsole.extension.AiChatImageCache;
import run.halo.aichatconsole.extension.AiChatMessage;
import run.halo.aichatconsole.extension.AiChatSession;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class HaloAiConsoleEndpoint implements CustomEndpoint {
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
  private static final int MAX_DATA_URL_LENGTH = 2_000_000;
  private static final long HARD_MAX_IMAGE_BYTES = 50L * 1024L * 1024L;
  private static final Set<String> ROLES = Set.of("user", "assistant");
  private static final String STORE_CONFIG_MAP_PREFIX = "halo-ai-console-store-";
  private static final String SESSION_CONFIG_MAP_PREFIX = "halo-ai-console-session-";
  private static final String JOB_CONFIG_MAP_PREFIX = "halo-ai-console-job-";
  private static final String LOG_CONFIG_MAP_PREFIX = "halo-ai-console-log-";
  private static final String USAGE_CONFIG_MAP_PREFIX = "halo-ai-console-usage-";
  private static final String INSTANCE_CONFIG_MAP_PREFIX = "halo-ai-console-instance-";
  private static final long INSTANCE_HEARTBEAT_TTL_MS = 35_000L;
  private static final long JOB_STALE_AFTER_MS = 30_000L;
  private static final String SESSION_KEY_PREFIX = "session:";
  private static final String LOG_KEY_PREFIX = "log:";
  private static final String IMAGE_KEY_PREFIX = "image:";
  private static final String JOB_KEY_PREFIX = "job:";
  private static final String LEGACY_MIGRATION_KEY = "migration:legacy";
  private static final String SETTINGS_KEY = "settings";
  private static final String GLOBAL_CONFIG_MAP = "halo-ai-console-config";
  private static final String GLOBAL_CONFIG_GROUP = "basic";

  private final ReactiveExtensionClient client;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String instanceId = safeName("instance", UUID.randomUUID().toString());
  private final Map<String, Disposable> runningJobs = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> runningJobStates = new ConcurrentHashMap<>();
  private final Map<String, UserUsageState> usageStates = new ConcurrentHashMap<>();
  private final Map<String, Sinks.Many<Map<String, Object>>> jobEventSinks = new ConcurrentHashMap<>();

  public HaloAiConsoleEndpoint(ReactiveExtensionClient client) {
    this.client = client;
    Flux.interval(Duration.ZERO, Duration.ofSeconds(10))
      .flatMap(tick -> heartbeatInstance().onErrorResume(error -> Mono.empty()))
      .subscribe();
    Mono.delay(Duration.ofSeconds(15))
      .then(markInterruptedJobsV2())
      .onErrorResume(error -> Mono.empty())
      .subscribe();
    Flux.interval(Duration.ofMinutes(5), Duration.ofHours(6))
      .flatMap(tick -> cleanupExpiredRecords().onErrorResume(error -> Mono.empty()))
      .subscribe();
  }

  private static final class UserUsageState {
    private final Deque<Long> requestTimes = new ArrayDeque<>();
    private String day = "";
    private int running;
    private int reservedTokens;
  }

  private static final class UsageReservation {
    private final String owner;
    private final String day;
    private final int promptTokens;
    private final String jobId;

    private UsageReservation(String owner, String day, int promptTokens, String jobId) {
      this.owner = owner;
      this.day = day;
      this.promptTokens = Math.max(0, promptTokens);
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
    return Mono.zip(owner(request), request.bodyToMono(Map.class).map(this::castMap))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var body = tuple.getT2();
        return settingsFor(owner).flatMap(settings -> {
          var maxImageBytes = maxImageBytes(settings);
          var messages = listOfMaps(body.get("messages"));
          if (messages.size() > MAX_MESSAGES_PER_SESSION) {
            throw badRequest("Too many messages in one session.");
          }
          var session = sessionFromMap(name, owner, body);
          var savedMessages = messages.stream()
            .map(message -> messageToMap(messageFromMap(name, owner, message, maxImageBytes)))
            .collect(Collectors.toList());
          var snapshot = sessionToMap(session, savedMessages);
          enforceSessionSize(snapshot);
          return updateSessionStore(owner, name, data -> data.put("session", writeMapValue(snapshot)))
            .then(ServerResponse.ok().bodyValue(snapshot));
        });
      });
  }

  private Mono<ServerResponse> deleteSession(ServerRequest request) {
    var name = safeName("chat", request.pathVariable("name"));
    return owner(request)
      .flatMap(owner -> updateStore(owner, data -> data.remove(sessionKey(name)))
        .then(deleteSessionStore(owner, name)))
      .then(ServerResponse.noContent().build());
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
    return Mono.zip(owner(request), request.bodyToMono(Map.class).map(this::castMap))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var body = tuple.getT2();
        body.putAll(requestAuditMeta(request));
        var log = callLogToMap(callLogFromMap(owner, body));
        return saveLog(owner, log)
          .then(ServerResponse.ok().bodyValue(log));
      });
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
    return Mono.zip(owner(request), request.bodyToMono(Map.class).map(this::castMap))
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
    return globalSettings().flatMap(settings -> ServerResponse.ok().bodyValue(settings));
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
      return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read DOMPurify asset."));
    }
  }

  private Mono<ServerResponse> saveSettings(ServerRequest request) {
    return Mono.zip(owner(request), request.bodyToMono(Map.class).map(this::castMap))
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var settings = validateSettings(tuple.getT2());
        return updateStore(owner, data -> data.put(SETTINGS_KEY, writeMapValue(settings)))
          .then(ServerResponse.ok().bodyValue(settings));
      });
  }

  private Mono<ServerResponse> createChatJob(ServerRequest request) {
    return Mono.zip(owner(request), request.bodyToMono(Map.class).map(this::castMap), globalSettings())
      .flatMap(tuple -> {
        var owner = tuple.getT1();
        var body = tuple.getT2();
        var globalSettings = tuple.getT3();
        return settingsFor(owner).flatMap(settings -> {
          var sessionBody = castMapValue(body.get("session"));
          var sessionId = safeName("chat", stringValue(sessionBody.get("id")));
          if (sessionId.isBlank()) {
            throw badRequest("Session id is required.");
          }
          var assistant = castMapValue(body.get("assistant"));
          var assistantId = stringValue(assistant.get("id"));
          if (assistantId.isBlank()) {
            throw badRequest("Assistant message id is required.");
          }
          var model = stringValue(body.get("model"));
          if (model.isBlank()) {
            throw badRequest("Model is required.");
          }
          enforceAllowedModel(model, globalSettings);
          var requestMessages = listOfMaps(body.get("requestMessages"));
          if (requestMessages.isEmpty()) {
            throw badRequest("Request messages are required.");
          }
          validateAiRequestMessages(requestMessages, globalSettings);
          var normalizedSession = normalizeSessionSnapshot(sessionId, owner, sessionBody, maxImageBytes(settings));
          var jobId = nextJobId();
          var promptTokens = estimateRequestTokens(requestMessages);
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
          var headers = headersForBackground(request);
          return reserveUsage(owner, globalSettings, promptTokens, jobId)
            .flatMap(reservation -> saveJob(owner, jobId, job)
            .then(updateSessionStore(owner, sessionId, data -> data.put("session", writeMapValue(normalizedSession))))
            .doOnSuccess(ignored -> runChatJob(owner, sessionId, assistantId, jobId, model,
              requestMessages, promptTokens, headers, globalSettings, reservation))
            .then(ServerResponse.ok().bodyValue(job))
            .onErrorResume(error -> releaseUsageReservation(reservation).then(Mono.error(error))));
        });
      });
  }

  private Mono<ServerResponse> createImageJob(ServerRequest request) {
    return Mono.zip(owner(request), request.bodyToMono(Map.class).map(this::castMap), globalSettings())
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
          throw badRequest("Image job requires session id, assistant id, model, and prompt.");
        }
        enforceAllowedModel(model, globalSettings);
        validateImagePayload(payload, globalSettings);
        var normalizedSession = normalizeSessionSnapshot(sessionId, owner, sessionBody, maxImageBytes(globalSettings));
        var jobId = nextJobId();
        var now = System.currentTimeMillis();
        var promptTokens = estimateImagePromptTokens(prompt, payload);
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
        var headers = headersForBackground(request);
        var aiPayload = toAiFoundationImagePayload(payload);
        return reserveUsage(owner, globalSettings, promptTokens, jobId)
          .flatMap(reservation -> saveJob(owner, jobId, job)
          .then(updateSessionStore(owner, sessionId, data -> data.put("session", writeMapValue(normalizedSession))))
          .doOnSuccess(ignored -> runImageJobV2(owner, sessionId, assistantId, jobId, model, aiPayload,
            promptTokens, headers, globalSettings, reservation))
          .then(ServerResponse.ok().bodyValue(job))
          .onErrorResume(error -> releaseUsageReservation(reservation).then(Mono.error(error))));
      });
  }

  private Mono<ServerResponse> getJob(ServerRequest request) {
    var jobId = safeName("job", request.pathVariable("name"));
    return owner(request).flatMap(owner -> fetchJob(owner, jobId)
      .flatMap(job -> ServerResponse.ok().bodyValue(job))
      .switchIfEmpty(ServerResponse.notFound().build()));
  }

  private Mono<ServerResponse> jobEvents(ServerRequest request) {
    var jobId = safeName("job", request.pathVariable("name"));
    return owner(request).flatMap(owner -> fetchJob(owner, jobId).flatMap(initial -> {
      var key = runningJobKey(owner, jobId);
      var sink = jobSink(key);
      var events = Flux.concat(Mono.just(initial), sink.asFlux())
        .map(job -> ServerSentEvent.builder(job)
          .event("job")
          .id(stringValue(job.get("id")))
          .build())
        .doFinally(signal -> {
          if (isTerminalJobStatus(initial.get("status"))) {
            jobEventSinks.remove(key, sink);
          }
        });
      return ServerResponse.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(events, new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {});
    }).switchIfEmpty(ServerResponse.notFound().build()));
  }

  private Sinks.Many<Map<String, Object>> jobSink(String key) {
    return jobEventSinks.computeIfAbsent(key, ignored ->
      Sinks.many().multicast().directBestEffort());
  }

  private void emitJobEvent(String owner, String jobId, Map<String, Object> job) {
    var key = runningJobKey(owner, jobId);
    var sink = jobEventSinks.get(key);
    if (sink == null) {
      return;
    }
    sink.tryEmitNext(new LinkedHashMap<>(job));
    if (isTerminalJobStatus(job.get("status"))) {
      sink.tryEmitComplete();
      jobEventSinks.remove(key, sink);
    }
  }

  private Map<String, Object> transientJobEvent(String jobId, Integer promptTokens, Map<String, Object> state,
    String status, String error) {
    var completionTokens = estimateTokens(stringValue(state.get("reasoning")) + "\n" + stringValue(state.get("content")));
    var totalTokens = (promptTokens == null ? 0 : promptTokens) + completionTokens;
    var job = new LinkedHashMap<String, Object>();
    job.put("id", jobId);
    job.put("status", status);
    job.put("error", error);
    job.put("updatedAt", System.currentTimeMillis());
    job.put("content", state.get("content"));
    job.put("reasoning", state.get("reasoning"));
    job.put("reasoningOpen", state.get("reasoningOpen"));
    job.put("images", listOfStrings(state.get("images")));
    job.put("promptTokens", promptTokens);
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
    return owner(request).flatMap(owner -> {
      var key = runningJobKey(owner, jobId);
      var disposable = runningJobs.remove(key);
      if (disposable != null && !disposable.isDisposed()) {
        disposable.dispose();
      }
      var state = runningJobStates.remove(key);
      var flush = state == null
        ? Mono.<Void>empty()
        : updateJobAndSession(owner, jobId, state, "cancelled", "Cancelled by user.");
      return flush.then(updateJob(owner, jobId, job -> {
          if (!stringValue(job.get("id")).isBlank()) {
            job.put("status", "cancelled");
            job.put("error", "Cancelled by user.");
            job.put("updatedAt", System.currentTimeMillis());
          }
        })
        .then(fetchJob(owner, jobId).flatMap(job -> releasePersistentUsageReservation(new UsageReservation(owner,
          dayKey(nullToZero(longValue(job.get("createdAt"))) > 0
            ? nullToZero(longValue(job.get("createdAt")))
            : System.currentTimeMillis()),
          intValue(job.get("promptTokens")) == null ? 0 : intValue(job.get("promptTokens")),
          jobId))))
        .then(ServerResponse.ok().bodyValue(Map.of("id", jobId, "status", "cancelled"))));
    });
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
        if (!"running".equals(status) && !"pending".equals(status)) {
          return Mono.<Void>empty();
        }
        var owner = stringValue(job.get("owner"));
        var jobId = stringValue(job.get("id"));
        if (owner.isBlank() || jobId.isBlank()) {
          return Mono.<Void>empty();
        }
        var state = new LinkedHashMap<String, Object>();
        state.put("_type", stringValue(job.get("type")));
        state.put("_sessionId", stringValue(job.get("sessionId")));
        state.put("_assistantId", stringValue(job.get("assistantId")));
        state.put("_jobId", jobId);
        state.put("_promptTokens", intValue(job.get("promptTokens")));
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
        if (!"running".equals(status) && !"pending".equals(status)) {
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
          if (alive && now - heartbeatAt <= Duration.ofMinutes(10).toMillis()) {
            return Mono.<Void>empty();
          }
          var state = new LinkedHashMap<String, Object>();
          state.put("_type", stringValue(job.get("type")));
          state.put("_sessionId", stringValue(job.get("sessionId")));
          state.put("_assistantId", stringValue(job.get("assistantId")));
          state.put("_jobId", jobId);
          state.put("_promptTokens", intValue(job.get("promptTokens")));
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
              promptTokens == null ? 0 : promptTokens, jobId)));
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
          throw badRequest("Image file is required.");
        }
        var mediaType = file.headers().getContentType();
        if (mediaType == null || !mediaType.toString().startsWith("image/")) {
          throw badRequest("Only image uploads are supported.");
        }
        var declaredLength = file.headers().getContentLength();
        if (declaredLength > maxImageBytes || declaredLength > HARD_MAX_IMAGE_BYTES) {
          throw badRequest("Image file exceeds the configured size limit.");
        }
        return DataBufferUtils.join(file.content(), (int) Math.min(maxImageBytes, HARD_MAX_IMAGE_BYTES) + 1)
          .onErrorMap(DataBufferLimitException.class, e -> badRequest("Image file exceeds the configured size limit."))
          .flatMap(buffer -> {
            try {
              var bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              if (bytes.length > maxImageBytes || bytes.length > HARD_MAX_IMAGE_BYTES) {
                throw badRequest("Image file exceeds the configured size limit.");
              }
              return forwardAttachmentUpload(request, file, bytes, mediaType);
            } finally {
              DataBufferUtils.release(buffer);
            }
          });
      })));
  }

  private Mono<ServerResponse> forwardAttachmentUpload(ServerRequest request, FilePart file, byte[] bytes,
    MediaType mediaType) {
    var resource = new ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return file.filename();
      }
    };
    var builder = new MultipartBodyBuilder();
    builder.part("file", resource)
      .filename(file.filename())
      .contentType(mediaType);
    var target = baseUrl(request) + "/apis/console.api.storage.halo.run/v1alpha1/attachments/-/upload";
    return WebClient.create().post()
      .uri(target)
      .headers(headers -> {
        copyHeader(request, headers, HttpHeaders.COOKIE);
        copyHeader(request, headers, "X-XSRF-TOKEN");
        copyHeader(request, headers, HttpHeaders.AUTHORIZATION);
      })
      .contentType(MediaType.MULTIPART_FORM_DATA)
      .body(BodyInserters.fromMultipartData(builder.build()))
      .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
        .flatMap(body -> ServerResponse.status(response.statusCode())
          .contentType(response.headers().contentType().orElse(MediaType.APPLICATION_JSON))
          .bodyValue(body)));
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
    return request.principal().map(Principal::getName).defaultIfEmpty("anonymous");
  }

  private Mono<List<Map<String, Object>>> legacyRestItems(ServerRequest request, String plural) {
    return legacyRestItemsPage(request, plural, 1, new ArrayList<>());
  }

  private Mono<List<Map<String, Object>>> legacyRestItemsPage(ServerRequest request, String plural, int page,
    List<Map<String, Object>> accumulated) {
    var target = baseUrl(request) + "/apis/halo-ai-console.halo.run/v1alpha1/" + plural
      + "?page=" + page + "&size=25";
    return WebClient.builder()
      .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize((int) HARD_MAX_IMAGE_BYTES))
      .build()
      .get()
      .uri(target)
      .headers(headers -> {
        copyHeader(request, headers, HttpHeaders.COOKIE);
        copyHeader(request, headers, "X-XSRF-TOKEN");
        copyHeader(request, headers, HttpHeaders.AUTHORIZATION);
      })
      .retrieve()
      .bodyToMono(Map.class)
      .map(this::castMap)
      .flatMap(body -> {
        accumulated.addAll(listOfMaps(body.get("items")));
        if (Boolean.TRUE.equals(booleanValue(body.get("hasNext")))) {
          return legacyRestItemsPage(request, plural, page + 1, accumulated);
        }
        return Mono.just(accumulated);
      });
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
    List<Map<String, Object>> requestMessages, Integer promptTokens, Map<String, String> headers,
    Map<String, Object> globalSettings, UsageReservation reservation) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("id", "server-" + jobId);
    payload.put("trigger", "submit-message");
    payload.put("messages", requestMessages);
    payload.put("maxOutputTokens", 4096);
    var state = new LinkedHashMap<String, Object>();
    state.put("content", "");
    state.put("reasoning", "");
    state.put("reasoningOpen", true);
    state.put("maxOutputCharacters", clampInt(globalSettings.get("maxOutputCharacters"), 4000, MAX_STREAM_TEXT_LENGTH, MAX_STREAM_TEXT_LENGTH));
    state.put("_type", "chat");
    state.put("_sessionId", sessionId);
    state.put("_assistantId", assistantId);
    state.put("_jobId", jobId);
    state.put("_promptTokens", promptTokens);
    var key = runningJobKey(owner, jobId);
    runningJobStates.put(key, state);
    var disposable = postAiStream(model, List.of("chat/ui-message/stream", "test-chat/ui-message/stream"), payload, headers, 0)
      .concatMap(chunk -> applyChatChunk(owner, sessionId, assistantId, jobId, promptTokens, state, chunk))
      .then(Mono.defer(() -> markChatJobFinished(owner, sessionId, assistantId, jobId, promptTokens, state, "success", "")))
      .onErrorResume(error -> markChatJobFinished(owner, sessionId, assistantId, jobId, promptTokens, state,
        "error", limitString(error.getMessage(), 4000)))
      .doFinally(signal -> {
        runningJobs.remove(key);
        runningJobStates.remove(key);
        releaseUsageReservation(reservation).subscribe();
      })
      .subscribe();
    runningJobs.put(key, disposable);
    if (disposable.isDisposed()) {
      runningJobs.remove(key, disposable);
      runningJobStates.remove(key);
    }
  }

  private void runImageJob(String owner, String sessionId, String assistantId, String jobId, String model,
    Map<String, Object> payload, Integer promptTokens, Map<String, String> headers,
    Map<String, Object> globalSettings, UsageReservation reservation) {
    runImageJobV2(owner, sessionId, assistantId, jobId, model, payload, promptTokens, headers, globalSettings, reservation);
    /*
    var state = new LinkedHashMap<String, Object>();
    state.put("content", "正在生成图像...");
    state.put("images", new ArrayList<String>());
    state.put("_type", "image");
    state.put("_sessionId", sessionId);
    state.put("_assistantId", assistantId);
    state.put("_jobId", jobId);
    state.put("_promptTokens", promptTokens);
    var key = runningJobKey(owner, jobId);
    runningJobStates.put(key, state);
    var stream = postAiStream(model, List.of("test-image-generation"), payload, headers, 0)
      .concatMap(chunk -> applyImageChunk(owner, sessionId, assistantId, jobId, promptTokens, state, chunk))
      .then(Mono.defer(() -> {
        if (listOfStrings(state.get("images")).isEmpty()) {
          return postAiJson(model, List.of("test-image-generation"), payload, headers, 0)
            .flatMap(parsed -> {
              mergeImages(state, collectGeneratedImages(parsed));
              if (listOfStrings(state.get("images")).isEmpty()) {
                state.put("content", "图像模型完成了请求，但没有返回图像。");
              } else {
                state.put("content", "已生成图像：");
              }
              return updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "success", "");
            });
        }
        state.put("content", "已生成图像：");
        return updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "success", "");
      }));
    var disposable = stream
      .onErrorResume(error -> updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state,
        "error", limitString(error.getMessage(), 4000)))
      .doFinally(signal -> {
        runningJobs.remove(key);
        runningJobStates.remove(key);
        releaseUsageReservation(reservation).subscribe();
      })
      .subscribe();
    runningJobs.put(key, disposable);
    if (disposable.isDisposed()) {
      runningJobs.remove(key, disposable);
      runningJobStates.remove(key);
    }
    */
  }

  private void runImageJobV2(String owner, String sessionId, String assistantId, String jobId, String model,
    Map<String, Object> payload, Integer promptTokens, Map<String, String> headers,
    Map<String, Object> globalSettings, UsageReservation reservation) {
    var state = new LinkedHashMap<String, Object>();
    state.put("content", "正在生成图像...");
    state.put("images", new ArrayList<String>());
    state.put("_type", "image");
    state.put("_sessionId", sessionId);
    state.put("_assistantId", assistantId);
    state.put("_jobId", jobId);
    state.put("_promptTokens", promptTokens);
    var key = runningJobKey(owner, jobId);
    runningJobStates.put(key, state);
    var maxImageBytes = maxImageBytes(globalSettings);
    var stream = updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "running", "")
      .then(postAiJson(model, List.of("test-image-generation", "image-generation"), payload, headers, 0))
      .flatMap(parsed -> {
        mergeImages(state, collectGeneratedImages(parsed, maxImageBytes), maxImageBytes);
        if (listOfStrings(state.get("images")).isEmpty()) {
          state.put("content", "图像模型完成了请求，但没有返回图像。");
        } else {
          state.put("content", "已生成图像：");
        }
        return updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "success", "");
      });
    var disposable = stream
      .onErrorResume(error -> updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state,
        "error", limitString(cleanAiFoundationError(error), 4000)))
      .doFinally(signal -> {
        runningJobs.remove(key);
        runningJobStates.remove(key);
        releaseUsageReservation(reservation).subscribe();
      })
      .subscribe();
    runningJobs.put(key, disposable);
    if (disposable.isDisposed()) {
      runningJobs.remove(key, disposable);
      runningJobStates.remove(key);
    }
  }

  private Flux<String> postAiStream(String model,
    List<String> paths, Map<String, Object> payload, Map<String, String> headers, int index) {
    if (index >= paths.size()) {
      return Flux.error(new IllegalStateException("No compatible AI Foundation chat endpoint is available."));
    }
    var target = headers.getOrDefault("_baseUrl", "") + "/apis/console.api.aifoundation.halo.run/v1alpha1/models/"
      + java.net.URLEncoder.encode(model, java.nio.charset.StandardCharsets.UTF_8) + "/" + paths.get(index);
    return WebClient.create().post()
      .uri(target)
      .headers(httpHeaders -> {
        putHeaderIfPresent(headers, httpHeaders, HttpHeaders.COOKIE);
        putHeaderIfPresent(headers, httpHeaders, "X-XSRF-TOKEN");
        putHeaderIfPresent(headers, httpHeaders, HttpHeaders.AUTHORIZATION);
      })
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(payload)
      .exchangeToFlux(response -> {
        var status = response.statusCode().value();
        if ((status == 404 || status == 405) && index + 1 < paths.size()) {
          return response.releaseBody().thenMany(postAiStream(model, paths, payload, headers, index + 1));
        }
        if (response.statusCode().isError()) {
          return response.bodyToMono(String.class).defaultIfEmpty("")
            .flatMapMany(text -> Flux.error(new IllegalStateException(text.isBlank()
              ? "AI Foundation returned " + response.statusCode().value()
              : text)));
        }
        return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
          .map(event -> {
            var frame = new StringBuilder();
            if (event.event() != null && !event.event().isBlank()) {
              frame.append("event:").append(event.event()).append('\n');
            }
            if (event.data() != null) {
              frame.append("data:").append(event.data()).append('\n');
            }
            frame.append('\n');
            return frame.toString();
          });
      });
  }

  private Mono<Map<String, Object>> postAiJson(String model, List<String> paths, Map<String, Object> payload,
    Map<String, String> headers, int index) {
    if (index >= paths.size()) {
      return Mono.error(new IllegalStateException("No compatible AI Foundation image endpoint is available."));
    }
    var target = headers.getOrDefault("_baseUrl", "") + "/apis/console.api.aifoundation.halo.run/v1alpha1/models/"
      + java.net.URLEncoder.encode(model, java.nio.charset.StandardCharsets.UTF_8) + "/" + paths.get(index);
    return WebClient.builder()
      .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize((int) HARD_MAX_IMAGE_BYTES))
      .build()
      .post()
      .uri(target)
      .headers(httpHeaders -> {
        putHeaderIfPresent(headers, httpHeaders, HttpHeaders.COOKIE);
        putHeaderIfPresent(headers, httpHeaders, "X-XSRF-TOKEN");
        putHeaderIfPresent(headers, httpHeaders, HttpHeaders.AUTHORIZATION);
      })
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(payload)
      .exchangeToMono(response -> {
        var status = response.statusCode().value();
        if ((status == 404 || status == 405) && index + 1 < paths.size()) {
          return response.releaseBody().then(postAiJson(model, paths, payload, headers, index + 1));
        }
        if (response.statusCode().isError()) {
          return response.bodyToMono(String.class).defaultIfEmpty("")
            .flatMap(text -> Mono.error(new IllegalStateException(text.isBlank()
              ? "AI Foundation returned " + response.statusCode().value()
              : text)));
        }
        return response.bodyToMono(Map.class).map(this::castMap);
      });
  }

  private Mono<Void> applyChatChunk(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state, String chunk) {
    var combined = (stringValue(state.remove("_sseBuffer")) + String.valueOf(chunk)).replace("\r\n", "\n");
    var frames = combined.split("\n\n", -1);
    var completeFrames = frames.length;
    if (!combined.endsWith("\n\n")) {
      completeFrames = Math.max(0, frames.length - 1);
      state.put("_sseBuffer", frames[frames.length - 1]);
    }
    for (var i = 0; i < completeFrames; i++) {
      var frame = frames[i];
      applySseFrameToState(state, frame);
    }
    emitJobEvent(owner, jobId, transientJobEvent(jobId, promptTokens, state, "running", ""));
    if (!shouldPersistRunningState(state)) {
      return Mono.empty();
    }
    return updateChatJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "running", "");
  }

  private Mono<Void> applyImageChunk(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state, String chunk) {
    for (var payload : ssePayloads(state, chunk)) {
      if (payload.isBlank() || "[DONE]".equals(payload) || "DONE".equals(payload)) {
        continue;
      }
      try {
        var parsed = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        mergeImages(state, collectGeneratedImages(parsed));
        var message = firstNonBlank(parsed.get("message"), parsed.get("status"), parsed.get("progress"));
        if (message != null && !String.valueOf(message).isBlank()) {
          state.put("content", limitString(String.valueOf(message), 2000));
        }
        if (!listOfStrings(state.get("images")).isEmpty()) {
          state.put("content", "正在接收图像...");
        }
      } catch (IllegalStateException | ResponseStatusException e) {
        throw e;
      } catch (Exception e) {
        state.put("content", limitString(payload, 2000));
      }
    }
    return updateImageJobAndSession(owner, sessionId, assistantId, jobId, promptTokens, state, "running", "");
  }

  private Mono<Void> markChatJobFinished(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state, String status, String error) {
    var pending = stringValue(state.remove("_sseBuffer"));
    if ("success".equals(status) && !pending.isBlank()) {
      applySseFrameToState(state, pending);
    }
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
      var completionTokens = estimateTokens(stringValue(state.get("reasoning")) + "\n" + stringValue(state.get("content")));
      var totalTokens = (promptTokens == null ? 0 : promptTokens) + completionTokens;
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
      job.put("promptTokens", promptTokens);
      job.put("completionTokens", completionTokens);
      job.put("totalTokens", totalTokens);
      Mono<Void> saveLog = Mono.empty();
      if (!"running".equals(status) && !Boolean.TRUE.equals(job.get("logged"))) {
        job.put("logged", true);
        var log = new LinkedHashMap<String, Object>();
        log.put("owner", owner);
        log.put("sessionId", sessionId);
        log.put("sessionTitle", session.get("title"));
        log.put("type", "chat");
        log.put("operation", "chat");
        log.put("model", job.get("model"));
        log.put("status", status);
        log.put("error", error);
        log.put("time", now);
        log.put("durationMs", Math.max(0L, now - nullToZero(longValue(job.get("createdAt")))));
        log.put("promptTokens", promptTokens);
        log.put("completionTokens", completionTokens);
        log.put("totalTokens", totalTokens);
        copyAuditFields(job, log);
        saveLog = saveLog(owner, log).then(incrementDailyUsage(owner, dayKey(now), totalTokens));
      }
      var savedJob = new LinkedHashMap<String, Object>(job);
      return saveJob(owner, jobId, savedJob).then(saveLog).then(updateSessionStore(owner, sessionId, data -> {
      var messages = listOfMaps(session.get("messages"));
      var found = false;
      for (var message : messages) {
        if (assistantId.equals(stringValue(message.get("id")))) {
          message.put("content", state.get("content"));
          message.put("reasoning", state.get("reasoning"));
          message.put("reasoningOpen", state.get("reasoningOpen"));
          message.put("updatedAt", now);
          message.put("promptTokens", promptTokens);
          message.put("completionTokens", completionTokens);
          message.put("totalTokens", totalTokens);
          found = true;
          break;
        }
      }
      if (!found) {
        var assistant = new LinkedHashMap<String, Object>();
        assistant.put("id", assistantId);
        assistant.put("role", "assistant");
        assistant.put("content", state.get("content"));
        assistant.put("reasoning", state.get("reasoning"));
        assistant.put("reasoningOpen", state.get("reasoningOpen"));
        assistant.put("createdAt", now);
        assistant.put("updatedAt", now);
        assistant.put("promptTokens", promptTokens);
        assistant.put("completionTokens", completionTokens);
        assistant.put("totalTokens", totalTokens);
        messages.add(assistant);
      }
      session.put("messages", messages);
      session.put("updatedAt", now);
      enforceSessionSize(session);
      data.put("session", writeMapValue(session));
      })).then(Mono.fromRunnable(() -> emitJobEvent(owner, jobId, savedJob)));
    }));
  }

  private Mono<Void> updateImageJobAndSession(String owner, String sessionId, String assistantId, String jobId,
    Integer promptTokens, Map<String, Object> state, String status, String error) {
    return fetchSessionSnapshot(owner, sessionId).flatMap(session -> fetchJob(owner, jobId).flatMap(job -> {
      var now = System.currentTimeMillis();
      var images = listOfStrings(state.get("images")).stream().limit(MAX_IMAGES_PER_MESSAGE).collect(Collectors.toList());
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
      job.put("promptTokens", promptTokens);
      job.put("completionTokens", 0);
      job.put("totalTokens", promptTokens == null ? 0 : promptTokens);
      Mono<Void> saveLog = Mono.empty();
      if (!"running".equals(status) && !Boolean.TRUE.equals(job.get("logged"))) {
        job.put("logged", true);
        var log = new LinkedHashMap<String, Object>();
        log.put("owner", owner);
        log.put("sessionId", sessionId);
        log.put("sessionTitle", session.get("title"));
        log.put("type", "image");
        log.put("operation", "image");
        log.put("model", job.get("model"));
        log.put("status", status);
        log.put("error", error);
        log.put("time", now);
        log.put("durationMs", Math.max(0L, now - nullToZero(longValue(job.get("createdAt")))));
        log.put("promptTokens", promptTokens);
        log.put("completionTokens", 0);
        log.put("totalTokens", promptTokens == null ? 0 : promptTokens);
        copyAuditFields(job, log);
        saveLog = saveLog(owner, log)
          .then(incrementDailyUsage(owner, dayKey(now), promptTokens == null ? 0 : promptTokens));
      }
      var savedJob = new LinkedHashMap<String, Object>(job);
      return saveJob(owner, jobId, savedJob).then(saveLog).then(updateSessionStore(owner, sessionId, data -> {
      var messages = listOfMaps(session.get("messages"));
      var found = false;
      for (var message : messages) {
        if (assistantId.equals(stringValue(message.get("id")))) {
          message.put("content", limitString(stringValue(state.get("content")), MAX_CONTENT_LENGTH));
          message.put("images", images);
          message.put("streaming", "running".equals(status));
          message.put("updatedAt", now);
          message.put("promptTokens", promptTokens);
          message.put("completionTokens", 0);
          message.put("totalTokens", promptTokens == null ? 0 : promptTokens);
          found = true;
          break;
        }
      }
      if (!found) {
        var assistant = new LinkedHashMap<String, Object>();
        assistant.put("id", assistantId);
        assistant.put("role", "assistant");
        assistant.put("content", limitString(stringValue(state.get("content")), MAX_CONTENT_LENGTH));
        assistant.put("images", images);
        assistant.put("createdAt", now);
        assistant.put("updatedAt", now);
        assistant.put("promptTokens", promptTokens);
        assistant.put("completionTokens", 0);
        assistant.put("totalTokens", promptTokens == null ? 0 : promptTokens);
        messages.add(assistant);
      }
      session.put("messages", messages);
      session.put("updatedAt", now);
      enforceSessionSize(session);
      data.put("session", writeMapValue(session));
      })).then(Mono.fromRunnable(() -> emitJobEvent(owner, jobId, savedJob)));
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
    spec.setCreatedAt(longOrNow(body.get("createdAt")));
    spec.setUpdatedAt(longOrNow(body.get("updatedAt")));
    session.setSpec(spec);
    return session;
  }

  private AiChatMessage messageFromMap(String sessionId, String owner, Map<String, Object> body, long maxImageBytes) {
    var id = stringValue(body.get("id"));
    if (id.isBlank()) {
      throw badRequest("Message id is required.");
    }
    var role = stringValue(body.get("role"));
    if (!ROLES.contains(role)) {
      throw badRequest("Unsupported message role.");
    }
    var message = new AiChatMessage();
    message.setMetadata(metadata(messageName(sessionId, id)));
    var spec = new AiChatMessage.MessageSpec();
    spec.setId(id);
    spec.setOwner(owner);
    spec.setSessionId(sessionId);
    spec.setRole(role);
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
    spec.setCreatedAt(longOrNow(body.get("createdAt")));
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
    return client.fetch(ConfigMap.class, storeName(owner))
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
    return client.fetch(ConfigMap.class, sessionStoreName(owner, sessionId))
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
    return client.fetch(ConfigMap.class, sessionStoreName(owner, sessionId))
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("session")))
      .filter(session -> !stringValue(session.get("id")).isBlank())
      .switchIfEmpty(fetchStore(owner)
        .map(store -> readMapValue(store.getData().get(sessionKey(sessionId))))
        .filter(session -> !stringValue(session.get("id")).isBlank()))
      .switchIfEmpty(Mono.defer(() -> {
        var session = new LinkedHashMap<String, Object>();
        session.put("id", sessionId);
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

  private Mono<Void> saveJob(String owner, String jobId, Map<String, Object> job) {
    var name = jobStoreName(owner, jobId);
    return client.fetch(ConfigMap.class, name)
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
          throw new IllegalStateException("Job snapshot is too large to save safely.");
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
    return client.fetch(ConfigMap.class, jobStoreName(owner, jobId))
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("job")))
      .filter(job -> !stringValue(job.get("id")).isBlank())
      .switchIfEmpty(fetchStore(owner)
        .map(store -> readMapValue(store.getData().get(jobKey(jobId))))
        .filter(job -> !stringValue(job.get("id")).isBlank()));
  }

  private Mono<Void> saveLog(String owner, Map<String, Object> log) {
    var time = nullToZero(longValue(log.get("time")));
    var name = LOG_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("log", time + "-" + UUID.randomUUID());
    var configMap = new ConfigMap();
    configMap.setMetadata(metadata(name));
    configMap.setData(new LinkedHashMap<>(Map.of("log", writeMapValue(log))));
    return client.create(configMap).then();
  }

  private Mono<Void> cleanupExpiredRecords() {
    return globalSettings().flatMap(settings -> {
      var now = System.currentTimeMillis();
      var jobRetentionDays = clampInt(settings.get("jobRetentionDays"), 1, 365, 7);
      var logRetentionDays = clampInt(settings.get("logRetentionDays"), 7, 3650, 90);
      var maxJobsPerUser = clampInt(settings.get("maxJobsPerUser"), 50, 10000, 500);
      var jobCutoff = now - Duration.ofDays(jobRetentionDays).toMillis();
      var logCutoff = now - Duration.ofDays(logRetentionDays).toMillis();
      return deleteExpiredJobs(jobCutoff)
        .then(deleteExcessJobs(maxJobsPerUser))
        .then(deleteExpiredLogs(logCutoff))
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
    var value = stringValue(status);
    return !value.isBlank() && !"running".equals(value) && !"pending".equals(value);
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
    return client.fetch(ConfigMap.class, sessionStoreName(owner, sessionId))
      .flatMap(client::delete)
      .then();
  }

  private Flux<Map<String, Object>> sessionStoreSessions(String owner) {
    var prefix = SESSION_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-";
    return client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(prefix),
        Comparator.comparing(this::idOf)
      )
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("session")))
      .filter(session -> !stringValue(session.get("id")).isBlank());
  }

  private String storeName(String owner) {
    return STORE_CONFIG_MAP_PREFIX + safeName("owner", owner);
  }

  private String sessionStoreName(String owner, String sessionId) {
    return SESSION_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("chat", sessionId);
  }

  private String jobStoreName(String owner, String jobId) {
    return JOB_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("job", jobId);
  }

  private String usageStoreName(String owner, String day) {
    return USAGE_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-" + safeName("day", day);
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
    var currentLogs = client.list(
        ConfigMap.class,
        item -> item.getMetadata() != null && idOf(item).startsWith(LOG_CONFIG_MAP_PREFIX + safeName("owner", owner) + "-"),
        Comparator.comparing(this::idOf)
      )
      .map(configMap -> readMapValue(configMap.getData() == null ? null : configMap.getData().get("log")));
    var legacyLogs = fetchStore(owner)
      .flatMapMany(store -> Flux.fromIterable(store.getData().entrySet()))
      .filter(entry -> entry.getKey().startsWith(LOG_KEY_PREFIX))
      .map(entry -> readMapValue(entry.getValue()));
    return currentLogs.concatWith(legacyLogs);
  }

  private Mono<Integer> currentDailyUsage(String owner, String day) {
    return client.fetch(ConfigMap.class, usageStoreName(owner, day))
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
    return client.fetch(ConfigMap.class, name)
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
        settings.put("imageMaxSizeMb", tuple.getT2().get("imageMaxSizeMb"));
        return settings;
      });
  }

  private Mono<Map<String, Object>> globalSettings() {
    return client.fetch(ConfigMap.class, GLOBAL_CONFIG_MAP)
      .map(configMap -> {
        var data = configMap.getData();
        if (data == null) {
          return validateGlobalSettings(new LinkedHashMap<>());
        }
        var grouped = readMapValue(data.get(GLOBAL_CONFIG_GROUP));
        if (!grouped.isEmpty()) {
          return validateGlobalSettings(grouped);
        }
        var flat = new LinkedHashMap<String, Object>();
        data.forEach(flat::put);
        return validateGlobalSettings(flat);
      })
      .switchIfEmpty(Mono.fromSupplier(() -> validateGlobalSettings(new LinkedHashMap<>())));
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
    settings.put("imageMaxSizeMb", clampInt(source.get("imageMaxSizeMb"), 1, 50, 8));
    settings.put("maxConcurrentJobs", clampInt(source.get("maxConcurrentJobs"), 1, 20, 2));
    settings.put("requestsPerMinute", clampInt(source.get("requestsPerMinute"), 1, 300, 12));
    settings.put("dailyTokenLimit", clampInt(source.get("dailyTokenLimit"), 1000, 10_000_000, 200_000));
    settings.put("maxContextMessages", clampInt(source.get("maxContextMessages"), 4, 100, MAX_REQUEST_MESSAGES));
    settings.put("maxContextCharacters", clampInt(source.get("maxContextCharacters"), 4000, 200_000, MAX_REQUEST_CHARS));
    settings.put("maxOutputCharacters", clampInt(source.get("maxOutputCharacters"), 4000, MAX_STREAM_TEXT_LENGTH, MAX_STREAM_TEXT_LENGTH));
    settings.put("maxImagesPerRequest", clampInt(source.get("maxImagesPerRequest"), 0, 50, MAX_REQUEST_IMAGES));
    settings.put("jobRetentionDays", clampInt(source.get("jobRetentionDays"), 1, 365, 7));
    settings.put("logRetentionDays", clampInt(source.get("logRetentionDays"), 7, 3650, 90));
    settings.put("maxJobsPerUser", clampInt(source.get("maxJobsPerUser"), 50, 10000, 500));
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
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This model is not allowed by plugin settings.");
    }
  }

  private Mono<UsageReservation> reserveUsage(String owner, Map<String, Object> globalSettings, Integer promptTokens,
    String jobId) {
    var maxConcurrent = clampInt(globalSettings.get("maxConcurrentJobs"), 1, 20, 2);
    var perMinute = clampInt(globalSettings.get("requestsPerMinute"), 1, 300, 12);
    var dailyLimit = clampInt(globalSettings.get("dailyTokenLimit"), 1000, 10_000_000, 200_000);
    var prompt = Math.max(0, promptTokens == null ? 0 : promptTokens);
    var now = System.currentTimeMillis();
    var day = dayKey(now);
    var reservation = new UsageReservation(owner, day, prompt, jobId);
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
      if (running >= maxConcurrent) {
        throw tooManyRequests("Too many concurrent AI jobs.");
      }
      if (requestTimes.size() >= perMinute) {
        throw tooManyRequests("Too many AI requests in one minute.");
      }
      if (consumed + reservedTokens + prompt > dailyLimit) {
        throw tooManyRequests("Daily AI token quota exceeded.");
      }
      var item = new LinkedHashMap<String, Object>();
      item.put("tokens", prompt);
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
        state.reservedTokens += prompt;
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
        state.reservedTokens = Math.max(0, state.reservedTokens - reservation.promptTokens);
      }
    }
    return releasePersistentUsageReservation(reservation);
  }

  private Mono<Void> releasePersistentUsageReservation(UsageReservation reservation) {
    return updateUsageRecord(reservation.owner, reservation.day, data -> {
      var reservations = readMapValue(data.get("reservations"));
      reservations.remove(safeName("job", reservation.jobId));
      data.put("reservations", writeMapValue(reservations));
      data.put("updatedAt", String.valueOf(System.currentTimeMillis()));
    }).then().onErrorResume(error -> Mono.empty());
  }

  private Mono<Void> requireAdminPermission(ServerRequest request) {
    return request.principal()
      .filter(principal -> principal instanceof Authentication authentication && hasAdminPermission(authentication))
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "AI chat admin permission is required.")))
      .then();
  }

  private boolean hasAdminPermission(Authentication authentication) {
    if (authentication == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
      .map(authority -> authority.getAuthority())
      .anyMatch(authority -> authority.contains("plugin:halo-ai-console:admin")
        || authority.contains("plugin:halo-ai-console:call-log-all")
        || authority.contains("role-template-halo-ai-console-admin")
        || authority.contains("super-role")
        || authority.contains("super-admin")
        || authority.contains("administrator"));
  }

  private ResponseStatusException tooManyRequests(String message) {
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
  }

  private String nextJobId() {
    return safeName("job", "job-" + UUID.randomUUID());
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

  private Map<String, String> headersForBackground(ServerRequest request) {
    var headers = new LinkedHashMap<String, String>();
    headers.put("_baseUrl", baseUrl(request));
    request.headers().firstHeader(HttpHeaders.COOKIE);
    var cookie = request.headers().firstHeader(HttpHeaders.COOKIE);
    if (cookie != null) {
      headers.put(HttpHeaders.COOKIE, cookie);
    }
    var xsrf = request.headers().firstHeader("X-XSRF-TOKEN");
    if (xsrf != null) {
      headers.put("X-XSRF-TOKEN", xsrf);
    }
    var authorization = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null) {
      headers.put(HttpHeaders.AUTHORIZATION, authorization);
    }
    return headers;
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

  private void copyAuditFields(Map<String, Object> source, Map<String, Object> target) {
    for (var key : List.of("ipAddress", "userAgent", "browser", "operatingSystem")) {
      var value = source.get(key);
      if (value != null && !String.valueOf(value).isBlank()) {
        target.put(key, value);
      }
    }
  }

  private String clientIp(ServerRequest request) {
    for (var header : List.of("X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP")) {
      var value = stringValue(request.headers().firstHeader(header));
      if (!value.isBlank()) {
        return limitString(value.split(",")[0].trim(), 128);
      }
    }
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

  private void putHeaderIfPresent(Map<String, String> source, HttpHeaders target, String name) {
    var value = source.get(name);
    if (value != null && !value.isBlank()) {
      target.set(name, value);
    }
  }

  private String cleanAiFoundationError(Throwable error) {
    var message = error == null ? "" : stringValue(error.getMessage());
    if (message.isBlank()) {
      return "AI Foundation request failed.";
    }
    var parsed = readMapValue(message);
    var detail = stringValue(firstNonBlank(parsed.get("detail"), parsed.get("title"), parsed.get("message")));
    if (!detail.isBlank()) {
      if (detail.contains("No static resource")) {
        return "当前 AI Foundation 版本没有提供该接口，或所选模型不支持此能力。";
      }
      return detail;
    }
    if (message.contains("No static resource")) {
      return "当前 AI Foundation 版本没有提供该接口，或所选模型不支持此能力。";
    }
    return message;
  }

  private void applySseFrameToState(Map<String, Object> state, String frame) {
    var maxOutput = clampInt(state.get("maxOutputCharacters"), 4000, MAX_STREAM_TEXT_LENGTH, MAX_STREAM_TEXT_LENGTH);
    var data = new StringBuilder();
    var event = "";
    for (var rawLine : String.valueOf(frame).replace("\r\n", "\n").split("\n")) {
      if (rawLine.isBlank() || rawLine.startsWith(":")) {
        continue;
      }
      var separator = rawLine.indexOf(':');
      var field = separator >= 0 ? rawLine.substring(0, separator) : rawLine;
      var value = separator >= 0 ? rawLine.substring(separator + 1).replaceFirst("^ ", "") : "";
      if ("event".equals(field)) {
        event = value;
      }
      if ("data".equals(field)) {
        if (!data.isEmpty()) {
          data.append('\n');
        }
        data.append(value);
      }
    }
    var payload = data.toString().trim();
    if (payload.isBlank()) {
      payload = String.valueOf(frame).trim();
    }
    if (payload.isBlank() || "[DONE]".equals(payload) || "DONE".equals(payload)) {
      return;
    }
    try {
      var parsed = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
      var type = stringValue(parsed.getOrDefault("type", event)).toLowerCase();
      var text = stringValue(firstNonBlank(parsed.get("delta"), parsed.get("text"), parsed.get("content"), parsed.get("data")));
      if (text.isBlank()) {
        return;
      }
      if (type.contains("reasoning")) {
        appendOutputLimited(state, "reasoning", text, maxOutput, "AI output exceeded the maximum length.");
      } else {
        if (!stringValue(state.get("reasoning")).isBlank()) {
          state.put("reasoningOpen", false);
        }
        appendOutputLimited(state, "content", text, maxOutput, "AI output exceeded the maximum length.");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      appendOutputLimited(state, "content", payload, maxOutput, "AI output exceeded the maximum length.");
    }
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

  private String appendLimited(String current, String delta, int maxLength, String error) {
    var next = stringValue(current) + stringValue(delta);
    if (next.length() > maxLength) {
      throw new IllegalStateException(error);
    }
    return next;
  }

  private List<String> ssePayloads(Map<String, Object> state, String chunk) {
    var combined = (stringValue(state.remove("_sseBuffer")) + String.valueOf(chunk)).replace("\r\n", "\n");
    var frames = combined.split("\n\n", -1);
    var completeFrames = frames.length;
    if (!combined.endsWith("\n\n")) {
      completeFrames = Math.max(0, frames.length - 1);
      state.put("_sseBuffer", frames[frames.length - 1]);
    }
    var payloads = new ArrayList<String>();
    for (var i = 0; i < completeFrames; i++) {
      var data = new StringBuilder();
      for (var rawLine : frames[i].split("\n")) {
        if (rawLine.startsWith("data:")) {
          if (!data.isEmpty()) {
            data.append('\n');
          }
          data.append(rawLine.substring(5).replaceFirst("^ ", ""));
        }
      }
      var payload = data.toString().trim();
      if (payload.isBlank()) {
        payload = frames[i].trim();
      }
      payloads.add(payload);
    }
    return payloads;
  }

  private List<String> collectGeneratedImages(Object value) {
    return collectGeneratedImages(value, HARD_MAX_IMAGE_BYTES);
  }

  private List<String> collectGeneratedImages(Object value, long maxImageBytes) {
    var found = new ArrayList<String>();
    collectGeneratedImages(value, found, maxImageBytes);
    return found.stream().filter(item -> !item.isBlank()).distinct().limit(MAX_IMAGES_PER_MESSAGE).collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  private void collectGeneratedImages(Object value, List<String> found) {
    collectGeneratedImages(value, found, HARD_MAX_IMAGE_BYTES);
  }

  @SuppressWarnings("unchecked")
  private void collectGeneratedImages(Object value, List<String> found, long maxImageBytes) {
    if (value instanceof Map<?, ?> map) {
      for (var key : List.of("url", "data", "base64", "b64Json", "b64_json")) {
        var next = map.get(key);
        if (next != null) {
          var reference = normalizeGeneratedImageReference(String.valueOf(next), maxImageBytes);
          if (!reference.isBlank()) {
            found.add(reference);
          }
        }
      }
      map.values().forEach(next -> collectGeneratedImages(next, found, maxImageBytes));
    } else if (value instanceof Iterable<?> iterable) {
      iterable.forEach(next -> collectGeneratedImages(next, found, maxImageBytes));
    } else if (value instanceof String text) {
      var reference = normalizeGeneratedImageReference(text, maxImageBytes);
      if (!reference.isBlank()) {
        found.add(reference);
      }
    }
  }

  private String normalizeGeneratedImageReference(String value) {
    return normalizeGeneratedImageReference(value, HARD_MAX_IMAGE_BYTES);
  }

  private String normalizeGeneratedImageReference(String value, long maxImageBytes) {
    var text = value == null ? "" : value.trim();
    if (text.isBlank()) {
      return "";
    }
    if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("/")) {
      if (text.length() > 2048) {
        throw new IllegalStateException("Generated image URL exceeds the maximum length.");
      }
      return text;
    }
    if (text.startsWith("data:image/")) {
      if (text.length() > MAX_DATA_URL_LENGTH) {
        throw new IllegalStateException("Generated image data exceeds the maximum length.");
      }
      validateDataUrlSize(text, maxImageBytes);
      return text;
    }
    if (text.length() <= 200) {
      return "";
    }
    if (text.length() > MAX_DATA_URL_LENGTH) {
      throw new IllegalStateException("Generated image data exceeds the maximum length.");
    }
    if (!text.matches("^[A-Za-z0-9+/=\\r\\n]+$")) {
      return "";
    }
    var payloadLength = text.codePoints().filter(code -> !Character.isWhitespace(code)).count();
    if (payloadLength * 3L / 4L > maxImageBytes || payloadLength * 3L / 4L > HARD_MAX_IMAGE_BYTES) {
      throw new IllegalStateException("Generated image payload exceeds the configured size limit.");
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

  private String baseUrl(ServerRequest request) {
    var uri = request.uri();
    var port = uri.getPort();
    var authority = port > 0 ? uri.getHost() + ":" + port : uri.getHost();
    return uri.getScheme() + "://" + authority;
  }

  private void copyHeader(ServerRequest request, HttpHeaders target, String name) {
    var values = request.headers().header(name);
    if (!values.isEmpty()) {
      target.put(name, values);
    }
  }

  private Map<String, Object> validateSettings(Map<String, Object> source) {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("lazyBatchSize", clampInt(source.get("lazyBatchSize"), 20, 200, 60));
    settings.put("olderBatchSize", clampInt(source.get("olderBatchSize"), 10, 100, 40));
    settings.put("imageMaxSizeMb", clampInt(source.get("imageMaxSizeMb"), 1, 50, 8));
    settings.put("autoCompressPercent", clampInt(source.get("autoCompressPercent"), 50, 98, 85));
    settings.put("memoryText", limitString(stringValue(source.get("memoryText")), 20000));
    return settings;
  }

  private void validateAiRequestMessages(List<Map<String, Object>> messages, Map<String, Object> globalSettings) {
    var maxMessages = clampInt(globalSettings.get("maxContextMessages"), 4, 100, MAX_REQUEST_MESSAGES);
    var maxChars = clampInt(globalSettings.get("maxContextCharacters"), 4000, 200_000, MAX_REQUEST_CHARS);
    var maxImages = clampInt(globalSettings.get("maxImagesPerRequest"), 0, 50, MAX_REQUEST_IMAGES);
    var maxImageBytes = maxImageBytes(globalSettings);
    if (messages.size() > maxMessages) {
      throw badRequest("Too many messages in AI context.");
    }
    var chars = 0;
    var images = 0;
    var attachments = 0;
    for (var message : messages) {
      var messageId = stringValue(message.get("id"));
      if (messageId.isBlank() || messageId.length() > 120) {
        throw badRequest("Message id is required.");
      }
      if (!ROLES.contains(stringValue(message.get("role")))) {
        throw badRequest("Unsupported message role.");
      }
      var parts = listOfMaps(message.get("parts"));
      if (parts.isEmpty()) {
        throw badRequest("Message parts must not be empty.");
      }
      for (var part : parts) {
        var type = stringValue(part.get("type"));
        if ("text".equals(type) && stringValue(part.get("id")).isBlank()) {
          throw badRequest("Message part id is required.");
        }
        var text = stringValue(firstNonBlank(part.get("text"), part.get("title")));
        if (text.length() > MAX_CONTENT_LENGTH) {
          throw badRequest("One message part is too large.");
        }
        chars += text.length();
        if ("file".equals(type) || part.containsKey("data") || part.containsKey("url")) {
          attachments++;
          var mediaType = stringValue(part.get("mediaType"));
          if (mediaType.startsWith("image/")) {
            images++;
          }
          if (stringValue(part.get("url")).length() > 2048) {
            throw badRequest("Attachment URL exceeds the maximum length.");
          }
          if (stringValue(part.get("data")).length() > MAX_DATA_URL_LENGTH) {
            throw badRequest("Attachment payload exceeds the maximum length.");
          }
          validateDataUrlSize(stringValue(part.get("data")), maxImageBytes);
        }
      }
    }
    if (chars > maxChars) {
      throw badRequest("AI context is too large.");
    }
    if (images > maxImages || attachments > MAX_REQUEST_ATTACHMENTS) {
      throw badRequest("Too many images or attachments in AI context.");
    }
  }

  private void validateImagePayload(Map<String, Object> payload, Map<String, Object> globalSettings) {
    var maxImages = clampInt(globalSettings.get("maxImagesPerRequest"), 0, 50, MAX_REQUEST_IMAGES);
    var maxImageBytes = maxImageBytes(globalSettings);
    var prompt = stringValue(payload.get("prompt"));
    if (prompt.length() > 12_000) {
      throw badRequest("Image prompt is too large.");
    }
    var inputImages = listOfMaps(payload.containsKey("images") ? payload.get("images") : payload.get("inputImages"));
    if (inputImages.size() > maxImages) {
      throw badRequest("Too many input images.");
    }
    for (var image : inputImages) {
      if (stringValue(image.get("url")).length() > 2048) {
        throw badRequest("Input image URL exceeds the maximum length.");
      }
      if (stringValue(image.get("data")).length() > MAX_DATA_URL_LENGTH) {
        throw badRequest("Input image payload exceeds the maximum length.");
      }
      validateDataUrlSize(stringValue(image.get("data")), maxImageBytes);
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
        return item;
      })
      .filter(image -> !stringValue(image.get("url")).isBlank() || !stringValue(image.get("data")).isBlank())
      .limit(MAX_IMAGES_PER_MESSAGE)
      .collect(Collectors.toList());
    normalized.put("images", images);
    for (var key : List.of("mask", "n", "size", "width", "height", "aspectRatio", "seed",
      "responseFormat", "maxRetries", "maxParallelCalls", "providerOptions", "headers")) {
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
      throw badRequest("Too many messages in one session.");
    }
    session.put("memory", limitString(stringValue(session.get("memory")), MAX_MEMORY_LENGTH));
    var serialized = writeMapValue(session);
    if (serialized.length() > MAX_SESSION_JSON_LENGTH) {
      throw badRequest("Session is too large to save safely.");
    }
  }

  private int clampInt(Object value, int min, int max, int fallback) {
    var parsed = intValue(value);
    var number = parsed == null ? fallback : parsed;
    return Math.max(min, Math.min(max, number));
  }

  private long maxImageBytes(Map<String, Object> settings) {
    var mb = clampInt(settings.get("imageMaxSizeMb"), 1, 50, 8);
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
      throw badRequest("Unable to serialize value.");
    }
  }

  private String writeMapValue(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw badRequest("Unable to serialize session snapshot.");
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
    var source = emptyToDefault(raw, prefix);
    var normalized = source.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
    normalized = normalized.replaceAll("^-|-$", "");
    if (normalized.isBlank()) {
      normalized = prefix;
    }
    if (normalized.length() > 56) {
      normalized = prefix + "-" + Integer.toHexString(source.hashCode()) + "-" + normalized.substring(0, 32);
    }
    return normalized;
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
      throw badRequest("Too many attachments in one message.");
    }
    files.forEach(file -> {
      file.setName(limitString(file.getName(), 240));
      file.setMediaType(validateMediaType(file.getMediaType()));
      file.setUrl(limitString(file.getUrl(), 2048));
      file.setAttachmentName(limitString(file.getAttachmentName(), 120));
      if (file.getSize() != null && (file.getSize() > maxImageBytes || file.getSize() > HARD_MAX_IMAGE_BYTES)) {
        throw badRequest("Image file exceeds the configured size limit.");
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
      throw badRequest("Too many images in one message.");
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
      throw badRequest("Image payload exceeds the configured size limit.");
    }
  }

  private String validateMediaType(String mediaType) {
    var value = limitString(mediaType, 120);
    if (!value.isBlank() && !value.startsWith("image/") && !"application/octet-stream".equals(value)) {
      throw badRequest("Unsupported media type.");
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
