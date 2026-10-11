package com.dwinovo.numen.agent.resume;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 回来时算一遍"她不在的这段时间世界变了什么":拿离开时那枚有界指纹({@link WorldSample})对着当下重读一遍,
 * 得出变了的方块与进出的物品。差异是<b>现算</b>的——存下来的只有那枚小指纹,没有第二份世界模型,
 * 也就没有"两份状态各自漂移"这回事:世界是源头,这里只是把两次观察放在一起看。
 *
 * <p>有界:方块差异最多 {@link #MAX_CHANGES} 条、物品差异最多 {@link #MAX_ITEMS} 条,超出的截断并如实标注。
 *
 * <p>纯 JVM:真正的方块由宿主经 {@link Reader} 注入,测试给个假读者就够。
 */
public final class ResumeDiff {

    /** 方块差异的条数上限——"她走了这段时间附近变了什么"该一眼看完,不是一份完整审计。 */
    public static final int MAX_CHANGES = 24;
    /** 物品差异的条数上限。 */
    public static final int MAX_ITEMS = 12;

    private ResumeDiff() {}

    /** 当下世界的只读口:绝对格上的方块 id;读不到(没加载、维度不对)返回 {@code null}。 */
    @FunctionalInterface
    public interface Reader {
        String blockAt(String dimension, int x, int y, int z);
    }

    /** 一格方块从 {@code before} 变成了 {@code after}。 */
    public record Change(int x, int y, int z, String before, String after) {}

    /**
     * @param moved     她现在离离开时那格多少格(采样帧偏了多远)
     * @param blocks    变了的方块,按坐标排好
     * @param gained    多出来的物品 id → 增量
     * @param lost      少掉的物品 id → 减量
     * @param truncated 差异超过上限,后面还有没列出来的
     */
    public record Result(int moved, List<Change> blocks, Map<String, Integer> gained,
                         Map<String, Integer> lost, boolean truncated) {
        public boolean empty() {
            return blocks.isEmpty() && gained.isEmpty() && lost.isEmpty() && moved == 0;
        }
    }

    /**
     * 算一遍差异。{@code before} 里记着哪些格、就核哪些格;读不出来的跳过(帧偏了或没加载),
     * 不把它当成"变了"。物品按 id 并集比总数。
     */
    public static Result compute(WorldSample before, Map<String, Integer> nowItems,
                                 int nowX, int nowY, int nowZ, Reader now) {
        int moved = (int) Math.round(Math.sqrt(sq(nowX - before.x()) + sq(nowY - before.y())
                + sq(nowZ - before.z())));

        List<Change> changes = new ArrayList<>();
        boolean truncated = false;
        for (Map.Entry<String, String> e : before.blocks().entrySet()) {
            int[] cell = WorldSample.cell(e.getKey());
            if (cell == null) {
                continue;
            }
            String actual = now.blockAt(before.dimension(), cell[0], cell[1], cell[2]);
            if (actual == null || actual.equals(e.getValue())) {
                continue;
            }
            if (changes.size() >= MAX_CHANGES) {
                truncated = true;
                break;
            }
            changes.add(new Change(cell[0], cell[1], cell[2], e.getValue(), actual));
        }

        Map<String, Integer> gained = new TreeMap<>();
        Map<String, Integer> lost = new TreeMap<>();
        Set<String> ids = new TreeSet<>();
        ids.addAll(before.items().keySet());
        ids.addAll(nowItems.keySet());
        int listed = 0;
        for (String id : ids) {
            int was = before.items().getOrDefault(id, 0);
            int is = nowItems.getOrDefault(id, 0);
            if (was == is) {
                continue;
            }
            if (listed >= MAX_ITEMS) {
                truncated = true;
                break;
            }
            if (is > was) {
                gained.put(id, is - was);
            } else {
                lost.put(id, was - is);
            }
            listed++;
        }
        return new Result(moved, List.copyOf(changes), gained, lost, truncated);
    }

    /**
     * 差异写成一段给模型看的话;没差异返回空串。她读的是"变化",不是坐标堆——但方块变化带一格坐标,
     * 因为她要能据此决定去不去看,这里与说话规则(不念坐标)不冲突:这是注入的事实不是她说的话。
     */
    public static String format(Result r) {
        if (r.empty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("While you were away, the world near where you left changed.");
        if (r.moved() > 0) {
            sb.append(" You are now ").append(r.moved()).append(" blocks from where you left.");
        }
        if (!r.blocks().isEmpty()) {
            sb.append(" ").append(r.blocks().size()).append(" sampled block(s) changed: ");
            for (int i = 0; i < r.blocks().size(); i++) {
                Change c = r.blocks().get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(c.before()).append(" -> ").append(c.after()).append(" at ")
                        .append(c.x()).append(',').append(c.y()).append(',').append(c.z());
            }
            sb.append('.');
        }
        if (!r.gained().isEmpty()) {
            sb.append(" You gained ").append(items(r.gained())).append('.');
        }
        if (!r.lost().isEmpty()) {
            sb.append(" You lost ").append(items(r.lost())).append('.');
        }
        if (r.truncated()) {
            sb.append(" (More changed than is listed — go look.)");
        }
        return sb.toString();
    }

    private static String items(Map<String, Integer> totals) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(" x").append(e.getValue());
        }
        return sb.toString();
    }

    private static long sq(long v) {
        return v * v;
    }
}
