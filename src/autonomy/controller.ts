// @ts-nocheck
import { executeCommand, commandExists } from '../agent/commands/index.js';
import * as world from '../agent/library/world.js';
import * as mc from '../utils/mcdata.js';
import { createBlockLookup, resourceLocationKey, selectBestMiningTarget } from './baritoneMining.js';
import { planAutonomyCommand } from './commandPlanner.js';
import { decideAutonomyStage } from './progression.js';
import { decideRecoveryAction } from './recovery.js';
import { planMaidCommand } from '../maid/maidCommandPlanner.js';
import { AgentActionLoop } from '../agent/execution/agentActionLoop.js';
import settings from '../agent/settings.js';

const RESOURCE_BLOCKS = [
    'oak_log',
    'birch_log',
    'spruce_log',
    'jungle_log',
    'acacia_log',
    'dark_oak_log',
    'mangrove_log',
    'cherry_log',
    'stone',
    'coal_ore',
    'iron_ore',
    'deepslate_iron_ore'
];

const HARVESTABLE_CROPS = ['wheat', 'carrots', 'potatoes', 'beetroots', 'nether_wart'];
const MAX_CROP_AGE = {
    wheat: 7,
    carrots: 7,
    potatoes: 7,
    beetroots: 3,
    nether_wart: 3,
    cocoa: 2
};

function getBlockAge(block) {
    if (!block) {
        return null;
    }
    if (typeof block.metadata === 'number') {
        return block.metadata;
    }
    if (typeof block._properties?.age === 'number') {
        return block._properties.age;
    }
    if (typeof block.getProperties === 'function') {
        const properties = block.getProperties();
        if (typeof properties?.age === 'number') {
            return properties.age;
        }
    }
    return null;
}

function scanCropContext(bot) {
    try {
        const cropPositions = bot.findBlocks({
            matching: (block) => block && HARVESTABLE_CROPS.includes(block.name),
            maxDistance: 16,
            count: 64
        }) || [];

        let readyCrops = 0;
        let cropTarget = null;

        for (const pos of cropPositions) {
            const block = bot.blockAt(pos);
            if (!block) {
                continue;
            }
            const age = getBlockAge(block);
            const maxAge = MAX_CROP_AGE[block.name];
            if (typeof age === 'number' && typeof maxAge === 'number' && age >= maxAge) {
                readyCrops += 1;
                cropTarget = cropTarget || block.name;
            }
        }

        const farmlandPositions = bot.findBlocks({
            matching: (block) => block && block.name === 'farmland',
            maxDistance: 16,
            count: 64
        }) || [];

        let emptyFarmland = 0;
        let replantTarget = null;
        for (const pos of farmlandPositions) {
            const above = bot.blockAt(pos.offset(0, 1, 0));
            if (!above || above.name === 'air') {
                emptyFarmland += 1;
                replantTarget = replantTarget || {
                    x: pos.x,
                    y: pos.y,
                    z: pos.z,
                    seedType: cropTarget === 'beetroots' ? 'beetroot_seeds' : `${cropTarget || 'wheat'}${cropTarget === 'wheat' ? '_seeds' : ''}`
                };
            }
        }

        return { readyCrops, emptyFarmland, cropTarget, replantTarget };
    } catch (error) {
        return {
            readyCrops: 0,
            emptyFarmland: 0,
            cropTarget: null,
            replantTarget: null
        };
    }
}

