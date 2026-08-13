// @ts-nocheck
import { History } from './history.js';
import { Coder } from './coder.js';
import { Prompter } from '../models/prompter.js';
import { initModes } from './modes.js';
import { initBot } from '../utils/mcdata.js';
import { containsCommand, commandExists, executeCommandWithOutcome, truncCommandMessage, isAction, blacklistCommands } from './commands/index.js';
import { ActionManager } from './action_manager.js';
import { AutonomyController } from '../autonomy/controller.js';
import { NPCContoller } from './npc/controller.js';
import { MemoryBank } from './memory_bank.js';
import { SelfPrompter } from './self_prompter.js';
import { CompanionRuntime } from '../companion/companionRuntime.js';
import { createEmbodimentRuntime } from '../embodiment/embodimentRuntime.js';
import { applyVoiceIntent } from '../voice/agentVoiceBridge.js';
import { createTtsAdapter } from '../voice/providers/index.js';
import { createVoiceReplyTracker, shouldSpeakVoiceReply } from '../voice/replySpeakPolicy.js';
import { routeVoiceTranscript } from '../voice/intentRouter.js';
import { VoiceRuntime } from '../voice/voiceRuntime.js';
import convoManager from './conversation.js';
import { handleTranslation, handleEnglishTranslation } from '../utils/translator.js';
import { buildRuntimeMessage, buildSpawnGreeting } from '../locale/chinese.js';
import { serverProxy, sendOutputToServer } from './mindserver_proxy.js';
import settings from './settings.js';
import { Task } from './tasks/tasks.js';
import { speak } from './speak.js';
import { log, validateNameFormat, handleDisconnection } from './connection_handler.js';
import { recoverAgentFromDeath } from './deathRecovery.js';
import { createDeathDisconnectGuard, shouldHandleRuntimeDisconnect } from './deathDisconnectGuard.js';
import { extractChineseMemories } from './memory/memoryExtractor.js';
import { StateMachineExecutionAdapter } from './execution/stateMachineAdapter.js';

