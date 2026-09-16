package io.github.joshuajj.haloaiconsole.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiFoundationCompatibilityVerifierTest {

  @Test
  void acceptsTheDeclaredStableMinimumAndNewerVersions() {
    assertThatCode(() -> AiFoundationCompatibilityVerifier.verifyVersion("1.0.1"))
      .doesNotThrowAnyException();
    assertThatCode(() -> AiFoundationCompatibilityVerifier.verifyVersion("1.1.0"))
      .doesNotThrowAnyException();
  }

  @Test
  void rejectsOlderAndPreReleaseVersions() {
    assertThatThrownBy(() -> AiFoundationCompatibilityVerifier.verifyVersion("1.0.0"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("1.0.1");
    assertThatThrownBy(() -> AiFoundationCompatibilityVerifier.verifyVersion("1.0.1-beta.1"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("1.0.1");
  }
}
