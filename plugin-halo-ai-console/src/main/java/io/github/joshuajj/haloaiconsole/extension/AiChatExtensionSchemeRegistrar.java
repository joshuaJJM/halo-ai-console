package io.github.joshuajj.haloaiconsole.extension;

import org.springframework.stereotype.Component;
import run.halo.app.extension.Extension;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;

@Component
public class AiChatExtensionSchemeRegistrar {
  public AiChatExtensionSchemeRegistrar(SchemeManager schemeManager) {
    registerWithNameIndex(schemeManager, AiChatSession.class);
    registerWithNameIndex(schemeManager, AiChatMessage.class);
    registerWithNameIndex(schemeManager, AiChatCallLog.class);
    registerWithNameIndex(schemeManager, AiChatImageCache.class);
  }

  private <E extends Extension> void registerWithNameIndex(SchemeManager schemeManager, Class<E> type) {
    schemeManager.register(type, indexSpecs -> indexSpecs.add(
      IndexSpecs.<E, String>single("name", String.class)
        .unique(true)
        .nullable(false)
        .indexFunc(extension -> extension.getMetadata().getName())
    ));
  }
}
