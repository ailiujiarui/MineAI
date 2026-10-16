package com.dwinovo.numen.pathing.search;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * 抬高估价:LSS-LRTA* 的学习一步 [Koenig &amp; Sun 2009]。一段搜索没搜到这段的终点,展开过的节点({@code expanded})都不是终点,
 * 目标要经过它们没展开的边界才到得了,所以它们的估价至少是"沿着搜到的边走到边界上某个节点,再加上那个节点自己的估价"里最小的:
 * <pre>h'(s) = min over 边界上的 b ( 沿展开过的节点从 s 走到 b 的最短价钱 + h(b) )</pre>
 * 从边界往里做一次 Dijkstra。每一步的走法都只会让真实代价更高,所以 {@code h'} 仍是下界(不会比真实代价高),又不低于原来的 {@code h}。
 * 边界一个都没有(展开过的节点就是一切可达的地方)时,展开过的节点都得到无穷大:这一趟的规格下从这里到不了。
 *
 * <p>边只收"从展开过的节点出去的":每展开一个节点,它的每一步成立的走法记一条(落点、价钱)。
 */
final class Learning {

    private Learning() {}

    /**
     * @param expanded 节点展开过没有;没展开的就是边界
     * @param h        每个节点现在的估价
     * @param nodes    节点数
     * @param from     边的起点,必须是展开过的节点
     * @param to       边的落点
     * @param cost     边的价钱
     * @param edges    边数
     * @return 每个节点抬高之后的估价(边界不变,展开过的不低于原来的)
     */
    static double[] raise(boolean[] expanded, double[] h, int nodes, int[] from, int[] to, double[] cost, int edges) {
        // 按落点分组:谁能一步走到它
        int[] start = new int[nodes + 1];
        for (int e = 0; e < edges; e++) {
            start[to[e] + 1]++;
        }
        for (int n = 0; n < nodes; n++) {
            start[n + 1] += start[n];
        }
        int[] into = new int[edges];
        int[] fill = Arrays.copyOf(start, nodes);
        for (int e = 0; e < edges; e++) {
            into[fill[to[e]]++] = e;
        }

        double[] raised = new double[nodes];
        PriorityQueue<Queued> queue = new PriorityQueue<>();
        for (int n = 0; n < nodes; n++) {
            if (expanded[n]) {
                raised[n] = Double.POSITIVE_INFINITY;
            } else {
                raised[n] = h[n];
                if (Double.isFinite(h[n])) {
                    queue.add(new Queued(h[n], n));
                }
            }
        }
        while (!queue.isEmpty()) {
            Queued top = queue.poll();
            if (top.value > raised[top.node]) {
                continue;
            }
            for (int i = start[top.node]; i < start[top.node + 1]; i++) {
                int e = into[i];
                int s = from[e];
                double through = top.value + cost[e];
                if (through < raised[s]) {
                    raised[s] = through;
                    queue.add(new Queued(through, s));
                }
            }
        }
        for (int n = 0; n < nodes; n++) {
            if (expanded[n]) {
                // 估价不一致时(旧路折扣、憋气的档)走出来的价钱可能低于原估价:只升不降
                raised[n] = Math.max(raised[n], h[n]);
            }
        }
        return raised;
    }

    private record Queued(double value, int node) implements Comparable<Queued> {

        @Override
        public int compareTo(Queued other) {
            return Double.compare(value, other.value);
        }
    }
}
