import argparse
import json
import os
import queue
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
DEFAULT_RESOURCE_ID = "volc.speech.dialog"
DEFAULT_APP_KEY = "PlgvMymc7f3tQnJ6"
DEFAULT_MODEL = "2.2.0.0"
DEFAULT_MINDSERVER_HOSTS = ["localhost", "127.0.0.1"]
DEFAULT_MINDSERVER_PORT = 8080


def get_key(name: str, default: str = "") -> str:
    keys_path = Path("keys.json")
    if keys_path.exists():
        try:
            keys = json.loads(keys_path.read_text(encoding="utf-8"))
            value = keys.get(name)
            if value:
                return str(value)
        except Exception:
            pass
    return str(os.environ.get(name, default))


def get_mindserver_port(default: int = DEFAULT_MINDSERVER_PORT) -> int:
    value = os.environ.get("MINDSERVER_PORT", "")
    if not value:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def write_int32(value: int) -> bytes:
    return int(value).to_bytes(4, "big", signed=True)


def encode_header(message_type: int, flags: int, serialization: int = 1, compression: int = 0) -> bytes:
    return bytes([
        0x11,
        ((message_type & 0x0F) << 4) | (flags & 0x0F),
        ((serialization & 0x0F) << 4) | (compression & 0x0F),
        0x00,
    ])


def encode_json_event(event_id: int, payload: dict, session_id: Optional[str] = None) -> bytes:
    payload_bytes = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    parts = [encode_header(0x1, 0x4, 1, 0), write_int32(event_id)]
    if session_id:
        session_bytes = session_id.encode("utf-8")
        parts.append(write_int32(len(session_bytes)))
        parts.append(session_bytes)
    parts.append(write_int32(len(payload_bytes)))
    parts.append(payload_bytes)
    return b"".join(parts)


def encode_audio_event(event_id: int, audio_bytes: bytes, session_id: str) -> bytes:
    session_bytes = session_id.encode("utf-8")
    return b"".join([
        encode_header(0x2, 0x4, 0, 0),
        write_int32(event_id),
        write_int32(len(session_bytes)),
        session_bytes,
        write_int32(len(audio_bytes)),
        audio_bytes,
    ])


def decode_frame(raw: bytes) -> dict:
    buffer = bytes(raw)
    message_type = (buffer[1] >> 4) & 0x0F
    flags = buffer[1] & 0x0F
    serialization = (buffer[2] >> 4) & 0x0F
    offset = 4
    event_id = None
    connect_id = None
    session_id = None

    if flags == 0x4:
        event_id = int.from_bytes(buffer[offset:offset + 4], "big", signed=True)
        offset += 4
        if event_id < 100:
            maybe_size = int.from_bytes(buffer[offset:offset + 4], "big", signed=True)
            remaining_after_size = len(buffer) - (offset + 4)
            if maybe_size > 0 and remaining_after_size > maybe_size + 3:
                offset += 4
                connect_id = buffer[offset:offset + maybe_size].decode("utf-8")
                offset += maybe_size
        else:
            session_size = int.from_bytes(buffer[offset:offset + 4], "big", signed=True)
            offset += 4
            session_id = buffer[offset:offset + session_size].decode("utf-8")
            offset += session_size

    payload_size = int.from_bytes(buffer[offset:offset + 4], "big", signed=True)
    offset += 4
    payload = buffer[offset:offset + payload_size]

    if serialization == 1:
        payload = json.loads(payload.decode("utf-8"))

    return {
        "message_type": message_type,
        "event": event_id,
        "connect_id": connect_id,
        "session_id": session_id,
        "payload": payload,
    }


def build_transcript_payload(text: str, speaker_id: str, metadata: Optional[dict] = None) -> dict:
    return {
        "text": text,
        "speakerId": speaker_id,
        "source": "mic",
        "timestamp": int(time.time() * 1000),
        "metadata": metadata or {},
    }


def build_start_session_payload(model: str, sample_rate: int, input_mode: str = "") -> dict:
    dialog_extra = {
        "model": model,
    }
    if input_mode:
        dialog_extra["input_mod"] = input_mode

    return {
        "asr": {
            "audio_info": {
                "format": "pcm",
                "sample_rate": sample_rate,
                "channel": 1,
            }
        },
        "dialog": {
            "extra": dialog_extra
        }
    }


