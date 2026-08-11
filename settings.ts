// @ts-nocheck
import 'dotenv/config';

const settings = {
    "minecraft_version": "auto", // or specific version like "1.21.6"
    "host": "127.0.0.1", // or "localhost", "your.ip.address.here"
    "port": 25565, // set to -1 to automatically scan for open ports
    "auth": "offline", // or "microsoft"

    // the mindserver manages all agents and hosts the UI
    "mindserver_port": 8080,
    "auto_open_ui": true, // opens UI in browser on startup
    
    "base_profile": "assistant", // survival, assistant, creative, or god_mode
    "profiles": [
        "./profiles/chinese_npc.json",
        // "./profiles/gpt.json",
        // "./profiles/claude.json",
        // "./profiles/gemini.json",
        // "./profiles/llama.json",
        // "./profiles/qwen.json",
        // "./profiles/grok.json",
        // "./profiles/mistral.json",
        // "./profiles/deepseek.json",
        // "./profiles/mercury.json",
        // "./profiles/andy-4.json", // Supports up to 75 messages!

        // using more than 1 profile requires you to /msg each bot indivually
        // individual profiles override values from the base profile
    ],

    "load_memory": false, // load memory from previous session
    "init_message": "请用中文介绍你自己，并告诉我你现在能做什么。", // sends to all on spawn
    "only_chat_with": [], // users that the bots listen to and send general messages to. if empty it will chat publicly

    "speak": false,
    // allows all bots to speak through text-to-speech. 
    // specify speech model inside each profile with format: {provider}/{model}/{voice}.
    // if set to "system" it will use basic system text-to-speech. 
    // Works on windows and mac, but linux requires you to install the espeak package through your package manager eg: `apt install espeak` `pacman -S espeak`.

    "chat_ingame": true, // bot responses are shown in minecraft chat
    "language": "zh-CN", // translate to/from this language. Supports these language names: https://cloud.google.com/translate/docs/languages
    "render_bot_view": false, // show bot's view in browser at localhost:3000, 3001...

    "allow_insecure_coding": false, // allows newAction command and model can write/run code on your computer. enable at own risk
    "allow_vision": false, // allows vision model to interpret screenshots as inputs
    "blocked_actions" : ["!checkBlueprint", "!checkBlueprintLevel", "!getBlueprint", "!getBlueprintLevel"] , // commands to disable and remove from docs. Ex: ["!setMode"]
    "safety": {
        "enabled": true,
        "default_role": "player",
        "trusted_players": [],
        "operators": [],
        "require_confirmation": true,
        "confirmation_ttl_ms": 60000,
        "high_risk_actions": ["!newAction", "!restart", "!clearChat", "!attackPlayer", "!discard", "!givePlayer", "!giveArmorSet"]
    },
    "code_timeout_mins": -1, // minutes code is allowed to run. -1 for no timeout
    "relevant_docs_count": 5, // number of relevant code function docs to select for prompting. -1 for all

    "max_messages": 15, // max number of messages to keep in context
    "num_examples": 2, // number of examples to give to the model
    "max_commands": -1, // max number of commands that can be used in consecutive responses. -1 for no limit
    "show_command_syntax": "full", // "full", "shortened", or "none"
    "narrate_behavior": true, // chat simple automatic actions ('Picking up item!')
    "chat_bot_messages": true, // publicly chat messages to other bots

    "spawn_timeout": 30, // num seconds allowed for the bot to spawn before throwing error. Increase when spawning takes a while.
    "block_place_delay": 0, // delay between placing blocks (ms) if using newAction. helps avoid bot being kicked by anti-cheat mechanisms on servers.
  
    "log_all_prompts": false, // log ALL prompts to file

    "autonomy": {
        "enabled": true,
        "interval_ms": 5000
    },

    "mod_runtime": {
        "enabled": true,
        "instance_mode": "launcher",
        "target_minecraft_version": "1.20.1",
        "target_loader": "forge"
    },

    "combat": {
        "mode": "auto",
        "epicfight": {
            "weapon_profiles": ["sword", "axe", "slashblade"]
        }
    },

    "forge_action": {
        "enabled": true,
        "host": "127.0.0.1",
        "port": 18765,
        "client_id": null,
        "target_player_name": null,
        "snapshot_freshness_ms": 2000,
        "ack_timeout_ms": 3000
    },

    "companion": {
        "mode": "task-with-companion-tone",
        "task_update_chat": true
    },

    "voice": {
        "enabled": true,
        "provider": "doubao",
        "asr_model": null,
        "tts_model": null,
        "command_mode": "hybrid",
        "reply_speak_mode": "voice-triggered-only",
        "mic": {
            "enabled": true,
            "python_command": ".\\.local\\voice-mic-venv\\Scripts\\python.exe",
            "speaker_id": "mic_user",
            "wake_phrases": ["豆包"],
            "wake_followup_silence_ms": 800,
            "wake_followup_max_wait_ms": 10000,
            "direct_commands": ["停止", "跟着我", "回家"],
            "sample_rate": 16000,
            "chunk_ms": 20,
            "speech_rms_threshold": 500,
            "min_speech_ms": 200,
            "trailing_silence_ms": 600,
            "max_utterance_ms": 15000,
            "leading_context_ms": 400,
            "device": null
        },
        "openvoice": {
            "python_command": ".\\.local\\openvoice-venv\\Scripts\\python.exe",
            "tts_script": "scripts/openvoice_tts.py",
            "reference_audio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3",
            "voice_name": "EN-US",
            "language": "EN_V2",
            "zh_voice_name": "ZH",
            "zh_language": "ZH",
            "zh_reference_audio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3",
            "active_profile": "default",
            "profiles": {
                "default": {
                    "voiceName": "EN-US",
                    "language": "EN_V2",
                    "referenceAudio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3",
                    "zhVoiceName": "ZH",
                    "zhLanguage": "ZH",
                    "zhReferenceAudio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3"
                },
                "maid_soft": {
                    "voiceName": "EN-AU",
                    "language": "EN_V2",
                    "referenceAudio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3",
                    "zhVoiceName": "ZH",
                    "zhLanguage": "ZH",
                    "zhReferenceAudio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3"
                },
                "combat_cool": {
                    "voiceName": "EN-US",
                    "language": "EN_V2",
                    "referenceAudio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3",
                    "zhVoiceName": "ZH",
                    "zhLanguage": "ZH",
                    "zhReferenceAudio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3"
                },
                "klee_zh": {
                    "voiceName": "EN-US",
                    "language": "EN_V2",
                    "referenceAudio": ".\\.local\\OpenVoice\\resources\\example_reference.mp3",
                    "zhVoiceName": "ZH",
                    "zhLanguage": "ZH",
                    "zhReferenceAudio": ".\\sources\\sound\\可莉.mp3"
                }
            }
        },
        "doubao": {
            "mode": "realtime",
            "ttsMode": "v3",
            "apiKey": process.env.DOUBAO_API_KEY || "",
            "realtime": {
                "endpoint": "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                "model": "1.2.6.1",
                "inputSampleRate": 16000,
                "outputSampleRate": 24000,
                "inputFormat": "pcm",
                "outputFormat": "pcm_s16le"
            }
        }
    },

    "mod_knowledge": {
        "enabled": true,
        "pack_sources": ["patchouli", "kubejs", "crafttweaker", "datapack"]
    },

    "maid": {
        "enabled": true,
        "default_modes": ["harvest", "replant", "store-items", "follow", "patrol", "defend"]
    },

    "build_coordination": {
        "enabled": true,
        "rebalance": true
    },

    "embodiment": {
        "provider": "ysm",
        "profile": {
            "id": "default",
            "displayName": "Default",
            "modelProvider": "ysm",
            "modelId": "default/steve"
        }
    },

    "run": {
        "base_dir": "./runs"
    }

}

export default settings;

