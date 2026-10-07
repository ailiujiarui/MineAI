# Baritone 寻路替换(进行中)

状态:进行中(2026-10-06)。这是一次**部分替换**:保留本模块自己的分层与门面,只把搜索内核换成 vendor 进来的 Baritone A*。
第一步(vendor)已完成,第二步(接上真身)由其他改动并行接线,第三步起(移动代价、卡住/回退恢复)未开始。
本文件是这条替换线的唯一说明,取代旧稿里散落的记录;整条寻路的设计见 `pathing.md`。

## 一、为什么换

20261006-132531 那次真实模型评测(提交 `baa5ccf63+dirty`,模型 `deepseek/deepseek-v4.1-flash`)里,失败最集中的一处是移动:

| 函数 | 调用 | 失败率 | 其他失败 |
|---|---|---|---|
| `numen.move.go` | 200 | 20% | `no_path` × 40 |

**before 数:`numen.move.go` 200 次调用里 40 次 `no_path`,即 20%。**

`no_path` 来自规划器在"绕开的代价比直走贵"的地形上对贪心/半截搜索收敛太早:凹形障碍、回廊、目标在一个口朝外的口袋里时,
只有严格朝目标靠近的步子才被接受,于是卡在墙前、口子对面,搜完就报没路。Baritone 的 A*(开着集、重开节点、按 g 代价松弛)在这些
地形上找得到绕行。换的是搜索内核,不是整套体系。

## 二、分几步

**第一步 vendor Baritone A*(已完成)。** 按 Baritone 1.21.1(LGPL-3.0)原样搬进来,去掉 Minecraft 依赖:

- 目录:`pathing/src/main/java/com/dwinovo/numen/pathing/search/baritone/`
  - `calc/AStarPathFinder.java`:A* 主循环。超时检查、取消检查、按 move 展开、favoring、`MIN_IMPROVEMENT` 松弛、
    `bestSoFar` 的算法原样保留;世界查询改从 `CalculationContext` 读。
  - `calc/AbstractNodeCostSearch.java`:系数表、`bestSoFar`、节点表、`calculate()` 原样保留;去掉 Baritone 设置与日志、
    去掉"已加载区块在静态截断"。
  - `calc/Path.java`、`calc/PathNode.java`、`calc/openset/{BinaryHeapOpenSet,LinkedListOpenSet,IOpenSet}.java`:节点、路径、开集。
  - `CalculationContext` / `Goal` / `Move` / `MutableMoveResult` / `Favoring` / `IPath` / `IPathFinder` / `PathCalculationResult` /
    `ActionCosts` / `BetterBlockPos`:搜索核心只读的最小接口面,不含 Minecraft。
- 这一层现在**没人调用**(生产代码没有引用),是"已 vendor、未接线"。

**第二步 接上真身(live seam,进行中)。** 把本模块的搜索入口指向新的 A*,让 `numen.move.go` 走这条 A*。接线要守住:
门面、预算口径、停因(`ARRIVED`/`EXHAUSTED`/`BUDGET`/`UNLOADED`/`CANCELLED`)与现有 `SearchResult` 的语义不变;
新搜索的 `PathCalculationResult` 要翻译成同一套结论,别让调用方看见两套。

**第三步 移动代价。** 把每种走法(平走、上一级、下落、跑酷、垫柱、挖穿……)的前提与代价接到新搜索的 `Move`/`CalculationContext`
上;`ActionCosts` 目前只有 `COST_INF` 哨兵,真实代价仍在 `plan/` 的成本模型里。目标族同理接到 `Goal` 接口。

**第四步 卡住/回退恢复。** 执行层发现"这一步没发生""重规划算出同一条""原地打转"时的回退与再规划(Baritone 的 `recover` 一路),
由另外的包 `.../baritone/recover/`(并行改动)补上。

**第五步 对比。** 接线前后各跑一遍真实模型评测,对比 `numen.move.go` 的 `no_path` 与整体成功率、pass^k、命令出错率、
轮数、墙钟。评测工具本轮已移除,口径见第四节。

## 三、回归测试(本文件对应的一步)

测试在 `pathing/src/test/java/com/dwinovo/numen/pathing/search/baritone/`:

- `NoPathRegressionTest.java`(新增,本次):三个小合成世界,断言**贪心走法报没路、vendored A* 找得到最便宜的绕行**。
  - `aWallWhoseGapIsOffTheStraightLineStopsTheGreedyWalk`:一道竖墙、缺口不在直线上;贪心停在墙前,A* 绕 18 步。
  - `aSerpentineCorridorAbandonedByTheGreedyWalkIsFollowed`:一格宽的蛇形回廊;贪心在一个拐角放弃,A* 走完 23 步。
  - `aPocketWhoseMouthFacesAwayFromTheStartIsEnteredFromBehind`:目标在口朝外(U 形)的口袋里;贪心撞在南墙上,A* 从背面口子进,17 步。
  - 每个用例都顺带校验:路径逐格相邻、不穿墙,长度等于已知最优——不是"找到就行",而是找到对的那条。
  - 现在直接调 `AStarPathFinder.calculate(...)`,所以**不需要接线就能过**。第二步接好后,应把这几个世界改指到接好线的入口,
    作为"新搜索真的在服务 `numen.move.go`"的第一道验收。
- `BaritoneAStarTest.java`、`calc/openset/OpenSetTest.java`:已有的 vendor 单测(绕墙、封死目标报失败、开集顺序/降键),
  不是本次新增,一并作为这一层的地基。

测试用到的最小假世界(网格 `CalculationContext`、四向 `Move`、单元 `Goal`)都在 `NoPathRegressionTest` 里,不依赖 Minecraft。

## 四、怎么量 `numen.move.go no_path`(前后)

`no_path` 是工具结果里的 `error_kind`,原先靠 `bench/` 的真模型评测(每个函数表、`runs.jsonl`/`transcripts`)逐条读出来。
`bench/` 评测已随本轮清理移除,刻画口径留作历史记录;接线后的对照数据需另行采集。当前已知的 before 基线(20261006-132531):
`numen.move.go` 200 次、20% 失败、`no_path×40`。

## 五、边界

- 本次只动测试与本文档:不碰 `src/main`、`bench/`、`settings.gradle`、CI,不跑 Gradle,不提交。
- vendor 代码按 LGPL-3.0 保留版权头;改动集中在"去掉 Minecraft/设置/日志、换成接口"。
- 接线与移动代价由并行的改动负责;本文随进度更新,不预先宣布未完成的事。
