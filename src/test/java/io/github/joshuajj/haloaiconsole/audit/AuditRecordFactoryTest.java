package io.github.joshuajj.haloaiconsole.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditRecordFactoryTest {
  @Test
  void writesServerSuppliedIdentityTimeDurationAndTokenUsage() {
    var record = AuditRecordFactory.create(
      "alice", "session-1", "Title", "chat", "context-compression", "model-1", "success", "",
      1_000L, 1_250L, 120, 30, 150,
      Map.of("ipAddress", "127.0.0.1", "browser", "Firefox", "operatingSystem", "Linux"));

    assertThat(record).containsEntry("owner", "alice")
      .containsEntry("time", 1_250L)
      .containsEntry("durationMs", 250L)
      .containsEntry("promptTokens", 120)
      .containsEntry("completionTokens", 30)
      .containsEntry("totalTokens", 150)
      .containsEntry("operation", "context-compression")
      .containsEntry("ipAddress", "127.0.0.1");
  }

  @Test
  void boundsUntrustedProviderErrorAndMetadataFields() {
    var record = AuditRecordFactory.create(
      "alice", "session-1", "Title", "chat", "chat", "model-1", "error", "x".repeat(5_000),
      2_000L, 1_000L, -1, -1, -1, Map.of("userAgent", "u".repeat(700)));

    assertThat(String.valueOf(record.get("error"))).hasSize(4_000);
    assertThat(String.valueOf(record.get("userAgent"))).hasSize(500);
    assertThat(record).containsEntry("durationMs", 0L)
      .containsEntry("promptTokens", 0)
      .containsEntry("totalTokens", 0);
  }
}
