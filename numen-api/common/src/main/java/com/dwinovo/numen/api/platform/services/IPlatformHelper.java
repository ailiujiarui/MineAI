package com.dwinovo.numen.api.platform.services;

public interface IPlatformHelper {

    String getPlatformName();

    /** The game's config directory ({@code <gameDir>/config}); used for the user-editable models file. */
    java.nio.file.Path getConfigDir();

    boolean isModLoaded(String modId);

    /** 当前加载的全部模组 id;测试夹具据此判断环境有没有被整合包改动。 */
    java.util.Set<String> loadedModIds();

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {

        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
