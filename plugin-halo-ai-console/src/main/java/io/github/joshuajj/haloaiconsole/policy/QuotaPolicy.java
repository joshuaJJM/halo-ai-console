package io.github.joshuajj.haloaiconsole.policy;

/** Pure quota decision logic shared by persistent reservations and unit tests. */
public final class QuotaPolicy {
  private QuotaPolicy() {
  }

  public record Limits(int maxConcurrent, int requestsPerMinute, int dailyTokenLimit) {
  }

  public record Snapshot(int running, int requestsInWindow, int consumedTokens,
                         int reservedTokens, int requestedReservationTokens) {
  }

  public static void validate(Snapshot snapshot, Limits limits) {
    if (snapshot.running() >= limits.maxConcurrent()) {
      throw new Exceeded("concurrency", "并发 AI 任务过多，请等待现有任务完成后重试。");
    }
    if (snapshot.requestsInWindow() >= limits.requestsPerMinute()) {
      throw new Exceeded("rate", "一分钟内的 AI 请求次数已达到上限，请稍后重试。");
    }
    var projectedTokens = (long) snapshot.consumedTokens()
      + Math.max(0, snapshot.reservedTokens())
      + Math.max(0, snapshot.requestedReservationTokens());
    if (projectedTokens > limits.dailyTokenLimit()) {
      throw new Exceeded("daily-token", "今日 AI Token 配额已用尽，请联系管理员或明日再试。");
    }
  }

  public static final class Exceeded extends IllegalStateException {
    private final String kind;

    public Exceeded(String kind, String message) {
      super(message);
      this.kind = kind;
    }

    public String kind() {
      return kind;
    }
  }
}
