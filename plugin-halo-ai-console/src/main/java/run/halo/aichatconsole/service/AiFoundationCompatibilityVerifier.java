package run.halo.aichatconsole.service;

import com.github.zafarkhaja.semver.Version;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class AiFoundationCompatibilityVerifier implements InitializingBean {
  static final String MINIMUM_VERSION = "1.0.0-beta.5";

  private final ReactiveExtensionClient client;

  public AiFoundationCompatibilityVerifier(ReactiveExtensionClient client) {
    this.client = client;
  }

  @Override
  public void afterPropertiesSet() {
    var plugin = client.fetch(Plugin.class, "ai-foundation")
      .block(Duration.ofSeconds(5));
    if (plugin == null || plugin.getSpec() == null) {
      throw new IllegalStateException(
        "未找到 AI Foundation。请先安装并启用 AI Foundation " + MINIMUM_VERSION + " 或更高版本。"
      );
    }
    verifyVersion(plugin.getSpec().getVersion());
  }

  static void verifyVersion(String rawVersion) {
    if (rawVersion == null || rawVersion.isBlank()) {
      throw new IllegalStateException(
        "无法读取 AI Foundation 版本。请安装 AI Foundation " + MINIMUM_VERSION + " 或更高版本。"
      );
    }
    final Version installed;
    try {
      installed = Version.parse(rawVersion.trim());
    } catch (RuntimeException error) {
      throw new IllegalStateException(
        "无法识别 AI Foundation 版本 " + rawVersion + "。最低支持版本为 " + MINIMUM_VERSION + "。",
        error
      );
    }
    var minimum = Version.parse(MINIMUM_VERSION);
    if (installed.isLowerThan(minimum)) {
      throw new IllegalStateException(
        "AI Foundation " + installed + " 不受支持。请升级到 " + MINIMUM_VERSION + " 或更高版本。"
      );
    }
  }
}
