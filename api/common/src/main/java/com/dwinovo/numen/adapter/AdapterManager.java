package com.dwinovo.numen.adapter;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.NumenPaths;
import com.dwinovo.numen.agent.adapter.AdapterRegistry;
import com.dwinovo.numen.agent.adapter.ReloadReport;
import com.dwinovo.numen.platform.Services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 模组适配器的引擎侧持有者:一个进程一份 {@link AdapterRegistry},目录固定在
 * {@code config/numen/adapters/},启动时装载、{@code /numen adapter reload} 时热重载。
 *
 * <p>它把纯 JVM 的适配框架接到运行时:目标模组在不在场走平台判断(和加载器
 * {@code Builtin} 闸门同一个 {@code isModLoaded}),缺模组的适配器整条跳过,坏文件不影响其余。
 *
 * <p>适配器本身只是"翻译表";真正动手读世界的动作由宿主按表去做(见后续的处理器接线)。
 */
public final class AdapterManager {

    private static final AdapterRegistry REGISTRY = new AdapterRegistry();
    private static boolean initialised;

    private AdapterManager() {}

    /** 适配文件目录:{@code config/numen/adapters/}。 */
    public static Path dir() {
        return NumenPaths.config().resolve("adapters");
    }

    public static AdapterRegistry registry() {
        return REGISTRY;
    }

    /** 建目录、铺示例、首次装载。幂等;任何一步失败都不抛出,保证本体照常起。 */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        try {
            Files.createDirectories(dir());
            ExampleAdapters.seedIfEmpty(dir());
        } catch (IOException failure) {
            Constants.LOG.warn("[numen-adapter] 建目录失败 {}: {}", dir(), failure.getMessage());
        }
        reload();
    }

    /** 从磁盘重读并换掉当前生效集合。 */
    public static synchronized ReloadReport reload() {
        ReloadReport report = REGISTRY.reload(dir(), Services.PLATFORM::isModLoaded);
        Constants.LOG.info("[numen-adapter] reload {}: {} loaded, {} failed, {} skipped (added={}, updated={}, removed={})",
                dir(), report.loaded(), report.failed(), report.skipped().size(),
                report.added(), report.updated(), report.removed());
        for (String error : report.errors()) {
            Constants.LOG.warn("[numen-adapter] {}", error);
        }
        return report;
    }

    /** 目录里有适配文件时不再铺示例——用户删了示例就别再长回来。 */
    static boolean hasAnyAdapter(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(path -> path.getFileName().toString().toLowerCase().endsWith(".json"));
        } catch (IOException failure) {
            return false;
        }
    }
}
