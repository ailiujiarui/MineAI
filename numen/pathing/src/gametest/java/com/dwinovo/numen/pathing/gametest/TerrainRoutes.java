package com.dwinovo.numen.pathing.gametest;

import java.util.List;
import java.util.function.Predicate;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 固定存档 {@code hills}(种子 0,区域 r.0.0,见 terrain/README.md)上的固定路线。起终点是站立的那一格(地面之上一格),
 * 常量是从制作存档时的勘测表上挑的,重做存档要重新挑。除了 long_300 和最后两条游泳路线,路线全程避开水(两侧各两格内都没有)。
 *
 * <p>类型:平地直行、上坡、下坡、陡坎(中途有一级 5 格以上的落差)、穿树林、树林里上坡、绕障碍(直线中间隔着 30 格以上的断崖)、
 * 起伏、长距离(160 格)。时限是够从容走完的刻数,超过就算没走到。
 *
 * <p>最后两条是地下的目标:从地面到封闭石头里的一格(像主人开了观察模式钻进石头),和到地下一个空腔(开跑前在那里挖出一间 3×3×3 的空房)。
 * 每条路线跑完,开跑前挖的和导航动过的格子都还原,挖出来的不会留给后面的路线。走路默认可挖可搭、只看价钱,这两条量的是往下挖着去的账。
 *
 * <p>后一批是难路线,全是地形里天然存在的:峡谷(30 格深的裂缝)底、跨峡谷、被高地或山脊挡住要绕远、掉进盆地、300 格以上的长路、
 * 破碎地形、密林。起终点按勘测表挑,没有照寻路能不能走通来挑;走不通的原样记着,是后面分析的素材。
 */
final class TerrainRoutes {

    private TerrainRoutes() {}

    /** 开跑前挖成空气的一个盒子,两个对角都含在内。 */
    record Box(BlockPos min, BlockPos max) {}

    /** 开跑前把一个盒子填成 {@code block}。 */
    record Fill(Box box, Block block) {}

    /**
     * 悬在地形上空的一间小场地(地板、墙、沟都是现搭的,四周是空,走出去就摔死):硬挡住的路线,只量"照实收场,原因对",与存档的地形无关。
     *
     * @param fills       开跑前搭的方块,按先后
     * @param noEdit      许可在这些盒子里拒绝挖与放
     * @param cobblestone 身上带几块圆石(垫路料)
     * @param expected    应当怎样收场:结局要满足它;没走到才是对的
     */
    record Stage(List<Fill> fills, List<Box> noEdit, int cobblestone, Predicate<Outcome> expected) {}

