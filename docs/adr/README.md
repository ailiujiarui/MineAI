# 架构决策记录(ADR)

每条记录写一个决定:当时的背景、定了什么、带来什么后果。格式照 Michael Nygard 的
[Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)(2011)。

- 按领域分目录,每个目录各自编号,编号不复用。跨目录引用写目录名加编号,如"body 0001"。
- 记录定下以后不改内容;决定变了就写一条新的,旧的状态改成"被 NNNN 取代"或"被 NNNN 修订",两条互相链接。
- 状态:已接受、被取代、被修订;定了还没做的写"已接受,未落地"。
- 现状与细节看各自的设计稿(`docs/pathing.md`、`docs/shell.md`、`docs/input-pipeline.md` 等);ADR 只记为什么这样定。

| 领域 | 目录 | 一句话 |
|---|---|---|
| 身体 | [body/](body/README.md) | 她为什么是一个服务端玩家,怎么让她动 |
| 操控 | [control/](control/README.md) | AI 怎么操控她:从注册工具、命令行到 Lua |
| 寻路 | [pathing/](pathing/README.md) | 从第一版 A* 到 0.1.5 |
| 模型接入 | [llm/](llm/README.md) | 怎么接各家模型:零依赖自写、线格式适配、三种协议、思考方言、档案 |
