package run.halo.aichatconsole.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.image.GenerateImageRequest;
import run.halo.aifoundation.image.ImageGenerationModel;
import run.halo.aifoundation.image.ImageResponseFormat;
import run.halo.aifoundation.media.DataContent;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.message.ModelMessagePart;
import run.halo.aifoundation.message.ModelMessageRole;
import run.halo.aifoundation.part.TextStreamPart;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

@Component
public class AiFoundationModelInvoker {
  private final ExtensionGetter extensionGetter;

  public AiFoundationModelInvoker(ExtensionGetter extensionGetter) {
    this.extensionGetter = extensionGetter;
  }

  public Mono<StreamingText> streamText(String modelName, List<Map<String, Object>> messages,
    int maxOutputTokens, String baseUrl) {
    var request = textRequest(messages, maxOutputTokens, baseUrl);
    return languageModel(modelName).map(model -> {
      var stream = model.streamText(request);
      Flux<TextDelta> deltas = stream.fullStream()
        .handle((part, sink) -> {
          var delta = textDelta(part);
          if (delta != null) {
            sink.next(delta);
          }
        });
      return new StreamingText(deltas, stream.result().map(this::textResult));
    });
  }

  public Mono<TextResult> generateText(String modelName, List<Map<String, Object>> messages,
    int maxOutputTokens, String baseUrl) {
    return languageModel(modelName)
      .flatMap(model -> model.generateText(textRequest(messages, maxOutputTokens, baseUrl)))
      .map(this::textResult);
  }

  public Mono<ImageResult> generateImage(String modelName, Map<String, Object> payload,
    String baseUrl) {
    return imageModel(modelName)
      .flatMap(model -> model.generateImage(imageRequest(payload, baseUrl)))
      .map(result -> {
        var images = result.getImages() == null
          ? List.<GeneratedImage>of()
          : result.getImages().stream()
            .map(file -> new GeneratedImage(
              file.getUrl(),
              file.getBase64(),
              file.getMediaType(),
              file.getFilename()
            ))
            .toList();
        var usage = result.getUsage();
        return new ImageResult(
          images,
          usage == null ? null : usage.getInputTokens(),
          usage == null ? null : usage.getOutputTokens(),
          usage == null ? null : usage.getTotalTokens()
        );
      });
  }

  private Mono<AiModelService> aiModelService() {
    return extensionGetter.getEnabledExtension(AiModelService.class)
      .switchIfEmpty(Mono.error(new IllegalStateException(
        "AI Foundation is not installed or enabled."
      )));
  }

  private Mono<LanguageModel> languageModel(String modelName) {
    return aiModelService().flatMap(service -> hasText(modelName)
      ? service.languageModel(modelName)
      : service.languageModel());
  }

  private Mono<ImageGenerationModel> imageModel(String modelName) {
    return aiModelService().flatMap(service -> hasText(modelName)
      ? service.imageGenerationModel(modelName)
      : service.imageGenerationModel());
  }

  private GenerateTextRequest textRequest(List<Map<String, Object>> messages,
    int maxOutputTokens, String baseUrl) {
    return GenerateTextRequest.builder()
      .messages(messages.stream().map(message -> modelMessage(message, baseUrl)).toList())
      .maxOutputTokens(maxOutputTokens)
      .build();
  }

