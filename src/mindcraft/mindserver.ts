// @ts-nocheck
import { Server } from 'socket.io';
import express from 'express';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import * as mindcraft from './mindcraft.js';
import { readFileSync } from 'fs';
import { randomUUID } from 'crypto';
import { requestBridgeStopAll } from '../clientBridge/bridgeControlClient.js';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Mindserver is:
// - central hub for communication between all agent processes
// - api to control from other languages and remote users 
// - host for webapp

let io;
let server;
const agent_connections = {};
const agent_listeners = [];
const forge_stop_inflight = new Map();
const forge_stop_last_requested_at = new Map();
const FORGE_STOP_AGENT_COOLDOWN_MS = 250;

const settings_spec = JSON.parse(readFileSync(path.join(__dirname, 'public/settings_spec.json'), 'utf8'));

class AgentConnection {
    constructor(settings, viewer_port, auth_token) {
        this.socket = null;
        this.microphone_socket = null;
        this.settings = settings;
        this.in_game = false;
        this.runtime_ready = false;
        this.full_state = null;
        this.viewer_port = viewer_port;
        this.auth_token = auth_token;
    }
    setSettings(settings) {
        this.settings = settings;
    }
}

export function registerAgent(settings, viewer_port) {
    const authToken = randomUUID();
    let agentConnection = new AgentConnection(settings, viewer_port, authToken);
    agent_connections[settings.profile.name] = agentConnection;
    return authToken;
}

export function rotateAgentAuthToken(agentName) {
    const connection = agent_connections[agentName];
    if (!connection) throw new Error(`Agent '${agentName}' is not registered`);
    connection.auth_token = randomUUID();
    connection.runtime_ready = false;
    return connection.auth_token;
}

export function getAgentAuthToken(agentName) {
    return agent_connections[agentName]?.auth_token || '';
}

function isAuthorizedAgentSocket(socket, agentName) {
    const connection = agent_connections[agentName];
    return !!connection
        && socket.handshake?.auth?.agentName === agentName
        && socket.handshake?.auth?.token === connection.auth_token;
}

export function logoutAgent(agentName) {
    if (agent_connections[agentName]) {
        agent_connections[agentName].in_game = false;
        agent_connections[agentName].runtime_ready = false;
        mindcraft.setAgentRuntimeReady(agentName, false);
        agentsStatusUpdate();
    }
}

