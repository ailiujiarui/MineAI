# Numen 适配器机制详细设计

> 目标：把「第三方模组交互不兼容」这类**纯逻辑翻译**工作，从编译期固定改为运行期可热重载，且不动 Numen 本体、不影响其他模组。
>
> 本文所有关于现状的判断都基于仓库源码逐行核对，涉及的文件、类名、方法名、行号均可直接对照。所有设计取舍都给出「为什么不做另一种」的理由。

---

## 0. 结论摘要

提案的前提**一半成立、一半不成立**，这个区别决定了整套设计的形状：

| 提案的判断 | 实际 | 影响 |
|---|---|---|
| 「适配逻辑在编译期固定且无法运行期热重载」 | **数据半边已经是热的**：`AdapterRegistry.reload` → 原子替换 → `AdapterManager.reload()` → `numen adapter reload` 全链路已通 | 不需要新建热重载框架，只需要把已有的一条链补齐 |
| 「需要把适配逻辑外置为动态模块」 | **代码半边是编译期固定的，而且当前完全不可达**：`AdapterHandlers` 四个注册表零调用点 | 这才是真正要修的东西，且修法是「开一扇门」而不是「造一套引擎」 |

一句话说清：**这套框架已经写了 80%，但有效运行部分为 0%。** 已有的 JSON 装载、原子热替换、按模组存在与否跳过、错误汇总报告——全部可用；缺的是让第三方能把处理器填进那四个空 Map 的**注册入口**，以及让 JSON 里写的规则和加载器里有的处理器**能对上号**的校验。

由此推出三条核心设计主张，后续所有章节都服务于它们：

1. **热重载的边界是数据，不是代码。** 槽位下标、物品 id、菜单 id、容器 id、意图名——全部进 JSON，改完 `reload` 即生效；「该调哪个 API」留在编译期 Java 里。业界同构：KubeJS/CraftTweaker 的纯声明部分可热载，代码块一律重启；CC:Tweaked、ComputerCraft 的 Lua 是另一条路线（要解释器），代价见 §4.1。
2. **不做自定义 ClassLoader。** Numen 内部对「外部扩展包」的官方答案是 **`ModJar.find` / Fabric `ModContainer.findPath` / NeoForge `IModFile.findResource`**——jar 内资源原地读，明写在 `SkillRegistry` 的类注释与 `ModJar` 的实现里。外置扩展包就是**一个真模组 jar**，不走自造类加载器。
3. **两条轨道永不合并。** 树内联动（`plugins/curios`、`plugins/ysm`、TLM）走 **`GearSource` + `Gate` + `Builtin`** 的强类型路线；外部适配器走 **`AdapterSpec` JSON + `AdapterHandlers`** 的弱类型路线。理由见 §4.4。

---

## 1. 现状：已经有的资产

### 1.1 适配器栈全清单（11 个文件）

```
agent/src/main/java/com/dwinovo/numen/agent/adapter/
    AdapterSpec.java          194 行  声明式 JSON 模型（record + fromJson）
    AdapterRegistry.java      161 行  装载 / 校验 / 原子换血 / 查询
    ReloadReport.java          67 行  一次 reload 的差异与错误
    ItemSelector.java          40 行  精确 / 前缀 / "*"
    Side.java                  32 行  CLIENT / SERVER / BOTH
agent/src/test/java/com/dwinovo/numen/agent/adapter/
    AdapterRegistryTest.java           5 个用例，纯 JVM

api/common/src/main/java/com/dwinovo/numen/adapter/
    AdapterManager.java        74 行  引擎侧持有者，config/numen/adapters/
    AdapterHandlers.java      102 行  名字 → 实现 的四张表（当前全空）
    AdapterGearSource.java     60 行  GearSource 的适配器实现
    ExampleAdapters.java       68 行  首启种子 JSON

core/common/src/main/java/com/dwinovo/numen/core/adapter/
    AdapterCommands.java       63 行  numen adapter reload | list
```

### 1.2 数据半边：链路完整、语义正确