export class Agent {
    async start(load_mem=false, init_message=null, count_id=0, startup_context={}) {
        this.last_sender = null;
        this.count_id = count_id;
        this._disconnectHandled = false;

        // Initialize components
        this.actions = new ActionManager(this);
        this.prompter = new Prompter(this, settings.profile);
        this.name = (this.prompter.getName() || '').trim();
        console.log(`Initializing agent ${this.name}...`);
        
        // Validate Name Format
        // connection_handler now ensures the message has [LoginGuard] prefix
        const nameCheck = validateNameFormat(this.name);
        if (!nameCheck.success) {
            log(this.name, nameCheck.msg);
            process.exit(1);
            return;
        }
        
        this.history = new History(this);
        this.coder = new Coder(this);
        this.npc = new NPCContoller(this);
        this.memory_bank = new MemoryBank();
        this.self_prompter = new SelfPrompter(this);
        this.companion_runtime = new CompanionRuntime({
            mode: settings.companion?.mode || 'task-with-companion-tone',
            language: settings.language || 'en'
        });
        this.embodiment_runtime = createEmbodimentRuntime({
            profile: settings.embodiment?.profile || {
                id: 'default',
                displayName: 'Default',
                modelProvider: 'ysm',
                modelId: 'default/steve'
            }
        });
        this.autonomy = settings.autonomy?.enabled === false
            ? null
            : new AutonomyController(this, {
                intervalMs: settings.autonomy?.interval_ms || 5000,
                onDecision: async (update) => this.handleCompanionTaskUpdate(update),
                onCommandResult: async (update) => this.handleCompanionCommandResult(update)
            });
        this.death_disconnect_guard = createDeathDisconnectGuard();
        this.voice_runtime = new VoiceRuntime({
            enabled: settings.voice?.enabled ?? false,
            commandMode: settings.voice?.command_mode || 'hybrid',
            companionMode: settings.companion?.mode || 'task-with-companion-tone',
            micConfig: settings.voice?.mic || {},
            ttsAdapter: createTtsAdapter(settings.voice || {}),
            onIntent: async (intent, event) => this.handleVoiceIntent(intent, event)
        });
        this.voice_reply_tracker = createVoiceReplyTracker();
        convoManager.initAgent(this);
        await this.prompter.initExamples();

        // load mem first before doing task
        let save_data = null;
        if (load_mem) {
            save_data = this.history.load();
        }
        let taskStart = null;
        if (save_data) {
            taskStart = save_data.taskStart;
        } else {
            taskStart = Date.now();
        }
        this.task = new Task(this, settings.task, taskStart);
        this.blocked_actions = settings.blocked_actions.concat(this.task.blocked_actions || []);
        blacklistCommands(this.blocked_actions);

        console.log(this.name, 'logging into minecraft...');
        this.bot = initBot(this.name);
        this.execution_state_machine = new StateMachineExecutionAdapter(this.bot, {
            enabled: settings.execution?.state_machine?.enabled === true,
            requestInterrupt: () => this.requestInterruptLegacy()
        });
        
        // Connection Handler
        const onDisconnect = (event, reason) => {
            if (this._disconnectHandled) return;
            if (this.death_disconnect_guard?.shouldSuppressDisconnect?.()) {
                log(this.name, `[DeathRecovery] Suppressing ${event} during death recovery window.`);
                return;
            }
            this._disconnectHandled = true;

            // Log and Analyze
            // handleDisconnection handles logging to console and server
            const { type } = handleDisconnection(this.name, reason);
     
            process.exit(1);
        };
        
        // Bind events
        this.bot.once('kicked', (reason) => onDisconnect('Kicked', reason));
        this.bot.once('end', (reason) => onDisconnect('Disconnected', reason));
        this.bot.on('error', (err) => {
            if (String(err).includes('Duplicate') || String(err).includes('ECONNREFUSED')) {
                 onDisconnect('Error', err);
            } else {
                 log(this.name, `[LoginGuard] Connection Error: ${String(err)}`);
            }
        });

        initModes(this);

        this.bot.on('login', () => {
            console.log(this.name, 'logged in!');
            serverProxy.login();
            
            // Set skin for profile, requires Fabric Tailor. (https://modrinth.com/mod/fabrictailor)
            if (this.prompter.profile.skin)
                this.bot.chat(`/skin set URL ${this.prompter.profile.skin.model} ${this.prompter.profile.skin.path}`);
            else
                this.bot.chat(`/skin clear`);
        });
		const spawnTimeoutDuration = settings.spawn_timeout;
        const spawnTimeout = setTimeout(() => {
            const msg = `Bot has not spawned after ${spawnTimeoutDuration} seconds. Exiting.`;
            log(this.name, msg);
            process.exit(1);
        }, spawnTimeoutDuration * 1000);
        this.bot.once('spawn', async () => {
            try {
                clearTimeout(spawnTimeout);
                if (settings.render_bot_view) {
                    const { addBrowserViewer } = await import('./vision/browser_viewer.js');
                    addBrowserViewer(this.bot, count_id);
                }
                if (settings.allow_vision) {
                    console.log('Initializing vision intepreter...');
                    const { VisionInterpreter } = await import('./vision/vision_interpreter.js');
                    this.vision_interpreter = new VisionInterpreter(this, settings.allow_vision);
                }

                // wait for a bit so stats are not undefined
                await new Promise((resolve) => setTimeout(resolve, 1000));
                
                console.log(`${this.name} spawned.`);
                this.clearBotLogs();
              
                await this._setupEventHandlers(save_data, init_message, startup_context);
                this.startEvents();
                serverProxy.runtimeReady();
              
                if (!load_mem) {
                    if (settings.task) {
                        this.task.initBotTask();
                        this.task.setAgentGoal();
                    } else if (this.autonomy) {
                        await this.autonomy.tick();
                    }
                } else {
                    // set the goal without initializing the rest of the task
                    if (settings.task) {
                        this.task.setAgentGoal();
                    } else if (this.autonomy) {
                        await this.autonomy.tick();
                    }
                }

                await new Promise((resolve) => setTimeout(resolve, 10000));
                this.checkAllPlayersPresent();

            } catch (error) {
                console.error('Error in spawn event:', error);
                process.exit(0);
            }
        });
    }

