package io.github.joshuajj.haloaiconsole.security;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Validates untrusted image bytes before they enter Halo attachment storage. */
public final class ImageUploadPolicy {
  private static final int MAX_DIMENSION = 2_048;
  private static final long MAX_PIXELS = 4_194_304L;

  private ImageUploadPolicy() {
  }

  public static SanitizedImage sanitize(byte[] source, long maxBytes) {
    if (source == null || source.length == 0 || source.length > maxBytes) {
      throw invalid("图片大小超过管理员配置的上限。");
    }
    try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
      if (input == null) {
        throw invalid("图片内容无法安全解码。");
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw invalid("仅支持 PNG 或 JPEG 图片。");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        if (!supportedFormat(reader.getFormatName())) {
          throw invalid("仅支持 PNG 或 JPEG 图片。");
        }
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        validateDimensions(width, height);

        BufferedImage image = reader.read(0);
        if (image == null) {
          throw invalid("图片内容无法安全解码。");
        }
        validateDimensions(image.getWidth(), image.getHeight());
        return png(image, maxBytes);
      } finally {
        reader.dispose();
      }
    } catch (InvalidImageException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw invalid("图片内容无法安全解码。");
    }
  }

  private static void validateDimensions(int width, int height) {
    long pixels = (long) width * height;
    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
      || pixels > MAX_PIXELS) {
      throw invalid("图片尺寸或像素数量超过安全上限。");
    }
  }

  private static SanitizedImage png(BufferedImage image, long maxBytes) throws IOException {
    var output = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", output)) {
      throw invalid("图片内容无法安全转换。");
    }
    var bytes = output.toByteArray();
    if (bytes.length > maxBytes) {
      throw invalid("图片转换后的大小超过管理员配置的上限。");
    }
    return new SanitizedImage(bytes, "image/png");
  }

  private static boolean supportedFormat(String format) {
    return "png".equalsIgnoreCase(format) || "jpeg".equalsIgnoreCase(format)
      || "jpg".equalsIgnoreCase(format);
  }

  private static InvalidImageException invalid(String message) {
    return new InvalidImageException(message);
  }

  public record SanitizedImage(byte[] bytes, String mediaType) {
  }

  public static final class InvalidImageException extends IllegalArgumentException {
    public InvalidImageException(String message) {
      super(message);
    }
  }
}
