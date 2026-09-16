package io.github.joshuajj.haloaiconsole.service;

import io.github.joshuajj.haloaiconsole.policy.JobLifecyclePolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Owns ephemeral SSE sinks; persisted Job state remains the source of truth. */
public final class JobEventRegistry {
  private final Map<String, Sinks.Many<Map<String, Object>>> sinks = new ConcurrentHashMap<>();

  public Sinks.Many<Map<String, Object>> sink(String key) {
    // Replay the most recent state so an event emitted between registration and the
    // HTTP response subscription is not lost during a browser reconnect.
    return sinks.computeIfAbsent(key, ignored -> Sinks.many().replay().limit(1));
  }

  public void emit(String key, Map<String, Object> job) {
    var sink = sinks.get(key);
    if (sink == null) {
      return;
    }
    sink.tryEmitNext(new LinkedHashMap<>(job));
    if (JobLifecyclePolicy.isTerminal(job.get("status"))) {
      sink.tryEmitComplete();
      sinks.remove(key, sink);
    }
  }

  public void remove(String key, Sinks.Many<Map<String, Object>> sink) {
    sinks.remove(key, sink);
  }

  public void completeAndRemove(String key, Sinks.Many<Map<String, Object>> sink) {
    sink.tryEmitComplete();
    sinks.remove(key, sink);
  }

  /**
   * Builds a reconnect-safe stream from persisted Job state and ephemeral updates.
   * A sink is registered before the authoritative reread, closing the read/register gap.
   */
  public Flux<Map<String, Object>> reconnectingStream(String key, Map<String, Object> initial,
    Supplier<Mono<Map<String, Object>>> authoritativeRefresh) {
    if (JobLifecyclePolicy.isTerminal(initial.get("status"))) {
      return Flux.just(new LinkedHashMap<>(initial));
    }
    var sink = sink(key);
    return Mono.defer(authoritativeRefresh)
      .flatMapMany(latest -> {
        if (JobLifecyclePolicy.isTerminal(latest.get("status"))) {
          completeAndRemove(key, sink);
          return Flux.just(new LinkedHashMap<>(latest));
        }
        return Flux.concat(Mono.just(new LinkedHashMap<>(latest)), sink.asFlux());
      })
      .switchIfEmpty(Flux.defer(() -> {
        completeAndRemove(key, sink);
        return Flux.empty();
      }));
  }

  public void completeMatching(Predicate<String> matcher) {
    sinks.entrySet().removeIf(entry -> {
      if (!matcher.test(entry.getKey())) {
        return false;
      }
      entry.getValue().tryEmitComplete();
      return true;
    });
  }

  public void completeAll() {
    sinks.values().forEach(Sinks.Many::tryEmitComplete);
    sinks.clear();
  }

  int activeSinkCount() {
    return sinks.size();
  }
}