    async _setupEventHandlers(save_data, init_message, startup_context={}) {
        const ignore_messages = [
            "Set own game mode to",
            "Set the time to",
            "Set the difficulty to",
            "Teleported ",
            "Set the weather to",
            "Gamerule "
        ];
        
        const respondFunc = async (username, message) => {
            if (message === "") return;
            if (username === this.name) return;
            if (settings.only_chat_with.length > 0 && !settings.only_chat_with.includes(username)) return;
            try {
                if (ignore_messages.some((m) => message.startsWith(m))) return;

                this.shut_up = false;

                console.log(this.name, 'received message from', username, ':', message);

                if (convoManager.isOtherAgent(username)) {
                    console.warn('received whisper from other bot??')
                }
                else {
                    this.handleMessage(username, message);
                }
            } catch (error) {
                console.error('Error handling message:', error);
            }
        }

		this.respondFunc = respondFunc;

        this.bot.on('whisper', respondFunc);
        
        this.bot.on('chat', (username, message) => {
            if (serverProxy.getNumOtherAgents() > 0) return;
            // only respond to open chat messages when there are no other agents
            respondFunc(username, message);
        });

        // Set up auto-eat
        this.bot.autoEat.options = {
            priority: 'foodPoints',
            startAt: 14,
            bannedFood: ["rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken"]
        };

        const restartCause = startup_context.restartCause || null;
        if (restartCause) {
            console.log(`[Lifecycle] Restoring agent after ${restartCause}.`);
            await this.history.add('system', `[Internal lifecycle] Agent restarted: ${restartCause}.`);
        }

        if (save_data?.self_prompt) {
            if (init_message) {
                this.history.add('system', init_message);
            }
            const restoredState = restartCause && (save_data.self_prompting_state === 1 || save_data.self_prompting_state === 3)
                ? 2
                : save_data.self_prompting_state;
            await this.self_prompter.handleLoad(save_data.self_prompt, restoredState);
        }
        if (save_data?.last_sender) {
            this.last_sender = save_data.last_sender;
            if (!restartCause && convoManager.otherAgentInGame(this.last_sender)) {
                const msg_package = {
                    message: `You have restarted and this message is auto-generated. Continue the conversation with me.`,
                    start: true
                };
                convoManager.receiveFromBot(this.last_sender, msg_package);
            }
        }
        else if (init_message) {
            await this.handleMessage('system', init_message, 2);
        }
        else if (!restartCause) {
            this.openChat(buildSpawnGreeting(this.prompter.profile, settings.language));
        }
    }

    checkAllPlayersPresent() {
        if (!this.task || !this.task.agent_names) {
          return;
        }

        const missingPlayers = this.task.agent_names.filter(name => !this.bot.players[name]);
        if (missingPlayers.length > 0) {
            console.log(`Missing players/bots: ${missingPlayers.join(', ')}`);
            this.cleanKill('Not all required players/bots are present in the world. Exiting.', 4);
        }
    }

    requestInterrupt() {
        this.execution_state_machine?.stop?.().catch?.(() => {});
        this.requestInterruptLegacy();
    }

    requestInterruptLegacy() {
        this.bot.interrupt_code = true;
        this.bot.stopDigging();
        this.bot.collectBlock.cancelTask();
        this.bot.pathfinder.stop();
        this.bot.pvp.stop();
    }

