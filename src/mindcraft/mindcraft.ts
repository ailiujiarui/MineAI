// @ts-nocheck
import { closeMindServer, createMindServer, getAgentAuthToken, numStateListeners, registerAgent, rotateAgentAuthToken, waitForMindServerListening } from './mindserver.js';
import { AgentProcess } from '../process/agent_process.js';
import { getServer } from './mcserver.js';
import open from 'open';
import { buildVoiceMicChildArgs } from '../voice/micLauncherConfig.js';
import { createVoiceMicProcess } from '../process/voiceMicProcess.js';
import { initRunContext } from '../utils/runContext.js';

let mindserver;
let connected = false;
let agent_processes = {};
let agent_count = 0;
let mindserver_port = 8080;
const voice_mic_processes = {};
let shutting_down = false;
let run_root = null;

export async function init(host_public=false, port=8080, auto_open_ui=true) {
    if (connected) {
        console.error('Already initiliazed!');
        return;
    }
    mindserver = createMindServer(host_public, port);
    run_root = initRunContext();
    mindserver_port = port;
    await waitForMindServerListening(mindserver);
    connected = true;
    if (auto_open_ui) {
        setTimeout(() => {
            // check if browser listener is already open
            if (numStateListeners() === 0) {
                open('http://localhost:'+port);
            }
        }, 3000);
    }
}

export async function createAgent(settings) {
    if (!settings.profile.name) {
        console.error('Agent name is required in profile');
        return {
            success: false,
            error: 'Agent name is required in profile'
        };
    }
    settings = JSON.parse(JSON.stringify(settings));
    let agent_name = settings.profile.name;
    const agentIndex = agent_count++;
    const viewer_port = 3000 + agentIndex;
    registerAgent(settings, viewer_port);
    let load_memory = settings.load_memory || false;
    let init_message = settings.init_message || null;

    try {
        try {
            const server = await getServer(settings.host, settings.port, settings.minecraft_version);
            settings.host = server.host;
            settings.port = server.port;
            settings.minecraft_version = server.version;
        } catch (error) {
            console.warn(`Error getting server:`, error);
            if (settings.minecraft_version === "auto") {
                settings.minecraft_version = null;
            }
            console.warn(`Attempting to connect anyway...`);
        }

    const agentProcess = new AgentProcess(agent_name, mindserver_port, {
        authTokenProvider: () => rotateAgentAuthToken(agent_name),
        runRoot: run_root || initRunContext()
    });
    agentProcess.start(load_memory, init_message, agentIndex);
    agent_processes[settings.profile.name] = agentProcess;
    const mic = settings.voice?.mic || {};
    const micTarget = mic.target_agent || mic.targetAgent || '';
    const ownsPhysicalMic = micTarget ? micTarget === agent_name : agentIndex === 0;
    if (settings.voice?.enabled && mic.enabled && ownsPhysicalMic) {
        const childArgs = buildVoiceMicChildArgs({
            scriptPath: 'scripts/voice-mic-listener.py',
            agent: agent_name,
            settingsPort: mindserver_port,
            micSettings: mic,
            doubaoRealtimeSettings: settings.voice?.doubao?.realtime || {}
        });
        const voiceMic = createVoiceMicProcess({
            agent: agent_name,
            pythonCommand: mic.python_command || mic.pythonCommand,
            childArgs,
            authTokenProvider: () => getAgentAuthToken(agent_name),
            onState: (state) => console.log('[voice-mic]', JSON.stringify(state))
        });
        voice_mic_processes[agent_name] = voiceMic;
        voiceMic.start();
    }
    } catch (error) {
        console.error(`Error creating agent ${agent_name}:`, error);
        destroyAgent(agent_name);
        return {
            success: false,
            error: error.message
        };
    }
    return {
        success: true,
        error: null
    };
}

export function getAgentProcess(agentName) {
    return agent_processes[agentName];
}

export function startAgent(agentName) {
    if (agent_processes[agentName]) {
        agent_processes[agentName].forceRestart();
    }
    else {
        console.error(`Cannot start agent ${agentName}; not found`);
    }
}

export function stopAgent(agentName) {
    voice_mic_processes[agentName]?.stop('agent-stop');
    if (agent_processes[agentName]) {
        agent_processes[agentName].stop();
    }
}

export function destroyAgent(agentName) {
    voice_mic_processes[agentName]?.stop('agent-destroy');
    delete voice_mic_processes[agentName];
    if (agent_processes[agentName]) {
        agent_processes[agentName].stop();
        delete agent_processes[agentName];
    }
}

export function setAgentRuntimeReady(agentName, ready) {
    const mic = voice_mic_processes[agentName];
    if (mic) mic.setReady(ready);
}

export async function shutdown() {
    if (shutting_down) return;
    shutting_down = true;
    console.log('Shutting down');
    await Promise.all(Object.values(voice_mic_processes).map((process) => process.shutdown()));
    const exits = Object.values(agent_processes).map((process) => {
        process.stop();
        return process.waitForExit(2000);
    });
    await Promise.all(exits);
    await closeMindServer();
}

