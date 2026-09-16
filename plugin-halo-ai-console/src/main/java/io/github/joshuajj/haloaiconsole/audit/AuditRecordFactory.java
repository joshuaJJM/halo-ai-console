package io.github.joshuajj.haloaiconsole.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds server-authored audit records with bounded persisted fields. */
public final class AuditRecordFactory {
  private AuditRecordFactory() {
  }

  public static Map<String, Object> create(String owner, String sessionId, String sessionTitle,
    String type, String operation, Object model, String status, String error, long startedAt,
    long finishedAt, int promptTokens, int completionTokens, int totalTokens,
    Map<String, Object> requestMetadata) {
    var record = new LinkedHashMap<String, Object>();
    record.put("owner", text(owner, 253));
    record.put("sessionId", text(sessionId, 120));
    record.put("sessionTitle", text(sessionTitle, 120));
    record.put("type", text(type, 32));
    record.put("operation", text(operation, 40));
    record.put("model", text(model, 160));
    record.put("status", text(status, 32));
    record.put("error", text(error, 4_000));
    record.put("time", Math.max(0L, finishedAt));
    record.put("durationMs", Math.max(0L, finishedAt - startedAt));
    record.put("promptTokens", Math.max(0, promptTokens));
    record.put("completionTokens", Math.max(0, completionTokens));
    record.put("totalTokens", Math.max(0, totalTokens));
    for (var key : List.of("ipAddress", "userAgent", "browser", "operatingSystem")) {
      var value = requestMetadata == null ? null : requestMetadata.get(key);
      if (value != null && !String.valueOf(value).isBlank()) {
        record.put(key, text(value, "userAgent".equals(key) ? 500 : 128));
      }
    }
    return record;
  }

  private static String text(Object value, int maximumLength) {
    var result = value == null ? "" : String.valueOf(value);
    return result.length() > maximumLength ? result.substring(0, maximumLength) : result;
  }
}
