# Minecraft Bot 评测入口

`evals/` 保存 Numen 游戏 Agent 的 benchmark 定义、评分规则、基线结果和验证证据，用于回答：bot 能完成哪些游戏任务、玩家指令能否正确影响行动，以及改动后能力是否有所变化。

## 当前评测与已记录成绩

以下汇总截至 **2026-10-04** 的已归档基线。先看“已记录成绩”，点击“结果记录”可查看原始评分与证据；这是各次运行留下的成绩，不表示后续代码改动已重新测过。

### 已经运行

| 评测 | 测什么、怎么测 | 已记录成绩 | 主要发现 | 结果记录 |
| --- | --- | --- | --- | --- |
| **规划组件：6 例** | 依赖顺序、已有库存、共享材料、合成余料、无法完成时的返回；真实 JVM 模块配合有限资源夹具 | **4/6 通过，2 例失败** | 共享库存重复分配；合成批次余料未复用 | [评分汇总](bot-v1/baselines/cached-233/summary.json)、[逐例证据](bot-v1/baselines/cached-233/results.jsonl) |
| **循环响应：6 例** | 空闲跟随、推理中改口、工具执行中补充输入、两类按钮停止、自然语言停止；真实 AgentLoop 配合脚本模型／工具 | **4/6 通过，2 例失败** | 改口后仍派发旧指令；自然语言“停下”没有即时取消 | [评分汇总](bot-v1/baselines/cached-233/summary.json)、[逐例证据](bot-v1/baselines/cached-233/results.jsonl) |
| **实际身体：6 例** | 跟随、停止、停止后保留自保且不恢复旧任务、任务替换返回、定时器并行、自卫抢占；真实 Minecraft GameTest | **6/6 通过** | 固定场地符合全部六项判据；尚不代表复杂地形或真实玩家整链表现 | [评分汇总](bot-v1/baselines/cached-233/summary.json)、[逐例证据](bot-v1/baselines/cached-233/results.jsonl) |
| **真实模型：合成木板** | 背包 2 个橡木原木变为至少 8 个木板；真实模型驱动游戏 | **任务成功，约 5.08 秒** | 当时开发环境可能含测试工具，保留物理成功证据，不作为干净环境的可比基线 | [运行结果](live-v1/baselines/deepseek-v4.1-flash-smoke/results/result.json)、[成绩边界](live-v1/README.md) |
| **真实模型：指定农业据点（旧场景）** | 空背包建立工具、工作站、指定小麦循环、食物储备和规定形状的据点；服务端独立观察 | **三次正式 dev 运行均未完成，各达成 1/6 个里程碑**；最新修复后运行在 10 分 41 秒耗尽 token 预算 | 最新 45 次请求均返回用量，无 SSE 超时；工具在 7 分 41 秒达标，熔炉仍未放置，农业、食物和庇护所未达标。这些是指定任务成绩，不代表自由生存能力 | [修复后最新结果](live-v1/baselines/deepseek-v4.1-flash-survival-postfix-20261004/summary.json)、[修复前复跑](live-v1/baselines/deepseek-v4.1-flash-survival-20261004/summary.json)、[首次结果](live-v1/baselines/deepseek-v4.1-flash-survival/results/result.json) |
| **真实模型：自由前期生存与补给** | 空背包、公开初始饱食 14／20，自选食物和避险路线；检查真实有效进食、食物储备及持续存活 | **扩大预算的对照运行成功，4/4，约 21 分钟**；原预算运行 2/4，因 token 耗尽未完成 | 同源码、模型、提示与评分；新预算 400 请求／1700 秒／3000 万 token。最终 HP20、food14、储备24，实际存活25206 tick，连续保持1202 tick。186 次请求，已报告约1349万 token，用量不完整 | [扩大预算结果](live-v1/baselines/deepseek-v4.1-flash-free-survival-v2-budget-20261004/summary.json)、[原预算结果](live-v1/baselines/deepseek-v4.1-flash-free-survival-v2-20261004/summary.json)、[场景定义](live-v1/scenarios/free-survival.json) |

