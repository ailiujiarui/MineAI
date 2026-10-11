---
name: ae2
description: 主人要搭/扩/修 Applied Energistics 2 (AE2) 的 ME 网络、读/改机器与总线配置、编码样板、发起自动合成时看这个——讲频道与控制器、能量、线缆与连接、按面工作的总线/接口/模式供应器、存储元件、压印器与合成 CPU,以及 ae2.network.inspect、ae2.config.read、ae2.config.write、ae2.pattern.encode、ae2.craft.request、ae2.craft.status 这组函数怎么用。
---

# AE2 自动化产线

AE2 把存储与物流包成一张 **ME 网络**:线缆和机器连成一张网,驱动器里的**存储元件**当仓库,各种**总线/接口/供应器**把物品搬进搬出,模式 + 合成 CPU 让整条产线一键做出来。本文件只讲怎么把它搭起来、配好、接进自动合成。

AE2 在脚本里是 `ae2` 名字空间下的四组函数,每个函数的参数、返回和例子用 `numen.api.help("ae2.network.inspect")` 这样现查:

- `ae2.network.inspect(x, y, z)`:看一眼这一格所在的整张 ME 网。
- `ae2.config.read` / `ae2.config.write`:读/改 AE2 机器与线缆部件的服务端配置。
- `ae2.pattern.encode`:在**已打开**的 ME 模式编码终端里把配方编成样板。
- `ae2.craft.request` / `ae2.craft.status`:向合成 CPU 下单,再查询它的进度。

走到机器旁、开 GUI、往槽里放东西这些通用动作,走核心函数:`numen.move.to`、`numen.use.block`、`numen.gui` 那一组、`numen.inv` 那一组,以及 `numen.build.place`、`numen.scan.blocks`、`numen.work.dig`。随包还发了一个高层模块 `ae2.automation`,把下单、编码、等网络上线各包成一次调用(见 §9)。

## 0. 先算频道,再动第一颗方块

频道是 AE2 九成麻烦的来源,先规划再摆:

- **普通线缆(玻璃/覆盖/智能)一整根只能带 8 个频道**;致密线缆 32 个;**控制器每个面给 32 个**。没有控制器的网络叫 ad-hoc,**全网络总共只有 8 台吃频道的设备**——第 9 台一接上,所有吃频道的设备一起停机。
- 吃频道:驱动器、终端、接口、模式供应器、各种总线、分子装配室、压印器……**等级发射器不吃**频道(可当红石灯泡随便放)。
- **先列清单**:这条网络要接几台吃频道的东西?按"控制器→致密线缆→普通线缆→每根普通线 ≤8 台设备"摆成**树**。别绕环、别让两股频道在半路合流——频道只走最短路径,会互相挤爆。
- 粗算例:主网只接 6 台 → 一根普通线缆就够;要接 20 台 → 控制器 + 致密线缆到分叉点,再从叉点分普通线、每支 ≤8。
- 布线优先用**智能线缆/致密智能线缆**:线上亮的竖条就是已占用频道,一眼看清路径。玻璃线缆看不到。
- **一根线要跨很远带很多频道** → 用 **ME P2P**:每个输入/输出只占 1 个频道,一根细线能搬 32×N 个频道。**一堆总线只为服务几台机器** → 收进**子网**(见 §7.4),主网只看到 1 个接口/供应器,只占 1 频道。
- 排障:对网上任意一格调 `ae2.network.inspect(x, y, z)`,回执里有 `channelsUsed`、`channelsLimit`、`controllerState`、`deviceCount`、`gridPowered` 和一句话 `summary`;要看某台机器自己的设置再用 `ae2.config.read`。更细的频道机理以本包 AE2 自带指南为准。

## 1. ME 网络与能量