function buildMaidSnapshot(bot, inventoryCounts, hostileCount) {
    const players = world.getNearbyPlayers(bot, 32) || [];
    const nearestPlayer = players[0] || null;
    const usedSlots = (bot.inventory?.items?.() || []).length;
    const totalSlots = (bot.inventory?.slots?.length || 36) || 36;
    const inventoryUtilization = totalSlots > 0 ? usedSlots / totalSlots : 0;
    const cropContext = scanCropContext(bot);
    const storeItemName = inventoryCounts.wheat ? 'wheat'
        : inventoryCounts.carrots ? 'carrots'
        : inventoryCounts.potatoes ? 'potatoes'
        : inventoryCounts.beetroots ? 'beetroots'
        : null;

    return {
        modes: ['harvest', 'replant', 'store-items', 'follow', 'patrol', 'defend'],
        nearestPlayerName: nearestPlayer?.username || null,
        cropTarget: cropContext.cropTarget,
        replantTarget: cropContext.replantTarget,
        storeItemName,
        snapshot: {
            readyCrops: cropContext.readyCrops,
            emptyFarmland: cropContext.emptyFarmland,
            hostileCount,
            inventoryUtilization,
            playerDistance: nearestPlayer
                ? Math.round(nearestPlayer.position.distanceTo(bot.entity.position))
                : 0
        }
    };
}

function countExposedFaces(bot, block) {
    const directions = [
        { x: 1, y: 0, z: 0 },
        { x: -1, y: 0, z: 0 },
        { x: 0, y: 1, z: 0 },
        { x: 0, y: -1, z: 0 },
        { x: 0, y: 0, z: 1 },
        { x: 0, y: 0, z: -1 }
    ];

    let exposed = 0;
    for (const dir of directions) {
        const neighbor = bot.blockAt(block.position.offset(dir.x, dir.y, dir.z));
        if (!neighbor || neighbor.name === 'air' || neighbor.name === 'cave_air' || neighbor.name === 'water') {
            exposed += 1;
        }
    }
    return exposed;
}

function buildKnownResourceLocations(bot) {
    try {
        const blocks = world.getNearestBlocks(bot, RESOURCE_BLOCKS, 48, 32) || [];
        return blocks.map((block) => ({
            blockName: block.name,
            x: block.position.x,
            y: block.position.y,
            z: block.position.z,
            distanceSq: bot.entity.position.distanceSquared(block.position),
            exposedFaces: countExposedFaces(bot, block)
        }));
    } catch (error) {
        return [];
    }
}

function buildInventoryCounts(bot) {
    const inventoryCounts = {};
    const items = bot.inventory?.items?.() || [];
    for (const item of items) {
        inventoryCounts[item.name] = (inventoryCounts[item.name] || 0) + item.count;
    }
    return inventoryCounts;
}

export function buildAutonomySnapshot(agent) {
    const inventoryCounts = buildInventoryCounts(agent.bot);
    const nearbyBlocks = [];
    try {
        for (const blockName of RESOURCE_BLOCKS) {
            const block = world.getNearestBlock(agent.bot, blockName, 24);
            if (block) {
                nearbyBlocks.push(blockName);
            }
        }
    } catch (error) {
        // Test doubles and partially initialized bots may not have full mcdata.
    }

    const nearbyEntities = world.getNearbyEntities(agent.bot, 16) || [];
    const hostileCount = nearbyEntities.filter(entity => mc.isHostile(entity)).length;

    return {
        gameMode: agent.bot.game?.gameMode || 'unknown',
        restrictToInventory: agent.bot.restrict_to_inventory === true,
        creativeUnrestricted: agent.bot.game?.gameMode === 'creative' && agent.bot.restrict_to_inventory !== true,
        inventoryCounts,
        nearbyBlocks,
        knownResourceLocations: buildKnownResourceLocations(agent.bot),
        position: {
            x: agent.bot.entity.position.x,
            y: agent.bot.entity.position.y,
            z: agent.bot.entity.position.z
        },
        hunger: agent.bot.food ?? 20,
        health: agent.bot.health ?? 20,
        hostileCount,
        dangerScore: hostileCount * 2 + ((agent.bot.health ?? 20) <= 8 ? 2 : 0) + ((agent.bot.food ?? 20) <= 8 ? 1 : 0),
        maid: buildMaidSnapshot(agent.bot, inventoryCounts, hostileCount)
    };
}

