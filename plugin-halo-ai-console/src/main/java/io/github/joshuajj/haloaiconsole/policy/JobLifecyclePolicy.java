package io.github.joshuajj.haloaiconsole.policy;

import java.util.Set;

/** Defines valid active and terminal states independently from transport and storage. */
public final class JobLifecyclePolicy {
  private static final Set<String> ACTIVE = Set.of("pending", "running");
  // `success` is the persisted status emitted by existing chat and image Job APIs.
  // Keep `completed` for compatibility with already stored or future normalized records.
  private static final Set<String> TERMINAL = Set.of("success", "completed", "error", "cancelled", "interrupted");

  private JobLifecyclePolicy() {
  }

  public static boolean isActive(Object status) {
    return ACTIVE.contains(text(status));
  }

  public static boolean isTerminal(Object status) {
    return TERMINAL.contains(text(status));
  }

  public static boolean shouldInterrupt(boolean instanceAlive, long now, long heartbeatAt,
    long maximumHeartbeatAge) {
    return !instanceAlive || heartbeatAt <= 0 || now - heartbeatAt > maximumHeartbeatAge;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