- **声明模型**：`AdapterSpec` 是 record，五个规则表 `slotMaps` / `equipRoutes` / `containers` / `guis` / `useRoutes`。紧凑构造器把 `targetMod` 的空值归一成 `""`、`side` 的空值归一成 `Side.BOTH`，五张表全部 `List.copyOf`（不可变）。取数助手 `str`（非原始类型一律返回 `""`）、`strOr`、`num`（**缺省 -1**）、`selector`（原始字符串或带 `item` 的对象）、`list`（只收 `JsonArray` of `JsonObject`）。`id` 为空直接抛 `IllegalArgumentException("adapter 缺少 id")`。
- **装载**：`AdapterRegistry.reload(dir, modPresent)` 逐个 `.json` 解析，**目标模组不在场则进 `skipped`**（`modPresent.test(spec.targetMod())`），**`id` 重复进 `errors`**，单文件异常被逐个捕获进 `errors` 不中断其余文件；最后 **原子换血**（`previous = copyOf(active)` → clear → putAll）后返回 `ReloadReport`。所有方法 `synchronized`，所以换血期间不会读到半张表。
- **查询**：`equip` / `use` / `container` / `gui` / `slots` 全部 first-match-wins，按 `LinkedHashMap` 插入序（也就是文件名字典序）遍历。
- **报告**：`ReloadReport` 带 `at / loaded / failed / skipped / added / removed / updated / errors` 八个字段，`toJson()` 全量输出。
- **热重载**：`AdapterManager.init()` 幂等（`initialised` 守卫），`dir()` = `NumenPaths.config().resolve("adapters")`，`reload()` 调 `REGISTRY.reload(dir(), Services.PLATFORM::isModLoaded)` 并把报告打成一行日志 + 每条错误一条 warn。
- **指令**：`AdapterCommands.install` 注册 `numen adapter` 组，`reload` 回 `report.toJson().toString()`，`list` 回 `{dir, adapters:[{id, targetMod, side, slotMaps, equipRoutes, containers, guis, useRoutes}]}`。
- **首启种子**：`ExampleAdapters.seedIfEmpty` 在目录里没有任何 `.json` 时写入三个示例（`example-curios` / `example-tacz` / `example-beyonddimensions`）。这**不是**线上数据迁移，是「没有配置文件时给用户一份可照抄的 schema 样板」。已存在同名文件跳过，用户清空/删文件被视为明确意图。

### 1.3 已有的 fail-soft 先例（新代码必须复用，不许自创）

| 位置 | 手法 |
|---|---|
| `NumenPlugins.register` | `try/catch(RuntimeException)` → `"[numen] 插件登记失败,它挂的东西可能只生效了一半"` |
| `core/common/.../plugins/Gate.install` | `try/catch(Throwable)` → `"[numen] 联动 {} 没接上,其余照常:{}"` |
| `AdapterRegistry.reload` | 逐文件捕获，错误入表，不中断 |
| `NumenPlugins.joinFragments` | **`FAILING` 集合**：首次失败记一条，之后静默不再刷屏；**恢复时再记一条**——这是运行期处理器隔离应当照抄的范式 |

---

## 2. 四条硬证据：只有一半接通

### 证据一：`AdapterHandlers` 四个注册表零调用点

`AdapterHandlers` 的类注释承诺「处理器缺失时对应规则等于没生效（fail-soft）；任何一处抛异常都不该拖垮调用方」。全仓库 `grep` 结果：

```
AdapterHandlers. 的非测试调用点：4 处，全是「读」
    core/common/.../core/tools/GuiOps.java:32
    core/common/.../core/tools/QueryExtraOps.java:304
    core/common/.../core/task/interact/InteractAtCompanionTask.java:83
    api/common/.../adapter/AdapterGearSource.java:32 与 :51
registerGear / registerUse / registerGui / registerContainer 调用点：0
clear() 调用点：0
```

**推论**：`GEAR` / `USE` / `GUI` / `CONTAINER` 四张 `ConcurrentHashMap` 永远是空的。于是：

- `InteractAtCompanionTask:83` `use(itemId)` → `Optional.empty()` → 每一条 `useRoutes` 都是死规则；
- `AdapterGearSource.slots()` 遍历到 `AdapterHandlers.gear(route.container())` 全为 `null` → 适配器穿戴来源产出的槽位永远是空的；
- `GuiOps` / `QueryExtraOps` 同理，永远走不进适配器分支。

连 `ExampleAdapters` 种下的三个示例都是纯装饰——它们**能装载、能列出、能被 reload 报告，但任何一条规则都不会被执行**。这就是「写了 80%、有效 0%」的准确含义。

### 证据二：第三方**无法编译**这些处理器

`buildSrc/src/main/groovy/api-common.gradle` 的瘦 jar include 清单（134–160 行）逐项为：

```
com/dwinovo/numen/api/**        NumenPaths*.class        agent/tool/api/**
agent/tool/**                   task/**                  cli/**
permission/**                   entity/NumenPlayer*.class
entity/InputDriver*.class       entity/package-info.class
agent/skill/SkillRegistry*.class  agent/skill/SkillInfo*.class
platform/Services*.class        platform/services/**
fabric.mod.json                 LICENSE*                 COPYING*
network/payload/PathDebugPayload*.class
network/payload/ClientUiActionPayload*.class
```

**没有 adapter 包的任何一项。** 插件在 `build.gradle` 里只 `compileOnly files(project(':api:neoforge').tasks.named('apiJar'))`——即只对着这枚瘦 jar 编译（`plugins/curios/build.gradle` 明写此模式，注释是「只对着 Curios 的公开 API 编译；运行时由玩家自己装的那份提供，本模块不携带它一个字节」）。

