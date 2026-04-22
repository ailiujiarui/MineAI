# Game AI Forge Agent

Minimal Forge `1.20.1` client-side scaffold for the future real-client control agent.

Current scope:

- Forge mod entrypoint
- client bootstrap placeholder
- local bridge client scaffold for Node-side coordination
- basic client-side command execution scaffold

Not implemented yet:

- player input injection
- Epic Fight and SlashBlade runtime control
- end-to-end in-game runtime proof with a real Forge client connected to the Node bridge

## Build Notes

This directory is scaffolded against the standard Forge `1.20.1` Gradle layout.

From the workspace root, you can bootstrap a local Gradle and attempt a build with:

```powershell
npm run forge-agent:build
```

Current local status:

1. `npm run forge-agent:build` completes successfully in this workspace
2. compiled mod artifact is emitted under `forge-agent/build/libs/`

Expected next steps:

1. add Gradle wrapper or keep using the local bootstrap script
2. install the built jar into a real Forge client and confirm bridge connectivity
3. extend command execution from generic actions into Epic Fight specific combat actions
