package com.dwinovo.numen.api.agent.catalog;

import com.dwinovo.numen.api.ai.AiLog;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 运行时从 models.dev 拉下来的那份目录,缓存在一个文件里(客户端放 {@code config/numen/cache/models-dev.json})。
 * 比随包快照新,所以优先级更高;文件不在或读不动就是空表,目录回头用快照。
 *
 * <p>配置目录和代理由客户端传进来,本类不知道 Minecraft。
 */
public final class DownloadCache implements CatalogSource {

    /** 缓存不到这么久不重拉。 */
    static final Duration MAX_AGE = Duration.ofDays(1);

    private final Path file;
    private final URI source;
    private volatile Map<String, ProviderInfo> providers;

    public DownloadCache(Path file) {
        this(file, ModelsDevDownload.API);
    }

    DownloadCache(Path file, URI source) {
        this.file = file;
        this.source = source;
        this.providers = read();
    }

    @Override
    public Map<String, ProviderInfo> providers() {
        return providers;
    }

    /**
     * 后台刷新:缓存不到一天就什么都不做;否则拉一次、落盘、换上新数据。拉不到(离线、被墙、对端出错)
     * 就保持现状,不报错——有缓存用缓存,没有用快照。future 里是"这次有没有换上新数据"。
     *
     * @param proxy {@code host:port},空 = 直连
     */
    public CompletableFuture<Boolean> refresh(String proxy) {
        if (isFresh()) {
            return CompletableFuture.completedFuture(false);
        }
        return ModelsDevDownload.slimmed(source, proxy).thenApply(slim -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, slim, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            providers = read();
            return true;
        }).exceptionally(e -> {
            AiLog.LOG.debug("[numen-catalog] models.dev refresh skipped: {}", e.toString());
            return false;
        });
    }

    private boolean isFresh() {
        try {
            return Files.exists(file) && Duration.between(
                    Files.getLastModifiedTime(file).toInstant(), Instant.now()).compareTo(MAX_AGE) < 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 文件不在或读不懂都是空表:缓存只是加速,坏了不该挡路。 */
    private Map<String, ProviderInfo> read() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            return java.util.Collections.unmodifiableMap(
                    ModelsDev.parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            AiLog.LOG.warn("[numen-catalog] unreadable cache {} — using the bundled snapshot ({})",
                    file, e.toString());
            return Map.of();
        }
    }
}