**推论**：`AdapterHandlers.UseHandler` / `GuiHandler` / `ContainerHandler` 这些类型不在瘦 jar 里，第三方模组**连 `implements` 都写不出来**，只能反射。所以「加几个注册方法」根本不解决问题——**SPI 必须搬家**，见 §6.1。

顺带确认一个便利事实：瘦 jar 契约面**已经含 gson**（`agent/tool/**` 在清单里，而 `ToolCall.args()` 返回 `JsonObject`、`ToolArgs` 的公开方法签名全是 `JsonObject`）。所以处理器签名用 `JsonObject` **不引入任何新依赖**，不需要为它做适配层。

### 证据三：失败隔离只覆盖了 JSON 解析，没覆盖代码执行

四个消费点**没有一个包 try/catch**（已逐处核对）：

```java
// GuiOps.java:30-39
var adapterRoute = AdapterManager.registry().gui(adapterMenu);
if (adapterRoute.isPresent()) {
    var handler = AdapterHandlers.gui(adapterRoute.get().source());
    if (handler != null) {
        JsonObject read = handler.read(self, menu, adapterRoute.get().source()); // ← 裸调
        if (read != null) return TaskResult.ok(read.toString()).toJson();
    }
}
```

`QueryExtraOps:302-313`、`InteractAtCompanionTask:81-91`、`AdapterGearSource:32` 同形。`AdapterHandlers` 的 javadoc 承诺「任何一处抛异常都不该拖垮调用方」，**这个承诺没有任何代码实现**。所以今天只要有一个处理器抛异常，就是异常直接穿出工具调用 / 任务链 / 状态片段。

### 证据四：JSON 与代码之间没有契约校验

- **`AdapterSpec` 没有 `schema` / `version` / `enabled` / `priority` / `requires`。** 用户写了一份 `gui` 规则、写了 `source: "bd_storage"`，但没有任何模组注册过这个名字 → `handler == null` → **静默什么都不发生**。用户视角是「我配了但没生效」，没有任何地方能告诉他为什么。
- **`Side` 实际上没有生效。** `AdapterRegistry.next` 从不按 `Side` 过滤；`on(Side)` 唯一的调用者是 `AdapterRegistryTest`。于是 `example-beyonddimensions.json` 里 `read: client` 的 GUI 规则在服务端侧照样被当作「有这条规则」，但客户端→服务端根本没有通路：`grep -rn "adapter" api/common/.../network/ core/common/.../network/` **无任何命中**，即 **adapter 没有任何网络载荷**。这是一条被声明了、被序列化了、但不被执行的语义。
- **三个死字段**（全仓库零消费者，仅定义处与种子 JSON 出现）：`GuiRoute.readSide`、`GuiRoute.serverIndex`、`EquipRoute.slot`、`SlotMap.index`。其中 `SlotMap.index` 只在 `AdapterRegistryTest` 里断言过 `== 46`。`AdapterGearSource` 只用了 `route.container()` 和 `route.item()`，**从不读 `route.slot()`**。
- **`priority` 缺失导致同级冲突靠文件名。** first-match-wins + 文件名字典序 = 两条规则命中同一物品时，谁生效取决于**文件名排序**，这是一个用户无法推理、也无法从文档里读出来的规则。

结论：这四条不是「优化点」，而是**当前状态下这套机制不可用的直接原因**。

---

## 3. 概念纠正：`Gate` 同名不同物

提案里说「继承现有 `Gate` 的 fail-soft 语义」——仓库里有**两个** `Gate`，指向完全不同的东西：

| 类 | 位置 | 职责 |
|---|---|---|
| `Gate` | `api/common/src/main/java/com/dwinovo/numen/api/permission/Gate.java` | **许可闸门**：裁决身体的动作是否被允许改世界。这是 CLAUDE.md 里「只有许可层决定改世界的动作」的落点，与适配器无关。 |
| `Gate` | `core/common/src/main/java/com/dwinovo/numen/core/plugins/Gate.java` | **模组在场上闸门**：`open(modId, ...)` 先测 `modLoaded`，再 `install()` 并 `catch(Throwable)`。这才是提案说的那个。 |

后者的真正的价值不是 try/catch，而是**多出来的一层间接**——`Gate` 的类注释把原因写得很清楚：如果直接传 `Runnable`，lambda 创建时就会解析 `NumenTlm::install` 的方法句柄，`NumenTlm` 立刻被加载，目标模组不在场就 `NoClassDefFoundError`，**闸门形同虚设**。所以签名是 `Function<Path, Runnable>` / `Supplier<Runnable>`，加载被推迟到闸门放行之后。设计里任何「按需加载第三方类」的地方都必须继承这个教训。

