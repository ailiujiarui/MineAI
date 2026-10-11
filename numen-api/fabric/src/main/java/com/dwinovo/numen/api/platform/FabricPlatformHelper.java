package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public java.nio.file.Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public java.util.Set<String> loadedModIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        FabricLoader.getInstance().getAllMods().forEach(m -> ids.add(m.getMetadata().getId()));
        return ids;
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
