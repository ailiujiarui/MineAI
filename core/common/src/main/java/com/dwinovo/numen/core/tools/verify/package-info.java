/**
 * 确定性自验证:{@code verify} 工具({@link com.dwinovo.numen.core.tools.verify.VerifyTool})把模型的宣称
 * 拿去和权威状态对照——背包数、某格方块、半径内的方块、机器配置——回一句 {@code verified} 判词。
 * 便宜、确定、不经过模型。读路径复用既有设施:{@code near} 走 {@code BlockScanner} 的同步小盒,
 * {@code machine} 复用 {@code MachineConfigOps} 的读取器。四连问见 {@link com.dwinovo.numen.core.tools} 包说明。
 */
package com.dwinovo.numen.core.tools.verify;
