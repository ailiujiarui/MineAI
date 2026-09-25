package com.dwinovo.numen.agent.acceptance;

import java.util.List;

/**
 * 世界量测的来源。真实靶场是 {@link RconRange};测试给一个内存实现即可。
 *
 * <p>它是验收骨骼与 Minecraft 之间唯一的缝:谓词只认识这里,不知道 RCON、也不知道服务端。
 */
@FunctionalInterface
public interface ObservationSource {

    /** 跑完这批探针,回一次快照。连不上就回 {@link Observation#unreachable}。 */
    Observation observe(List<Probe> probes);
}