    clearBotLogs() {
        this.bot.output = '';
        this.bot.interrupt_code = false;
    }

    shutUp() {
        this.shut_up = true;
        if (this.self_prompter.isActive()) {
            this.self_prompter.stop(false);
        }
        convoManager.endAllConversations();
    }

    async handleVoiceIntent(intent, event) {
        this.voice_reply_tracker?.arm(event);
        console.log('[voice-debug][agent] handleVoiceIntent:', JSON.stringify({
            kind: intent?.kind,
            payload: intent?.payload,
            source: event?.source,
            speakerId: event?.speakerId,
            metadata: event?.metadata || {}
        }));
        try {
            return await applyVoiceIntent(event, intent, {
            onCommand: async (payload) => {
                if (payload.trim() === '!stop') {
                    const localStop = await executeCommandWithOutcome(this, payload, {
                        actor: event.speakerId || 'voice_user',
                        origin: 'user'
                    });
                    if (localStop.outcome !== 'success') {
                        await this.routeResponse(event.speakerId || 'voice_user', localStop.result);
                        return;
                    }
                    if (settings.forge_action?.enabled === false) {
                        await this.routeResponse(event.speakerId || 'voice_user', '已停止当前动作。');
                        return;
                    }
                    const forgeResult = await serverProxy.requestForgeStop({
                        speakerId: event.speakerId || 'voice_user',
                        origin: event.source || 'voice'
                    });
                    const forgeMessage = forgeResult?.status === 'ok'
                        ? 'Forge 客户端已接受停止指令。'
                        : forgeResult?.status === 'timeout'
                            ? 'Forge 停止指令等待确认超时。'
                            : forgeResult?.status === 'rejected'
                                ? 'Forge 停止指令被拒绝。'
                                : forgeResult?.status === 'disconnected'
                                    ? 'Forge 客户端在确认停止前断开连接。'
                                    : '未找到可用的 Forge 客户端。';
                    await this.routeResponse(event.speakerId || 'voice_user', `已停止 Mineflayer；${forgeMessage}`);
                    return;
                }
                await this.handleMessage(event.speakerId || 'voice_user', payload, 1, {
                    skipIntentRouting: true
                });
            },
            onGoal: async (payload) => {
                const escaped = payload.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
                await this.handleMessage(event.speakerId || 'voice_user', `!goal("${escaped}")`, 1);
            },
            onCompanion: async (payload) => {
                if (this.embodiment_runtime) {
                    this.embodiment_runtime.applyCompanionSignal({
                        mood: 'warm',
                        expression: 'happy'
                    });
                }
                await this.handleMessage(event.speakerId || 'voice_user', payload, 1, {
                    skipIntentRouting: true
                });
            },
            onConversation: async (payload) => {
                await this.handleMessage(event.speakerId || 'voice_user', payload, 1);
            }
            });
        } finally {
            // A command-only or empty model response may leave the one-shot voice
            // reply armed. Do not let a later autonomous/system chat consume it.
            this.voice_reply_tracker?.reset?.();
        }
    }

    async handleCompanionTaskUpdate(update) {
        if (this.embodiment_runtime) {
            this.embodiment_runtime.applyAutonomySignal({
                state: 'tasking',
                urgency: 'focused'
            });
        }
        if (!settings.companion?.task_update_chat) {
            return;
        }
        if (this.shut_up) {
            return;
        }
        const message = await this.companion_runtime.describeTaskUpdate({
            stage: update.decision.stage,
            nextCommand: update.nextCommand || `goal:${update.decision.stage}`
        });
        if (message) {
            await this.openChat(message);
        }
    }

