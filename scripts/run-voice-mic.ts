// @ts-nocheck
import fs from 'fs';
import path from 'path';
import { spawn } from 'child_process';
import settings from '../settings.ts';
import { buildVoiceMicChildArgs, resolveVoiceMicPythonCommand } from '../src/voice/micLauncherConfig.js';

const pythonCommand = resolveVoiceMicPythonCommand(settings.voice?.mic?.python_command || settings.voice?.mic?.pythonCommand);
const scriptPath = 'scripts/voice-mic-listener.py';

function parseAgentArg(argv) {
  const remaining = [];
  let agent = '';

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];

    if (arg === '--agent' && i + 1 < argv.length) {
      agent = argv[i + 1];
      i += 1;
      continue;
    }

    if (!agent && !arg.startsWith('-')) {
      agent = arg;
      continue;
    }

    remaining.push(arg);
  }

  return { agent, remaining };
}

function tryResolveAgentFromSettings() {
  const profilePaths = settings.profiles || [];
  for (const profilePath of profilePaths) {
    const resolved = path.resolve(process.cwd(), profilePath);
    if (!fs.existsSync(resolved)) {
      continue;
    }

    try {
      const profile = JSON.parse(fs.readFileSync(resolved, 'utf8'));
      if (profile?.name) {
        return profile.name;
      }
    } catch {
      // Ignore malformed profiles and keep scanning.
    }
  }

  return '';
}

const argv = process.argv.slice(2);
const wantsHelp = argv.includes('--help') || argv.includes('-h');
const parsed = parseAgentArg(argv);
const resolvedAgent = parsed.agent || process.env.VOICE_AGENT || tryResolveAgentFromSettings();

let childArgs = [scriptPath];
if (!wantsHelp) {
  if (!resolvedAgent) {
    console.error('voice:mic launcher requires an agent name. Pass it as `dummy`, `--agent dummy`, or set VOICE_AGENT.');
    process.exit(1);
  }

  childArgs = buildVoiceMicChildArgs({
    scriptPath,
    agent: resolvedAgent,
    remainingArgs: parsed.remaining,
    envPort: process.env.MINDSERVER_PORT,
    settingsPort: settings.mindserver_port,
    micSettings: settings.voice?.mic || {},
    mindserverHost: process.env.MINDSERVER_HOST || '',
    doubaoRealtimeSettings: settings.voice?.doubao?.realtime || {}
  });
} else {
  childArgs.push(...parsed.remaining);
}

const child = spawn(pythonCommand, childArgs, {
  stdio: 'inherit',
  shell: false
});

child.on('exit', (code) => {
  process.exit(code ?? 0);
});

child.on('error', (error) => {
  console.error('voice:mic launcher failed:', error);
  process.exit(1);
});
