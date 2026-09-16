package io.github.joshuajj.haloaiconsole.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationRequestPolicyTest {
  private static final ConversationRequestPolicy.Limits LIMITS =
    new ConversationRequestPolicy.Limits(30, 80_000, 12, 20, 200_000, 2_048, 2_000_000);

  @Test
  void acceptsAWellFormedLongConversationWithinLimits() {
    var messages = new ArrayList<Map<String, Object>>();
    for (var index = 0; index < 30; index++) {
      messages.add(message("message-" + index, "x".repeat(2_000)));
    }

    var metrics = ConversationRequestPolicy.validate(messages, LIMITS);

    assertThat(metrics.messages()).isEqualTo(30);
    assertThat(metrics.characters()).isEqualTo(60_000);
  }

  @Test
  void rejectsMessageCountAndTotalCharactersBeforeProviderInvocation() {
    var tooMany = new ArrayList<Map<String, Object>>();
    for (var index = 0; index < 31; index++) {
      tooMany.add(message("message-" + index, "ok"));
    }

    assertThatThrownBy(() -> ConversationRequestPolicy.validate(tooMany, LIMITS))
      .isInstanceOf(ConversationRequestPolicy.Violation.class)
      .hasMessageContaining("消息数量");
    assertThatThrownBy(() -> ConversationRequestPolicy.validate(
      List.of(message("large", "x".repeat(80_001))), LIMITS))
      .isInstanceOf(ConversationRequestPolicy.Violation.class)
      .hasMessageContaining("上下文总量");
  }

  @Test
  void rejectsMissingPartIdsAndExcessAttachments() {
    var invalid = Map.<String, Object>of(
      "id", "message-1",
      "role", "user",
      "parts", List.of(Map.of("type", "text", "text", "hello")));
    assertThatThrownBy(() -> ConversationRequestPolicy.validate(List.of(invalid), LIMITS))
      .hasMessageContaining("消息内容标识");

    var attachments = new ArrayList<Map<String, Object>>();
    for (var index = 0; index < 21; index++) {
      attachments.add(Map.of("type", "file", "url", "https://example.com/" + index,
        "attachmentName", "attachment-" + index));
    }
    var excessive = Map.<String, Object>of(
      "id", "message-2", "role", "user", "parts", attachments);
    assertThatThrownBy(() -> ConversationRequestPolicy.validate(List.of(excessive), LIMITS))
      .hasMessageContaining("图片或附件数量");
  }

  @Test
  void countsImagesByTheirValidatedPartTypeInsteadOfClientMimeHints() {
    var noImagesAllowed = new ConversationRequestPolicy.Limits(30, 80_000, 0, 20, 200_000, 2_048, 700_000);
    var image = Map.<String, Object>of(
      "id", "message-image", "role", "user",
      "parts", List.of(Map.of("id", "image-part", "type", "image", "mediaType", "image/png",
        "data", "data:image/png;base64,AA==")));
    assertThatThrownBy(() -> ConversationRequestPolicy.validate(List.of(image), noImagesAllowed))
      .isInstanceOf(ConversationRequestPolicy.Violation.class)
      .hasMessageContaining("图片或附件数量");

    var missingMime = Map.<String, Object>of(
      "id", "message-missing-mime", "role", "user",
      "parts", List.of(Map.of("id", "image-part", "type", "image", "data", "data:image/png;base64,AA==")));
    assertThatThrownBy(() -> ConversationRequestPolicy.validate(List.of(missingMime), LIMITS))
      .isInstanceOf(ConversationRequestPolicy.Violation.class)
      .hasMessageContaining("PNG 或 JPEG");
  }

  @Test
  void rejectsBrowserSuppliedMediaUrlWithoutAHaloAttachmentReference() {
    var message = Map.<String, Object>of(
      "id", "message-remote-image", "role", "user",
      "parts", List.of(Map.of("id", "remote-image", "type", "image", "mediaType", "image/png",
        "url", "https://example.invalid/image.png")));

    assertThatThrownBy(() -> ConversationRequestPolicy.validate(List.of(message), LIMITS))
      .isInstanceOf(ConversationRequestPolicy.Violation.class)
      .hasMessageContaining("Halo 附件");
  }

  @Test
  void acceptsAttachmentReferenceUntilTheServerVerifiesItsOwnership() {
    var message = Map.<String, Object>of(
      "id", "message-attachment-image", "role", "user",
      "parts", List.of(Map.of("id", "attachment-image", "type", "image", "mediaType", "image/png",
        "url", "https://halo.example/attachments/example.png", "attachmentName", "attachment-a")));

    assertThat(ConversationRequestPolicy.validate(List.of(message), LIMITS).images()).isEqualTo(1);
  }

  private Map<String, Object> message(String id, String content) {
    return Map.of(
      "id", id,
      "role", "user",
      "parts", List.of(Map.of("id", id + "-part", "type", "text", "text", content)));
  }
}
