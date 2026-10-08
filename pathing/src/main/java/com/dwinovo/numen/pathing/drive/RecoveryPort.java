package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.IPath;
import com.dwinovo.numen.pathing.search.baritone.PathCalculationResult;
import com.dwinovo.numen.pathing.search.baritone.recover.Recovery;
import com.dwinovo.numen.pathing.search.baritone.recover.StuckRecovery;

import net.minecraft.core.BlockPos;

/**
 * 段状态机与 vendored 恢复助手之间的那道缝。助手({@link StuckRecovery})只认 Baritone 的两样东西:
 * 一次计算的结论 {@link PathCalculationResult} 与一个节点 {@link BetterBlockPos}。段状态机这边只有
 * {@link SearchResult}/{@link Route} 与 {@link BlockPos},所以这里把前者翻成后者,再把助手交回的半程
 * {@link Recovery.Fallback} 翻回 {@link Route}。
 *
 * <p>助手不持有 finder:线上搜索(第二节接上 {@code BridgeSearch})把跑完的 {@code IPathFinder} 留在桥
 * 内部,{@link SearchResult} 没有把它交出来。于是"从搜索里再捞一段 {@code bestPathSoFar}/
 * {@code pathToMostRecentNodeConsidered}"这一步暂时到不了;交给助手的结论里带着的路线,是它现在唯一能
 * 交还的半程。等搜索侧把 finder(或它捞出的半程)交出来时,只需在这里构造带 finder 的助手、并把
 * {@link #routeOrNull} 换成搜索侧的翻译,段状态机与助手的接口不变。
 */
final class RecoveryPort {

    private final StuckRecovery recovery;

    RecoveryPort() {
        this.recovery = new StuckRecovery();
    }

    /** 一次搜索的结论交出去:交出路线就是一段半程,交不出就是失败。 */
    Recovery onResult(SearchResult result) {
        return recovery.onResult(calculation(result));
    }

    /** 卡住与否这一刻交出去;{@code current} 是身体此刻所在的节点,定不出为 null。 */
    Recovery onStuck(boolean stuck, BlockPos current) {
        return recovery.onStuck(stuck, current == null ? null
                : new BetterBlockPos(current.getX(), current.getY(), current.getZ()));
    }

    /**
     * 助手交回的一段半程对应的 Numen 路线;那段半程不是线上搜索交出的(而是它自己的 finder 捞的)时为
     * null——那需要搜索侧的翻译,见类注释。
     */
    static Route routeOrNull(Recovery.Fallback fallback) {
        return fallback.result().path() instanceof RoutePath path ? path.route() : null;
    }

    private static PathCalculationResult calculation(SearchResult result) {
        Route route = result.route();
        if (route == null) {
            return new PathCalculationResult(PathCalculationResult.Type.FAILURE);
        }
        PathCalculationResult.Type type = result.arrived()
                ? PathCalculationResult.Type.SUCCESS_TO_GOAL
                : PathCalculationResult.Type.SUCCESS_SEGMENT;
        return new PathCalculationResult(type, new RoutePath(route));
    }

    /** 不在目标里的兜底目标:线上搜索的路线自带目标,助手不读它,只为让 {@link IPath} 完整。 */
    private static final com.dwinovo.numen.pathing.search.baritone.Goal NO_GOAL =
            new com.dwinovo.numen.pathing.search.baritone.Goal() {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic(int x, int y, int z) {
                    return 0;
                }
            };

    /** 把 Numen 的一段半程路线扮成 vendored 的 {@link IPath},好让助手原样交还。 */
    private static final class RoutePath implements IPath {

        private final Route route;
        private final List<BetterBlockPos> positions;

        RoutePath(Route route) {
            this.route = route;
            List<BetterBlockPos> out = new ArrayList<>(route.legs().size() + 1);
            out.add(at(route.start()));
            for (Route.Leg leg : route.legs()) {
                out.add(at(leg.maneuver().to()));
            }
            this.positions = List.copyOf(out);
        }

        private static BetterBlockPos at(BlockPos pos) {
            return new BetterBlockPos(pos.getX(), pos.getY(), pos.getZ());
        }

        Route route() {
            return route;
        }

        @Override
        public List<BetterBlockPos> positions() {
            return positions;
        }

        @Override
        public BetterBlockPos getSrc() {
            return positions.get(0);
        }

        @Override
        public BetterBlockPos getDest() {
            return positions.get(positions.size() - 1);
        }

        @Override
        public com.dwinovo.numen.pathing.search.baritone.Goal getGoal() {
            return NO_GOAL;
        }

        @Override
        public int length() {
            return positions.size();
        }

        @Override
        public int getNumNodesConsidered() {
            return 0;
        }

        @Override
        public IPath postProcess() {
            return this;
        }
    }
}
