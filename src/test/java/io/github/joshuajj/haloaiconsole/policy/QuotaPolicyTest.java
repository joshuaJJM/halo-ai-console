package io.github.joshuajj.haloaiconsole.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuotaPolicyTest {
  private static final QuotaPolicy.Limits LIMITS = new QuotaPolicy.Limits(2, 12, 200_000);

  @Test
  void acceptsARequestOnlyWhenEveryServerSideCounterIsWithinLimit() {
    assertThatCode(() -> QuotaPolicy.validate(
      new QuotaPolicy.Snapshot(1, 11, 100_000, 20_000, 10_000), LIMITS))
      .doesNotThrowAnyException();
  }

  @Test
  void rejectsConcurrencyRateAndProjectedDailyUsageAtTheirBoundaries() {
    assertExceeded("concurrency", new QuotaPolicy.Snapshot(2, 0, 0, 0, 1));
    assertExceeded("rate", new QuotaPolicy.Snapshot(0, 12, 0, 0, 1));
    assertExceeded("daily-token", new QuotaPolicy.Snapshot(0, 0, 190_000, 9_000, 1_001));
  }

  @Test
  void includesTheMaximumOutputBudgetInTheRequestedReservation() {
    assertExceeded("daily-token", new QuotaPolicy.Snapshot(0, 0, 190_000, 0, 10_001));
  }

  private void assertExceeded(String kind, QuotaPolicy.Snapshot snapshot) {
    assertThatThrownBy(() -> QuotaPolicy.validate(snapshot, LIMITS))
      .isInstanceOfSatisfying(QuotaPolicy.Exceeded.class,
        exceeded -> org.assertj.core.api.Assertions.assertThat(exceeded.kind()).isEqualTo(kind));
  }
}