`Builtin`（`core/fabric/.../plugins/Builtin.java`）是配套的闭合清单：只列 `yes_steve_model` 一项，注释明写「内嵌联动是闭合集合…扫描是给开放集合用的」；并且**`Builtin` 自身的任何方法（含合成的 lambda 方法）都不许出现联动类型**，因为校验器会提前加载参数类型，而联动依赖是 `compileOnly`，会在 datagen / `runClient` 里炸 `ClassNotFoundException`。

**适配器路线与这两者天然兼容**：适配器不 `register` 新的耦合代码，它只填数据表，所以不存在「无模组时加载到目标类」的问题——前提是 SPI 里不出现任何第三方类型，见 §5.2。

---

## 4. 设计边界（先说不做什么）

### 4.1 不做脚本引擎

理由不是「实现难」，而是**重开一扇隔离/许可门**。植入 JS/Lua，等于让配置目录里的字符串获得与 Numen 本体同等的类访问能力：脚本能直接改世界、绕开许可层、绕开 `GearSource` 的「只陈述游戏规则、不做许可裁决」的分工。要把它做安全，就得再造一套沙箱 + 权限模型，成本远超收益。而且按 CLAUDE.md 的「不要为未提出的需求造抽象」，脚本引擎对应的需求在本提案里并不存在——提案要的是「少改代码就能适配」，而不是「用配置写程序」。

### 4.2 不做自定义 ClassLoader

三个理由，任一成立就足够：

1. **`net.minecraft.*` 必须过加载器自己的类加载器。** 自造类加载器加载的游戏类拿不到 Mixin 变换，结果是未变换的 `net.minecraft.*`，或者更常见的 `ClassCastException`。
2. **NeoForge/Fabric 的 mod 类加载器与加载器元数据、`fabric.mod.json`/`mods.toml`、访问加宽器绑定**，绕开它等于绕开整套加载器契约。
3. **已经有官方答案而且 Numen 已经在用。** `ModJar`（Fabric 走 `ModContainer.findPath`，NeoForge 走 `IModFile.findResource`）就是「读另一个 jar 里的东西」的唯一出口，`SkillRegistry` 靠它**原地读**内嵌模组的 `skills` 目录，注释写着「jar 内路径怎么映射成 Path 只有加载器知道……不经类加载器」。

**所以「外部扩展包」在 Numen 里的定义是：一个真模组 jar。** 它有自己的 `fabric.mod.json` / `mods.toml`，由加载器正常加载，内部 `NumenPlugins.register(...)` 注册自己的处理器。这直接复用现成的编译与加载通路，零新增机制。

### 4.3 不做 `provides` / 依赖图 / 拓扑排序

v1 用 `requires`（声明用了哪些处理器名）做**装载期校验**即可：名字没注册就进 `skipped` 并给出原因。真正的依赖解析（A 依赖 B 的规则、循环检测、顺序求解）是另一件事，当前没有需求，不造。

### 4.4 两条轨道永不合并

| | 树内联动（`plugins/curios`、`plugins/ysm`、TLM） | 外部适配器（`AdapterSpec` + `AdapterHandlers`） |
|---|---|---|
| 载体 | 编译期类型安全的 `GearSource` / 强类型代码 | 运行期 JSON + 名字查表 |
| 入口 | `NumenPlugins.register` + `Gate` + `Builtin` | `config/numen/adapters/*.json` |
| 集合性质 | **闭合**：我们声称支持什么，就是什么 | **开放**：别人写的东西坏了不波及我们 |
| 失败代价 | 编译期暴露 | 运行期 `skipped` + 原因字符串 |
| 例子 | `CuriosGear` 排序用 `CurioApi` 的 `ISlotType#getOrder`；`kindsOf` 先判物品是不是 curio，避免 `curios:all` 之类的兜底声明把镐子也认领了 | 「Curios 的戒指槽在索引 46」这种纯映射 |

**不要**试图把 `CuriosGear` 改写成 JSON——它调的是 `CuriosApi` 的静态方法，需要编译期依赖，且逻辑（可见性过滤、按 order 排序、curio 判定）不是「翻译」而是「计算」。反过来也不要把 JSON 里的槽位下标硬编码进 Java。**判据**：如果实现体需要 `import` 目标模组的任何类型，它属于树内联动；如果实现体只做「查表 + 调 Numen 自己的方法」，它属于外部适配器。

这条界线同时保护了两边的质量下限：树内联动可以承诺正确性，外部适配器可以承诺隔离性。

---

## 5. 目标架构

### 5.1 分层

