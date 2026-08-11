import argparse
import base64
import json
import math
import os
import queue
import random
import re
import struct
import sys
import threading
import time
import uuid
from collections import deque
from pathlib import Path
from typing import List, Optional, Tuple

DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
DEFAULT_MODEL = "1.2.6.1"
DEFAULT_MINDSERVER_HOSTS = ["localhost", "127.0.0.1"]
DEFAULT_MINDSERVER_PORT = 8080
DEFAULT_SPEECH_RMS_THRESHOLD = 500
DEFAULT_MIN_SPEECH_MS = 200
DEFAULT_TRAILING_SILENCE_MS = 600
DEFAULT_MAX_UTTERANCE_MS = 15000
DEFAULT_LEADING_CONTEXT_MS = 400
FINAL_TRANSCRIPT_TIMEOUT_SECONDS = 5.0

AudioOperation = Tuple[str, Optional[bytes]]
PROJECT_ROOT = Path(__file__).resolve().parent.parent

IDLE_NO_AUDIO_CODE = "52000033"
DEFAULT_SESSION_RECONNECT_LIMIT = 5
DEFAULT_SESSION_RECONNECT_BACKOFF_SECONDS = 0.5
DEFAULT_SESSION_RECONNECT_BACKOFF_CAP_SECONDS = 8.0


def classify_doubao_error(error) -> dict:
    """Classify provider frames and transport exceptions without broad idle matching."""
    payload = error if isinstance(error, dict) else getattr(error, "provider_payload", {})
    nested = payload.get("error") if isinstance(payload.get("error"), dict) else {}
    status_code = getattr(error, "status_code", None)
    outer_code = str(nested.get("code") or payload.get("code") or status_code or "")
    message = str(
        nested.get("message")
        or payload.get("message")
        or error
        or "Unknown Doubao realtime error"
    )
    embedded_codes = re.findall(r"(?:code|codes)\s*[=:]\s*(\d+)", message, re.IGNORECASE)
    inner_code = embedded_codes[-1] if embedded_codes else ""

    if IDLE_NO_AUDIO_CODE in {outer_code, inner_code}:
        retry_class = "idle-expired"
    elif outer_code in {"401", "403"} or "unauthorized" in message.lower() or "forbidden" in message.lower():
        retry_class = "fatal-auth"
    elif outer_code == "429" or "rate limit" in message.lower() or "too many requests" in message.lower():
        retry_class = "rate-limited"
    elif isinstance(error, dict) or payload:
        retry_class = "fatal-provider"
    else:
        retry_class = "transient-network"

    return {
        "retry_class": retry_class,
        "outer_code": outer_code,
        "inner_code": inner_code,
        "message": message,
    }


class DoubaoProviderError(RuntimeError):
    def __init__(self, payload: dict):
        self.provider_payload = payload
        super().__init__(classify_doubao_error(payload)["message"])


def read_project_env(name: str) -> str:
    env_path = PROJECT_ROOT / ".env"
    if not env_path.exists():
        return ""
    try:
        for raw_line in env_path.read_text(encoding="utf-8-sig").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip() != name:
                continue
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            return value
    except OSError:
        return ""
    return ""


def get_key(name: str, default: str = "") -> str:
    env_value = read_project_env(name)
    if env_value:
        return env_value

    keys_path = PROJECT_ROOT / "keys.json"
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


def encode_json_event(event: dict) -> str:
    return json.dumps(event, ensure_ascii=False)


def decode_frame(raw) -> dict:
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    return json.loads(raw)


def build_transcript_payload(text: str, speaker_id: str, metadata: Optional[dict] = None) -> dict:
    return {
        "text": text,
        "speakerId": speaker_id,
        "source": "mic",
        "timestamp": int(time.time() * 1000),
        "metadata": metadata or {},
    }