前三行来自 `bot-v1.2` 分层基线，**不调用真实模型**；身体基线使用 NeoForge `21.1.233`。其余三行使用 B.AI 的 `deepseek-v4.1-flash`。木板有一次已归档结果，农业专项有三次 `survival-v1` 正式 dev 结果，自由生存有两次 `survival-v2` 正式 dev 结果。前两次农业运行的源码、场景及预算相同；第三次修复传输解析和自身背包查询，并启用聊天自动导出，保持模型、提示、场景、判分及预算不变。自由生存改了目标、初始饥饿及评分，旧农业分数不能与之直接比较；自由生存第二轮仅扩大请求数和 token 上限，时间仍为1700秒。种子均为73002，农业首次出生高度为66，后两次为78，自由生存两轮均为66；独立生成的世界和模型行为仍有随机性，单次成功不能代表稳定成功率。运行预算、模型用量、环境和源码摘要见各结果文件。

**成绩口径：** 分层评测的 `4/6` 表示六个样例中四个符合判据，规划通过项包含“正确报告无法完成”；农业的 `1/6` 和自由生存的 `2/4` 表示各自未完成任务中首次达到的里程碑。自由生存扩大预算后为`4/4`，并满足全部最终条件的连续保持要求，因此整场成功。上述已运行生存场景的里程碑均已接入独立观察与判分，比例不是开发进度。各层成绩不合并成“总智能分”，单次开发样例也不能当作总体成功率；真实玩家“第一条正确回应／指令影响身体”的完整延迟仍未评分。

### 已定义但尚未运行

