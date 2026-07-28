package run.halo.aichatconsole.extension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@GVK(group = "ai-chat-console.halo.run", version = "v1alpha1", kind = "AiChatSession", plural = "ai-chat-sessions", singular = "ai-chat-session")
public class AiChatSession extends AbstractExtension {
  private SessionSpec spec = new SessionSpec();
  private SessionStatus status = new SessionStatus();

  public SessionSpec getSpec() {
    return spec;
  }

  public void setSpec(SessionSpec spec) {
    this.spec = spec;
  }

  public SessionStatus getStatus() {
    return status;
  }

  public void setStatus(SessionStatus status) {
    this.status = status;
  }

  public static class SessionSpec {
    private String title;
    private String owner;
    private String memory;
    private List<String> tags = new ArrayList<>();
    private Long contextClearedAt;
    private Long createdAt;
    private Long updatedAt;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public Long getContextClearedAt() { return contextClearedAt; }
    public void setContextClearedAt(Long contextClearedAt) { this.contextClearedAt = contextClearedAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
  }

  public static class SessionStatus {
    private Instant lastSyncedAt;
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
  }
}