function escapeGoalPrompt(prompt) {
    return prompt.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

export class AutonomyController {
    constructor(agent, deps = {}) {
        this.agent = agent;
        this.executeCommand = deps.executeCommand || executeCommand;
        this.buildSnapshot = deps.buildSnapshot || (() => buildAutonomySnapshot(agent));
        this.onDecision = deps.onDecision || (async () => {});
        this.onCommandResult = deps.onCommandResult || (async () => {});
        this.currentStage = null;
        this.currentGoalPrompt = null;
        this.lastCommand = null;
        this.userMission = null;
        this.resourceBlacklist = new Set();
        this.replanAttempts = 0;
        this.lastFeedbackActionId = null;
        this.intervalMs = deps.intervalMs || 5000;
        this.elapsedMs = 0;
        this.loopActive = false;
    }

    shouldRun() {
        return !this.agent.task?.data;
    }

    setMission(text) {
        this.userMission = text || null;
        this.currentGoalPrompt = null;
    }

    getBestTargetForCommand(nextCommand, snapshot) {
        const match = nextCommand.match(/^!collectBlocks\("([^"]+)"/);
        if (!match) {
            return null;
        }
        const blockName = match[1];
        return selectBestMiningTarget(
            snapshot.position || { x: 0, y: 0, z: 0 },
            snapshot.knownResourceLocations || [],
            createBlockLookup([blockName]),
            this.resourceBlacklist
        );
    }

    noteFailedCommand(nextCommand, snapshot, resultText) {
        if (!resultText || !/could not find|unable to find|failed|unreachable|cannot/i.test(resultText)) {
            return;
        }

        const bestTarget = this.getBestTargetForCommand(nextCommand, snapshot);
        if (bestTarget) {
            this.resourceBlacklist.add(resourceLocationKey(bestTarget));
        }
        this.lastCommand = null;
    }

    applySkillFeedback(nextCommand, snapshot, feedback) {
        if (!feedback || feedback.actionId === this.lastFeedbackActionId) return;
        this.lastFeedbackActionId = feedback.actionId;
        if (feedback.success) {
            this.replanAttempts = 0;
            return;
        }
        const reason = feedback.failureReason;
        if (reason === 'permission_denied' || reason === 'inventory_full') {
            this.lastCommand = nextCommand;
            return;
        }
        if (reason === 'target_missing') {
            this.noteFailedCommand(nextCommand, snapshot, feedback.message);
        }
        if (reason === 'path_failed' || reason === 'execution_error' || reason === 'target_missing') {
            if (this.replanAttempts < 1) {
                this.replanAttempts += 1;
                this.lastCommand = null;
            } else {
                this.lastCommand = nextCommand;
            }
        }
    }

    async tick() {
        const actionLoopEnabled = settings.autonomy?.agent_action_loop?.enabled === true;
        if (this.loopActive) return null;
        if (!this.shouldRun()) {
            return null;
        }

        const snapshot = {
            ...this.buildSnapshot(),
            blacklistedResourceKeys: [...this.resourceBlacklist]
        };
        const decision = decideRecoveryAction(snapshot) || decideAutonomyStage(snapshot);
        this.agent.recordCurriculumStage?.(decision.stage);
        const isIdle = typeof this.agent.isIdle === 'function' ? this.agent.isIdle() : true;
        const maidPlan = decision.stage === 'survival_steady_state' ? planMaidCommand(snapshot) : null;
        const effectiveGoalPrompt = this.userMission
            ? `${decision.goalPrompt} Overall user mission: ${this.userMission}.`
            : decision.goalPrompt;

        if (snapshot.creativeUnrestricted && !this.userMission) {
            this.currentStage = decision.stage;
            this.currentGoalPrompt = effectiveGoalPrompt;
            this.lastCommand = null;
            return decision;
        }

        if (!isIdle) {
            return decision;
        }

        if (actionLoopEnabled) this.loopActive = true;

        if (decision.stage !== this.currentStage || effectiveGoalPrompt !== this.currentGoalPrompt) {
            this.currentStage = decision.stage;
            this.currentGoalPrompt = effectiveGoalPrompt;
            await this.agent.history?.add?.('system', `[autonomy] stage=${decision.stage} goal=${effectiveGoalPrompt}`);
            await this.executeCommand(this.agent, `!goal("${escapeGoalPrompt(effectiveGoalPrompt)}")`);
            this.lastCommand = null;
            await this.onDecision({ decision, nextCommand: null, snapshot, goalChanged: true });
        }

        if (actionLoopEnabled) {
            return this.runBoundedActionLoop(decision, effectiveGoalPrompt);
        }

        const nextCommand = planAutonomyCommand(decision.stage, snapshot) || maidPlan?.command || null;
        if (nextCommand && nextCommand !== this.lastCommand) {
            this.lastCommand = nextCommand;
            const result = await this.executeCommand(this.agent, nextCommand);
            this.noteFailedCommand(nextCommand, snapshot, typeof result === 'string' ? result : '');
            const feedback = this.agent.getSkillFeedback?.(1)?.[0] || null;
            this.applySkillFeedback(nextCommand, snapshot, feedback);
            await this.onDecision({ decision, nextCommand, snapshot, feedback, goalChanged: false, maidDecision: maidPlan?.decision || null });
            await this.onCommandResult({ decision, nextCommand, snapshot, feedback, result });
        }

        return decision;
    }

    async runBoundedActionLoop(decision, effectiveGoalPrompt) {
        if (!this.shouldRun()) return decision;
        const config = settings.autonomy?.agent_action_loop || {};
        const controller = this;
        try {
            const loop = new AgentActionLoop({
                agent: this.agent,
                maxIterations: config.max_iterations || 2,
                observe: () => {
                    const snapshot = {
                        ...controller.buildSnapshot(),
                        blacklistedResourceKeys: [...controller.resourceBlacklist]
                    };
                    return { decision, goal: effectiveGoalPrompt, snapshot };
                },
                plan: ({ observation }) => {
                    const nextCommand = planAutonomyCommand(decision.stage, observation.snapshot)
                        || (decision.stage === 'survival_steady_state' ? planMaidCommand(observation.snapshot)?.command : null)
                        || null;
                    return nextCommand ? { command: nextCommand } : { done: true };
                },
                execute: async (nextCommand, context) => {
                    const snapshot = context.observation.snapshot;
                    if (nextCommand === controller.lastCommand) return { outcome: 'success', result: 'duplicate-skip' };
                    controller.lastCommand = nextCommand;
                    const result = await controller.executeCommand(controller.agent, nextCommand);
                    controller.noteFailedCommand(nextCommand, snapshot, typeof result === 'string' ? result : '');
                    const feedback = controller.agent.getSkillFeedback?.(1)?.[0] || null;
                    controller.applySkillFeedback(nextCommand, snapshot, feedback);
                    await controller.onDecision({ decision, nextCommand, snapshot, feedback, goalChanged: false });
                    await controller.onCommandResult({ decision, nextCommand, snapshot, feedback, result });
                    const outcome = feedback?.failureReason === 'permission_denied'
                        ? 'denied'
                        : feedback?.failureReason === 'inventory_full'
                            ? 'failed'
                            : feedback?.success === false ? 'failed' : 'success';
                    return { outcome, result, feedback };
                },
                getSkillFeedback: (limit) => this.agent.getSkillFeedback?.(limit) || [],
                executionContext: { origin: 'internal' },
                validateCommand: (command) => {
                    const name = command.match(/^!(\w+)/)?.[0];
                    return name && commandExists(name) ? null : '命令不存在或格式无效。';
                }
            });
            await loop.run();
            return decision;
        } catch (error) {
            console.error('[autonomy] bounded action loop failed:', error);
            return decision;
        } finally {
            this.loopActive = false;
        }
    }

    async update(delta) {
        if (!this.shouldRun()) {
            return null;
        }

        this.elapsedMs += delta;
        if (this.elapsedMs < this.intervalMs) {
            return null;
        }

        this.elapsedMs = 0;
        return this.tick();
    }
}