- 网络 = 被线缆和可连方块连起来的一群设备。**连接只看方块相邻,不看距离**;不同颜色的线缆之间**不连**;石英纤维只传电、不连网。
- **控制器**是可选的,但一装就是网络枢纽,每面 32 频道。规则:所有控制器方块必须互相连通;整块 ≤7×7×7;每个方块最多与两个相邻方块相接、且只在一条轴上(三面都贴会变红),否则整块变红罢工。**一个网络只能有一个控制器**。
- 没控制器 = ad-hoc(总 8 设备);超过就加控制器或拆掉几台。
- 能量:AE2 用自己的 **AE**。**能源接收器**把别的模组的电转成 AE,接在网络任意处即可(**2 FE = 1 AE**);或烧**振动室**(默认 40 AE/t,可升级)。控制器自身每方块耗 6 AE/t。
- 网络有内置小缓冲(每个线缆/机器约 25 AE)。强烈建议放**能量单元**(energy_cell,200k AE):大量物品一次性进出时,瞬间耗电会超过存储量、网络缺电重启,加一个就稳。夜里靠存电跑、或要顶住空间存储的瞬时电流,用**致密能量单元**(1.6M AE)。
- 电量和供电状态:`ae2.network.inspect` 回执里的 `energyStored`/`energyMax`/`energyAvgUsage`/`energyPowered`/`gridPowered`。

## 2. 线缆

- 玻璃线缆最便宜(8 频道);盖上羊毛 → 覆盖线缆(纯外观、无机制差别);四根覆盖线 + 红石/萤石 → **致密线缆**(32 频道,但**不能直接挂总线/面板,要先降到普通线**)。
- 16 种颜色:同色线相邻**不连**,异色彼此与 fluix(无色)照连。用颜色把并排的线隔开,频道才会走你要的路径。**颜色和频道数量无关**。
- **智能线缆**:前 4 个频道显示为线缆本色竖条,后 4 个为白色;致密智能每条纹代表 4 个频道。有控制器时能看到频道的真实走向。
- **石英纤维**:两个网络共电不共网,给子网供电就靠它。
- **线缆锚点**:装在线的某一面,阻止该面与相邻方块/线连接;也能当梯子爬。
- 终端、总线、等级发射器、扁平能量接收器都是**线缆部件**,多个可叠在同一格线上;潜行右键用扳手/网络工具可单独拆掉一个部件,不影响旁边的。
- 转向/拆卸用 **certus 石英扳手**;**网络工具**是"带诊断的扳手",能拆部件但不能转向。
- 拆部件或看逐面连接:`ae2.network.inspect` 给出整张网,`ae2.config.read(x, y, z)` 在线缆那一格会列出每个可配部件的 `side`/`part`/`settings`,读不出服务端配置的裸线缆/锚点会直说。

## 3. 部件是按"面"工作的

放置时**贴着哪一面,就在那一面工作**:

- **导入总线(import bus)**:贴在目标容器/机器的一面,朝那面**抽**东西进网络。**导出总线(export bus)**:朝那面**推**东西出网络。两者默认什么都不做,过滤槽是**白名单**(`numen.move.to` 到旁边,`numen.use.block` 开它,把物品放进过滤槽)。
- **存储总线(storage bus)**:把贴着的那格容器**当成网络存储**;默认全收,过滤槽是白名单。用它把原有箱子/桶接进 ME。
- **接口(interface,整方块或扁平)**:自身有 9 格。上面几格设成"要囤什么"(放物品进去 + 点数量),它就从网络取来把这几格填够;没设成囤货的格,被塞进东西就推进网络。接口的过滤槽**空着**时,贴着它的存储总线能看到整张网络——做"子网仓库"的关键。
- **模式供应器(pattern provider,整方块/定向/扁平)**:把**模式(patterns)**里的原料**整批**推给相邻机器,也从相邻容器收物品进网络。
  - 普通版六面都推、六面都连网;用扳手点一下变**定向版**,只推选定的一面、且那一面**不连网**(做子网必经);**扁平版**贴在线上、可叠多个。
  - 挨着**分子装配室**时,会把合成/锻造/切石模式连同原料一起送过去,装配室做完自动把产物吐回供应器——这是合成模式自动化的核心组合。
  - 它**整批**推、不推半批;多台供应器带同一模式可并行,并会轮询所有面以并行使用多台机器。
- **终端**:线缆部件,看/取/放网络存储;合成终端多一个合成格并会自动补料;模式编码终端做 patterns;模式访问终端可给整片供应器插模式。
- **常见错法**:**线缆不是管道**,没有内部库存,把供应器/导出总线对着线缆毫无意义;终端贴反了不连网;供应器推的方向上不是机器而是线缆(它只好当线缆);把子网里的存储总线错接到主网,**导入总线会把料存进主网仓库而不是送到目标机器**。

