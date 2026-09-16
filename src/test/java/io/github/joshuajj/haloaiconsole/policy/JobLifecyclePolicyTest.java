package io.github.joshuajj.haloaiconsole.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobLifecyclePolicyTest {
  @Test
  void recognizesEverySupportedActiveAndTerminalState() {
    assertThat(JobLifecyclePolicy.isActive("pending")).isTrue();
    assertThat(JobLifecyclePolicy.isActive("running")).isTrue();
    assertThat(JobLifecyclePolicy.isTerminal("success")).isTrue();
    assertThat(JobLifecyclePolicy.isTerminal("completed")).isTrue();
    assertThat(JobLifecyclePolicy.isTerminal("error")).isTrue();
    assertThat(JobLifecyclePolicy.isTerminal("cancelled")).isTrue();
    assertThat(JobLifecyclePolicy.isTerminal("interrupted")).isTrue();
    assertThat(JobLifecyclePolicy.isTerminal("running")).isFalse();
    assertThat(JobLifecyclePolicy.isTerminal("unknown")).isFalse();
  }

  @Test
  void interruptsOnlyDeadMissingOrStaleInstances() {
    var now = 100_000L;
    var maximumAge = 10_000L;
    assertThat(JobLifecyclePolicy.shouldInterrupt(true, now, 95_000L, maximumAge)).isFalse();
    assertThat(JobLifecyclePolicy.shouldInterrupt(false, now, 95_000L, maximumAge)).isTrue();
    assertThat(JobLifecyclePolicy.shouldInterrupt(true, now, 0L, maximumAge)).isTrue();
    assertThat(JobLifecyclePolicy.shouldInterrupt(true, now, 89_999L, maximumAge)).isTrue();
  }
}