  private ModelMessage modelMessage(Map<String, Object> source, String baseUrl) {
    var role = switch (stringValue(source.get("role")).toLowerCase()) {
      case "assistant" -> ModelMessageRole.ASSISTANT;
      case "system" -> ModelMessageRole.SYSTEM;
      case "tool" -> ModelMessageRole.TOOL;
      default -> ModelMessageRole.USER;
    };
    var parts = new ArrayList<ModelMessagePart>();
    for (var sourcePart : listOfMaps(source.get("parts"))) {
      var type = stringValue(sourcePart.get("type")).toLowerCase();
      if ("text".equals(type)) {
        var text = stringValue(sourcePart.get("text"));
        if (hasText(text)) {
          parts.add(ModelMessagePart.text(text));
        }
        continue;
      }
      if ("reasoning".equals(type)) {
        var text = stringValue(sourcePart.get("text"));
        if (hasText(text)) {
          parts.add(ModelMessagePart.reasoning(text));
        }
        continue;
      }
      if ("file".equals(type) || "image".equals(type)) {
        var media = dataContent(sourcePart, baseUrl);
        var mediaType = stringValue(sourcePart.get("mediaType"));
        parts.add(mediaType.startsWith("image/") || "image".equals(type)
          ? ModelMessagePart.image(media)
          : ModelMessagePart.file(media));
      }
    }
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("Message has no supported parts.");
    }
    return new ModelMessage(role, parts);
  }

  private GenerateImageRequest imageRequest(Map<String, Object> payload, String baseUrl) {
    var builder = GenerateImageRequest.builder()
      .prompt(stringValue(payload.get("prompt")));
    var imageSource = payload.containsKey("images") ? payload.get("images") : payload.get("inputImages");
    var images = listOfMaps(imageSource).stream()
      .map(image -> dataContent(image, baseUrl))
      .toList();
    if (!images.isEmpty()) {
      builder.images(images);
    }
    var mask = castMap(payload.get("mask"));
    if (!mask.isEmpty()) {
      builder.mask(dataContent(mask, baseUrl));
    }
    var n = integerValue(payload.get("n"));
    if (n != null) {
      builder.n(n);
    }
    var size = stringValue(payload.get("size"));
    var width = integerValue(payload.get("width"));
    var height = integerValue(payload.get("height"));
    if (hasText(size)) {
      builder.size(size);
    } else if (width != null && height != null) {
      builder.size(width, height);
    }
    putText(payload, "aspectRatio", builder::aspectRatio);
    var seed = integerValue(payload.get("seed"));
    if (seed != null) {
      builder.seed(seed);
    }
    var responseFormat = stringValue(payload.get("responseFormat"));
    builder.responseFormat("BASE64".equalsIgnoreCase(responseFormat)
      ? ImageResponseFormat.BASE64
      : ImageResponseFormat.URL);
    var maxRetries = integerValue(payload.get("maxRetries"));
    if (maxRetries != null) {
      builder.maxRetries(maxRetries);
    }
    var maxParallelCalls = integerValue(payload.get("maxParallelCalls"));
    if (maxParallelCalls != null) {
      builder.maxParallelCalls(maxParallelCalls);
    }
    var headers = stringMap(payload.get("headers"));
    if (!headers.isEmpty()) {
      builder.headers(headers);
    }
    return builder.build();
  }

  private DataContent dataContent(Map<String, Object> source, String baseUrl) {
    var filename = stringValue(firstNonBlank(source.get("filename"), source.get("name"),
      source.get("title")));
    var mediaType = stringValue(source.get("mediaType"));
    var data = stringValue(source.get("data"));
    if (hasText(data)) {
      if (data.startsWith("data:")) {
        return DataContent.dataUrl(data, filename);
      }
      return DataContent.data(data,
        hasText(mediaType) ? mediaType : "application/octet-stream", filename);
    }
    var url = stringValue(source.get("url"));
    if (url.startsWith("/") && hasText(baseUrl)) {
      url = baseUrl.replaceFirst("/+$", "") + url;
    }
    if (!hasText(url)) {
      throw new IllegalArgumentException("Media content requires data or URL.");
    }
    return DataContent.url(url,
      hasText(mediaType) ? mediaType : "application/octet-stream", filename);
  }

  private TextDelta textDelta(TextStreamPart part) {
    var type = stringValue(part.getType()).toLowerCase();
    if (type.contains("reasoning") && hasText(part.getDelta())) {
      return new TextDelta("reasoning", part.getDelta(), "");
    }
    if (type.contains("text") && hasText(part.getDelta())) {
      return new TextDelta("text", part.getDelta(), "");
    }
    if ("error".equals(type) || hasText(part.getErrorText())) {
      return new TextDelta("error", "", stringValue(part.getErrorText()));
    }
    return null;
  }

  private TextResult textResult(GenerateTextResult result) {
    var usage = result.getTotalUsage() == null ? result.getUsage() : result.getTotalUsage();
    return new TextResult(
      stringValue(result.getText()),
      stringValue(result.getReasoningText()),
      usage == null ? null : usage.getInputTokens(),
      usage == null ? null : usage.getOutputTokens(),
      usage == null ? null : usage.getTotalTokens()
    );
  }

  private void putText(Map<String, Object> source, String key,
    java.util.function.Consumer<String> consumer) {
    var value = stringValue(source.get(key));
    if (hasText(value)) {
      consumer.accept(value);
    }
  }

  private Map<String, String> stringMap(Object value) {
    var result = new LinkedHashMap<String, String>();
    castMap(value).forEach((key, item) -> {
      if (item != null) {
        result.put(key, String.valueOf(item));
      }
    });
    return result;
  }

  private List<Map<String, Object>> listOfMaps(Object value) {
    if (!(value instanceof Iterable<?> iterable)) {
      return List.of();
    }
    var result = new ArrayList<Map<String, Object>>();
    for (var item : iterable) {
      var map = castMap(item);
      if (!map.isEmpty()) {
        result.add(map);
      }
    }
    return result;
  }

  private Map<String, Object> castMap(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      return Map.of();
    }
    var result = new LinkedHashMap<String, Object>();
    source.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private Integer integerValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return hasText(value) ? Integer.valueOf(String.valueOf(value)) : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Object firstNonBlank(Object... values) {
    for (var value : values) {
      if (hasText(value)) {
        return value;
      }
    }
    return "";
  }

  private boolean hasText(Object value) {
    return value != null && !String.valueOf(value).isBlank();
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  public record TextDelta(String type, String text, String error) {
  }

  public record TextResult(String text, String reasoning, Integer inputTokens,
                           Integer outputTokens, Integer totalTokens) {
  }

  public record StreamingText(Flux<TextDelta> deltas, Mono<TextResult> result) {
  }

  public record GeneratedImage(String url, String base64, String mediaType, String filename) {
  }

  public record ImageResult(List<GeneratedImage> images, Integer inputTokens,
                            Integer outputTokens, Integer totalTokens) {
  }
}
