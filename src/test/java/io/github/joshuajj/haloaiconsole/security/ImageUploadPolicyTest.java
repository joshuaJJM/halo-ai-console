package io.github.joshuajj.haloaiconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageUploadPolicyTest {
  private static final long MAX_BYTES = 10L * 1024L * 1024L;

  @Test
  void reencodesAcceptedImageAndDropsTrailingBytes() throws Exception {
    var original = png(2, 3);
    var trailing = "not-image-metadata".getBytes(StandardCharsets.US_ASCII);
    var input = new byte[original.length + trailing.length];
    System.arraycopy(original, 0, input, 0, original.length);
    System.arraycopy(trailing, 0, input, original.length, trailing.length);

    var image = ImageUploadPolicy.sanitize(input, MAX_BYTES);

    assertThat(image.mediaType()).isEqualTo("image/png");
    assertThat(image.bytes()).doesNotContain(trailing);
    var decoded = ImageIO.read(new java.io.ByteArrayInputStream(image.bytes()));
    assertThat(decoded.getWidth()).isEqualTo(2);
    assertThat(decoded.getHeight()).isEqualTo(3);
  }

  @Test
  void rejectsOversizedDimensionsBeforePersistingTheImage() throws Exception {
    var tooWide = png(2_049, 1);

    assertThatThrownBy(() -> ImageUploadPolicy.sanitize(tooWide, MAX_BYTES))
      .isInstanceOf(ImageUploadPolicy.InvalidImageException.class)
      .hasMessageContaining("尺寸");
  }

  @Test
  void rejectsUnknownFormats() {
    assertThatThrownBy(() -> ImageUploadPolicy.sanitize("not an image".getBytes(StandardCharsets.US_ASCII), MAX_BYTES))
      .isInstanceOf(ImageUploadPolicy.InvalidImageException.class)
      .hasMessageContaining("PNG");
  }

  private byte[] png(int width, int height) throws Exception {
    var output = new ByteArrayOutputStream();
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    assertThat(ImageIO.write(image, "png", output)).isTrue();
    return output.toByteArray();
  }
}
