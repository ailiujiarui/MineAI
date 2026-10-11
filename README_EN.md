<div align="center">

# MineAI

**A Minecraft agent forked from [Numen](https://github.com/Dwinovo/minecraft-numen)**

[**English**](README_EN.md) · [简体中文](README.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0-A8731E?style=flat-square)

<a href="#installation">Installation</a> · <a href="#getting-started">Getting Started</a> · <a href="#built-in-integrations">Built-in Integrations</a> · <a href="#architecture">Architecture</a> · <a href="#license">License</a>

</div>

MineAI is an **embodied agent that runs inside Minecraft**. It lives in the game as a mod and controls a real
player: it perceives the world, plans, and carries out tasks — travelling, mining, building, fighting, crafting,
and using containers and machines. The engine, the Lua program interface, skills and the external-brain mode all
come from the upstream project [Numen](https://github.com/Dwinovo/minecraft-numen); this repository builds on top
of it with **integrations and enhancements aimed at technology modpacks**.

## Relation to upstream

MineAI is a fork of Numen and tracks its latest architecture. The difference is concentrated in one thing:
*actually being able to use technology mods*.

- It is built on upstream's latest **Lua program model** (the model writes Lua programs that run in a sandbox and
  freely combine atomic actions, completing dozens of steps in a single call);
- it fills in the `plugins/` **NeoForge integrations** for technology modpacks, exposed to the model as Lua APIs such as
  `ae2.*`, `mekanism.*` and `jei.*`;
- it adds deterministic **self-verification** and an **offline knowledge base**, so the agent measures the real
  world before drawing conclusions.

For upstream's general capabilities, installation and full architecture, see the
[Numen website](https://numen.dwinovo.cn).

## Installation

MineAI targets **Minecraft 1.21.1 (Fabric / NeoForge, Java 21)**. Download the matching loader's `mineai-<version>-<loader>-1.21.1.jar` from this repository's [GitHub Releases](../../releases) and put it in your pack's `mods/`. Choose **NeoForge** for technology-modpack integrations; Fabric currently bundles only the YSM integration.

To build player release jars yourself, use Java 21 and run **two separate invocations**:

```bash
./gradlew datagenAll --no-daemon
./gradlew releaseJars --no-daemon
```

Take the matching jar from `build/release/fabric/` or `build/release/neoforge/`. Integrations load automatically when the target mod is installed and its loader is supported; absent target mods do not affect the base mod. Release filenames use MineAI; internal `mod_id` values (`numen` / `numen_api`), API package names and Lua namespaces retain Numen.

> The agent's reasoning happens on your own client, and the API key is stored only locally. For multiplayer,
> install it on both the server and each client.

## Getting Started

1. **Set up a model.** In game, press the key that opens the panel (`G` by default; you can change it in the
   controls settings). On the settings page, choose a model provider and enter **your own API key**. Both the
   OpenAI and the Anthropic protocols are supported natively; any compatible provider works by entering its address.
2. **Summon an agent.** Summon an agent from the panel and give it a name.
3. **Start talking.** Tell it what you want done in plain language.

### External Brain

MineAI as a whole can act as an **MCP server** and be driven directly by Claude Code, Codex, Cursor, or any other
MCP client. Reasoning and token usage are then counted against the external agent, so you can drive it with that
client's **subscription plan**, usually cheaper than paying for API calls by usage.

### Controls

| Key | Action |
|---|---|
| `G` | Open the panel |
| `Y` | Type a quick message without opening the panel |
| Hold `R` and scroll the mouse wheel | Choose which agent to talk to when you have several |
| Hold `V` | Voice input; release to send. It does not block WASD movement |

## Built-in Integrations

These integrations load automatically when the target mod is present, all exposed to the model as upstream-style
**Lua API groups** (in a script, `group.fn(...)`):

| Mod | Fabric 1.21.1 | NeoForge 1.21.1 | Lua namespace / capability |
|---|---|---|---|
| **Yes Steve Model (YSM)** | Supported | Supported | Model integration |
| **Applied Energistics 2** | Not integrated | Supported | `ae2.network` / `ae2.config` / `ae2.pattern` / `ae2.craft`: read an ME network (channels / controller / energy / devices); read and write server-side settings of machines and cable parts; encode patterns in the pattern encoding terminal; request autocrafting and query the job |
| **Mekanism** | Not integrated | Supported (read-only) | `mekanism.machine.inspect`: read machine state, per-side transmission config, chemical tanks and heat; cannot modify machine side configuration |
| **JEI** | Not integrated | Supported | `jei.recipe`: recipe lookup for an item (made by / used in / catalysts) |
| **Touhou Little Maid, Curios, FTB Quests, Kaleidoscope** | Not integrated | Supported | Other integrations bundled in the NeoForge release jar |

This matrix describes integrations wired into this repository, not the loaders supported by the target mods themselves. Fabric currently has only YSM integration, without AE2, Mekanism, JEI or other technology integrations.

Capabilities that ship with the mod and need no other mod:

- **Self-verification `numen.verify`**: the deterministic checks `have` / `block` / `near` measure a one-line claim
  against the authoritative server state. A long-term goal is checked before it clears; if the claim does not hold,
  the agent continues with the real difference.
- **Offline knowledge base `numen.kb`**: indexes GuideME / Patchouli / FTB quest text; `kb.search` looks it up
  locally for the model to consult the pack's own documentation.

For the latest functions and return values, trust the API index the model actually sees (`numen.api`).

## Architecture

<p align="center">
  <img src="assets/diagrams/numen-architecture.svg" alt="MineAI's layered architecture" width="760">
</p>

The architecture is organized into three layers, from the bottom up. The bottom layer is **Minecraft**, where the
agent controls a real player. The middle layer is the interface opened to the large language model; its main part
is the **Lua API** made of **atomic actions** (perception, movement, digging, building, interaction, which other
mods can plug into), plus a small **Java API** provided as tool calls. The top layer is the **large language
model**, which writes Lua programs that run in a sandbox and freely combine atomic actions, completing dozens of
steps in a single call. See the [upstream website](https://numen.dwinovo.cn) for the full architecture.

## Plugins

Any developer can register new Lua APIs with the agent through a plugin, teaching it to use other mods. The
`plugins/` directory of this repository contains both inherited integrations (Yes Steve Model, Touhou Little Maid,
Curios, FTB Quests, Kaleidoscope, ...) and the technology-mod integrations added here, which can serve as reference
templates for writing your own. See the matrix above for the loader support of the actual release jars.

## Publishing

`Build` runs compilation, data generation and JVM regression tests on pushes and PRs targeting `main` and `1.21.1`. `Publish` is manually dispatched and requires the MC version branch matching `minecraft_version` in `gradle.properties` (`1.21.1` here); publishing from `main` is rejected. The exact commit being published must already have a successful `Build`.

Select `Publish` in Actions, or use GitHub CLI:

```bash
gh workflow run publish.yml --ref 1.21.1 -f channel=beta
```

`channel=beta` creates a prerelease; `channel=release` creates a stable GitHub Release. The pipeline is `datagenAll` → `releaseJars` → save artifacts and tag → GitHub Release. It uses only this repository's built-in `GITHUB_TOKEN` (Actions must be allowed to write contents), requires no additional publishing secrets, and does not publish to Maven, Modrinth or CurseForge.

The sole version source is `version` in `gradle.properties`. Tags are `v<version>-<MC>-beta` or `v<version>-<MC>` for stable releases. Each version/MC pair may be published only once across channels: a beta cannot be followed by a stable release with the same version. If a tag already exists, use a new version for a new release; if the original run failed partway through, select **Re-run failed jobs** on that run.

## FAQ

**Is my API key safe?**
Yes. Reasoning happens on your own client, and the API key is stored only locally and sent directly to the model
provider you chose. It never passes through any third-party server.

**Does it work on multiplayer servers?**
Yes. Install it on both the server and each client; each player uses their own API key and drives their own agents.

**Does it cost money?**
MineAI itself is open source and free; calls to the large language model depend on the model provider you choose.

**How is it different from upstream Numen?**
MineAI tracks the upstream architecture and mainly adds technology-modpack integrations, self-verification and an
offline knowledge base on top of it. For general capabilities, upstream is the reference.

## License

The source code is released under [LGPL-3.0](LICENSE); plugins that use the project through its API are not bound
by it. Screenshots, the demo animation and diagrams are released under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), and all other art is reserved. See
[licenses/ASSETS.txt](licenses/ASSETS.txt) for details.

## Acknowledgements

This project is forked from [Numen](https://github.com/Dwinovo/minecraft-numen) (by dwinovo and its
[contributors](https://github.com/Dwinovo/minecraft-numen/graphs/contributors)), to whom we are grateful. For the
open-source projects and research it builds on, see the upstream website.
