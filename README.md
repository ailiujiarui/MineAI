<div align="center">

# MineAI

**基于 [Numen](https://github.com/Dwinovo/minecraft-numen) 二开的 Minecraft 智能体**

[English](README_EN.md) · [**简体中文**](README.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0-A8731E?style=flat-square)

<a href="#安装">安装</a> · <a href="#快速开始">快速开始</a> · <a href="#内置集成">内置集成</a> · <a href="#架构">架构</a> · <a href="#许可">许可</a>

</div>

MineAI 是一个**运行在 Minecraft 内部的具身智能体**，作为模组直接存在于游戏之中。它操纵一个真实的玩家，
能够感知世界、规划并执行任务：寻路、挖掘、建造、战斗、合成，以及使用容器与机器。底层引擎、Lua 程序接口、
技能与外接大脑等能力继承自上游 [Numen](https://github.com/Dwinovo/minecraft-numen)，本仓库在其之上做了
**面向科技整合包的集成与增强**。

## 与上游的关系

MineAI 是 Numen 的二开分支，跟踪上游最新架构。与上游的差别集中在“让智能体真正会用科技模组”这件事上：

- 以**上游最新的 Lua 程序模型**为底座（模型编写在沙箱中运行的 Lua 程序，自由组合原子动作，一次调用连续完成数十个步骤）；
- 在 `plugins/` 下补齐了面向科技整合包的联动，向外暴露成 `ae2.*` / `mekanism.*` / `jei.*` 等 Lua API；
- 增加了确定性的**自验证**能力与**离线知识库**，让智能体在下结论前先量真实世界。

上游的通用能力、安装方式与完整架构说明，请见 [Numen 官网](https://numen.dwinovo.cn)。

## 安装

MineAI 目前面向 **Minecraft 1.21.1（Fabric / NeoForge，Java 21）**。从本仓库的构建产物安装：

1. 用 Java 21 构建：`./gradlew :core:neoforge:build`（或 `:core:fabric:build`）；
2. 把 `core/neoforge/build/libs/` 下生成的 jar 放进整合包的 `mods/`；
3. 若目标模组（AE2、Mekanism、JEI 等）已安装，对应联动会自动接上；不装也不影响本体。

> 智能体的推理发生在你自己的客户端上，API key 只保存在本地。联机时服务端与客户端都需安装。

## 快速开始

1. **配置模型。** 进入游戏后按打开面板的快捷键（默认 `G`，可在按键设置中修改），在设置页选择模型服务并填入**你自己的 API key**。原生支持 OpenAI 与 Anthropic 两种协议，兼容它们的服务商填入地址即可用。
2. **召唤智能体。** 在面板中召唤一个智能体，为它起一个名字。
3. **开始交流。** 用自然语言告诉它你想做的事。

### 外接大脑

本模组可以整体作为 **MCP 服务器**，由 Claude Code、Codex、Cursor 等支持 MCP 的客户端直接驱动。此时
推理与 token 消耗都计在外部智能体上，可用其**订阅套餐**驱动，通常比按量调用 API 更划算。

### 常用操作

| 按键 | 作用 |
|---|---|
| `G` | 打开面板 |
| `Y` | 不打开面板，快速输入一句话 |
| 按住 `R` 并滚动鼠标滚轮 | 有多个智能体时，选择当前对话对象 |
| 按住 `V` | 语音输入，松开后发送，不影响 WASD 移动 |

## 内置集成

以下联动在目标模组在场时自动装载，全部以上游的 **Lua API 组**形式暴露给模型（脚本里是 `组.函数(...)`）：

| 模组 | Lua 命名空间 | 能力 |
|---|---|---|
| **Applied Energistics 2** | `ae2.network` / `ae2.config` / `ae2.pattern` / `ae2.craft` | 读 ME 网络（频道/控制器/电量/设备）、读写机器与线缆 part 的服务端配置、在模式编码终端编样板、向合成 CPU 下单并查询进度 |
| **Mekanism** | `mekanism.machine` | 读机器状态、逐面传输配置、化学品罐与热量 |
| **JEI** | `jei.recipe` | 查询物品的配方（产物/用途/催化剂） |

本体自带、不依赖任何模组的能力：

- **自验证 `numen.verify`**：`have` / `block` / `near` 三个确定性检查，拿一句话的宣称去量权威的服务端状态，
  给一句骗不了自己的判词。长期目标在收工前会用它核对，量不过就带着真实差异继续做。
- **离线知识库 `numen.kb`**：索引 GuideME / Patchouli / FTB 任务文本，`kb.search` 在本地检索，供模型查阅整合包自带的说明。

最新的命令与返回值请以模型实际看到的 API 索引为准（`numen.api`）。

## 架构

<p align="center">
  <img src="assets/diagrams/numen-architecture.svg" alt="MineAI 的分层架构" width="760">
</p>

架构自下而上分为三层。最底层是 **Minecraft**，智能体在其中操纵一个真实的玩家。中间层是开放给大语言模型的
接口，主体是由**原子动作**构成的 **Lua API**（感知、移动、挖掘、建造、交互，其他模组也能经插件接入），
另有少量以工具调用形式提供的 **Java API**。最上层是**大语言模型**，它编写在沙箱中运行的 Lua 程序，
自由组合原子动作，一次调用连续完成数十个步骤。完整的架构说明见[上游官网](https://numen.dwinovo.cn)。

## 插件

任何开发者都可以通过插件向智能体注册新的 Lua API，让它学会使用其他模组。本仓库 `plugins/` 下既有继承自
上游的联动（是，史蒂夫模型、车万女仆、Curios、FTB Quests、森罗物语等），也有本仓库新增的科技模组联动，
可作为编写插件的参考模板。

## 常见问题

**我的 API key 安全吗？**
安全。推理发生在你自己的客户端上，API key 只保存在本地，直接发往你选择的模型服务，不经过任何第三方服务器。

**联机服能用吗？**
能。服务端与客户端都安装后即可在联机服中使用；每位玩家使用自己的 API key 驱动属于自己的智能体。

**要花钱吗？**
MineAI 本身开源免费，调用大模型的费用由你选择的模型服务决定。

**和上游 Numen 有什么区别？**
MineAI 跟踪上游架构，主要在其之上补了面向科技整合包的模组联动、自验证与离线知识库。通用能力以上游为准。

## 许可

本项目源代码以 [LGPL-3.0](LICENSE) 协议发布，通过 API 使用本项目的插件不受此限。截图、演示动图与架构图
以 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 协议发布，其余美术资源保留所有权利，详见
[licenses/ASSETS.txt](licenses/ASSETS.txt)。

## 致谢

本项目基于 [Numen](https://github.com/Dwinovo/minecraft-numen)（作者 dwinovo 及其[贡献者](https://github.com/Dwinovo/minecraft-numen/graphs/contributors)）二开，
在此致谢。所借鉴的开源项目与研究工作的详情，请见上游官网。