```
                    ┌── 数据（热重载） ─────────────────────┐
config/numen/adapters/*.json  →  AdapterSpec  →  AdapterRegistry（原子换血）
                    └───────────────────────────────────────┘
                                     ↓ 按名字查
   ┌── 代码（编译期固定，进程内注册一次） ──────────────────┐
   AdapterHandlers: GEAR / USE / GUI / CONTAINER 四张表     │
   ← 由 NumenApi.registerXxxHandler(...) 填入（新入口）      │
   ← 由外部适配器模组在自己的 NumenPlugins.register 里调用   │
   └───────────────────────────────────────────────────────┘
                                     ↓
   消费点：GuiOps / QueryExtraOps / InteractAtCompanionTask / AdapterGearSource
   （全部改为经过「安全调用」这一道收口，见 §6.2）
```

**关键性质**：JSON 决定「这条规则什么时候命中、命中后交给谁」，代码决定「交给谁之后具体做什么」。`reload` 只影响前者，因此永远不会执行到一半的旧代码，也永远不会加载新代码——这正是「热重载的是数据不是代码」在实现层的含义。

### 5.2 SPI 的位置与签名

**位置**：`api/common/src/main/java/com/dwinovo/numen/api/adapter/`（`api` 包下，与 `NumenApi`、`GearSource` 同级）。

**必须满足的约束**（逐条对应已有铁律）：

- `api/` 不得引用 `core/`：签名里只能出现 `NumenPlayer`、`BlockPos`、`AbstractContainerMenu`、`ItemStack`、`JsonObject`、`String`——这些全在瘦 jar 或原版/Minecraft 侧。
- **不出现任何第三方类型**：否则就重演 §3 里 `Builtin` 的 `ClassNotFoundException`。适配器处理器只能看到 Numen 的抽象和原版类型。
- **`NumenPlayer` 已经在瘦 jar 里**（`entity/NumenPlayer*.class` 在 include 清单中），所以处理器能拿到身体；`JsonObject` 也在契约面内（§2 证据二），零新依赖。
- 只暴露**一扇门**：外部模块只能通过 `NumenApi` 注册，不允许直接 `registerGear` 到静态表上——与 `NumenPlugins.register(NumenPlugin)` 的既有模式一致（插件只拿得到 `apiJar`，物理上就绕不过去）。

### 5.3 `AdapterSpec` 的新字段

| 字段 | 类型 | 缺省 | 语义 |
|---|---|---|---|
| `schema` | int | 1 | 声明文件格式版本。**大于引擎支持的版本 → `skipped` 并写明「schema 1 太新」**，而不是解析出一堆默认值假装成功 |
| `enabled` | boolean | true | 用户临时禁用某份适配器，不用删文件（与 `ExampleAdapters` 的「删文件是明确意图」互补） |
| `priority` | int | 0 | 数值大者优先；同级再退回原文件名字典序（**同级仍然确定，不引入随机**） |
| `requires` | List\<String\> | 空 | 声明本文件用到的处理器名（`equipRoute.container` / `useRoute.intent` / `guiRoute.source` / `containerRoute.access` 里出现的名字）。装载期逐个查表，**任一未注册 → 整份进 `skipped`，原因写明「处理器 X 未注册」** |

`requires` 是这四项里唯一改变可用性的：它把「静默无效」变成「装载期可解释的失败」，直接消灭 §2 证据四的第一条。

### 5.4 `Side` 的 v1 语义：只支持 SERVER

**做法**：装载期对 `side == CLIENT` 或 `BOTH` 的适配器**进 `skipped`，原因字符串写明「v1 只支持 server 侧适配器」**，并把 `Side.on` 的语义从「测试里用」变成「装载期真正生效」。

**为什么不是「实现客户端侧」**：那需要新增一类跨端载荷（客户端读 → 打包 → 发服务端 → 服务端并入模型上下文），是本提案外的第二件事；而 adapter 至今没有任何网络载荷，从零加一条通路不该塞在「打开注册入口」这个 commit 里。

**为什么必须「显式跳过」而不是「静默当作 server 处理」**：`example-beyonddimensions.json` 的 `read: client` 说明用户会这么写；静默处理会让用户以为客户端规则生效了，实际在服务端解析客户端菜单，结果是错误数据而不是错误提示。

**`readSide` / `serverIndex` 的处置**：v1 一并进 `skipped`（原因：客户端侧未支持），**不保留成死字段**。`EquipRoute.slot` / `SlotMap.index` 同样处置：要么给出消费者，要么删掉——按 CLAUDE.md「不给未提出的需求造抽象」，倾向删掉，需要时以 `requires` 声明的新处理器形式加回来。

> 顺带说明：`AdapterGearSource` 今天用的是 `route.container()` 而非 `route.slot()`，即「容器名 → 槽位列表」由处理器负责（这正是 `CuriosGear` 的做法：自己算出槽位列表）。`slot` 字段与这个分工重复，属于设计冗余。

### 5.5 状态与可观测性

