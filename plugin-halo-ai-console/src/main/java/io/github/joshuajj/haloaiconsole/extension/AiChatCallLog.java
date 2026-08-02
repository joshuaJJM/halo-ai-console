package io.github.joshuajj.haloaiconsole.extension;

import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@GVK(group = "halo-ai-console.halo.run", version = "v1alpha1", kind = "AiChatCallLog", plural = "halo-ai-call-logs", singular = "halo-ai-call-log")
public class AiChatCallLog extends AbstractExtension {
  private CallLogSpec spec = new CallLogSpec();

  public CallLogSpec getSpec() {
    return spec;
  }

  public void setSpec(CallLogSpec spec) {
    this.spec = spec;
  }

  public static class CallLogSpec {
    private String owner;
    private String sessionId;
    private String sessionTitle;
    private String type;
    private String operation;
    private String model;
    private String status;
    private String error;
    private String ipAddress;
    private String userAgent;
    private String browser;
    private String operatingSystem;
    private Long time;
    private Long durationMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

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

    public String getSessionTitle() {
      return sessionTitle;
    }

    public void setSessionTitle(String sessionTitle) {
      this.sessionTitle = sessionTitle;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getOperation() {
      return operation;
    }

    public void setOperation(String operation) {
      this.operation = operation;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public String getError() {
      return error;
    }

    public void setError(String error) {
      this.error = error;
    }

    public String getIpAddress() {
      return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
      this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
      return userAgent;
    }

    public void setUserAgent(String userAgent) {
      this.userAgent = userAgent;
    }

    public String getBrowser() {
      return browser;
    }

    public void setBrowser(String browser) {
      this.browser = browser;
    }

    public String getOperatingSystem() {
      return operatingSystem;
    }

    public void setOperatingSystem(String operatingSystem) {
      this.operatingSystem = operatingSystem;
    }

    public Long getTime() {
      return time;
    }

    public void setTime(Long time) {
      this.time = time;
    }

    public Long getDurationMs() {
      return durationMs;
    }

    public void setDurationMs(Long durationMs) {
      this.durationMs = durationMs;
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
  }
}