class DoubaoRealtimeMicBridge:
    def __init__(self, args):
        self.args = args
        self.session_id = str(uuid.uuid4())
        self.current_text = ""
        self.socket = None
        import socketio

        self.sio = socketio.Client()
        self.audio_queue: queue.Queue[bytes] = queue.Queue()
        self.running = False

    def connect_transport(self):
        from websocket import create_connection

        headers = {
            "X-Api-App-ID": self.args.app_id,
            "X-Api-Access-Key": self.args.access_token,
            "X-Api-Resource-Id": self.args.resource_id,
            "X-Api-App-Key": self.args.app_key,
            "X-Api-Connect-Id": str(uuid.uuid4()),
        }
        self.socket = create_connection(self.args.endpoint, header=[f"{k}: {v}" for k, v in headers.items()], timeout=10)
        print(f"[voice-mic] connected realtime websocket: {self.args.endpoint}")
        self.socket.send_binary(encode_json_event(1, {}))
        self._wait_for_event(50)
        self.socket.send_binary(encode_json_event(100, build_start_session_payload(
            self.args.model,
            self.args.sample_rate,
            self.args.input_mode,
        ), self.session_id))
        self._wait_for_event(150)
        print(f"[voice-mic] session started: {self.session_id}")

    def connect_mindserver(self):
        hosts = [self.args.mindserver_host] if self.args.mindserver_host else DEFAULT_MINDSERVER_HOSTS
        last_error = None

        for host in hosts:
            try:
                self.sio.connect(f"http://{host}:{self.args.port}")
                print(f"[voice-mic] connected MindServer: http://{host}:{self.args.port}")
                return
            except Exception as error:
                last_error = error

        raise last_error

    def _wait_for_event(self, expected_event: int):
        deadline = time.time() + 10
        while time.time() < deadline:
            frame = decode_frame(self.socket.recv())
            if frame["event"] == expected_event:
                return frame
            if frame["event"] in (51, 153, 599):
                payload = frame.get("payload") or {}
                raise RuntimeError(payload.get("message") or payload.get("error") or f"Unexpected event {frame['event']}")
        raise TimeoutError(f"Timed out waiting for realtime event {expected_event}")

    def _receiver_loop(self):
        while self.running:
            try:
                frame = decode_frame(self.socket.recv())
            except Exception:
                break

            if frame["event"] == 451:
                results = frame["payload"].get("results") or []
                latest = results[-1] if results else None
                if latest:
                    latest_text = latest.get("text", "").strip()
                    if latest_text:
                        if latest.get("is_interim"):
                            print(f"[voice-mic] interim: {latest_text}")
                        else:
                            print(f"[voice-mic] final chunk: {latest_text}")
                            self.current_text = latest_text
            elif frame["event"] == 459:
                text = self.current_text.strip()
                self.current_text = ""
                if text:
                    print(f"[voice-mic] emit transcript: {text}")
                    self.sio.emit("voice-transcript", (
                        self.args.agent,
                        build_transcript_payload(
                            text,
                            self.args.speaker_id,
                            {"transport": "local-mic"}
                        )
                    ))
            elif frame["event"] == 599:
                payload = frame.get("payload") or {}
                print(f"[voice-mic] dialog error: {payload}")

    def _sender_loop(self):
        while self.running:
            chunk = self.audio_queue.get()
            if chunk is None:
                break
            self.socket.send_binary(encode_audio_event(200, chunk, self.session_id))

    def _open_input_stream(self):
        import sounddevice as sd

        frames_per_chunk = max(1, int(self.args.sample_rate * (self.args.chunk_ms / 1000)))
        print(f"[voice-mic] opening input device: {self.args.device if self.args.device is not None else 'default'}")
        print(f"[voice-mic] sample_rate={self.args.sample_rate} chunk_ms={self.args.chunk_ms} frames={frames_per_chunk}")

        def callback(indata, frames, _time_info, status):
            if status:
                print(f"[voice-mic] input status: {status}")
                return
            self.audio_queue.put(bytes(indata))

        return sd.RawInputStream(
            samplerate=self.args.sample_rate,
            blocksize=frames_per_chunk,
            dtype="int16",
            channels=1,
            device=self.args.device,
            callback=callback,
        )

    def run(self):
        self.connect_mindserver()
        self.connect_transport()
        self.running = True

        receiver = threading.Thread(target=self._receiver_loop, daemon=True)
        sender = threading.Thread(target=self._sender_loop, daemon=True)
        receiver.start()
        sender.start()

        stream = self._open_input_stream()
        with stream:
            try:
                while True:
                    time.sleep(0.1)
            except KeyboardInterrupt:
                pass
            finally:
                self.running = False
                self.audio_queue.put(None)
                try:
                    self.socket.send_binary(encode_json_event(102, {}, self.session_id))
                except Exception:
                    pass
                try:
                    self.socket.close()
                except Exception:
                    pass
                try:
                    self.sio.disconnect()
                except Exception:
                    pass


def parse_args():
    parser = argparse.ArgumentParser(description="Always-listening local microphone helper for Mindcraft voice input.")
    parser.add_argument("--agent", required=True, help="Target bot name")
    parser.add_argument("--speaker-id", default="mic_user", help="Logical speaker id injected into voice transcripts")
    parser.add_argument("--port", type=int, default=get_mindserver_port(), help="MindServer port")
    parser.add_argument("--mindserver-host", default="", help="Optional MindServer host override")
    parser.add_argument("--device", default=None, help="Optional sounddevice input device name or index")
    parser.add_argument("--sample-rate", type=int, default=16000, help="Microphone sample rate")
    parser.add_argument("--chunk-ms", type=int, default=20, help="Microphone chunk size in milliseconds")
    parser.add_argument("--input-mode", default="", help="Optional dialog.extra.input_mod override")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="Doubao realtime endpoint")
    parser.add_argument("--resource-id", default=DEFAULT_RESOURCE_ID, help="Doubao realtime resource id")
    parser.add_argument("--app-key", default=DEFAULT_APP_KEY, help="Doubao realtime app key")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Doubao realtime model version")
    parser.add_argument("--app-id", default=get_key("DOUBAO_APP_ID"), help="Doubao App ID")
    parser.add_argument("--access-token", default=get_key("DOUBAO_ACCESS_TOKEN"), help="Doubao Access Token")
    return parser.parse_args()


def main():
    args = parse_args()
    if not args.app_id or not args.access_token:
        raise SystemExit("DOUBAO_APP_ID and DOUBAO_ACCESS_TOKEN are required for microphone streaming.")
    bridge = DoubaoRealtimeMicBridge(args)
    bridge.run()


if __name__ == "__main__":
    main()
