package run.halo.aichatconsole.extension;

import java.util.ArrayList;
import java.util.List;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@GVK(group = "ai-chat-console.halo.run", version = "v1alpha1", kind = "AiChatMessage", plural = "ai-chat-messages", singular = "ai-chat-message")
public class AiChatMessage extends AbstractExtension {
  private MessageSpec spec = new MessageSpec();

  public MessageSpec getSpec() {
    return spec;
  }

  public void setSpec(MessageSpec spec) {
    this.spec = spec;
  }

  public static class MessageSpec {
    private String id;
    private String sessionId;
    private String owner;
    private String role;
    private String content;
    private String reasoning;
    private Boolean reasoningOpen;
    private Long createdAt;
    private Long updatedAt;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private List<Attachment> files = new ArrayList<>();
    private List<String> images = new ArrayList<>();

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getSessionId() {
      return sessionId;
    }

    public void setSessionId(String sessionId) {
      this.sessionId = sessionId;
    }

    public String getOwner() {
      return owner;
    }

    public void setOwner(String owner) {
      this.owner = owner;
    }

    public String getRole() {
      return role;
    }

    public void setRole(String role) {
      this.role = role;
    }

    public String getContent() {
      return content;
    }

    public void setContent(String content) {
      this.content = content;
    }

    public String getReasoning() {
      return reasoning;
    }

    public void setReasoning(String reasoning) {
      this.reasoning = reasoning;
    }

    public Boolean getReasoningOpen() {
      return reasoningOpen;
    }

    public void setReasoningOpen(Boolean reasoningOpen) {
      this.reasoningOpen = reasoningOpen;
    }

    public Long getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
      this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
      return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
      this.updatedAt = updatedAt;
    }

    public Integer getPromptTokens() {
      return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
      this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
      return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
      this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
      return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
      this.totalTokens = totalTokens;
    }

    public List<Attachment> getFiles() {
      return files;
    }

    public void setFiles(List<Attachment> files) {
      this.files = files;
    }

    public List<String> getImages() {
      return images;
    }

    public void setImages(List<String> images) {
      this.images = images;
    }
  }

  public static class Attachment {
    private String name;
    private String mediaType;
    private String data;
    private String url;
    private String attachmentName;
    private Long size;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getMediaType() {
      return mediaType;
    }

    public void setMediaType(String mediaType) {
      this.mediaType = mediaType;
    }

    public String getData() {
      return data;
    }

    public void setData(String data) {
      this.data = data;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getAttachmentName() {
      return attachmentName;
    }

    public void setAttachmentName(String attachmentName) {
      this.attachmentName = attachmentName;
    }

    public Long getSize() {
      return size;
    }

    public void setSize(Long size) {
      this.size = size;
    }
  }
}