def build_session_event(model: str, sample_rate: int, input_mode: str = "") -> dict:
    instructions = input_mode or (
        "请只做中文语音识别并返回准确原文，不要回答用户。Minecraft术语请按原样转写："
        "唤醒词‘豆包’；数量‘一组’‘两组’‘一套’；物品‘钻石’‘下界合金’‘头盔’‘胸甲’‘护腿’‘靴子’。"
        "不要把游戏术语替换成人名或同音词。"
    )
    return {
        "type": "session.create",
        "session": {
            "model": model,
            "instructions": instructions,
            "audio": {
                "input": {"format": {"type": "pcm", "rate": sample_rate}},
                "output": {"format": {"type": "pcm_s16le", "rate": 24000}},
            },
        },
    }


def pcm16_rms(chunk: bytes) -> int:
    """Return the RMS amplitude of little-endian mono PCM16 audio."""
    sample_count = len(chunk) // 2
    if sample_count == 0:
        return 0
    samples = struct.unpack(f"<{sample_count}h", chunk[:sample_count * 2])
    return math.isqrt(sum(sample * sample for sample in samples) // sample_count)


class Pcm16SilenceSegmenter:
    def __init__(
        self,
        sample_rate: int,
        speech_rms_threshold: int,
        min_speech_ms: int,
        trailing_silence_ms: int,
        max_utterance_ms: int,
        leading_context_ms: int = DEFAULT_LEADING_CONTEXT_MS,
    ):
        self.sample_rate = sample_rate
        self.speech_rms_threshold = speech_rms_threshold
        self.min_speech_ms = min_speech_ms
        self.trailing_silence_ms = trailing_silence_ms
        self.max_utterance_ms = max_utterance_ms
        self.leading_context_ms = max(0, leading_context_ms)
        self._leading_chunks = deque()
        self._leading_ms = 0.0
        self._pending_chunks: List[bytes] = []
        self._pending_speech_ms = 0.0
        self._utterance_ms = 0.0
        self._silence_ms = 0.0
        self._active = False

    def _chunk_duration_ms(self, chunk: bytes) -> float:
        return (len(chunk) // 2) * 1000.0 / self.sample_rate

    def _reset(self):
        self._leading_chunks.clear()
        self._leading_ms = 0.0
        self._pending_chunks.clear()
        self._pending_speech_ms = 0.0
        self._utterance_ms = 0.0
        self._silence_ms = 0.0
        self._active = False

    def process(self, chunk: bytes) -> List[AudioOperation]:
        if not chunk:
            return []

        chunk_ms = self._chunk_duration_ms(chunk)
        is_speech = pcm16_rms(chunk) >= self.speech_rms_threshold

        if not self._active:
            if not is_speech:
                if self.leading_context_ms > 0:
                    self._leading_chunks.append(chunk)
                    self._leading_ms += chunk_ms
                    while self._leading_chunks and self._leading_ms > self.leading_context_ms:
                        removed = self._leading_chunks.popleft()
                        self._leading_ms -= self._chunk_duration_ms(removed)
                self._pending_chunks.clear()
                self._pending_speech_ms = 0.0
                return []

            self._pending_chunks.append(chunk)
            self._pending_speech_ms += chunk_ms
            if self._pending_speech_ms < self.min_speech_ms:
                return []

            self._active = True
            self._utterance_ms = self._pending_speech_ms
            operations = [("append", leading) for leading in self._leading_chunks]
            operations.extend(("append", pending) for pending in self._pending_chunks)
            self._leading_chunks.clear()
            self._leading_ms = 0.0
            self._pending_chunks = []
        else:
            self._utterance_ms += chunk_ms
            operations = [("append", chunk)]

        self._silence_ms = 0.0 if is_speech else self._silence_ms + chunk_ms
        if self._silence_ms >= self.trailing_silence_ms or self._utterance_ms >= self.max_utterance_ms:
            operations.append(("commit", None))
            self._reset()
        return operations

    def flush(self) -> List[AudioOperation]:
        if not self._active:
            self._reset()
            return []
        self._reset()
        return [("commit", None)]


class DoubaoRealtimeMicBridge:
    def __init__(self, args):
        self.args = args
        self.session_id = ""
        self.current_text = ""
        self.socket = None
        sio_factory = getattr(args, "sio_factory", None)
        if sio_factory is None:
            import socketio

            sio_factory = socketio.Client
        self.sio = sio_factory()
        self.audio_queue: queue.Queue[AudioOperation] = queue.Queue()
        self.segmenter = Pcm16SilenceSegmenter(
            args.sample_rate,
            args.speech_rms_threshold,
            args.min_speech_ms,
            args.trailing_silence_ms,
            args.max_utterance_ms,
            getattr(args, "leading_context_ms", DEFAULT_LEADING_CONTEXT_MS),
        )
        self._transcript_condition = threading.Condition()
        self._pending_transcripts = 0
        self.running = False
        self._stop_event = threading.Event()
        self._transport_error = None
        self._session_lock = threading.Lock()
        self._session_generation = 0
        self._session_active = False
        self._session_done = threading.Event()
        self._session_failure = None
        self._speech_waiting = threading.Event()
        max_pre_roll_chunks = max(
            1,
            int(min(args.max_utterance_ms, 5000) / max(1, args.chunk_ms)),
        )
        self._pre_roll = deque(maxlen=max_pre_roll_chunks + 1)
        self._socket_factory = getattr(args, "socket_factory", None)
        self._jitter = getattr(args, "jitter", lambda: random.uniform(0.8, 1.2))
        self._session_reconnect_limit = getattr(
            args, "session_reconnect_limit", DEFAULT_SESSION_RECONNECT_LIMIT
        )
        self._session_reconnect_backoff = getattr(
            args,
            "session_reconnect_backoff_seconds",
            DEFAULT_SESSION_RECONNECT_BACKOFF_SECONDS,
        )
        self._playback_active = False
        self._playback_until = 0.0
        self._playback_lock = threading.Lock()

    def connect_transport(self):
        if self._socket_factory is None:
            from websocket import create_connection

            socket_factory = create_connection
        else:
            socket_factory = self._socket_factory

        headers = {"X-Api-Key": self.args.api_key}
        socket = None
        try:
            socket = socket_factory(
                self.args.endpoint,
                header=[f"{k}: {v}" for k, v in headers.items()],
                timeout=10,
            )
            print(f"[voice-mic] connected realtime websocket: {self.args.endpoint}")
            socket.send(encode_json_event(build_session_event(
                self.args.model,
                self.args.sample_rate,
                self.args.input_mode,
            )))
            self._wait_for_event("session.created", socket)
            if hasattr(socket, "settimeout"):
                socket.settimeout(None)
            self.socket = socket
            self.session_id = str(uuid.uuid4())
            print(f"[voice-mic] session started: {self.session_id}")
            return socket
        except Exception:
            if socket is not None:
                try:
                    socket.close()
                except Exception:
                    pass
            raise

    def connect_mindserver(self):
        hosts = [self.args.mindserver_host] if self.args.mindserver_host else DEFAULT_MINDSERVER_HOSTS
        last_error = None

        for host in hosts:
            try:
                self.sio.connect(
                    f"http://{host}:{self.args.port}",
                    auth={
                        "agentName": os.environ.get("MINDCRAFT_AGENT_NAME", self.args.agent),
                        "token": os.environ.get("MINDCRAFT_AGENT_TOKEN", ""),
                    },
                )
                if hasattr(self.sio, "on"):
                    self.sio.on("voice-playback-state", self._on_playback_state)
                self.sio.emit("register-microphone", self.args.agent)
                print(f"[voice-mic] connected MindServer: http://{host}:{self.args.port}")
                return
            except Exception as error:
                last_error = error

        raise last_error

    def _on_playback_state(self, state):
        active = bool((state or {}).get("active"))
        cooldown_ms = max(0, int((state or {}).get("cooldownMs", 250)))
        with self._playback_lock:
            self._playback_active = active
            self._playback_until = time.monotonic() + cooldown_ms / 1000.0 if not active else 0.0
        self.segmenter._reset()
        with self._session_lock:
            self._pre_roll.clear()
            self._speech_waiting.clear()

    def _is_playback_suppressed(self):
        with self._playback_lock:
            return self._playback_active or time.monotonic() < self._playback_until

    def _wait_for_event(self, expected_event: str, socket=None):
        socket = socket or self.socket
        deadline = time.time() + 10
        while time.time() < deadline:
            frame = decode_frame(socket.recv())
            if frame.get("type") == expected_event:
                return frame
            if frame.get("type") == "error":
                raise DoubaoProviderError(frame)
        raise TimeoutError(f"Timed out waiting for realtime event {expected_event}")

    def _is_current_generation(self, generation: int) -> bool:
        with self._session_lock:
            return generation == self._session_generation and self._session_active

    def _fail_session(self, generation: int, error):
        classified = classify_doubao_error(error)
        with self._session_lock:
            if generation != self._session_generation or not self._session_active:
                return
            if self._session_failure is None:
                self._session_failure = classified
            self._session_active = False
            self._session_done.set()

    def _receiver_loop(self, generation=None, socket=None):
        generation = self._session_generation if generation is None else generation
        socket = socket or self.socket
        while self.running and self._is_current_generation(generation):
            try:
                frame = decode_frame(socket.recv())
            except Exception as error:
                self._fail_session(generation, error)
                break
            if not self._is_current_generation(generation):
                break

            if frame.get("type") in ("conversation.item.input_audio_transcription.completed", "input_audio_buffer.transcription.completed"):
                latest_text = (frame.get("transcript") or frame.get("text") or "").strip()
                if latest_text:
                    print(f"[voice-mic] final chunk: {latest_text}")
                    self.current_text = latest_text
                text = self.current_text.strip()
                self.current_text = ""
                if text:
                    print(f"[voice-mic] emit transcript: {text}")
                    try:
                        self.sio.emit("voice-transcript", (
                            self.args.agent,
                            build_transcript_payload(
                                text,
                                self.args.speaker_id,
                                {"transport": "local-mic"}
                            )
                        ))
                    except Exception as error:
                        self._fail_session(generation, {
                            "error": {
                                "code": "mindserver-emit-failed",
                                "message": f"MindServer transcript emit failed: {error}",
                            }
                        })
                        break
                self._record_transcript_completed()
            elif frame.get("type") == "error":
                classified = classify_doubao_error(frame)
                if classified["retry_class"] != "idle-expired":
                    print(f"[voice-mic] dialog error: {frame.get('error')}")
                self._fail_session(generation, frame)
                break

    def _sender_loop(self, generation=None, socket=None, audio_queue=None):
        generation = self._session_generation if generation is None else generation
        socket = socket or self.socket
        audio_queue = audio_queue or self.audio_queue
        while True:
            item = audio_queue.get()
            if len(item) == 3:
                item_generation, operation, chunk = item
                if item_generation != generation:
                    continue
            else:
                operation, chunk = item
            if operation == "stop":
                break
            if not self._is_current_generation(generation):
                continue
            try:
                if operation == "append" and chunk is not None:
                    socket.send(encode_json_event({
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(chunk).decode("ascii"),
                    }))
                elif operation == "commit":
                    socket.send(encode_json_event({"type": "input_audio_buffer.commit"}))
                    self._record_commit_sent()
                elif operation == "barrier" and chunk is not None:
                    chunk.set()
            except Exception as error:
                self._fail_session(generation, error)
                break

    def _stdin_loop(self):
        try:
            for line in sys.stdin:
                if line.strip().lower() in ("stop", "quit", "exit"):
                    self._stop_event.set()
                    return
        except Exception:
            return
        self._stop_event.set()

    def _record_commit_sent(self):
        with self._transcript_condition:
            self._pending_transcripts += 1

    def _record_transcript_completed(self):
        with self._transcript_condition:
            if self._pending_transcripts > 0:
                self._pending_transcripts -= 1
            self._transcript_condition.notify_all()

    def _reset_pending_transcripts(self):
        with self._transcript_condition:
            self._pending_transcripts = 0
            self._transcript_condition.notify_all()

    def _wait_for_pending_transcripts(self, timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        with self._transcript_condition:
            while self._pending_transcripts > 0:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return False
                self._transcript_condition.wait(remaining)
        return True

    def _open_input_stream(self):
        import sounddevice as sd

        frames_per_chunk = max(1, int(self.args.sample_rate * (self.args.chunk_ms / 1000)))
        print(f"[voice-mic] opening input device: {self.args.device if self.args.device is not None else 'default'}")
        print(f"[voice-mic] sample_rate={self.args.sample_rate} chunk_ms={self.args.chunk_ms} frames={frames_per_chunk}")

        def callback(indata, frames, _time_info, status):
            if status:
                print(f"[voice-mic] input status: {status}")
                return
            if self._is_playback_suppressed():
                self.segmenter._reset()
                return
            operations = self.segmenter.process(bytes(indata))
            if operations:
                self._queue_audio_operations(operations)

        return sd.RawInputStream(
            samplerate=self.args.sample_rate,
            blocksize=frames_per_chunk,
            dtype="int16",
            channels=1,
            device=self.args.device,
            callback=callback,
        )

    def _queue_audio_operations(self, operations: List[AudioOperation]):
        with self._session_lock:
            if self._session_active:
                generation = self._session_generation
                target_queue = self.audio_queue
                for operation, chunk in operations:
                    target_queue.put((generation, operation, chunk))
                return
            for operation in operations:
                self._pre_roll.append(operation)
            self._speech_waiting.set()

    def _interruptible_wait(self, seconds: float) -> bool:
        return self._stop_event.wait(max(0.0, seconds))

    def _wait_for_sender_drain(self, timeout: float) -> bool:
        with self._session_lock:
            if not self._session_active:
                return False
            generation = self._session_generation
            target_queue = self.audio_queue
        barrier = threading.Event()
        target_queue.put((generation, "barrier", barrier))
        return barrier.wait(timeout)

    def _connect_for_waiting_speech(self):
        last_error = None
        for attempt in range(self._session_reconnect_limit + 1):
            if self._stop_event.is_set() or not self._speech_waiting.is_set():
                return None
            try:
                socket = self.connect_transport()
                with self._session_lock:
                    self._session_generation += 1
                    generation = self._session_generation
                    self.audio_queue = queue.Queue()
                    session_queue = self.audio_queue
                    self._session_failure = None
                    self._session_done.clear()
                    self._session_active = True
                    pending = list(self._pre_roll)
                    self._pre_roll.clear()
                    self._speech_waiting.clear()
                    for operation, chunk in pending:
                        session_queue.put((generation, operation, chunk))
                receiver = threading.Thread(
                    target=self._receiver_loop,
                    args=(generation, socket),
                    daemon=True,
                )
                sender = threading.Thread(
                    target=self._sender_loop,
                    args=(generation, socket, session_queue),
                    daemon=True,
                )
                receiver.start()
                sender.start()
                return generation, socket, sender, receiver
            except Exception as error:
                last_error = classify_doubao_error(error)
                if last_error["retry_class"] in {"fatal-auth", "fatal-provider"}:
                    raise RuntimeError(last_error["message"]) from error
                if attempt >= self._session_reconnect_limit:
                    break
                backoff_base = self._session_reconnect_backoff
                if last_error["retry_class"] == "rate-limited":
                    backoff_base *= 4
                delay = min(
                    DEFAULT_SESSION_RECONNECT_BACKOFF_CAP_SECONDS,
                    backoff_base * (2 ** attempt),
                ) * self._jitter()
                if self._interruptible_wait(delay):
                    return None
        raise RuntimeError(
            f"Doubao realtime reconnect exhausted: {(last_error or {}).get('message', 'unknown error')}"
        )

    def _finish_session(self, session):
        if not session:
            return None
        generation, socket, sender, receiver = session
        failure = self._session_failure
        with self._session_lock:
            if generation == self._session_generation:
                self._session_active = False
        try:
            self.audio_queue.put((generation, "stop", None))
        except Exception:
            pass
        try:
            socket.close()
        except Exception:
            pass
        sender.join(timeout=1.0)
        receiver.join(timeout=1.0)
        self._reset_pending_transcripts()
        self.current_text = ""
        return failure

    def run(self):
        self.connect_mindserver()
        self.running = True

        stdin_reader = threading.Thread(target=self._stdin_loop, daemon=True)
        stdin_reader.start()

        stream = None
        session = None
        try:
            stream = self._open_input_stream()
            with stream:
                while not self._stop_event.is_set():
                    if session is None:
                        if not self._speech_waiting.wait(0.1):
                            continue
                        session = self._connect_for_waiting_speech()
                        if session is None:
                            continue
                        continue
                    if not self._session_done.wait(0.1):
                        continue
                    failure = self._finish_session(session)
                    session = None
                    self.segmenter._reset()
                    with self._session_lock:
                        self._pre_roll.clear()
                        self._speech_waiting.clear()
                    if not failure:
                        continue
                    if failure["retry_class"] == "idle-expired":
                        print("[voice-mic] realtime session idle-expired; armed for the next utterance")
                        continue
                    if failure["retry_class"] in {"transient-network", "rate-limited"}:
                        print(f"[voice-mic] realtime session interrupted; armed for the next utterance: {failure['message']}")
                        continue
                    raise RuntimeError(failure["message"])
        except KeyboardInterrupt:
            pass
        finally:
            if session is not None:
                for operation in self.segmenter.flush():
                    self._queue_audio_operations([operation])
                self._wait_for_sender_drain(FINAL_TRANSCRIPT_TIMEOUT_SECONDS)
                if not self._wait_for_pending_transcripts(FINAL_TRANSCRIPT_TIMEOUT_SECONDS):
                    print("[voice-mic] timed out waiting for final transcript")
                self._finish_session(session)
            self.running = False
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
    parser.add_argument("--speech-rms-threshold", type=int, default=DEFAULT_SPEECH_RMS_THRESHOLD, help="PCM16 RMS level considered speech")
    parser.add_argument("--min-speech-ms", type=int, default=DEFAULT_MIN_SPEECH_MS, help="Speech required before an utterance starts")
    parser.add_argument("--trailing-silence-ms", type=int, default=DEFAULT_TRAILING_SILENCE_MS, help="Silence that commits the current utterance")
    parser.add_argument("--max-utterance-ms", type=int, default=DEFAULT_MAX_UTTERANCE_MS, help="Maximum duration before forced commit")
    parser.add_argument("--leading-context-ms", type=int, default=DEFAULT_LEADING_CONTEXT_MS, help="Audio retained before speech activation")
    parser.add_argument("--input-mode", default="", help="Optional dialog.extra.input_mod override")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="Doubao realtime endpoint")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Doubao realtime model version")
    parser.add_argument("--api-key", default=get_key("DOUBAO_API_KEY"), help="Doubao new-console API key")
    return parser.parse_args()


def main():
    args = parse_args()
    if not args.api_key:
        raise SystemExit("DOUBAO_API_KEY is required for duplex realtime microphone streaming.")
    bridge = DoubaoRealtimeMicBridge(args)
    bridge.run()


if __name__ == "__main__":
    main()