    /**
     * 一条固定路线。
     *
     * @param carve  开跑前挖成空气的盒子(空房、通道);没有为空表
     * @param buried 终点那一格得是封闭的实心(六面都是实心方块):核对存档里那一格还是石头
     * @param reach  目标是"离终点这么近以内"({@code within(at(终点), 0, reach)},与模型调 move.to(主人, reach) 同口径);0 是走到终点那一格
     * @param stage  悬空搭的硬挡场地;存档里的路线为 null
     */
    record Route(String name, String kind, BlockPos from, BlockPos to, int limit, List<Box> carve, boolean buried, int reach,
                 Stage stage) {

        Route(String name, String kind, BlockPos from, BlockPos to, int limit, List<Box> carve, boolean buried, int reach) {
            this(name, kind, from, to, limit, carve, buried, reach, null);
        }

        Route(String name, String kind, BlockPos from, BlockPos to, int limit) {
            this(name, kind, from, to, limit, List.of(), false, 0, null);
        }

        /** 这条路线的目标。 */
        Goal goal() {
            return reach == 0 ? Goals.at(to) : Goals.within(Goals.at(to), 0, reach);
        }

        /** 起终点的水平直线距离。 */
        double distance() {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** 终点比起点高几格。 */
        int rise() {
            return to.getY() - from.getY();
        }
    }

    static final Route FLAT = new Route("flat", "平地直行", new BlockPos(343, 67, 212), new BlockPos(252, 63, 133), 1500);
    static final Route UPHILL = new Route("uphill", "上坡", new BlockPos(340, 68, 257), new BlockPos(226, 106, 294), 2000);
    static final Route DOWNHILL = new Route("downhill", "下坡", new BlockPos(238, 105, 298), new BlockPos(344, 67, 241), 2000);
    static final Route STEP = new Route("step", "陡坎", new BlockPos(290, 64, 165), new BlockPos(254, 66, 213), 1200);
    static final Route FOREST = new Route("forest", "穿树林", new BlockPos(128, 71, 343), new BlockPos(155, 68, 268), 1500);
    static final Route FOREST_UPHILL = new Route("forest_uphill", "树林上坡", new BlockPos(231, 85, 355), new BlockPos(213, 106, 298), 1500);
    static final Route WALL = new Route("wall", "绕障碍", new BlockPos(251, 70, 238), new BlockPos(311, 68, 238), 1500);
    static final Route LONG_A = new Route("long_a", "长距离", new BlockPos(311, 65, 174), new BlockPos(348, 72, 330), 3000);
    static final Route LONG_B = new Route("long_b", "长距离", new BlockPos(132, 69, 359), new BlockPos(193, 69, 211), 3000);
    static final Route ROLLING = new Route("rolling", "起伏", new BlockPos(306, 78, 273), new BlockPos(213, 69, 237), 2000);

    static final Route RAVINE_FLOOR = new Route("ravine_floor", "下到峡谷底", new BlockPos(300, 65, 228), new BlockPos(278, 32, 242), 6000);
    static final Route RAVINE_CROSS = new Route("ravine_cross", "跨峡谷", new BlockPos(300, 66, 215), new BlockPos(262, 87, 262), 1500);
    static final Route RIDGE_DETOUR = new Route("ridge_detour", "山脊挡路绕远", new BlockPos(123, 69, 164), new BlockPos(83, 95, 203), 2000);
    static final Route HILL_DETOUR = new Route("hill_detour", "高地挡路绕远", new BlockPos(340, 64, 411), new BlockPos(300, 86, 371), 2000);
    static final Route BASIN_DESCENT = new Route("basin_descent", "下到盆地", new BlockPos(168, 72, 359), new BlockPos(245, 44, 425), 20000);
    static final Route LONG_300 = new Route("long_300", "300格长路", new BlockPos(70, 70, 130), new BlockPos(440, 86, 100), 4000);
    static final Route BROKEN = new Route("broken", "破碎地形", new BlockPos(205, 88, 398), new BlockPos(300, 87, 440), 2500);
    static final Route DENSE_FOREST = new Route("dense_forest", "密林", new BlockPos(403, 70, 64), new BlockPos(444, 86, 104), 2000);

    /** 游泳的两条,留给以后的"征服水"当起点基线:横渡一道六十格宽的深水(最深 27 格),和穿过深浅交错的一段湖(深水里夹着一格深的浅滩)。 */
    static final Route LAKE_CROSS = new Route("lake_cross", "横渡深湖", new BlockPos(156, 63, 183), new BlockPos(217, 63, 183), 1200);
    static final Route LAKE_SHALLOWS = new Route("lake_shallows", "深浅交错的湖", new BlockPos(217, 63, 138), new BlockPos(250, 63, 138), 1200);

    /** 地下的两条:从平地出发,往下挖着去封闭石头里的一格 / 一间挖出来的空房。 */
    static final Route BURIED_CELL = new Route("buried_cell", "到封闭石头里的一格", new BlockPos(343, 67, 212), new BlockPos(343, 60, 212),
            1500, List.of(), true, 0);
    static final Route BURIED_CAVITY = new Route("buried_cavity", "到地下一个空腔", new BlockPos(311, 65, 174), new BlockPos(311, 56, 174),
            1500, List.of(new Box(new BlockPos(310, 56, 173), new BlockPos(312, 58, 175))), false, 0);

    /**
     * 远处的地下三条(第 9 步粗层估价的尺子):终点都在起点几十到一百格外的地下,量"估价看不见远处的挖与走"。选址依据是对存档做过的
     * 一次扫描(不进仓库):
     * <ul>
     *   <li>rock_far:起点在峡谷底(井底),斜向八十多格、落差 30 格,终点在实心岩石里,开跑前在终点挖出一格能站的(脚与头两格)。
     *       起点到终点的整条直线(身高两格)上没有一格空气或流体,终点周围 7×8×7 全是实心,终点离它头顶的地表一百多格,中间没有现成的通道。
     *       目标是离终点两格以内,与模型调 move.to(主人, 2) 同口径;</li>
     *   <li>cave_far:终点是存档里 x 251..379、z 148..266 的一座大洞穴(26631 格)底部的落脚点,起点在地面,水平九十多格、落差 52 格。
     *       按"空气一步、实心一千步"的最短路,起点到终点只需挖 2 格,但要在洞里绕 300 步;</li>
     *   <li>tunnel_end:起点在中央山丘的坡脚,开跑前沿水平挖出一条隧道(向 +z 28 格、向 +x 22 格、再向 +z 16 格,共 66 格,两个拐弯,
     *       宽一格高两格),终点在隧道尽头;隧道离地面至少三格岩,看她顺着隧道走还是另挖。</li>
     * </ul>
     */
    static final Route ROCK_FAR = new Route("rock_far", "从峡谷底穿实心岩到远处", new BlockPos(276, 31, 240), new BlockPos(205, 1, 295), 24000,
            List.of(new Box(new BlockPos(205, 1, 295), new BlockPos(205, 2, 295))), false, 2);
    static final Route CAVE_FAR = new Route("cave_far", "到远处洞穴底", new BlockPos(327, 67, 232), new BlockPos(362, 15, 148), 12000);
    static final Route TUNNEL_END = new Route("tunnel_end", "到隧道尽头", new BlockPos(256, 80, 252), new BlockPos(278, 80, 296), 1000,
            List.of(new Box(new BlockPos(256, 80, 253), new BlockPos(256, 81, 280)),
                    new Box(new BlockPos(257, 80, 280), new BlockPos(278, 81, 280)),
                    new Box(new BlockPos(278, 80, 281), new BlockPos(278, 81, 296))), false, 0);

    /** 连续台阶与连续下坡:存档里一道笔直的十二级台阶(沿 +x 每格高一级),和一段每隔几格落一级的下坡,量上坡、下坡时身体的步态。 */
    static final Route STAIRS_UP = new Route("stairs_up", "连续台阶上", new BlockPos(419, 74, 105), new BlockPos(432, 86, 105), 600);
    static final Route SLOPE_DOWN = new Route("slope_down", "连续下坡", new BlockPos(283, 91, 274), new BlockPos(312, 70, 272), 600);

    // ==================== 硬挡住的路线 ====================

    /** 场地的地板:从 x=240 到 264、宽五格(z 254..258),在 y=199,悬在存档的地形上空。 */
    private static Fill floor(int x0, int x1) {
        return new Fill(new Box(new BlockPos(x0, 199, 254), new BlockPos(x1, 199, 258)), Blocks.STONE);
    }

    private static Fill wall(int x0, int x1, Block block) {
        return new Fill(new Box(new BlockPos(x0, 200, 254), new BlockPos(x1, 203, 258)), block);
    }

    private static final BlockPos STAGE_FROM = new BlockPos(241, 200, 256);
    private static final BlockPos STAGE_TO = new BlockPos(263, 200, 256);

    /** 禁区挡在中间:三格厚的石墙隔断整条场地,墙所在的 x 范围整片(从底到顶、整个区域)是许可不让挖放的禁区——绕不过去,结局是"许可拒绝",点出墙上的一格。 */
    static final Route ZONE_BETWEEN = new Route("zone_between", "禁区挡在中间", STAGE_FROM, STAGE_TO, 600, List.of(), false, 0,
            new Stage(List.of(floor(240, 264), wall(250, 252, Blocks.STONE)),
                    List.of(new Box(new BlockPos(248, 0, 0), new BlockPos(254, 319, 511))), 0,
                    o -> o instanceof Outcome.Denied d && d.cell().getX() >= 250 && d.cell().getX() <= 252));

    /** 方块不够过河:六格宽的沟(下面是虚空),身上只有三块圆石——搭了三块用完,结局是"缺料",说出这条路还要放几块。 */
    static final Route BRIDGE_SHORT = new Route("bridge_short", "方块不够过河", STAGE_FROM, STAGE_TO, 800, List.of(), false, 0,
            new Stage(List.of(floor(240, 246), floor(253, 264)), List.of(), 3,
                    o -> o instanceof Outcome.NoMaterials n && n.needed() > 0));

    /**
     * 没有镐挖不动黑曜石:封死的走廊(地、四壁、顶与两头是基岩,垫路料也没处可垫)里横着两格厚的黑曜石墙,空手——结局是"没有路",原因是挖不动。
     * 走廊封死,是为了让"有料能不能绕过去"的诊断也答不出路,留下的原因只有黑曜石。
     */
    static final Route OBSIDIAN_HAND = new Route("obsidian_hand", "没有镐挖黑曜石", STAGE_FROM, STAGE_TO, 600, List.of(),
            false, 0, new Stage(List.of(
                    new Fill(new Box(new BlockPos(240, 199, 253), new BlockPos(264, 203, 253)), Blocks.BEDROCK),
                    new Fill(new Box(new BlockPos(240, 199, 259), new BlockPos(264, 203, 259)), Blocks.BEDROCK),
                    new Fill(new Box(new BlockPos(240, 203, 254), new BlockPos(264, 203, 258)), Blocks.BEDROCK),
                    new Fill(new Box(new BlockPos(240, 199, 254), new BlockPos(264, 199, 258)), Blocks.BEDROCK),
                    new Fill(new Box(new BlockPos(240, 199, 253), new BlockPos(240, 203, 259)), Blocks.BEDROCK),
                    new Fill(new Box(new BlockPos(264, 199, 253), new BlockPos(264, 203, 259)), Blocks.BEDROCK),
                    new Fill(new Box(new BlockPos(250, 200, 254), new BlockPos(251, 202, 258)), Blocks.OBSIDIAN)), List.of(), 0,
                    o -> o instanceof Outcome.NoRoute n && n.cause() != null
                            && n.cause().reason() == com.dwinovo.numen.pathing.plan.Reason.TOO_HARD));

    static final List<Route> ALL = List.of(FLAT, UPHILL, DOWNHILL, STEP, FOREST, FOREST_UPHILL, WALL, LONG_A, LONG_B, ROLLING,
            RAVINE_FLOOR, RAVINE_CROSS, RIDGE_DETOUR, HILL_DETOUR, BASIN_DESCENT, LONG_300, BROKEN, DENSE_FOREST,
            LAKE_CROSS, LAKE_SHALLOWS, BURIED_CELL, BURIED_CAVITY, STAIRS_UP, SLOPE_DOWN, ROCK_FAR, CAVE_FAR, TUNNEL_END,
            ZONE_BETWEEN, BRIDGE_SHORT, OBSIDIAN_HAND);
}
