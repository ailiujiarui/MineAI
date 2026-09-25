package com.dwinovo.numen.agent.recover;

/**
 * 停滞检测:连着几次看到同一个"进度签名"就判原地打转。
 *
 * <p>进度签名由宿主给(比如"目标物品:当前数量")。签名不变 = 世界没因她而动 = 该换法子了。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class ProgressTracker {

    private final int window;
    private String last = "";
    private int repeats;

    public ProgressTracker() {
        this(3);
    }

    /** @param window 连续这么多次看到同一个签名就判停滞 */
    public ProgressTracker(int window) {
        this.window = Math.max(1, window);
    }

    /** 看一次签名,返回是不是停滞了。 */
    public boolean observe(String signature) {
        String value = signature == null ? "" : signature;
        if (value.equals(last)) {
            repeats++;
        } else {
            last = value;
            repeats = 1;
        }
        return repeats >= window;
    }

    public void reset() {
        last = "";
        repeats = 0;
    }
}
