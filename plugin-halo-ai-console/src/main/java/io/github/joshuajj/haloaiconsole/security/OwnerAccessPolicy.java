package io.github.joshuajj.haloaiconsole.security;

/** Rejects records whose persisted owner does not match the authenticated owner. */
public final class OwnerAccessPolicy {
  private OwnerAccessPolicy() {
  }

  public static boolean owns(String authenticatedOwner, Object storedOwner) {
    var expected = authenticatedOwner == null ? "" : authenticatedOwner.trim();
    var actual = storedOwner == null ? "" : String.valueOf(storedOwner).trim();
    return !expected.isBlank() && expected.equals(actual);
  }
}
