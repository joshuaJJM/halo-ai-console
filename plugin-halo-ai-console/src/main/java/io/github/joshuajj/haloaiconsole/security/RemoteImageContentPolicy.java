package io.github.joshuajj.haloaiconsole.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Base64;

/** Guards server-side retrieval and decoding of images returned by an AI provider. */
public final class RemoteImageContentPolicy {
  private static final int BASE64_WHITESPACE_ALLOWANCE = 4_096;

  private RemoteImageContentPolicy() {
  }

  public static URI requirePublicHttpsUri(String value) throws IOException {
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException error) {
      throw new IOException("生成图片地址格式无效。", error);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
      || uri.getHost().isBlank() || uri.getUserInfo() != null
      || (uri.getPort() != -1 && uri.getPort() != 443)) {
      throw new IOException("生成图片地址必须是公网 HTTPS 地址。");
    }
    final InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(uri.getHost());
    } catch (Exception error) {
      throw new IOException("无法解析生成图片地址。", error);
    }
    if (addresses.length == 0) {
      throw new IOException("生成图片地址没有可用的公网地址。");
    }
    for (var address : addresses) {
      if (!isPublicAddress(address.getAddress())) {
        throw new IOException("生成图片地址不能指向本机、内网或保留地址。");
      }
    }
    return uri;
  }

  public static byte[] decodeBase64(String encoded, long maxBytes) throws IOException {
    if (encoded == null || encoded.isBlank()) {
      throw new IOException("生成图片数据为空。");
    }
    var maximumEncoded = Math.addExact(Math.multiplyExact((maxBytes + 2L) / 3L, 4L),
      BASE64_WHITESPACE_ALLOWANCE);
    if (encoded.length() > maximumEncoded) {
      throw new IOException("生成图片超过大小限制。");
    }
    var compact = encoded.replaceAll("\\s", "");
    if (compact.length() > maximumEncoded - BASE64_WHITESPACE_ALLOWANCE) {
      throw new IOException("生成图片超过大小限制。");
    }
    try {
      var decoded = Base64.getMimeDecoder().decode(compact);
      if (decoded.length > maxBytes) {
        throw new IOException("生成图片超过大小限制。");
      }
      return decoded;
    } catch (IllegalArgumentException error) {
      throw new IOException("生成图片 Base64 数据无效。", error);
    }
  }

  private static boolean isPublicAddress(byte[] address) {
    if (address.length == 4) {
      return isPublicIpv4(address);
    }
    if (address.length != 16) {
      return false;
    }
    if (isIpv4Mapped(address)) {
      return isPublicIpv4(new byte[] {address[12], address[13], address[14], address[15]});
    }
    var first = Byte.toUnsignedInt(address[0]);
    var second = Byte.toUnsignedInt(address[1]);
    if (isAllZero(address) || (first == 0 && isOnlyLastByteOne(address))
      || (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80)) {
      return false;
    }
    return first < 0xff;
  }

  private static boolean isPublicIpv4(byte[] address) {
    var first = Byte.toUnsignedInt(address[0]);
    var second = Byte.toUnsignedInt(address[1]);
    return first != 0 && first != 10 && first != 127 && first < 224
      && !(first == 100 && second >= 64 && second <= 127)
      && !(first == 169 && second == 254)
      && !(first == 172 && second >= 16 && second <= 31)
      && !(first == 192 && (second == 0 || second == 168))
      && !(first == 198 && (second == 18 || second == 19));
  }

  private static boolean isIpv4Mapped(byte[] address) {
    for (var index = 0; index < 10; index++) {
      if (address[index] != 0) {
        return false;
      }
    }
    return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
  }

  private static boolean isAllZero(byte[] address) {
    for (var value : address) {
      if (value != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean isOnlyLastByteOne(byte[] address) {
    for (var index = 1; index < address.length - 1; index++) {
      if (address[index] != 0) {
        return false;
      }
    }
    return address[address.length - 1] == 1;
  }
}