- **持久化最近一次 `ReloadReport`**（新增一个持有者，或让 `AdapterManager` 缓存），使 `adapter list` 能逐适配器回答**「它为什么没生效」**：解析失败 / `id` 重复 / 目标模组不在场 / `requires` 里的处理器未注册 / schema 太新 / 客户端侧未支持 / 已被 `enabled: false` 关闭。现在的 `list` 只回计数，等于把最有用的诊断信息丢掉了。
- **诊断只增一个出口**，不新建日志体系：沿用 `Constants.LOG`，错误一行一条 warn（现状已如此）。
- **处理器健康状态**：失败时记一次、恢复时再记一次（照抄 `joinFragments` 的 `FAILING` 集合语义），避免每 tick 刷屏。

### 5.6 热重载的确切语义（必须写进文档，而不是靠用户猜）

- **生效时机**：`reload` 之后**下一次路由解析**使用新数据。
- **不影响在途任务**：一条已经进入 `InteractAtCompanionTask.act()`、已经取到 `UseRoute` 的任务不会因为 `reload` 而换成新规则、也不会因为旧规则消失而中断——它已经拿着 `intent` 字符串了。
- **不影响已装备状态**：`AdapterGearSource.slots()` 是查询式接口（`GearSource` 的约定是「句柄只在当次调用内有效」），所以下一次查询自然用新槽位表；已写入身体的物品不会因为规则变化被自动卸下。**这是有意的**：卸装是改世界的动作，必须经许可层，不能被一次 `reload` 触发。
- **不重新加载代码**：注册是一次性的，`reload` 不会、也不能让处理器实现变更生效。要改实现，改模组、重启。

---

## 6. 落地：六个 commit 粒度的步骤

按依赖顺序排列，每步可独立编译、独立测试、独立回滚。

### 6.1 开一扇门：SPI 搬家 + `NumenApi` 注册方法

**改**：

- 新增 `api/common/src/main/java/com/dwinovo/numen/api/adapter/`，把三个函数式接口搬过去（`UseHandler` / `GuiHandler` / `ContainerHandler` / `GearSource` 已在 `api`），签名只碰 §5.2 允许的类型。
- `api/common/src/main/java/com/dwinovo/numen/api/NumenApi.java` 增加四个方法：`registerGearHandler(String, GearSource)`、`registerUseHandler(String, UseHandler)`、`registerGuiHandler(String, GuiHandler)`、`registerContainerHandler(String, ContainerHandler)`。javadoc 照 `registerGear` 的风格写清「必须在 `NumenPlugins.register` 的块里调，服务端侧」，并**与 `registerGear` 一样声明这是运行期唯一入口**。
- `api/common/.../NumenPlugins.Impl` 实现这四个方法，转发到 `AdapterHandlers`。
- **`buildSrc/src/main/groovy/api-common.gradle` 的 include 清单加入 `com/dwinovo/numen/api/adapter/**`**（或新包名对应的路径）。**这一步不能漏**——漏了就等于什么都没做（§2 证据二）。
- `AdapterHandlers` 降级为纯容器（可留在 `api/common/.../adapter/` 内部包，不再对外），四个注册方法可收窄为包内可见，对外只有 `NumenApi` 那一扇门。

**验收**：写一个**独立的测试插件模块**（不放进 `plugins/curios`，避免污染参考实现），只 `compileOnly` 瘦 jar，注册一个 use 处理器 + 一个 gui 处理器；能编译、能被 `numen adapter list` 看到、能让 `example-tacz` 的 `tacz_fire` 规则第一次真正执行。

### 6.2 把异常隔离收口在注册处，而不是四个消费点

**为什么不是四个消费点各加 try/catch**：那样等于同一段隔离逻辑抄四遍，且以后新增消费点必然漏一处——同时违反 CLAUDE.md 的「不要重复实现」和「修根因，不加兜底」。**正确的收口点是注册包装器**：`NumenApi.registerXxxHandler` 收到实现后，返回一个包了 try/catch 的包装体再放进表里。

**语义**（逐条对齐既有先例）：

- `catch(Throwable)`（对齐 `Gate.install`，不是 `RuntimeException`）。
- 抛出时打一条 warn，**带上适配器 id / 处理器名 / 意图名**，让用户能定位到是哪份 JSON 的哪条规则。
- 抛出时返回 `null` / `false` / 空集，使该路由**等同于规则没生效**——这正是 `AdapterHandlers` javadoc 已经承诺的行为，只是此前无人实现。
- 健康状态用 `FAILING` 集合语义：首次记、恢复再记一次。
- **不吞掉语义**：`GuiOps` 的 `read` 返回 `null` 时继续走通用 dump（现状已如此），而不是假装读到了空菜单。

**验收**：一个故意抛 `IllegalStateException` 的处理器 → 任务链**失败并记录 id**，而不是把异常抛穿；其余适配器不受影响。

