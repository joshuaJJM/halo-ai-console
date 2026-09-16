package io.github.joshuajj.haloaiconsole.policy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the bounded, provider-neutral message envelope before model invocation. */
public final class ConversationRequestPolicy {
  private static final Set<String> ROLES = Set.of("user", "assistant");
  private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/png", "image/jpeg");

  private ConversationRequestPolicy() {
  }

  public record Limits(int maxMessages, int maxCharacters, int maxImages, int maxAttachments,
                       int maxPartCharacters, int maxUrlCharacters, int maxDataCharacters) {
  }

  public record Metrics(int messages, int characters, int images, int attachments) {
  }

  public static Metrics validate(List<Map<String, Object>> messages, Limits limits) {
    if (messages.size() > limits.maxMessages()) {
      throw new Violation("发送给 AI 的上下文消息数量超过限制。");
    }
    var characters = 0L;
    var images = 0;
    var attachments = 0;
    for (var message : messages) {
      var messageId = text(message.get("id"));
      if (messageId.isBlank() || messageId.length() > 120) {
        throw new Violation("缺少消息标识。");
      }
      if (!ROLES.contains(text(message.get("role")))) {
        throw new Violation("不支持该消息角色。");
      }
      var parts = maps(message.get("parts"));
      if (parts.isEmpty()) {
        throw new Violation("消息内容不能为空。");
      }
      for (var part : parts) {
        var type = text(part.get("type"));
        var mediaType = text(part.get("mediaType")).toLowerCase();
        if ("text".equals(type) && text(part.get("id")).isBlank()) {
          throw new Violation("缺少消息内容标识。");
        }
        var partText = firstText(part.get("text"), part.get("title"));
        if (partText.length() > limits.maxPartCharacters()) {
          throw new Violation("单段消息内容超过大小限制。");
        }
        characters += partText.length();
        if (characters > limits.maxCharacters()) {
          throw new Violation("发送给 AI 的上下文总量超过限制。");
        }
        if ("image".equals(type)) {
          if (!IMAGE_MEDIA_TYPES.contains(mediaType)) {
            throw new Violation("图片必须声明为 PNG 或 JPEG 媒体类型。");
          }
          if (text(part.get("data")).isBlank() && text(part.get("url")).isBlank()) {
            throw new Violation("图片必须包含数据或访问地址。");
          }
          if (!text(part.get("url")).isBlank() && text(part.get("attachmentName")).isBlank()) {
            throw new Violation("图片访问地址必须引用当前用户已上传的 Halo 附件。");
          }
          images++;
        }
        if ("file".equals(type) || "image".equals(type) || part.containsKey("data") || part.containsKey("url")) {
          attachments++;
          if (!"image".equals(type) && IMAGE_MEDIA_TYPES.contains(mediaType)) {
            images++;
          }
          if (text(part.get("url")).length() > limits.maxUrlCharacters()) {
            throw new Violation("附件访问地址超过最大长度限制。");
          }
          if (!text(part.get("url")).isBlank() && text(part.get("attachmentName")).isBlank()) {
            throw new Violation("附件访问地址必须引用当前用户已上传的 Halo 附件。");
          }
          if (text(part.get("data")).length() > limits.maxDataCharacters()) {
            throw new Violation("附件数据超过最大长度限制。");
          }
        }
      }
    }
    if (images > limits.maxImages() || attachments > limits.maxAttachments()) {
      throw new Violation("发送给 AI 的图片或附件数量超过限制。");
    }
    return new Metrics(messages.size(), Math.toIntExact(characters), images, attachments);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
      .filter(Map.class::isInstance)
      .map(item -> (Map<String, Object>) item)
      .toList();
  }

  private static String firstText(Object first, Object second) {
    var value = text(first);
    return value.isBlank() ? text(second) : value;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  public static final class Violation extends IllegalArgumentException {
    public Violation(String message) {
      super(message);
    }
  }
}