| 评测 | 包含的目标或场景 | 当前状态与入口 |
| --- | --- | --- |
| **完整能力场景：4 例** | 从零准备前置条件；多目标共享资源；冶炼期间采集并回来取产物；资源点失效后重规划 | 原清单中均为 **未运行**；对应场景专用的组织、注入或判分接入尚待完成。见 [cases.json](bot-v1/cases.json) 的 `live.prerequisites`、`live.shared_resources`、`live.smelting_parallel`、`live.replanning` |
| **完整响应场景：6 例** | 空闲时跟随；推理中改口；挖矿中返回；点击停止；自然语言停止；挖矿中遇袭后战斗或逃跑 | 原清单中均为 **未运行**；尚未完成专门的整链事件注入与响应判分。见 [cases.json](bot-v1/cases.json) 的其余六个 `live.*` 样例 |
| **真实模型：初期物资生存** | 空背包取得石镐、熔炉、8 个火把、3 个熟牛肉，同时维持生命与饱食 | 已有[可运行场景定义](live-v1/scenarios/initial-survival.json)，**尚无真实运行成绩**；它检查背包物资，与可持续生存分开记录 |
| **真实模型：第二阶段远征准备** | 固定初期物资出发，穿全套铁甲，备齐镐、盾、水桶、食物、火把和便携工作站；逐件耐久至少 80% | [场景、评分与运行方式](live-v1/README.md#expedition-v1)已实现；[校准与验证记录](live-v1/expedition-verification.json)单列，**尚无真实模型成绩**，不是上一阶段存档续跑 |
| **真实模型：击杀末影龙** | 从零开始，覆盖基本生存、远征准备、末地资源、进入末地、击杀末影龙五阶段 | 已记录[五阶段设计与观测证据](live-v1/README.md#dragon-five-stage)；已有[场景脚手架](live-v1/scenarios/defeat-dragon.json)，阶段判分尚未完整实现，**未运行**；完整通关所需能力尚未验证 |

原 `bot-v1` 清单共 **28 例：18 例分层评测已跑，10 例完整玩家场景未跑**。真实客户端运行器后来已经接通，但木板和生存试跑不替代这 10 个专项场景，也不自动补齐它们的成绩。

### 校准与中止记录

[本地脚本校准](live-v1/baselines/survival-clean-calibration/results/result.json) 已成功，用固定 localhost 回复证明运行器能驱动真实游戏，不计模型能力分。生存的[首段受污染试跑](live-v1/baselines/survival-interrupted-fixtures/run.json) 因测试工具进入模型环境而中止，不计能力成绩；上表生存结果来自排除测试夹具后的正式运行。历史记录保留，不能与正式成绩混合统计。

远征准备专项的[客户端负例校准](live-v1/verification/expedition-v1/client/calibration.json)已通过：初始物资未被误判成功，记录 **0/5、`budget_exhausted`**；这是脚本不执行任务时的预期结果，**不计模型能力分**。本轮双加载器构建、848 项可执行 JVM 测试、278 项 GameTest（含 5 项新增校准）和 48 项 Python 测试通过；API 测试编译仍被已有的 `AdapterHandlersTest.java:72` 返回类型错误阻塞，见[完整验证记录](live-v1/expedition-verification.json)。

## 目录里有什么

这里集中保存评测定义和归档证据。启动脚本放在 `tools/`，运行期 Java 代码按职责放在 `agent/`、`ai/`、`api/` 和 `core/`；完整运行工作目录放在被 Git 忽略的 `build/evals/`。仅复制 `evals/` 不能独立运行评测，需要完整项目及相应环境。具体代码位置见下文导航。

| 位置 | 用途 |
| --- | --- |
| [bot-v1/README.md](bot-v1/README.md) | 规划、循环响应和实际身体三个层次的最小评测说明、运行命令及该轮基线边界 |
| [bot-v1/cases.json](bot-v1/cases.json) | 固定样例、业务预期和评分标准，包含可执行样例与明确登记的未运行场景 |
| [bot-v1/baselines/](bot-v1/baselines/) | 离线与 GameTest 基线：逐例结果、分层汇总、源码摘要和原始证据 |
| [bot-v1/verification.json](bot-v1/verification.json) | 该轮构建、测试、依赖环境与阻塞记录 |
| [live-v1/README.md](live-v1/README.md) | 真实模型与 Minecraft 客户端完整链路的使用说明、成功标准和结果解释 |
| [live-v1/scenarios/](live-v1/scenarios/) | 真实游戏场景：木板合成、初期物资生存、自由前期生存与补给、指定农业据点、远征准备、击杀末影龙 |
| [live-v1/baselines/](live-v1/baselines/) | 归档的真实模型运行、脚本校准和中止记录；包含结果、日志、源码摘要及轨迹 |
| [live-v1/verification/](live-v1/verification/) | 预检、构建、回归测试等验证日志，较大的日志按 gzip 压缩 |
| [live-v1/verification.json](live-v1/verification.json)、[survival-verification.json](live-v1/survival-verification.json) | 冒烟链路与可持续生存场景的验证汇总、结果位置和证据哈希 |

`bot-v1/README.md` 保留最初分层基线的历史说明；其中“整链未接入”描述的是该轮状态。后续真实客户端接入与生存试跑以 `live-v1/` 的记录为准。目录名不等于所有场景的评分版本：农业专项与新自由生存都放在 `live-v1/` 下，但分别使用 `survival-v1` 和 `survival-v2`。

## 两条评测链路

**分层评测（bot-v1）**：Python 启动器调用 JVM 测试和 Minecraft GameTest。规划与响应使用真实模块配合脚本夹具；身体层执行真实游戏工具并观察位置、任务和生命等状态。这条链路不调用付费模型，其分数不能解释为真实 LLM 的任务成功率或玩家等待时间。

**真实模型评测（live-v1）**：Python 启动器创建独立运行目录，启动 NeoForge 客户端和新世界；客户端通过产品现有目标入口驱动同伴，使用指定模型配置；服务端独立读取世界事实，判定里程碑、最终成功条件和预算终止。模型自己决定行动方案，不能靠声称“完成”通过。

完整场景规则与结果解释见 [真实客户端评测说明](live-v1/README.md)。

## 运行期代码在哪里

以下路径均相对仓库根目录，可直接点击跳转。

| 职责 | 代码位置 | 做什么 |
| --- | --- | --- |
| 分层评测启动器 | [tools/run_bot_eval.py](../tools/run_bot_eval.py) | 运行规划、响应和身体评测，校验逐例记录并汇总成绩 |
| 真实模型启动器 | [tools/run_live_eval.py](../tools/run_live_eval.py) | 校验场景与预算、准备独立配置和目录、启动客户端、检查最终结果及冻结源码 |
| 链路校准 | [tools/calibrate_live_eval.py](../tools/calibrate_live_eval.py) | 用本机固定模型协议回复验证真实合成或远征准备负例；输出合成用量，不作为模型能力成绩 |
| 通用判分与预算 | [agent/.../acceptance/LiveEvalRun.java](../agent/src/main/java/com/dwinovo/numen/agent/acceptance/LiveEvalRun.java) | 纯 JVM 逻辑：检查快照条件、记录里程碑、累计持续达标时间、调用数与用量，并生成报告和轨迹 |
| 客户端运行编排 | [api/.../client/eval/LiveEvalClient.java](../api/common/src/client/java/com/dwinovo/numen/client/eval/LiveEvalClient.java) | 创建评测会话、连接真实大脑、收集事件和世界快照、安装预算限制、持久化结果并结束运行 |
| 世界准备与快照 | [api/.../client/eval/LiveEvalWorld.java](../api/common/src/client/java/com/dwinovo/numen/client/eval/LiveEvalWorld.java) | 创建隔离世界、初始化主人和同伴，并在服务端线程读取实际状态、清理本次观察器 |
| 世界观察器接口 | [api/.../eval/WorldEvalObservers.java](../api/common/src/main/java/com/dwinovo/numen/eval/WorldEvalObservers.java) | 按场景注册与管理观察器，转发实际方块破坏及原生食物消费；`api` 不依赖具体 `core` 观察器 |
| 生存场景观察 | [core/.../eval/SurvivalWorldObserver.java](../core/common/src/main/java/com/dwinovo/numen/core/eval/SurvivalWorldObserver.java) | 读取工具、已放置工作站、农田、收割补种、食物和庇护所事实 |
| 自由生存观察 | [FreeSurvivalWorldObserver.java](../core/common/src/main/java/com/dwinovo/numen/core/eval/FreeSurvivalWorldObserver.java) | 记录安全食物、真实有效补给、实际存活 tick 与当前危险；不规定农业或建筑路线 |
| 远征准备观察 | [ExpeditionWorldObserver.java](../core/common/src/main/java/com/dwinovo/numen/core/eval/ExpeditionWorldObserver.java) | 记录镐和盾牌、四件护甲实际穿戴、逐件耐久、混合食物储备及当前安全状态 |
| 场景注册及收割证据 | [NumenCore.java](../core/common/src/main/java/com/dwinovo/numen/core/NumenCore.java)、[BlockDigger.java](../core/common/src/main/java/com/dwinovo/numen/core/act/BlockDigger.java) | 注册生存观察器；方块实际破坏后报告事件，支持成熟小麦收割归因 |
| 客户端评测入口 | [AgentLoopRegistry.java](../api/common/src/client/java/com/dwinovo/numen/client/agent/AgentLoopRegistry.java) | 在客户端 tick 中调用可选的评测入口 |
| 大脑事件与执行接入 | [AgentLoop.java](../agent/src/main/java/com/dwinovo/numen/agent/loop/AgentLoop.java)、[EntityAgentLoop.java](../api/common/src/client/java/com/dwinovo/numen/client/agent/EntityAgentLoop.java)、[ToolDispatcher.java](../api/common/src/client/java/com/dwinovo/numen/client/agent/ToolDispatcher.java) | 提供事件订阅、模型请求和工具派发前的可选预算检查，复用真实产品执行链 |
| 模型传输预算接入 | [CancelToken.java](../ai/src/main/java/com/dwinovo/numen/agent/http/CancelToken.java)、[HttpLlmTransport.java](../ai/src/main/java/com/dwinovo/numen/agent/http/HttpLlmTransport.java) | 每次实际流式 HTTP 请求尝试前检查额度，包含传输重试 |
| NeoForge 启动配置 | [core/neoforge/build.gradle](../core/neoforge/build.gradle) | 接收 `numenLiveEval` 与隔离游戏目录参数；评测构建排除 GameTest 类，避免测试工具进入模型环境 |

运行期接入只在显式启用真实评测时激活。具体生存规则留在 `core/`，观察器接口和游戏连接留在 `api/`，通用判分留在 `agent/`，HTTP 请求限制通过 `ai/` 的通用接口接入。修改评测时应沿这些职责定位文件。

## 评测与校验测试在哪里

| 内容 | 代码位置 |
| --- | --- |
| 规划能力样例 | [PlanningBenchmark.java](../agent/src/test/java/com/dwinovo/numen/agent/plan/PlanningBenchmark.java) |
| 循环响应样例 | [ResponseBenchmark.java](../agent/src/test/java/com/dwinovo/numen/agent/loop/ResponseBenchmark.java) |
| 真实身体样例 | [BenchmarkGameTests.java](../core/neoforge/src/main/java/com/dwinovo/numen/core/gametest/BenchmarkGameTests.java) |
| 通用判分、预算及持续达标校验 | [LiveEvalRunTest.java](../agent/src/test/java/com/dwinovo/numen/agent/acceptance/LiveEvalRunTest.java) |
| 生存世界观察器校验 | [SurvivalEvalGameTests.java](../core/neoforge/src/main/java/com/dwinovo/numen/core/gametest/SurvivalEvalGameTests.java) |
| 自由补给与安全状态校验 | [FreeSurvivalEvalGameTests.java](../core/neoforge/src/main/java/com/dwinovo/numen/core/gametest/FreeSurvivalEvalGameTests.java) |
| 远征准备判分校验 | [ExpeditionEvalGameTests.java](../core/neoforge/src/main/java/com/dwinovo/numen/core/gametest/ExpeditionEvalGameTests.java) |
| 请求许可与取消校验 | [TransportCancelTest.java](../ai/src/test/java/com/dwinovo/numen/agent/http/TransportCancelTest.java) |
| Python 启动器和记录校验 | [test_run_bot_eval.py](../tools/test_run_bot_eval.py)、[test_run_live_eval.py](../tools/test_run_live_eval.py) |

观察器或运行器测试通过，只证明评测设施满足相应断言；bot 的能力成绩读取对应的基线结果。

## 如何运行和查看结果

从仓库根目录执行；完整命令、依赖和环境覆盖说明分别见 [bot-v1](bot-v1/README.md) 与 [live-v1](live-v1/README.md)。

```powershell
# 分层评测的 JVM 路径，不调用真实模型；--offline 要求 Gradle 依赖已缓存。
python -B tools/run_bot_eval.py --layer jvm --offline

# 只检查真实评测准备情况，不启动游戏、不调用模型。
python -B tools/run_live_eval.py --prepare-only
```

真实试跑需要指定 provider 配置、场景和请求数／时间／token 预算；按 `live-v1/README.md` 的命令启动。`--offline` 只影响 Gradle 下载，不会阻止真实模型联网。

新运行默认写入仓库根目录下的 `build/evals/bot-v1/` 或 `build/evals/live-v1/`，不会自动成为这里的归档基线。`evals/**/baselines/` 保存选定运行的可复核证据；原始世界和运行配置留在工作目录中，其中 `game/` 包含私有凭据，不应整目录归档到 `evals/`。

读结果时先确认评测版本、场景、源码摘要、模型、预算和依赖环境，再看状态与证据：任务未完成、尚未运行、设施异常和脚本校准是不同情况。保留原始结果，不用校准成功替代真实模型成绩，不把未返回的用量或未知费用填成 0。修改评分、场景或预算后，应明确记录新配置，不能直接与旧成绩混合汇总。
