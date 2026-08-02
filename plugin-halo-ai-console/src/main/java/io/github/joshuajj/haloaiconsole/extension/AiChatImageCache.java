package io.github.joshuajj.haloaiconsole.extension;

import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@GVK(group = "halo-ai-console.halo.run", version = "v1alpha1", kind = "AiChatImageCache", plural = "halo-ai-image-caches", singular = "halo-ai-image-cache")
public class AiChatImageCache extends AbstractExtension {
  private ImageCacheSpec spec = new ImageCacheSpec();

  public ImageCacheSpec getSpec() {
    return spec;
  }

  public void setSpec(ImageCacheSpec spec) {
    this.spec = spec;
  }

  public static class ImageCacheSpec {
    private String owner;
    private String sessionId;
    private String messageId;
    private String sourceUrl;
    private String dataUrl;
    private String mediaType;
    private Long createdAt;

    public String getOwner() {
      return owner;
    }

    public void setOwner(String owner) {
      this.owner = owner;
    }

    public String getSessionId() {
      return sessionId;
    }

    public void setSessionId(String sessionId) {
      this.sessionId = sessionId;
    }

    public String getMessageId() {
      return messageId;
    }

    public void setMessageId(String messageId) {
      this.messageId = messageId;
    }

    public String getSourceUrl() {
      return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
      this.sourceUrl = sourceUrl;
    }

    public String getDataUrl() {
      return dataUrl;
    }

    public void setDataUrl(String dataUrl) {
      this.dataUrl = dataUrl;
    }

    public String getMediaType() {
      return mediaType;
    }

    public void setMediaType(String mediaType) {
      this.mediaType = mediaType;
    }

    public Long getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
      this.createdAt = createdAt;
    }
  }
}
