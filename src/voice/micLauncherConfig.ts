// @ts-nocheck

const DEFAULT_VOICE_MIC_PYTHON = '.\\.local\\voice-mic-venv\\Scripts\\python.exe';

function hasExplicitPortArg(args = []) {
    for (let i = 0; i < args.length; i++) {
        const arg = String(args[i] || '');
        if (arg === '--port' || arg.startsWith('--port=')) {
            return true;
        }
    }
    return false;
}

function hasExplicitArg(args = [], flagName) {
    for (let i = 0; i < args.length; i++) {
        const arg = String(args[i] || '');
        if (arg === flagName || arg.startsWith(`${flagName}=`)) {
            return true;
        }
    }
    return false;
}

function resolveMindServerPort(envPort, settingsPort) {
    const candidates = [envPort, settingsPort];
    for (const candidate of candidates) {
        if (candidate === undefined || candidate === null || candidate === '') {
            continue;
        }
        return String(candidate);
    }
    return '';
}

export function resolveVoiceMicPythonCommand(configuredPythonCommand) {
    return String(configuredPythonCommand || '').trim() || DEFAULT_VOICE_MIC_PYTHON;
}

export function buildVoiceMicChildArgs(options = {}) {
    const scriptPath = options.scriptPath || 'scripts/voice-mic-listener.py';
    const agent = options.agent || '';
    const remainingArgs = [...(options.remainingArgs || [])];
    const micSettings = options.micSettings || {};
    const doubaoRealtimeSettings = options.doubaoRealtimeSettings || {};
    const childArgs = [scriptPath];

    if (agent) {
        childArgs.push('--agent', agent);
    }

    if (!hasExplicitPortArg(remainingArgs)) {
        const resolvedPort = resolveMindServerPort(options.envPort, options.settingsPort);
        if (resolvedPort) {
            childArgs.push('--port', resolvedPort);
        }
    }

    const configuredArgs = [
        ['--speaker-id', micSettings.speaker_id || micSettings.speakerId],
        ['--sample-rate', micSettings.sample_rate || micSettings.sampleRate],
        ['--chunk-ms', micSettings.chunk_ms || micSettings.chunkMs],
        ['--device', micSettings.device]
    ];

    for (const [flagName, value] of configuredArgs) {
        if (value === undefined || value === null || value === '') {
            continue;
        }
        if (!hasExplicitArg(remainingArgs, flagName)) {
            childArgs.push(flagName, String(value));
        }
    }

    const configuredBridgeArgs = [
        ['--mindserver-host', options.mindserverHost],
        ['--endpoint', doubaoRealtimeSettings.endpoint],
        ['--resource-id', doubaoRealtimeSettings.resource_id || doubaoRealtimeSettings.resourceId],
        ['--app-key', doubaoRealtimeSettings.app_key || doubaoRealtimeSettings.appKey],
        ['--model', doubaoRealtimeSettings.model]
    ];

    for (const [flagName, value] of configuredBridgeArgs) {
        if (value === undefined || value === null || value === '') {
            continue;
        }
        if (!hasExplicitArg(remainingArgs, flagName)) {
            childArgs.push(flagName, String(value));
        }
    }

    childArgs.push(...remainingArgs);
    return childArgs;
}