### 6.3 `AdapterSpec` 四个新字段 + 装载期校验

**改**：`AdapterSpec` 增加 `schema` / `enabled` / `priority` / `requires`（§5.3）；`fromJson` 用现有助手读（`num` 缺省 -1 的语义不适合 `schema`，需要显式缺省 1，注意不要硬套）；`AdapterRegistry.next` 加三道闸：`enabled == false` → `skipped`；`schema > SUPPORTED` → `skipped`；`requires` 中有名字查不到处理器 → `skipped`。`priority` 参与排序：**先 priority 降序，再文件名字典序**（保持同级确定性）。

**注意**：这个校验需要 `AdapterRegistry` 能查询「处理器是否已注册」。`AdapterRegistry` 在 `agent/` 模块，`AdapterHandlers` 在 `api/common`。二者依赖方向是 `agent → api`（`AdapterManager` 已经在用 `AdapterRegistry`），所以**给 `reload` 再传一个 `Predicate<String> handlerPresent`**，与既有的 `Predicate<String> modPresent` 完全同形。这样 `AdapterRegistry` 依然不知道 gson 之外的世界，测试可以喂假谓词，**纯 JVM 测试能力不退化**。

**验收**：`AdapterRegistryTest` 增加三个新用例（`requires` 未注册 → `skipped` 带原因；`schema` 过新 → `skipped`；两条规则抢同一物品时 `priority` 决定胜者）。现有五个用例不改。

### 6.4 `Side` 在装载期真正生效

**改**：`reload` 过滤 `side`，v1 只放行 `SERVER`（§5.4）；`CLIENT` / `BOTH` 进 `skipped` 并给出人话原因。同时在 `ExampleAdapters` 里把 `example-beyonddimensions` 的 `side` 从 `both` 改成 `server`，或把它标注为「演示 schema、v1 不加载」——**两个示例文件不该有两个互相矛盾的示范**。

**验收**：`filtersBySide` 从「只测 `on(Side.SERVER)`」升级为「装载期把 `cli-something` 记进 `skipped`，而 `skipped` 里带原因字符串」。

### 6.5 诊断：`adapter list` 能回答「为什么没生效」

**改**：`AdapterManager` 缓存最近一次 `ReloadReport`（或逐适配器的状态表）；`AdapterCommands` 的 `list` 在每项上加 `status` 与 `reason` 字段（`active` / `skipped: 目标模组 curios 不在场` / `skipped: 处理器 curios 未注册` / `error: 第 3 行 JSON 语法错误`）。`reload` 的返回保持原样（`report.toJson()`），**不破坏已有输出格式**。

**验收**：把一份适配器的 `targetMod` 改成一个不存在的 id → `reload` 后在 `list` 里看到 `skipped` 与原因，而不是从列表里凭空消失。

### 6.6 文档化 reload 语义

在 §5.6 的基础上，把「只影响下一次路由解析」「不影响在途任务」「不重新加载代码」「不会自动卸装」写进 `AdapterCommands` 的指令 note（`numen adapter reload` 的已有 note 是 "Instant; no restart or rebuild. A bad file is reported and skipped, never fatal."，在此基础上补一句边界），使玩家在游戏内 `--help` 就能看到边界。

**注意**：CLAUDE.md 有「不要创建文档」的约束，所以**不新增仓库内 md**；用户可见的说明放在指令 note 与 javadoc 里。

---

## 7. 与 `AGENTS.md` / `CLAUDE.md` 的对应

| 铁律 | 本设计如何满足 |
|---|---|
| 机械放 `api/`，内容放 `core/` | SPI 与 `NumenApi` 在 `api/`；`AdapterCommands` 保持在 `core/`；`AdapterSpec`/`AdapterRegistry` 留在 `agent/`（纯数据层，无 Minecraft 依赖，能跑纯 JVM 测试） |
| `api/` 不得引用 `core/` | 新 SPI 签名只用 `NumenPlayer` / `BlockPos` / `AbstractContainerMenu` / `ItemStack` / `JsonObject` / `String`，全部在瘦 jar 或原版侧 |
| 只改任务要求的东西，不重构、不改名、不重排格式 | 六个 commit 各自独立；`AdapterHandlers` 只在 6.1 动，`AdapterSpec` 只在 6.3 动；不做无关格式化 |
| 修根因，不加兜底、不吞异常 | 6.2 把隔离收口在**注册处**（根因：唯一的注入点是注册，不是四个消费点），且隔离后明确「该路由等同于没生效」，不假装成功 |
| 先复用已有检查，再写新的 | 复用 `AdapterRegistry` 的 `skipped`/`errors` 通道、`ReloadReport`、`Gate.install` 的 `catch(Throwable)`、`joinFragments` 的 `FAILING` 语义、`NumenPaths.config()` 的路径唯一出口、`Services.PLATFORM::isModLoaded` |
| 不为未提出的需求造抽象 | 不做脚本引擎、不做自定义类加载器、不做 `provides`/依赖图/拓扑排序、不做客户端侧通道（§4、§5.4） |
| 只有许可层决定改世界的动作 | reload **不触发自动卸装**；`GearSource` 的注释「来源只陈述游戏规则…不做许可裁决」不被破坏 |
| 身体做的事必须回报给模型 | 处理器返回值经 `TaskResult.ok(...).toJson()` / `GearSource.slots()` 进入既有回报路径，不新增旁路 |
| 注释只描述当前行为 | 6.1 必须同步修改 `AdapterHandlers` 那段与实现不符的 javadoc（现在写的 fail-soft 是承诺，改完才是描述） |
| 不改 `version` | 本设计不动 `gradle.properties` 的 version |
| CONTRIBUTING 命令带 `--no-daemon` | 执行说明里写明 |

