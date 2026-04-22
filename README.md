# MineAI

> 一个面向 Minecraft 的 AI Agent 工程框架。
>  
> 它不只是“会聊天的 Bot”，而是一个可执行任务、可接语音、可桥接客户端、可适配模组战斗的 Minecraft AI 系统。

## 项目简介

MineAI 是一个以 Minecraft 自动体为核心的工程化项目。它基于 `mineflayer` 构建，但在此基础上继续补齐了多模型接入、语音链路、客户端桥接、Forge Agent、模组运行时识别、自主阶段推进、战斗控制和测试体系等能力。

这个仓库更适合两类人：

- 想快速跑起一个可交互、可配置、可扩展的 Minecraft AI Bot
- 想在 Minecraft 场景里继续做 Agent、语音、模组适配、自动化任务或战斗控制开发

## 为什么是 MineAI

### 相比 Mindcraft

基于 Mindcraft 公开 README 和当前两个仓库的代码结构来看，MineAI 更强调“面向 Minecraft AI 代理的工程扩展层”，优势主要体现在：

- 更强的工程化扩展能力：内置了 `clientBridge`、`forge-agent`、`modRuntime`、`modKnowledge` 等模块，便于继续做客户端控制、模组识别和战斗扩展。
- 更完整的语音链路：仓库已经集成语音输入、语音输出、唤醒词、语音回复策略、本地与云端语音 provider。
- 更适合模组与客户端联动：默认就考虑了 Forge 运行时、Prism Launcher、客户端桥接、自定义战斗模式等现实使用场景。
- 更适合作为二次开发底座：目录结构按能力域拆分，测试覆盖了语音、桥接、自治控制、战斗导航、模组运行时等模块。

一句话说，Mindcraft 更像知名的基础原型与研究框架，MineAI 更像在真实 Minecraft 使用链路上继续工程化演进的版本。

### 相比 AIRI

基于 AIRI 公开仓库定位来看，AIRI 更偏通用 AI Companion / 桌面交互体验；MineAI 则更聚焦 Minecraft 本身，优势在于：

- 场景更聚焦：MineAI 的核心问题就是“让 AI 在 Minecraft 世界里行动、判断、战斗、协作和接入模组”。
- 执行能力更强：这里不是泛陪伴 UI，而是围绕任务执行、玩家跟随、资源采集、战斗处理、客户端控制来设计。
- 模组与战斗更贴近实际：内置 Epic Fight、SlashBlade、Touhou Little Maid 等能力识别与运行时适配方向。
- 更适合做 Minecraft Agent 研究与项目化落地：代码路径更清晰，能力边界更明确，不需要先从泛 AI 容器裁剪出 Minecraft 能力。

一句话说，AIRI 更适合通用 AI 伴生体验，MineAI 更适合做 Minecraft AI Agent。

## 核心能力

- 多模型支持：OpenAI、Gemini、Claude、DeepSeek、Groq、Qwen、Mistral、Ollama、OpenRouter 等
- Minecraft Bot 控制：聊天、移动、采集、合成、战斗、任务执行
- 自主阶段推进：从早期生存到资源发展阶段的自动决策
- 语音系统：语音输入、语音回复、唤醒词、TTS/ASR provider 接入
- 客户端桥接：将本地客户端动作和 AI 控制逻辑连接起来
- Forge Agent：为模组客户端/战斗类场景提供扩展入口
- 模组运行时识别：扫描 Mod 目录，识别能力标签并自动调整行为
- 可测试：仓库内已经包含针对战斗、桥接、语音、自治、模组运行时的测试

## 3 分钟快速开始

### 1. 环境要求

- Minecraft Java Edition
- Node.js 18 或 20 LTS
- 至少一个可用的大模型 API Key
- 如果你要用语音或 Forge 扩展，需要额外准备对应本地环境

### 2. 安装依赖

```bash
npm install
```

### 3. 配置 API Key

将 `keys.example.json` 复制为 `keys.json`，填入你实际使用的密钥。

如果你不想使用 `keys.json`，项目也支持从环境变量读取密钥。

### 4. 选择配置

默认配置位于：

- `settings.ts`
- `profiles/`

默认使用的 profile 是：

- `./profiles/gpt.json`

### 5. 启动 Minecraft 世界

默认连接地址在 `settings.ts` 中是：

- `host: 127.0.0.1`
- `port: 55916`
- `auth: offline`

最简单的方式是启动一个本地 Minecraft 世界，并开放到局域网，端口设为 `55916`。

### 6. 启动 MineAI

```bash
npm run dev
```

如果一切正常，MindServer 会启动，Agent 会按当前配置创建并连接。

## 常用命令

```bash
# 启动主程序
npm run dev

# 运行测试
npm test

# 类型检查
npm run typecheck

# 启动客户端桥接
npm run client:bridge

# 发送客户端指令
npm run client:command

# 运行客户端自动战斗
npm run client:autocombat

# 语音麦克风监听
npm run voice:mic

# 构建 Forge Agent
npm run forge-agent:build
```

## 项目结构

