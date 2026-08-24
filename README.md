# MineAI

MineAI 是面向 Minecraft Java 1.20.1 的中文 AI NPC。它用 DeepSeek 负责理解和规划，用 Mineflayer 执行原版世界任务，用 Forge Bridge 接入客户端和模组能力，并可使用豆包完成实时语音输入与 HTTP v3 语音回复。

## 能力

- 中文聊天、语音指令和结构化意图路由
- 采集、制作、移动、跟随、恢复和长期任务推进
- 有界 Agent Action Loop、状态机、取消、超时和后置验证
- 女仆式沉浸战斗：保护、攻击、撤退、停火和战斗抢占
- Mineflayer Pathfinder、PVP、Tool 与状态机扩展
- Forge 1.20.1 客户端桥接，支持动作 ACK、模组能力检测和安全停止
- 豆包 realtime ASR partial/final 事件流与 HTTP v3 TTS
- 离线回放、语音、战斗、桥接和自治测试

默认安全边界：模型只能输出受注册表和权限系统约束的高层动作；不能直接执行任意 Mineflayer、Forge、Shell 或文件系统操作。partial 语音抢占默认关闭。

## 快速开始

### 环境

- Node.js 18 或 20 LTS
- Minecraft Java Edition 1.20.1
- 一个可连接的 Minecraft 世界，或 Forge 1.20.1 客户端
- DeepSeek API key

### 安装

```bash
npm install
```

### 配置 `.env`

复制 `.env.example` 为 `.env`，填写密钥。项目通过 `.env` 读取密钥，不需要在系统中设置环境变量。

```dotenv
DEEPSEEK_API_KEY=你的DeepSeek密钥
DOUBAO_API_KEY=你的豆包新版控制台API密钥
DOUBAO_TTS_MODEL=seed-audio-1.0
```

默认中文 NPC 配置位于 `profiles/chinese_npc.json`。全局连接、语音、自治、战斗和 Forge 开关位于 `settings.ts`。

### 启动

1. 启动 Minecraft，并让 Mineflayer 能够连接到 `settings.ts` 中的地址和端口。
2. 选择 `profiles/chinese_npc.json` 作为 Agent profile。
3. 启动 MineAI：

```bash
npm run dev
```

默认配置使用离线模式连接本地世界。公开服务器、真实账号和高风险动作请先完成权限与安全配置。

## 语音

豆包 realtime 负责 ASR，豆包 HTTP v3 负责 TTS。语音开关、唤醒词、麦克风参数和 `partial_asr_enabled` 位于 `settings.ts` 的 `voice` 配置中。

```bash
npm run voice:mic -- --agent <agent-name>
npm run voice:test:doubao-asr
npm run voice:test:doubao
```

partial ASR 只允许本地安全短语（停止、停火、别打、撤退、保护我）抢占当前语音和战斗，不会绕过 DeepSeek 生成任意命令。

## Forge 与客户端桥接

Forge 扩展工程位于 `forge-agent/`。Node 侧桥接、协议和战斗适配位于 `src/clientBridge/` 与 `src/combat/`。

```bash
npm run client:bridge
npm run client:command
npm run client:autocombat
npm run forge-agent:build
```

Forge ACK 表示客户端主线程已执行动作输入，不代表一定造成伤害；Node 侧仍会检查 command id、客户端状态和后置快照。当前环境需要可用的 Java/Gradle 工具链才能编译 Forge Agent。

## 常用开发命令

```bash
npm run typecheck
npm test
npm run replay -- <case.json>
```

`npm test` 在缺少第三方模型 key 时可能失败相关模型测试；语音、战斗、桥接和自治测试不需要连接真实服务。

## 目录

```text
src/agent/          Agent、权限、任务和 MindServer
src/autonomy/       自治循环、阶段推进和恢复
src/voice/          ASR、TTS、语音路由和播放控制
src/combat/         战斗控制器、仲裁器和 Forge 适配
src/clientBridge/   客户端桥接协议与运行时
src/modRuntime/     Mod 扫描、能力检测和实例发现
src/maid/            女仆工作与农务规划
forge-agent/        Forge 1.20.1 客户端扩展
profiles/           Agent profile
scripts/            启动、语音、桥接和 Forge 工具
tests/              回归测试
docs/               设计文档和发展路线
```

## 安全

- 不要提交 `.env`、`keys.json` 或任何真实 API key。
- 不要直接连接不受控的公开服务器。
- 高风险命令需要权限和确认；Forge、战斗和自主行为默认按配置受限。
- AI 输出和行动结果必须以游戏状态反馈为准，不要把模型回复当作执行成功证明。

## 设计与贡献

开始阅读：`docs/MINEAI_DEVELOPMENT_ROADMAP.md`、`settings.ts`、`main.ts` 和 `src/agent/`。

新增能力应经过结构化命令注册表、权限检查、取消路径、后置验证和回归测试。设计变更先写入 `docs/`，再实现代码；提交前运行类型检查、相关专项测试和完整 review。

## 许可证

本项目主体代码使用 MIT License，详见 [LICENSE](./LICENSE)。第三方依赖、Minecraft、Forge、模组和模型服务遵循各自许可证与服务条款。