// Initialize the server
export function createMindServer(host_public = false, port = 8080) {
    const app = express();
    server = http.createServer(app);
    io = new Server(server);

    // Serve static files
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    app.use(express.static(path.join(__dirname, 'public')));

    // Socket.io connection handling
    io.on('connection', (socket) => {
        let curAgentName = null;
        console.log('Client connected');

        agentsStatusUpdate(socket);

        socket.on('create-agent', async (settings, callback) => {
            console.log('API create agent...');
            for (let key in settings_spec) {
                if (!(key in settings)) {
                    if (settings_spec[key].required) {
                        callback({ success: false, error: `Setting ${key} is required` });
                        return;
                    }
                    else {
                        settings[key] = settings_spec[key].default;
                    }
                }
            }
            for (let key in settings) {
                if (!(key in settings_spec)) {
                    delete settings[key];
                }
            }
            if (settings.profile?.name) {
                if (settings.profile.name in agent_connections) {
                    callback({ success: false, error: 'Agent already exists' });
                    return;
                }
                let returned = await mindcraft.createAgent(settings);
                callback({ success: returned.success, error: returned.error });
                let name = settings.profile.name;
                if (!returned.success && agent_connections[name]) {
                    mindcraft.destroyAgent(name);
                    delete agent_connections[name];
                }
                agentsStatusUpdate();
            }
            else {
                console.error('Agent name is required in profile');
                callback({ success: false, error: 'Agent name is required in profile' });
            }
        });

        socket.on('get-settings', (agentName, callback) => {
            if (isAuthorizedAgentSocket(socket, agentName)) {
                callback({ settings: agent_connections[agentName].settings });
            } else {
                callback({ error: `Agent '${agentName}' authentication failed.` });
            }
        });

        socket.on('connect-agent-process', (agentName) => {
            if (isAuthorizedAgentSocket(socket, agentName)) {
                agent_connections[agentName].socket = socket;
                agentsStatusUpdate();
            }
        });

        socket.on('register-microphone', (agentName) => {
            const connection = agent_connections[agentName];
            if (!isAuthorizedAgentSocket(socket, agentName)) return;
            connection.microphone_socket = socket;
        });

        socket.on('voice-playback-state', (agentName, state) => {
            const connection = agent_connections[agentName];
            if (!isAuthorizedAgentSocket(socket, agentName) || connection?.socket !== socket) return;
            const active = Boolean(state?.active);
            connection.microphone_socket?.emit('voice-playback-state', {
                active,
                cooldownMs: Number.isFinite(Number(state?.cooldownMs)) ? Number(state.cooldownMs) : 250,
                generation: state?.generation || null
            });
        });

        socket.on('login-agent', (agentName) => {
            if (isAuthorizedAgentSocket(socket, agentName)) {
                agent_connections[agentName].socket = socket;
                agent_connections[agentName].in_game = true;
                agent_connections[agentName].runtime_ready = false;
                curAgentName = agentName;
                agentsStatusUpdate();
            }
            else {
                console.warn(`Agent ${agentName} failed MindServer authentication`);
            }
        });

        socket.on('agent-runtime-ready', (agentName) => {
            const connection = agent_connections[agentName];
            if (!isAuthorizedAgentSocket(socket, agentName) || connection.socket !== socket) return;
            connection.runtime_ready = true;
            mindcraft.setAgentRuntimeReady(agentName, true);
            agentsStatusUpdate();
        });

        socket.on('forge-stop-request', async (request, callback) => {
            const connection = agent_connections[curAgentName];
            if (!connection || connection.socket !== socket || !connection.runtime_ready) {
                callback?.({ status: 'rejected', message: 'Agent is not runtime-ready' });
                return;
            }

            const config = connection.settings?.forge_action || {};
            if (config.enabled === false) {
                callback?.({ status: 'unavailable', message: 'Forge action channel is disabled' });
                return;
            }

            if (forge_stop_inflight.has(curAgentName)) {
                callback?.(await forge_stop_inflight.get(curAgentName));
                return;
            }

            const sinceLastRequest = Date.now() - (forge_stop_last_requested_at.get(curAgentName) || 0);
            if (sinceLastRequest < FORGE_STOP_AGENT_COOLDOWN_MS) {
                callback?.({
                    status: 'rejected',
                    message: `Agent Forge stop rate limit active; retry after ${FORGE_STOP_AGENT_COOLDOWN_MS - sinceLastRequest}ms`
                });
                return;
            }

            const requestId = `${curAgentName}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
            const configuredAckTimeout = Number(config.ack_timeout_ms || config.ackTimeoutMs || 3000);
            const ackTimeoutMs = Math.min(Math.max(Number.isFinite(configuredAckTimeout) ? configuredAckTimeout : 3000, 100), 3000);
            const pending = requestBridgeStopAll({
                host: config.host || '127.0.0.1',
                port: config.port || 18765,
                requestId,
                clientId: config.client_id || config.clientId || undefined,
                targetPlayerName: config.target_player_name || config.targetPlayerName || undefined,
                snapshotFreshnessMs: config.snapshot_freshness_ms || config.snapshotFreshnessMs || 2000,
                ackTimeoutMs
            }).catch((error) => ({
                status: /timed out/i.test(String(error))
                    ? 'timeout'
                    : /disconnect|closed/i.test(String(error))
                        ? 'disconnected'
                        : 'unavailable',
                message: error instanceof Error ? error.message : String(error)
            })).finally(() => {
                forge_stop_inflight.delete(curAgentName);
            });

            forge_stop_inflight.set(curAgentName, pending);
            forge_stop_last_requested_at.set(curAgentName, Date.now());
            callback?.(await pending);
        });

        socket.on('disconnect', () => {
            const connection = agent_connections[curAgentName];
            if (connection?.socket === socket) {
                console.log(`Agent ${curAgentName} disconnected`);
                connection.in_game = false;
                connection.socket = null;
                connection.runtime_ready = false;
                mindcraft.setAgentRuntimeReady(curAgentName, false);
                agentsStatusUpdate();
            }
            if (connection?.microphone_socket === socket) connection.microphone_socket = null;
            if (agent_listeners.includes(socket)) {
                removeListener(socket);
            }
        });

        socket.on('chat-message', (agentName, json) => {
            if (!agent_connections[agentName]) {
                console.warn(`Agent ${agentName} tried to send a message but is not logged in`);
                return;
            }
            console.log(`${curAgentName} sending message to ${agentName}: ${json.message}`);
            agent_connections[agentName].socket.emit('chat-message', curAgentName, json);
        });

        socket.on('set-agent-settings', (agentName, settings) => {
            const agent = agent_connections[agentName];
            if (agent) {
                agent.setSettings(settings);
                agent.socket.emit('restart-agent');
            }
        });

        socket.on('restart-agent', (agentName) => {
            console.log(`Restarting agent: ${agentName}`);
            agent_connections[agentName].socket.emit('restart-agent');
        });

        socket.on('stop-agent', (agentName) => {
            mindcraft.stopAgent(agentName);
        });

        socket.on('start-agent', (agentName) => {
            mindcraft.startAgent(agentName);
        });

        socket.on('destroy-agent', (agentName) => {
            if (agent_connections[agentName]) {
                mindcraft.destroyAgent(agentName);
                delete agent_connections[agentName];
            }
            agentsStatusUpdate();
        });

        socket.on('stop-all-agents', () => {
            console.log('Killing all agents');
            for (let agentName in agent_connections) {
                mindcraft.stopAgent(agentName);
            }
        });

        socket.on('shutdown', async () => {
            console.log('Shutting down');
            await mindcraft.shutdown();
            process.exit(0);
        });

		socket.on('send-message', (agentName, data) => {
			if (!agent_connections[agentName]) {
				console.warn(`Agent ${agentName} not in game, cannot send message via MindServer.`);
				return
			}
			try {
				agent_connections[agentName].socket.emit('send-message', data)
			} catch (error) {
				console.error('Error: ', error);
			}
		});

        socket.on('voice-transcript', (agentName, data) => {
            const connection = agent_connections[agentName];
            if (!isAuthorizedAgentSocket(socket, agentName) || !connection?.socket || !connection.runtime_ready) {
                console.warn(`Voice transcript authentication or readiness failed for Agent ${agentName}; transcript was ignored.`);
                return;
            }
            try {
                console.log(`[voice-debug][mindserver] transcript for ${agentName}:`, JSON.stringify({
                    source: data?.source,
                    speakerId: data?.speakerId,
                    text: data?.text,
                    metadata: data?.metadata || {}
                }));
                connection.socket.emit('voice-transcript', data);
            } catch (error) {
                console.error('Error forwarding voice transcript: ', error);
            }
        });

        socket.on('bot-output', (agentName, message) => {
            io.emit('bot-output', agentName, message);
        });

        socket.on('listen-to-agents', () => {
            addListener(socket);
        });
    });

    if (host_public) {
        console.log('Public hosting not supported yet. Using localhost.');
    }
    const host = 'localhost';
    server.listen(port, host, () => {
        console.log(`MindServer running on port ${port} on host ${host}`);
    });

    return server;
}

export function waitForMindServerListening(targetServer = server) {
    if (!targetServer) return Promise.reject(new Error('MindServer has not been created'));
    if (targetServer.listening) return Promise.resolve(targetServer);
    return new Promise((resolve, reject) => {
        const cleanup = () => {
            targetServer.off('listening', onListening);
            targetServer.off('error', onError);
        };
        const onListening = () => { cleanup(); resolve(targetServer); };
        const onError = (error) => { cleanup(); reject(error); };
        targetServer.once('listening', onListening);
        targetServer.once('error', onError);
    });
}

export async function closeMindServer() {
    const currentIo = io;
    const currentServer = server;
    io = null;
    server = null;
    if (currentIo) await new Promise((resolve) => currentIo.close(() => resolve()));
    if (currentServer?.listening) {
        await new Promise((resolve) => currentServer.close(() => resolve()));
    }
}

function agentsStatusUpdate(socket) {
    if (!socket) {
        socket = io;
    }
    let agents = [];
    for (let agentName in agent_connections) {
        const conn = agent_connections[agentName];
        agents.push({
            name: agentName, 
            in_game: conn.in_game,
            viewerPort: conn.viewer_port,
            socket_connected: !!conn.socket
            ,runtime_ready: !!conn.runtime_ready
        });
    };
    socket.emit('agents-status', agents);
}


let listenerInterval = null;
function addListener(listener_socket) {
    agent_listeners.push(listener_socket);
    if (agent_listeners.length === 1) {
        listenerInterval = setInterval(async () => {
            const states = {};
            for (let agentName in agent_connections) {
                let agent = agent_connections[agentName];
                if (agent.in_game) {
                    try {
                        const state = await new Promise((resolve) => {
                            agent.socket.emit('get-full-state', (s) => resolve(s));
                        });
                        states[agentName] = state;
                    } catch (e) {
                        states[agentName] = { error: String(e) };
                    }
                }
            }
            for (let listener of agent_listeners) {
                listener.emit('state-update', states);
            }
        }, 1000);
    }
}

function removeListener(listener_socket) {
    agent_listeners.splice(agent_listeners.indexOf(listener_socket), 1);
    if (agent_listeners.length === 0) {
        clearInterval(listenerInterval);
        listenerInterval = null;
    }
}

// Optional: export these if you need access to them from other files
export const getIO = () => io;
export const getServer = () => server;
export const numStateListeners = () => agent_listeners.length;
