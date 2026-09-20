package io.github.joshuajj.haloaiconsole.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KubernetesNamePolicyTest {

  @Test
  void preservesExistingCanonicalNames() {
    assertThat(KubernetesNamePolicy.canonicalName("chat", "chat-123e4567-e89b-12d3-a456-426614174000"))
      .isEqualTo("chat-123e4567-e89b-12d3-a456-426614174000");
  }

  @Test
  void distinguishesValuesTheLegacySlugMerged() {
    var slash = KubernetesNamePolicy.canonicalName("chat", "hello/a");
    var underscore = KubernetesNamePolicy.canonicalName("chat", "hello_a");

    assertThat(KubernetesNamePolicy.legacyName("chat", "hello/a"))
      .isEqualTo(KubernetesNamePolicy.legacyName("chat", "hello_a"));
    assertThat(slash).isNotEqualTo(underscore);
    assertThat(slash).matches("chat-[0-9a-f]{24}");
    assertThat(underscore).matches("chat-[0-9a-f]{24}");
  }

  @Test
  void distinguishesLongValuesWithoutUsingHashCodeAsTheKey() {
    var first = "a".repeat(60) + "Aa";
    var second = "a".repeat(60) + "BB";

    assertThat(first.hashCode()).isEqualTo(second.hashCode());
    assertThat(KubernetesNamePolicy.legacyName("job", first))
      .isEqualTo(KubernetesNamePolicy.legacyName("job", second));
    assertThat(KubernetesNamePolicy.canonicalName("job", first))
      .isNotEqualTo(KubernetesNamePolicy.canonicalName("job", second));
  }
}
