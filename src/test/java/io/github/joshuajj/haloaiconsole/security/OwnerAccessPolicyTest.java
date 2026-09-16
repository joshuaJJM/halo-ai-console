package io.github.joshuajj.haloaiconsole.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OwnerAccessPolicyTest {
  @Test
  void acceptsOnlyAnExactAuthenticatedOwnerMatch() {
    assertThat(OwnerAccessPolicy.owns("alice", "alice")).isTrue();
    assertThat(OwnerAccessPolicy.owns("alice", "bob")).isFalse();
    assertThat(OwnerAccessPolicy.owns("alice", "")).isFalse();
    assertThat(OwnerAccessPolicy.owns("", "alice")).isFalse();
    assertThat(OwnerAccessPolicy.owns(null, "alice")).isFalse();
  }
}