    async handleCompanionCommandResult(update) {
        if (this.embodiment_runtime) {
            this.embodiment_runtime.applyAutonomySignal({
                state: 'tasking',
                urgency: 'concerned'
            });
        }
        if (!settings.companion?.task_update_chat) {
            return;
        }

        const resultText = typeof update.result === 'string' ? update.result : '';
        if (!resultText || !/failed|error|could not|cannot|unable/i.test(resultText)) {
            return;
        }

        const message = await this.companion_runtime.describeTaskFailure({
            stage: update.decision.stage,
            failedCommand: update.nextCommand,
            reason: resultText
        });

        if (message) {
            await this.openChat(message);
        }
    }

    async handleVoiceTranscript(data) {
        if (!this.voice_runtime) {
            return null;
        }

        const transcriptEvent = {
            text: data.text || '',
            source: data.source || 'asr',
            speakerId: data.speakerId || data.from || 'voice_user',
            timestamp: data.timestamp || Date.now(),
            metadata: data.metadata || {}
        };
        console.log('[voice-debug][agent] handleVoiceTranscript input:', JSON.stringify(transcriptEvent));

        const intent = await this.voice_runtime.handleTranscript(transcriptEvent);
        console.log('[voice-debug][agent] handleVoiceTranscript result:', JSON.stringify(intent));
        return intent;
    }

    async maybeHandleInstructionIntent(source, message) {
        const intent = await routeVoiceTranscript(
            {
                text: message,
                source: 'chat',
                speakerId: source
            },
            {
                commandMode: settings.voice?.command_mode || 'hybrid'
                ,companionMode: settings.companion?.mode || 'task-with-companion-tone'
            }
        );

        if (!intent || intent.kind === 'conversation') {
            return false;
        }

        await this.handleVoiceIntent(intent, {
            speakerId: source,
            source: 'chat',
            text: message,
            timestamp: Date.now(),
            metadata: {
                bridgedFrom: 'chat'
            }
        });

        return true;
    }