## 4. 看整张网:`ae2.network.inspect`

对网上任意一格(线缆、机器、部件都行)调 `ae2.network.inspect(x, y, z)`,一次把这张网的状态交回来,只读、不动世界。主要字段:

- `block`:那一格的方块 id;`onGrid`:在不在 AE2 网上;`nodeActive`:这个节点活不活;`gridPowered`:网络有没有电;`summary`:一句话总结。
- 频道:`channelsUsed`(全网已用)、`channelsLimit`(无控制器时的 ad-hoc 上限,有控制器为 0)、`nodeChannelsUsed`/`nodeChannelsMax`(本节点的占用/带宽)、`channelMode`。
- 控制器:`controllerState`(`NO_CONTROLLER` / `CONTROLLER_ONLINE` / `CONTROLLER_CONFLICT` / `UNKNOWN`)、`controllerPresent`。
- 电量:`energyStored`、`energyMax`、`energyAvgUsage`、`energyPowered`。
- 设备:`deviceCount`(网里多少节点)、`devices`(形如 `3 x ae2:drive` 的清单,最多的在前,最多 32 条)。

离网时只有 `block` 与 `onGrid=false`,其余为 0/false,`summary` 会说清为什么(那一格是空气 / 没有网格节点 / 网格还没启起来,过一 tick 再看)。想等网络上线再开工,用 §9 的 `ae2.automation.wait_online`。

## 5. 读/改机器与部件的配置:`ae2.config.read` / `ae2.config.write`

AE2 的配置住在服务端:**能读就先读**,别按世界方向猜。

- `ae2.config.read(x, y, z)`:读这一格方块实体自己的配置。回执:`block`、`settings`(每项 `name`、`value`、`allowed`)、是储电方块时的 `aeCurrent`/`aeMax`。
- `ae2.config.read(x, y, z, side)`:线缆上的部件(总线、等级发射器、成形/湮灭面……)把配置挂在部件自己身上,不在线缆方块实体里。给 `side`(`north`/`south`/`east`/`west`/`up`/`down`)读那一面部件。不给 `side` 时,回执的 `parts` 会列出每个可配部件的 `side`/`part`/`settings`;裸线缆、锚点、开关总线不存设置,会直说没有可读配置。
- `ae2.config.write(x, y, z, setting, value)`(部件再加 `side`):改一项,回执给 `setting`、`oldValue`、`newValue`、`allowed`,以及出错时的合法取值。名字与取值**大小写不敏感**,以读回来的 `allowed` 为准。
- AE2 整方块机器与部件上常见的设置名(实际以 `ae2.config.read` 返回的 `allowed` 为准):

  | 设置名 | 含义 | 取值 |
  | --- | --- | --- |
  | `access` | 访问权限 | NO_ACCESS / READ / WRITE / READ_WRITE |
  | `io_direction` | 相对面输入/输出 | LEFT / RIGHT / UP / DOWN |
  | `blocking_mode` | 机器有料时不推下一批 | YES / NO |
  | `lock_crafting_mode` | 锁定合成条件 | NONE / LOCK_UNTIL_PULSE / LOCK_WHILE_HIGH / LOCK_WHILE_LOW / LOCK_UNTIL_RESULT |
  | `fuzzy_mode` | 模糊匹配(耐久/NBT) | IGNORE_ALL / PERCENT_99 / PERCENT_75 / PERCENT_50 / PERCENT_25 |
  | `storage_filter` | 存储过滤 | NONE / EXTRACTABLE_ONLY |
  | `scheduling_mode` | 多面轮询 | DEFAULT / ROUNDROBIN / RANDOM |
  | `redstone_controlled` | 红石控制 | IGNORE / LOW_SIGNAL / HIGH_SIGNAL / SIGNAL_PULSE |
  | `level_emitter_mode` | 等级发射器比较量 | STORED_AMOUNT / STORABLE_AMOUNT |
  | `craft_via_redstone` | 靠红石触发合成 | YES / NO |
  | `place_block` | 成形面:放置还是丢弃 | YES / NO |
  | `inscriber_separate_sides` | 压印器分面模式 | YES / NO |
  | `inscriber_input_capacity` | 压印器输入缓冲 | ONE / FOUR / SIXTY_FOUR |
  | `auto_export` | 压印器自动输出 | YES / NO |
  | `pattern_access_terminal` | 是否在模式访问终端显示 | YES / NO |
  | `power_units` | 电量显示单位 | AE / FE |

