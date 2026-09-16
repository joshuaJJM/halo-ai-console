package io.github.joshuajj.haloaiconsole.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class RemoteImageContentPolicyTest {
  @Test
  void rejectsNonPublicOrNonHttpsGeneratedImageUrls() {
    assertThrows(IOException.class,
      () -> RemoteImageContentPolicy.requirePublicHttpsUri("http://example.com/image.png"));
    assertThrows(IOException.class,
      () -> RemoteImageContentPolicy.requirePublicHttpsUri("https://127.0.0.1/image.png"));
    assertThrows(IOException.class,
      () -> RemoteImageContentPolicy.requirePublicHttpsUri("https://[::1]/image.png"));
    assertThrows(IOException.class,
      () -> RemoteImageContentPolicy.requirePublicHttpsUri("https://example.com:8443/image.png"));
  }

  @Test
  void boundsBase64BeforeAndAfterDecoding() throws IOException {
    assertArrayEquals(new byte[] {1, 2, 3},
      RemoteImageContentPolicy.decodeBase64("AQID", 3));
    assertThrows(IOException.class,
      () -> RemoteImageContentPolicy.decodeBase64("AQIDBA==", 3));
    assertThrows(IOException.class,
      () -> RemoteImageContentPolicy.decodeBase64("A".repeat(8_200), 3));
  }
}
