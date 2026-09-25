package com.dwinovo.numen.agent.acceptance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RCON 靶场:验收骨骼伸进 Minecraft 的那一只手。
 *
 * <p>它做两件事,分得很清:开跑前用 {@link #apply} 把世界摆成题面(清场、放方块、发物品),
 * 收尾时用 {@link #observe} 把谓词的每条探针问一遍。两件事都走服务端控制台——
 * <b>模型看不到这条通道,也就借不到它</b>。
 *
 * <p>起服一端只需在 {@code server.properties} 里打开 {@code enable-rcon=true}、
 * 设好 {@code rcon.port} 与 {@code rcon.password}。靶场的地址从
 * {@link RconConfig#fromSystemProperties} 读。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class RconRange implements ObservationSource, AutoCloseable {

    private final RconClient client;

    public RconRange(RconConfig config) {
        this.client = new RconClient(config);
    }

    /** 连好再交回来。 */
    public static RconRange open(RconConfig config) throws IOException {
        RconRange range = new RconRange(config);
        range.client.connect();
        return range;
    }

    /** 摆题面用的一行;返回服务端回话。 */
    public String run(String command) throws IOException {
        return client.command(command);
    }

    /** 按顺序跑几行摆场命令,返回各自回话。 */
    public List<String> apply(String... commands) throws IOException {
        List<String> out = new ArrayList<>(commands.length);
        for (String command : commands) {
            out.add(client.command(command));
        }
        return out;
    }

    @Override
    public Observation observe(List<Probe> probes) {
        long now = System.currentTimeMillis();
        Map<String, String> outputs = new LinkedHashMap<>();
        try {
            for (Probe probe : probes) {
                outputs.put(probe.id(), client.command(probe.command()));
            }
            return Observation.of(now, outputs);
        } catch (IOException unreachable) {
            return Observation.unreachable(now, unreachable.getMessage());
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
