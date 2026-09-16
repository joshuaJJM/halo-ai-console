package io.github.joshuajj.haloaiconsole.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;

class JobEventRegistryTest {
  @Test
  void successCompletesAndRemovesTheSseSink() {
    var registry = new JobEventRegistry();
    var received = new CopyOnWriteArrayList<Map<String, Object>>();
    var completed = new CopyOnWriteArrayList<Boolean>();
    registry.sink("alice/job-1").asFlux().subscribe(received::add, error -> { }, () -> completed.add(true));

    registry.emit("alice/job-1", Map.of("id", "job-1", "status", "success"));

    assertThat(received).containsExactly(Map.of("id", "job-1", "status", "success"));
    assertThat(completed).containsExactly(true);
    assertThat(registry.activeSinkCount()).isZero();
  }

  @Test
  void activeJobsKeepTheirSinkUntilATerminalEventArrives() {
    var registry = new JobEventRegistry();
    registry.sink("alice/job-1");

    registry.emit("alice/job-1", Map.of("id", "job-1", "status", "running"));

    assertThat(registry.activeSinkCount()).isEqualTo(1);
    registry.completeMatching(key -> key.startsWith("alice/"));
    assertThat(registry.activeSinkCount()).isZero();
  }

  @Test
  void terminalEventEmittedBeforeSubscriptionIsReplayedToReconnect() {
    var registry = new JobEventRegistry();
    var sink = registry.sink("alice/job-1");

    registry.emit("alice/job-1", Map.of("id", "job-1", "status", "success"));
    var replayed = sink.asFlux().collectList().block();

    assertThat(replayed).containsExactly(Map.of("id", "job-1", "status", "success"));
    assertThat(registry.activeSinkCount()).isZero();
  }

  @Test
  void completedJobReconnectsWithOneEventAndNoNewSink() {
    var registry = new JobEventRegistry();
    var refreshCalled = new AtomicBoolean(false);

    var events = registry.reconnectingStream("alice/job-1",
      Map.of("id", "job-1", "status", "success"), () -> {
        refreshCalled.set(true);
        return Mono.just(Map.of("id", "job-1", "status", "success"));
      }).collectList().block();

    assertThat(events).containsExactly(Map.of("id", "job-1", "status", "success"));
    assertThat(refreshCalled).isFalse();
    assertThat(registry.activeSinkCount()).isZero();
  }

  @Test
  void terminalEventBetweenRegistrationAndSubscriptionIsNotLost() {
    var registry = new JobEventRegistry();
    var events = registry.reconnectingStream("alice/job-1",
      Map.of("id", "job-1", "status", "running"), () -> {
        registry.emit("alice/job-1", Map.of("id", "job-1", "status", "success"));
        return Mono.just(Map.of("id", "job-1", "status", "running"));
      }).collectList().block();

    assertThat(events).containsExactly(
      Map.of("id", "job-1", "status", "running"),
      Map.of("id", "job-1", "status", "success"));
    assertThat(registry.activeSinkCount()).isZero();
  }

  @Test
  void authoritativeRereadReplacesStaleRunningStateWithTerminalState() {
    var registry = new JobEventRegistry();

    var events = registry.reconnectingStream("alice/job-1",
      Map.of("id", "job-1", "status", "running"),
      () -> Mono.just(Map.of("id", "job-1", "status", "success")))
      .collectList().block();

    assertThat(events).containsExactly(Map.of("id", "job-1", "status", "success"));
    assertThat(registry.activeSinkCount()).isZero();
  }
}