    async handleMessage(source, message, max_responses=null, options={}) {
        await this.checkTaskDone();
        if (!source || !message) {
            console.warn('Received empty message from', source);
            return false;
        }

        let used_command = false;
        if (max_responses === null) {
            max_responses = settings.max_commands === -1 ? Infinity : settings.max_commands;
        }
        if (max_responses === -1) {
            max_responses = Infinity;
        }

        const self_prompt = source === 'system' || source === this.name;
        const from_other_bot = convoManager.isOtherAgent(source);

        if (!self_prompt && !from_other_bot) { // from user, check for forced commands
            const user_command_name = containsCommand(message);
            if (user_command_name) {
                if (user_command_name !== '!confirm' && user_command_name !== '!cancelConfirm') {
                    this.self_prompter.handlePlayerInstruction(source);
                }
                if (!commandExists(user_command_name)) {
                    this.routeResponse(source, `Command '${user_command_name}' does not exist.`);
                    return false;
                }
                this.routeResponse(source, `*${source} used ${user_command_name.substring(1)}*`);
                if (user_command_name === '!newAction') {
                    // all user-initiated commands are ignored by the bot except for this one
                    // add the preceding message to the history to give context for newAction
                    this.history.add(source, message);
                }
                const command_outcome = await executeCommandWithOutcome(this, message, { actor: source, origin: 'user' });
                if ((user_command_name === '!confirm' || user_command_name === '!cancelConfirm')
                    && command_outcome.outcome === 'success') {
                    this.self_prompter.handlePlayerInstruction(source);
                }
                if (!this.self_prompter.isStopped()) {
                    this.self_prompter.recordCommandOutcome(command_outcome);
                }
                if (command_outcome.result)
                    this.routeResponse(source, command_outcome.result);
                return true;
            }
            this.self_prompter.handlePlayerInstruction(source);
        }

        if (from_other_bot)
            this.last_sender = source;

        if (!self_prompt && !from_other_bot) {
            this.history.structured.rememberEvent(message, source);
            for (const extracted of extractChineseMemories(message)) {
                if (extracted.kind === 'preference') {
                    this.history.structured.addPreference(source, extracted.value);
                } else if (extracted.key) {
                    this.history.structured.rememberFact(`${source}:${extracted.key}`, extracted.value, 'user-stated');
                }
            }
        }

        if (!self_prompt && !from_other_bot && !options.skipIntentRouting) {
            // Route the original text first so Chinese commands are not lost in translation.
            const routed = await this.maybeHandleInstructionIntent(source, message);
            if (routed) {
                return true;
            }
        }

        // Conversational prompts still use the model's established English context.
        if (!self_prompt) {
            message = await handleEnglishTranslation(message);
        }
        console.log('received message from', source, ':', message);

        const checkInterrupt = () => this.self_prompter.shouldInterrupt(self_prompt) || this.shut_up || convoManager.responseScheduledFor(source);
        
        let behavior_log = this.bot.modes.flushBehaviorLog().trim();
        if (behavior_log.length > 0) {
            const MAX_LOG = 500;
            if (behavior_log.length > MAX_LOG) {
                behavior_log = '...' + behavior_log.substring(behavior_log.length - MAX_LOG);
            }
            behavior_log = 'Recent behaviors log: \n' + behavior_log;
            await this.history.add('system', behavior_log);
        }

        // Handle other user messages
        await this.history.add(source, message);
        this.history.save();

        if (!self_prompt && this.self_prompter.isActive()) // message is from user during self-prompting
            max_responses = 1; // force only respond to this message, then let self-prompting take over
        for (let i=0; i<max_responses; i++) {
            if (checkInterrupt()) break;
            let history = this.history.getHistory();
            let res = await this.prompter.promptConvo(history);

            console.log(`${this.name} full response to ${source}: ""${res}""`);

            if (res.trim().length === 0) {
                console.warn('no response')
                break; // empty response ends loop
            }

            let command_name = containsCommand(res);

            if (command_name) { // contains query or command
                res = truncCommandMessage(res); // everything after the command is ignored
                this.history.add(this.name, res);
                
                if (!commandExists(command_name)) {
                    this.history.add('system', `Command ${command_name} does not exist.`);
                    console.warn('Agent hallucinated command:', command_name)
                    if (self_prompt) {
                        this.self_prompter.recordCommandOutcome({
                            outcome: 'invalid',
                            commandName: command_name,
                            args: []
                        });
                    }
                    continue;
                }

                if (checkInterrupt()) break;
                this.self_prompter.handleUserPromptedCmd(self_prompt, isAction(command_name));

                if (settings.show_command_syntax === "full") {
                    this.routeResponse(source, res);
                }
                else if (settings.show_command_syntax === "shortened") {
                    // show only "used !commandname"
                    let pre_message = res.substring(0, res.indexOf(command_name)).trim();
                    let chat_message = `*used ${command_name.substring(1)}*`;
                    if (pre_message.length > 0)
                        chat_message = `${pre_message}  ${chat_message}`;
                    this.routeResponse(source, chat_message);
                }
                else {
                    // no command at all
                    let pre_message = res.substring(0, res.indexOf(command_name)).trim();
                    if (pre_message.trim().length > 0)
                        this.routeResponse(source, pre_message);
                }

                const command_outcome = await executeCommandWithOutcome(this, res, {
                    actor: self_prompt ? this.name : source,
                    origin: self_prompt ? 'system' : (from_other_bot ? 'internal' : 'model')
                });

                console.log('Agent executed:', command_name, 'and got:', command_outcome);
                used_command = true;

                if (self_prompt || !this.self_prompter.isStopped()) {
                    this.self_prompter.recordCommandOutcome(command_outcome);
                }

                if (command_outcome.result)
                    this.history.add('system', command_outcome.result);
                else
                    break;
                if (command_outcome.outcome === 'confirmation-required'
                    || command_outcome.outcome === 'player-action-required') {
                    if (command_outcome.result) {
                        await this.routeResponse(source, command_outcome.result);
                    }
                    break;
                }
            }
            else { // conversation response
                this.history.add(this.name, res);
                this.routeResponse(source, res);
                break;
            }
            
            this.history.save();
        }

        return used_command;
    }