- **过滤 / 囤货 / 放模式 / 插升级卡,都是"往槽里放东西",不是配置**:`numen.move.to` 到旁边 → `numen.use.block` 开 GUI → `numen.gui.view` 看槽位与角色(`[config]` 过滤槽、`[pattern]` 模式槽、`[upgrade]` 升级槽、`[input]`/`[output]` 机器槽)→ 用 `numen.gui` 的放/移函数把物品放进对应槽 → `numen.gui.close`。
- 升级卡(加速/容量/模糊/反向/红石/合成/能量)也放进 `[upgrade]` 槽,不是设置。
- 客户端控件(模式/红石/调度那几个按钮)你点不到;需要非默认档时,用 `ae2.config.write` 走服务端同一份配置,或者如实告诉主人。

## 6. 存储

- **ME 驱动器(drive)** 有 10 格插**存储元件**;元件分 **1k/4k/16k/64k/256k**。物品元件最多 63 种,流体元件最多 5 种。
- 容量看两个数:**字节(bytes)** 是总量,**类型(types)** 是能存多少种不同东西。物品每件 1 bit,8 件 = 1 字节;每种东西先扣一笔"类型开销"(所以一个元件只放 1 种东西时容量是放满 63 种的两倍)。
- **别把 mob 掉落里带不同耐久/附魔的装备往网络里塞**——每件都是一个独立类型,很快塞满、还拖慢排序。入库前先过滤掉。
- 别只追最高级元件:各档类型上限一样,低级元件类型开销小,后期仍有用。要"大容量单物品仓库":用元件工作台把元件**分区**成只收某几种物品,单独放一个驱动器并把它**优先级调高**。
- **元件工作台(cell_workbench)**:给元件分区/清分区、装升级卡(模糊/反向/均分/虚空/能量)。被分区的元件视为"本来就存着这些",同优先级下入库优先选它。
- 流体元件存流体;**气体元件**要装了对应扩展模组才有(AE2 本体没有)。便携元件能装能量卡当充电背包。
- 驱动器每格有状态灯:绿=空、蓝=有内容、橙=类型满、红=字节满、黑=无电或无频道。

## 7. 自动合成

三件套:**谁发起请求** → **合成 CPU** → **模式供应器**。你直接从脚本下单,不用主人点客户端合成按钮。

### 7.1 编码模式(patterns):`ae2.pattern.encode`

用模式编码终端加**空白模式**:

- 先 `numen.move.to` 到模式**编码**终端旁(arrive 用 `use`)、`numen.use.block` 打开它,手上或终端网络里先有一张 `ae2:blank_pattern`。模式**访问**终端只能插模式、编不了。
- 再调 `ae2.pattern.encode`,传一张表:
  - `inputs`、`outputs`:各是 `{item = "命名空间:id", count = 数量}` 的列表,`count` 不写按 1。`inputs` 是机器要吃的、`outputs` 是它产出的;未知物品会当场拒绝。
  - `mode` 不写默认 `processing`(处理模式);`crafting` 是原版 3×3 工作台配方。
  - `substitute`:只对合成模式有意义,允许 AE2 物品替换。
- 回执:`summary`、`mode`、`inInventory`(编好的样板是不是已经进背包了)、`where`(不在背包时在哪,例如"在终端的输出槽",用 `numen.gui.quick` 取)。编码格是**鬼影格**:只收"设置"、不收真实物品,别的动作放不进去,这条路替你写进去。
- **处理模式(processing)** 最通用——"供应器把这些原料推出去,网络以后会收到这个产物"。任何机器、炉子、整条产线都能用,网络不关心中间过程。
- **合成模式(crafting)** 是工作台配方,放进供应器并配**分子装配室**自动做;供应器会把模式随原料一起送进装配室。锻造台/切石机模式同理,可共用一套装配室:输出只写配方产物那一件,`inputs` 按配方摆。
- 查配方用 `numen.inv.recipes`,但**别照抄它的全部步骤**:多步配方要自己编码成"从原料到成品"的一条(例如处理器模式里**不该**包含模具,因为模具已放好在压印器里)。