```text
MineAI/
├─ src/
│  ├─ agent/            # Bot 核心行为、模式、命令、技能
│  ├─ autonomy/         # 自主阶段推进与恢复策略
│  ├─ clientBridge/     # 本地客户端桥接与自动战斗
│  ├─ voice/            # 语音输入输出、provider、路由
│  ├─ modRuntime/       # Mod 运行时检测与实例发现
│  ├─ modKnowledge/     # Mod 知识索引
│  ├─ companion/        # 陪伴风格与 companion runtime
│  ├─ forgeAgent/       # Forge Agent 支持代码
│  ├─ maid/             # 女仆/农务等专用行为规划
│  ├─ models/           # 模型适配层
│  └─ mindcraft/        # 当前主运行入口相关模块
├─ scripts/             # 语音、桥接、Forge 构建与部署脚本
├─ profiles/            # Agent 配置模板
├─ tasks/               # 任务定义
├─ tests/               # 回归测试
├─ forge-agent/         # Forge 端扩展工程
├─ main.ts              # 程序入口
└─ settings.ts          # 全局运行配置
```

## 配置说明

MineAI 的主要配置入口有两个：

### `settings.ts`

用于控制全局运行行为，例如：

- Minecraft 连接地址
- 是否开启自动 UI
- 是否启用自治
- 是否启用模组运行时
- 是否启用语音
- 战斗模式
- 运行输出目录

### `profiles/*.json`

用于控制单个 Agent 的身份与模型配置，例如：

- 角色名称
- 模型提供方与模型名
- 不同能力使用不同模型
- Prompt 风格

## 进阶能力

### 语音能力

项目已经接入：

- 麦克风监听
- 唤醒词
- 语音意图路由
- 语音回复策略
- OpenVoice / 豆包等 provider 相关支持

相关脚本位于 `scripts/`，核心代码位于 `src/voice/`。

### 客户端桥接

如果你不只想控制 Mineflayer Bot，还想和本地客户端行为联动，可以关注：

- `src/clientBridge/`
- `scripts/run-client-bridge.ts`
- `scripts/run-client-autocombat.ts`

### Forge Agent 与模组适配

如果你的目标是做模组战斗、客户端动作控制或运行时 Mod 能力识别，可以关注：

- `forge-agent/`
- `src/forgeAgent/`
- `src/modRuntime/`
- `src/modKnowledge/`

## 安全提醒

这个项目包含可执行动作规划、代码调用链路、语音输入和外部模型接入能力，请不要在不了解风险的情况下直接连接公开服务器或启用高风险能力。

尤其需要注意：

- `allow_insecure_coding` 打开后，会提升模型执行本地代码相关风险
- 不要把带真实密钥的 `keys.json` 上传到 GitHub
- 不要直接在高价值账号、公开服务器或不受控环境中测试危险能力

## 适合的使用场景

- Minecraft AI Bot 原型开发
- 多模型驱动的 Minecraft Agent
- Minecraft 语音助手 / 语音同伴
- Minecraft 客户端桥接与本地自动战斗
- Forge 模组环境下的 AI 自动体
- 面向 Minecraft 场景的 Agent 研究与实验项目

## 开发与贡献

如果你准备继续开发这个项目，建议按下面顺序进入代码：

1. 从 `main.ts` 和 `settings.ts` 理解启动流程
2. 从 `src/agent/` 理解核心 Bot 行为
3. 从 `src/autonomy/` 理解自主决策
4. 从 `src/clientBridge/` / `src/voice/` / `src/modRuntime/` 进入对应扩展方向
5. 修改后优先补 `tests/` 里的回归测试

如果你准备发布到 GitHub，建议至少保留：

- `README.md`
- `package.json`
- `package-lock.json`
- `src/`
- `scripts/`
- `profiles/`
- `tasks/`
- `tests/`
- `forge-agent/`

## 许可证与版权说明

### 主项目许可证

本项目（MineAI）的主要代码目前采用与原 Mindcraft 项目一致的 MIT License，详细内容请参阅根目录下的 [LICENSE](./LICENSE) 文件。

### 第三方组件与依赖许可证

本项目使用了多个第三方开源依赖与运行时组件，包括但不限于 Node.js 生态依赖、Python 依赖、Minecraft 相关库以及可选的模组/运行时工具。这些第三方组件仍然遵循它们各自的许可证条款。

重要说明：

- 本项目根目录下的 `LICENSE` 仅描述 MineAI 当前仓库主体代码的许可证。
- 通过 `package.json`、`requirements.txt`、Forge 运行时或其他方式引入的第三方依赖，版权仍归各自原作者所有。
- 如果你在二次开发、再分发或商业使用中涉及第三方组件，请自行核查并遵守其许可证要求。

### 版权归属

- MineAI 当前仓库中的主体整合与二次开发部分，版权归其各自贡献者所有。
- 原始 Mindcraft 项目及其保留代码部分，版权与许可证归原作者及原项目贡献者所有。
- 第三方依赖、模型、模组及工具，版权归各自作者或权利人所有。

## 使用声明

- 本项目运行过程中产生的文本、语音、行动决策、日志和其他输出内容，仅应作为参考、实验、学习或开发用途使用。
- AI 会犯错，请务必自行甄别其输出内容、行为决策和执行结果的准确性与安全性。
- 对于本项目提供的自动化能力、联网能力、语音能力、客户端控制能力及你基于本项目继续扩展出的功能，使用者需自行承担使用风险与合规责任。
- 请勿将本项目用于干扰他人服务、破坏客户端/服务器正常运行、规避访问控制、侵犯隐私或其他不当用途。
- 请勿将本项目用于任何违反适用法律法规的行为。若因使用不当产生任何直接或间接后果，项目维护者与贡献者不承担相关责任。

## License

[LICENSE](./LICENSE)