---

## 8. 验收（三条边界测试，全部是当前状态下必然失败的）

1. **坏适配器互不干扰**：一个目录里放四份坏文件——JSON 语法错、缺 `id`、`requires` 指向未注册的处理器、`targetMod` 指向不存在的模组——`reload` 后：语法错/缺 id 进 `errors`，后两者进 `skipped` **且带原因**；同一目录里的好文件照常 `loaded`，`active` 表里正常可查。**这条覆盖 §2 证据一、四。**
2. **处理器抛异常不穿链**：注册一个必抛的处理器，触发对应任务 → 任务链**以失败告终并在日志里带上适配器 id 与规则名**，异常不穿出到工具调用边界，其他适配器继续工作，健康状态只记一次（不刷屏）。**这条覆盖 §2 证据三。**
3. **改数据即生效、在途任务不受影响**：`reload` 后修改某条 `slotMap` 的 `index`，下一次 `AdapterGearSource.slots()` 用新值；同时一个已经取到 `UseRoute` 的在途任务仍按旧规则完成。**这条覆盖 §5.6 与热重载的边界。**

现有 `AdapterRegistryTest` 的五个用例只覆盖纯数据层（装载、错误隔离、`on(Side)`、reload 差异、模组不在场跳过），**且其中四个调单参 `reload(dir)`（等价 `mod -> true`），模组在场判定只被最后一个用例覆盖**——6.3 引入 `handlerPresent` 后，应把谓词注入作为默认测试路径。

---

## 9. 明确不做（v1 范围外）

- 客户端侧适配器与跨端载荷（需要新增一类网络通路，是独立议题）。
- 用配置写程序（脚本引擎）。
- 自造类加载器 / jar 内适配器目录扫描（`plugins/<id>/adapters/` 这种能读，但引入来源优先级、去重、与 `config` 目录的覆盖关系，属于第二件事；v1 只有 `config/numen/adapters/` 一个来源）。
- 适配器之间的依赖解析、规则继承、模板/宏。
- 自动生成适配器（从模组 jar 反推 slot 映射）。
- 让 `reload` 触发自动卸装或自动重新装备。

---

## 附录：涉及文件与改动一览

| 文件 | 6.1 | 6.2 | 6.3 | 6.4 | 6.5 |
|---|---|---|---|---|---|
| `api/common/.../api/adapter/`（新） | 新建 | | | | |
| `api/common/.../api/NumenApi.java` | 加 4 方法 | | | | |
| `api/common/.../NumenPlugins.java`（`Impl`） | 转发 | | | | |
| `api/common/.../adapter/AdapterHandlers.java` | 降级为内部容器 + 改 javadoc | 包装体 | | | |
| `api/common/.../adapter/AdapterManager.java` | | | 传谓词 | | 缓存报告 |
| `agent/.../adapter/AdapterSpec.java` | | | +4 字段 | 去掉死字段 | |
| `agent/.../adapter/AdapterRegistry.java` | | | +3 闸 + priority 排序 | + side 过滤 | |
| `agent/.../adapter/ExampleAdapters.java` | | | 加 `schema`/`requires` | 修 `side` | |
| `core/common/.../core/adapter/AdapterCommands.java` | | | | | `list` 带 status/reason |
| `buildSrc/src/main/groovy/api-common.gradle` | **include 清单加 adapter 包** | | | | |
| `core/common/.../core/tools/GuiOps.java` | 无改动（收口在注册处） | | | | |
| `core/common/.../core/tools/QueryExtraOps.java` | 同上 | | | | |
| `core/common/.../core/task/interact/InteractAtCompanionTask.java` | 同上 | | | | |
| `api/common/.../adapter/AdapterGearSource.java` | 同上 | | | | |
| `agent/src/test/.../AdapterRegistryTest.java` | +注册门测试 | +抛异常测试 | +3 用例 | 改 `filtersBySide` | |