### 7.2 下单与查询:`ae2.craft.request` / `ae2.craft.status`

- `ae2.craft.request(x, y, z, item, count)`:坐标是网上任意 AE2 设备那一格;先 `numen.move.to` 站到它 8 格内。用普通注册表物品 id(不指定特殊组件/NBT)。`count` 是**额外合成数量**,不是把库存补到该数量。
- 回执:`requestId`(保存它)与 `state="calculating"`——这只是开始算料,**不代表接单、更不代表完成**。缺料、没模式、没有合适的 CPU 会如实失败并写进 `detail`,不消耗原料。
- `ae2.craft.status(requestId)`:回执 `state`(`calculating`/`submitted`/`completed`/`failed`/`canceled`)、`detail`(在做什么,失败时是原因)、`progress`/`total`(提交后已做/总量)、以及 `item`/`count`/`bytes`/`jobId`。
- **`submitted` 才是 CPU 接单,`completed` 才是做完。不要重发带 item 的新订单来轮询**,那会多做一批;只反复查同一个 `requestId`。随包模块 `ae2.automation.request_until_done` 就是"下一次单、跟到停"。
- 产物回 **ME 网络**,不在你背包;需要拿到手时再从终端取出并核实。处理模式还要真实机器及产物回流;木头→木板的工作台配方应编 `mode = "crafting"`,配分子装配室。
- 查询账本在关闭世界时清空,记录超过 64 条时新订单会淘汰已结束的旧记录;AE2 自身仍保存已提交作业。查不到旧编号不代表作业成功或失败,先查网络/CPU 与产物库存。

### 7.3 处理器的来路

1. **处理器(所有 AE2 机器的原料)**:找陨石、挖中间的神秘方块拿到 4 个压印模具。**压印器(inscriber)** 分面工作:顶/底槽从顶/底进,中间槽从四个侧面进,产物从四个侧面抽;用扳手可转向。压印器设置里常见的 `inscriber_separate_sides`、`inscriber_input_capacity`、`auto_export` 用 `ae2.config.read`/`ae2.config.write` 读写。充能器把赛特斯水晶充能;硅→印硅,金/赛特斯/钻石→三种印电路,再 + 印硅 + 红石 → 逻辑/计算/工程处理器。这本身就是第一条自动化产线(见 §8)。

### 7.4 合成 CPU 与产物回网

- **合成 CPU 多方块**:`1k…256k 合成存储`(至少一个,存中间产物)+ 可选`并行处理单元`(加快推送、让多步并行)+ 可选`合成监控器`(显示进度)+ `合成单元`(补空)。必须是**实心长方体**、无空洞。每个 CPU 同一时间只接一个请求,所以给大任务和小任务各留一个。
- **产物必须"重新进入系统"**:装配室的产物会吐回供应器/接口;机器产物要用导入总线或接口抽回网络。**只用带存储总线的箱子接住产物不算**——那绕过了网络,合成不会完成。
- **常备库存**:接口设好囤货数量 + **合成卡** → 不够时请求自动合成(适合少量多种)。**导出总线 + 等级发射器 + 合成卡** → 数量低于阈值时发红石、导出总线请求合成并导出(适合大量单一物品)。等级发射器还能"发红石以合成物品",给无限农场/无输入产线当虚拟模式。
- **递归配方**(产物又是原料,如复制下界合金升级模板)标准算法处理不了:**等级发射器 + 合成卡**设成"发红石以合成物品"伪装成一个模式,再搭个小回路把产物灌回装配室(两个存储总线优先级要一高一低)。
- **子网**:把一整片机器包进独立网络,主网只看到一个接口/供应器——省频道、限制可见存储、并行扩容。供电子网用**石英纤维**。做子网时最常翻车的就是**连接没断开**:满屏接口总线,结果全通过某个整方块机器连回了主网。用 `ae2.network.inspect` 逐格核对 `onGrid`/`deviceCount` 能看出有没有漏连。

## 8. 完整示例:处理器自动化产线

目标:主网请求"逻辑处理器",产线自动做出并送回。用 1 个模式供应器 + 1 个桶 + 4 台压印器(印硅/印逻辑/印计算/印工程)+ 1 台总装压印器 + 若干导入/导出/存储总线。整套放在**主网之外**,主网只占 **1 个频道**。