    async routeResponse(to_player, message) {
        if (this.shut_up) return;
        let self_prompt = to_player === 'system' || to_player === this.name;
        if (self_prompt && this.last_sender) {
            // this is for when the agent is prompted by system while still in conversation
            // so it can respond to events like death but be routed back to the last sender
            to_player = this.last_sender;
        }

        if (convoManager.isOtherAgent(to_player) && convoManager.inConversation(to_player)) {
            // if we're in an ongoing conversation with the other bot, send the response to it
            convoManager.sendToBot(to_player, message);
        }
        else {
            // otherwise, use open chat
            this.openChat(message);
            // note that to_player could be another bot, but if we get here the conversation has ended
        }
    }

    async openChat(message) {
        let to_translate = message;
        let remaining = '';
        let command_name = containsCommand(message);
        let translate_up_to = command_name ? message.indexOf(command_name) : -1;
        if (translate_up_to != -1) { // don't translate the command
            to_translate = to_translate.substring(0, translate_up_to);
            remaining = message.substring(translate_up_to);
        }
        const translatedText = (await handleTranslation(to_translate)).trim();
        message = translatedText + " " + remaining;
        // newlines are interpreted as separate chats, which triggers spam filters. replace them with spaces
        message = message.replaceAll('\n', ' ');
        const spokenText = translatedText;

        if (settings.only_chat_with.length > 0) {
            for (let username of settings.only_chat_with) {
                this.bot.whisper(username, message);
            }
        }
        else {
            if (settings.speak) {
                speak(to_translate, this.prompter.profile.speak_model);
            }
            const allowVoiceReply = shouldSpeakVoiceReply(
                this.voice_reply_tracker,
                settings.voice?.reply_speak_mode || 'always',
                spokenText
            );
            console.log('[voice-debug][agent] openChat speak decision:', JSON.stringify({
                original: to_translate,
                spokenText,
                allowVoiceReply,
                replySpeakMode: settings.voice?.reply_speak_mode || 'always'
            }));
            if (this.voice_runtime && settings.voice?.enabled && allowVoiceReply) {
                if (this.embodiment_runtime) {
                    this.embodiment_runtime.applyVoiceSignal({
                        speaking: true,
                        style: settings.voice?.provider || 'local'
                    });
                }
                this.voice_runtime.speak({
                    text: spokenText,
                    channel: 'chat',
                    metadata: {
                        speaker: this.name,
                        voiceProfile: this.embodiment_runtime?.getState?.()?.profile?.id || undefined
                    }
                }).catch((error) => {
                    console.error('Voice runtime speak failed:', error);
                }).finally(() => {
                    if (this.embodiment_runtime) {
                        this.embodiment_runtime.clearTransientSignals();
                    }
                });
            }
            if (settings.chat_ingame) {this.bot.chat(message);}
            sendOutputToServer(this.name, message);
        }
    }

