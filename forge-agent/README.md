# Game AI Forge Agent

Minimal Forge `1.20.1` client-side scaffold for the future real-client control agent.

Current scope:

- Forge mod entrypoint
- client bootstrap placeholder
- local bridge client scaffold for Node-side coordination
- basic client-side command execution scaffold
- protocol-version and payload validation on the Node bridge
- command acknowledgement waiting with configurable timeout and disconnect cleanup
- Node-side real-client smoke runner (`npm run forge-agent:smoke`) for hello/snapshot/ack diagnostics

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

Build requirements and current status:

1. Forge `1.20.1` must be built with JDK 17; the build script now rejects incompatible JDK versions and propagates Gradle failures.
2. A successful build emits the compiled mod artifact under `forge-agent/build/libs/`.
3. The current workstation uses JDK 25, so a local build requires installing/selecting JDK 17 first.

Expected next steps:

1. run the smoke command and manually launch the `Closing Song1.6.4` Forge instance from PCL
2. confirm the jar is loaded in the client log and capture a real hello/snapshot/ack cycle
3. extend command execution from generic actions into Epic Fight specific combat actions
