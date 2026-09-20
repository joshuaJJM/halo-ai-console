package io.github.joshuajj.haloaiconsole.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds deterministic Kubernetes-safe names without allowing lossy normalization to merge IDs.
 */
public final class KubernetesNamePolicy {
  private static final int LEGACY_MAX_LENGTH = 56;
  private static final int DIGEST_HEX_LENGTH = 24;

  private KubernetesNamePolicy() {
  }

  public static String canonicalName(String prefix, String raw) {
    var source = emptyToDefault(raw, prefix);
    var legacy = legacyName(prefix, source);
    if (source.equals(legacy) && legacy.length() <= LEGACY_MAX_LENGTH) {
      return legacy;
    }
    return prefix + "-" + sha256Hex(prefix + "\u0000" + source).substring(0, DIGEST_HEX_LENGTH);
  }

  /**
   * Preserves the historical lossy result only for compatibility lookups. Never use for new keys.
   */
  public static String legacyName(String prefix, String raw) {
    var source = emptyToDefault(raw, prefix);
    var normalized = source.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
    normalized = normalized.replaceAll("^-|-$", "");
    if (normalized.isBlank()) {
      normalized = prefix;
    }
    if (normalized.length() > LEGACY_MAX_LENGTH) {
      normalized = prefix + "-" + Integer.toHexString(source.hashCode()) + "-" + normalized.substring(0, 32);
    }
    return normalized;
  }

  private static String emptyToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String sha256Hex(String source) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
      var hex = new StringBuilder(digest.length * 2);
      for (var value : digest) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("Java runtime does not provide SHA-256.", error);
    }
  }
}