1. **先算频道再摆**:主网侧只挂 1 台**模式供应器**(1 频道);其余总线都在子网上,不占主网。子网自己 ≤8 台吃频道设备:5 台压印器 + 多个总线会超 8,所以把它们拆成几条更小的子网(每条只服务 1~2 台机器),彼此靠桶传物品。**先把分组定好,再动方块。**
2. **摆结构**:`numen.build.place` 放机架、桶与整方块机器(一排压印器、模式供应器);压印器用放置/扳手定好朝向。用 `ae2.config.write` 把压印器设成 `inscriber_separate_sides = "YES"`,再用 `numen.move.to` + `numen.use.block` + `numen.gui` 给每台升级槽装 4 张加速卡、放好对应模具。
3. **主网侧**:模式供应器贴主网线缆;在模式编码终端里用 `ae2.pattern.encode` 编码 3 条**处理模式**(原料→成品,不含模具),再用 `numen.gui` 找到 `[pattern]` 槽放进去。
4. **第一段(投料)**:供应器把原料(硅、金/赛特斯/钻石、红石)推进旁边的桶。桶旁一条子网上放**导出总线**(白名单对应原料,带 2 张加速卡)把料送进对应压印器;**存储总线**让这条子网只认识桶和压印器。子网经**石英纤维**从主网取电。
5. **第二段(中间件)**:前 4 台压印器产出印硅/印逻辑/印计算/印工程,用**导入总线**抽入子网,再由存储总线/导出总线送进第 5 台总装压印器。
6. **第三段(总装回网)**:第 5 台压印器产出成品,用**导入总线**或**接口**抽回,再**推回模式供应器**(它的返回槽算"重新进入系统")。
7. **验证**:`ae2.network.inspect` 读 `channelsUsed`/`gridPowered`/`deviceCount` 判断子网是否够频道、供上电;`ae2.config.read` 复核压印器设置;`numen.use.block` + `numen.gui.view` 逐台看进料/出货槽;用 `ae2.craft.request` 请求一次、再 `ae2.craft.status` 跟到完成(或直接 `ae2.automation.request_until_done`),看产物是否回到网络。

其他产线照本包 AE2 自带指南找:矿石福运机、熔炉 1 频道自动化、投水自动化、进阶赛特斯农场等。

## 9. 用脚本落地

- **`numen.api.help("ae2.network.inspect")` / `numen.api.help("ae2")`**:现查每个函数的签名、参数、返回和例子,以及这个命名空间下有什么。写错名字时回执也会附上最近的正确名字。
- **`ae2.network.inspect(x, y, z)`**:一次读整张网的频道、控制器、电量、设备清单(见 §4)。
- **`ae2.config.read` / `ae2.config.write`**:读/改机器与线缆部件的服务端配置(见 §5);读回执里的 `allowed` 就是合法取值。
- **`ae2.pattern.encode`**:在打开的编码终端里编样板(见 §7.1)。
- **`ae2.craft.request` / `ae2.craft.status`**:下单并查询(见 §7.2);**只查不重下**。
- **通用动作**:`numen.move.to` 走到机器旁(arrive 用 `use`);`numen.use.block` 开机器/终端 GUI,也用来放线缆/部件/机器、拿扳手转向;`numen.gui.view` 看槽位与角色,`numen.gui.put`/`numen.gui.move`/`numen.gui.quick` 放/移/整叠,`numen.gui.close` 关;`numen.build.place` 摆方块;`numen.scan.blocks` 找方块;`numen.work.dig`/`numen.work.mine` 挖;`numen.inv.make`/`numen.inv.craft` 备料,`numen.inv.recipes` 查配方。
- **随包模块 `ae2.automation`**(把上面的原子调用拼成一件完整的事,第一次用到才装):
  - `ae2.automation.request_until_done(x, y, z, item, count, opts)`:下单并轮询到停,完成返回最后一条 `ae2.craft.status`,失败/取消则抛出;从不下第二单。
  - `ae2.automation.ensure_pattern_encoded(spec)`:调 `ae2.pattern.encode` 并确认样板进了背包,没进就报出它落在哪。
  - `ae2.automation.wait_online(x, y, z, opts)`:等 `ae2.network.inspect` 报出 `onGrid`、`nodeActive`、`gridPowered` 都为真再往下做。