    startEvents() {
        // Custom events
        this.bot.on('time', () => {
            if (this.bot.time.timeOfDay == 0)
            this.bot.emit('sunrise');
            else if (this.bot.time.timeOfDay == 6000)
            this.bot.emit('noon');
            else if (this.bot.time.timeOfDay == 12000)
            this.bot.emit('sunset');
            else if (this.bot.time.timeOfDay == 18000)
            this.bot.emit('midnight');
        });

        let prev_health = this.bot.health;
        this.bot.lastDamageTime = 0;
        this.bot.lastDamageTaken = 0;
        this.bot.on('health', () => {
            if (this.bot.health < prev_health) {
                this.bot.lastDamageTime = Date.now();
                this.bot.lastDamageTaken = prev_health - this.bot.health;
            }
            prev_health = this.bot.health;
        });
        // Logging callbacks
        this.bot.on('error' , (err) => {
            console.error('Error event!', err);
        });
        // Use connection handler for runtime disconnects
        this.bot.on('end', (reason) => {
            if (!shouldHandleRuntimeDisconnect(this)) {
                if (this.death_disconnect_guard?.shouldSuppressDisconnect?.()) {
                    log(this.name, '[DeathRecovery] Ignoring runtime end event during death recovery window.');
                }
                return;
            }
            this._disconnectHandled = true;
            const { msg } = handleDisconnection(this.name, reason);
            this.cleanKill(msg);
        });
        this.bot.on('death', async () => {
            await recoverAgentFromDeath(this);
        });
        this.bot.on('kicked', (reason) => {
            if (!shouldHandleRuntimeDisconnect(this)) {
                if (this.death_disconnect_guard?.shouldSuppressDisconnect?.()) {
                    log(this.name, '[DeathRecovery] Ignoring runtime kicked event during death recovery window.');
                }
                return;
            }
            this._disconnectHandled = true;
            const { msg } = handleDisconnection(this.name, reason);
            this.cleanKill(msg);
        });
        this.bot.on('messagestr', async (message, _, jsonMsg) => {
            if (jsonMsg.translate && jsonMsg.translate.startsWith('death') && message.startsWith(this.name)) {
                console.log('Agent died: ', message);
                let death_pos = this.bot.entity.position;
                this.memory_bank.rememberPlace('last_death_position', death_pos.x, death_pos.y, death_pos.z);
                let death_pos_text = null;
                if (death_pos) {
                    death_pos_text = `x: ${death_pos.x.toFixed(2)}, y: ${death_pos.y.toFixed(2)}, z: ${death_pos.x.toFixed(2)}`;
                }
                let dimention = this.bot.game.dimension;
                this.handleMessage('system', `You died at position ${death_pos_text || "unknown"} in the ${dimention} dimension with the final message: '${message}'. Your place of death is saved as 'last_death_position' if you want to return. Previous actions were stopped and you have respawned.`);
            }
        });
        this.bot.on('idle', () => {
            this.bot.clearControlStates();
            this.bot.pathfinder.stop(); // clear any lingering pathfinder
            this.bot.modes.unPauseAll();
            setTimeout(() => {
                if (this.isIdle()) {
                    this.actions.resumeAction();
                }
            }, 1000);
        });

        // Init NPC controller
        this.npc.init();

        // This update loop ensures that each update() is called one at a time, even if it takes longer than the interval
        const INTERVAL = 300;
        let last = Date.now();
        setTimeout(async () => {
            while (true) {
                let start = Date.now();
                await this.update(start - last);
                let remaining = INTERVAL - (Date.now() - start);
                if (remaining > 0) {
                    await new Promise((resolve) => setTimeout(resolve, remaining));
                }
                last = start;
            }
        }, INTERVAL);

        this.bot.emit('idle');
    }

    async update(delta) {
        await this.bot.modes.update();
        this.self_prompter.update(delta);
        if (this.autonomy) {
            await this.autonomy.update(delta);
        }
        await this.checkTaskDone();
    }

    isIdle() {
        return !this.actions.executing;
    }
    

    cleanKill(msg='Killing agent process...', code=1) {
        this.history.add('system', msg);
        this.bot.chat(buildRuntimeMessage(
            code === 0 ? 'exiting' : 'reconnecting',
            this.prompter?.profile,
            settings.language
        ));
        this.history.save();
        process.exit(code);
    }
    async checkTaskDone() {
        if (this.task.data) {
            let res = this.task.isDone();
            if (res) {
                await this.history.add('system', `Task ended with score : ${res.score}`);
                await this.history.save();
                // await new Promise(resolve => setTimeout(resolve, 3000)); // Wait 3 second for save to complete
                console.log('Task finished:', res.message);
                this.killAll();
            }
        }
    }

    killAll() {
        serverProxy.shutdown();
    }
}
