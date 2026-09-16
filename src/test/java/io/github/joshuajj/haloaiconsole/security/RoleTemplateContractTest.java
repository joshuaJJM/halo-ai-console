package io.github.joshuajj.haloaiconsole.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class RoleTemplateContractTest {
  private static final String API_GROUP = "console.api.halo-ai-console.halo.run";

  @Test
  void ordinaryRoleCoversOwnChatEndpointsWithoutAdministratorEndpoints() throws IOException {
    var role = role("role-template-halo-ai-console-view-and-manage-own");
    var resources = resourcesFor(role, API_GROUP);
    assertThat(resources).contains(
      "sessions-with-messages", "sessions", "sessions/snapshot", "call-logs", "audit-logs",
      "settings", "assets", "image-caches", "jobs", "jobs/events", "jobs/cancel", "me",
      "models/generate-text");
    assertThat(resources).doesNotContain("global-settings", "migration/status");

    var nonResourceUrls = nonResourceUrls(role);
    assertThat(nonResourceUrls).contains(
      "/apis/console.api.halo-ai-console.halo.run/v1alpha1/attachments/upload",
      "/apis/console.api.halo-ai-console.halo.run/v1alpha1/jobs/chat",
      "/apis/console.api.halo-ai-console.halo.run/v1alpha1/jobs/image",
      "/apis/console.api.halo-ai-console.halo.run/v1alpha1/models/*/generate-text");
  }

  @Test
  void auditAndAdministratorCapabilitiesRemainSeparate() throws IOException {
    var audit = role("role-template-halo-ai-console-audit");
    var admin = role("role-template-halo-ai-console-admin");

    assertThat(resourcesFor(audit, API_GROUP)).containsExactly("audit-logs");
    assertThat(resourcesFor(admin, API_GROUP))
      .contains("call-logs", "audit-logs", "migration/status", "global-settings");
    assertThat(nonResourceUrls(audit)).isEmpty();
    assertThat(nonResourceUrls(admin))
      .containsExactly("/apis/console.api.halo-ai-console.halo.run/v1alpha1/migration/legacy");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> role(String name) throws IOException {
    var yaml = new Yaml();
    var path = Path.of("plugin-halo-ai-console/extensions/roleTemplates.yaml");
    try (var reader = Files.newBufferedReader(path)) {
      for (var document : yaml.loadAll(reader)) {
        var role = (Map<String, Object>) document;
        var metadata = (Map<String, Object>) role.get("metadata");
        if (name.equals(metadata.get("name"))) {
          return role;
        }
      }
    }
    throw new AssertionError("Missing role template: " + name);
  }

  @SuppressWarnings("unchecked")
  private List<String> resourcesFor(Map<String, Object> role, String apiGroup) {
    var result = new ArrayList<String>();
    for (var rule : (List<Map<String, Object>>) role.get("rules")) {
      var groups = (List<String>) rule.getOrDefault("apiGroups", List.of());
      if (groups.contains(apiGroup)) {
        result.addAll((List<String>) rule.getOrDefault("resources", List.of()));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private List<String> nonResourceUrls(Map<String, Object> role) {
    var result = new ArrayList<String>();
    for (var rule : (List<Map<String, Object>>) role.get("rules")) {
      result.addAll((List<String>) rule.getOrDefault("nonResourceURLs", List.of()));
    }
    return result;
  }
}
