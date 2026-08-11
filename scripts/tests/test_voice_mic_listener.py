import queue
import struct
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace

from scripts.voice_mic_listener import (
    DEFAULT_ENDPOINT,
    DEFAULT_MODEL,
    DEFAULT_MINDSERVER_HOSTS,
    DoubaoRealtimeMicBridge,
    Pcm16SilenceSegmenter,
    build_session_event,
    build_transcript_payload,
    classify_doubao_error,
    decode_frame,
    encode_json_event,
    pcm16_rms,
    read_project_env,
)


def pcm_chunk(amplitude, duration_ms=20, sample_rate=16000):
    samples = int(sample_rate * duration_ms / 1000)
    return struct.pack(f"<{samples}h", *([amplitude] * samples))


class VoiceMicListenerTests(unittest.TestCase):
    def make_bridge(self, **overrides):
        class FakeSio:
            def connect(self, *_args, **_kwargs):
                return None

            def emit(self, *_args, **_kwargs):
                return None

            def disconnect(self):
                return None

        settings = {
            "agent": "MineAIZH",
            "speaker_id": "mic_user",
            "sample_rate": 16000,
            "speech_rms_threshold": 500,
            "min_speech_ms": 40,
            "trailing_silence_ms": 40,
            "max_utterance_ms": 200,
            "chunk_ms": 20,
            "api_key": "test-key",
            "endpoint": DEFAULT_ENDPOINT,
            "model": DEFAULT_MODEL,
            "input_mode": "",
            "session_reconnect_limit": 2,
            "session_reconnect_backoff_seconds": 0.01,
            "jitter": lambda: 1.0,
            "sio_factory": FakeSio,
        }
        settings.update(overrides)
        return DoubaoRealtimeMicBridge(SimpleNamespace(**settings))

    def make_segmenter(self, **overrides):
        settings = {
            "sample_rate": 16000,
            "speech_rms_threshold": 500,
            "min_speech_ms": 40,
            "trailing_silence_ms": 40,
            "max_utterance_ms": 200,
        }
        settings.update(overrides)
        return Pcm16SilenceSegmenter(**settings)

    def test_build_transcript_payload_marks_microphone_source(self):
        payload = build_transcript_payload("follow me", "mic_user", {"transport": "local-mic"})
        self.assertEqual(payload["text"], "follow me")
        self.assertEqual(payload["speakerId"], "mic_user")
        self.assertEqual(payload["source"], "mic")

    def test_json_event_round_trip(self):
        event = {"type": "input_audio_buffer.append", "audio": "AQID"}
        self.assertEqual(decode_frame(encode_json_event(event)), event)

    def test_project_env_reader_handles_comments_and_quoted_values(self):
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text('# comment\nDOUBAO_API_KEY="project-key"\n', encoding="utf-8")
            with patch("scripts.voice_mic_listener.PROJECT_ROOT", Path(directory)):
                self.assertEqual(read_project_env("DOUBAO_API_KEY"), "project-key")

    def test_session_event_uses_new_duplex_contract(self):
        event = build_session_event(DEFAULT_MODEL, 16000)
        self.assertEqual(event["type"], "session.create")
        self.assertEqual(event["session"]["model"], "1.2.6.1")
        self.assertEqual(event["session"]["audio"]["input"]["format"]["rate"], 16000)
        self.assertIn("豆包", event["session"]["instructions"])
        self.assertIn("两组", event["session"]["instructions"])
        self.assertIn("钻石", event["session"]["instructions"])

    def test_playback_state_resets_audio_and_applies_cooldown(self):
        bridge = self.make_bridge()
        bridge.segmenter.process(pcm_chunk(1000))
        bridge._pre_roll.append(("append", pcm_chunk(1000)))
        bridge._speech_waiting.set()
        bridge._on_playback_state({"active": True})
        self.assertTrue(bridge._is_playback_suppressed())
        self.assertEqual(len(bridge._pre_roll), 0)
        self.assertFalse(bridge._speech_waiting.is_set())
        bridge._on_playback_state({"active": False, "cooldownMs": 20})
        self.assertTrue(bridge._is_playback_suppressed())
        time.sleep(0.03)
        self.assertFalse(bridge._is_playback_suppressed())

    def test_default_endpoint_is_new_duplex_endpoint(self):
        self.assertEqual(DEFAULT_ENDPOINT, "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue")

    def test_default_mindserver_hosts_try_localhost_first(self):
        self.assertEqual(DEFAULT_MINDSERVER_HOSTS[0], "localhost")
        self.assertIn("127.0.0.1", DEFAULT_MINDSERVER_HOSTS)

    def test_transcript_emit_uses_two_socketio_arguments(self):
        class FakeSocket:
            def __init__(self):
                self.calls = []

            def emit(self, event, data):
                self.calls.append((event, data))

        bridge = DoubaoRealtimeMicBridge.__new__(DoubaoRealtimeMicBridge)
        bridge.args = SimpleNamespace(agent="MineAIZH", speaker_id="mic_user")
        bridge.sio = FakeSocket()
        payload = build_transcript_payload("hello", "mic_user", {"transport": "local-mic"})
        bridge.sio.emit("voice-transcript", ("MineAIZH", payload))

        self.assertEqual(bridge.sio.calls[0][0], "voice-transcript")
        self.assertEqual(bridge.sio.calls[0][1][0], "MineAIZH")
        self.assertEqual(bridge.sio.calls[0][1][1]["text"], "hello")

    def test_pcm16_rms_uses_sample_amplitude(self):
        self.assertEqual(pcm16_rms(pcm_chunk(1200)), 1200)
        self.assertEqual(pcm16_rms(b""), 0)

    def test_short_noise_is_dropped_without_append_or_commit(self):
        segmenter = self.make_segmenter(min_speech_ms=60)

        self.assertEqual(segmenter.process(pcm_chunk(1000)), [])
        self.assertEqual(segmenter.process(pcm_chunk(0)), [])
        self.assertEqual(segmenter.flush(), [])

    def test_speech_is_buffered_until_minimum_then_committed_after_silence(self):
        segmenter = self.make_segmenter()

        self.assertEqual(segmenter.process(pcm_chunk(1000)), [])
        started = segmenter.process(pcm_chunk(1000))
        self.assertEqual([operation for operation, _ in started], ["append", "append"])
        self.assertEqual(segmenter.process(pcm_chunk(0))[0][0], "append")
        finished = segmenter.process(pcm_chunk(0))
        self.assertEqual([operation for operation, _ in finished], ["append", "commit"])

    def test_leading_context_is_sent_before_first_speech_chunks(self):
        segmenter = self.make_segmenter(leading_context_ms=40)
        quiet_one = pcm_chunk(0)
        quiet_two = pcm_chunk(0)
        speech_one = pcm_chunk(1000)
        speech_two = pcm_chunk(1100)

        self.assertEqual(segmenter.process(quiet_one), [])
        self.assertEqual(segmenter.process(quiet_two), [])
        self.assertEqual(segmenter.process(speech_one), [])
        started = segmenter.process(speech_two)

        self.assertEqual([operation for operation, _ in started], ["append"] * 4)
        self.assertEqual([chunk for _, chunk in started], [quiet_one, quiet_two, speech_one, speech_two])

    def test_segmenter_resets_for_multiple_utterances(self):
        segmenter = self.make_segmenter(min_speech_ms=20, trailing_silence_ms=20)

        first = segmenter.process(pcm_chunk(1000)) + segmenter.process(pcm_chunk(0))
        second = segmenter.process(pcm_chunk(1000)) + segmenter.process(pcm_chunk(0))

        self.assertEqual([item[0] for item in first], ["append", "append", "commit"])
        self.assertEqual([item[0] for item in second], ["append", "append", "commit"])

    def test_maximum_utterance_forces_commit(self):
        segmenter = self.make_segmenter(min_speech_ms=20, max_utterance_ms=60)

        operations = []
        for _ in range(3):
            operations.extend(segmenter.process(pcm_chunk(1000)))

        self.assertEqual([item[0] for item in operations], ["append", "append", "append", "commit"])

    def test_flush_commits_only_an_active_utterance(self):
        segmenter = self.make_segmenter(min_speech_ms=20)

        segmenter.process(pcm_chunk(1000))
        self.assertEqual(segmenter.flush(), [("commit", None)])
        self.assertEqual(segmenter.flush(), [])

    def test_pending_transcript_wait_tracks_commit_completion(self):
        bridge = DoubaoRealtimeMicBridge.__new__(DoubaoRealtimeMicBridge)
        bridge._transcript_condition = threading.Condition()
        bridge._pending_transcripts = 0

        bridge._record_commit_sent()
        self.assertFalse(bridge._wait_for_pending_transcripts(0))
        bridge._record_transcript_completed()
        self.assertTrue(bridge._wait_for_pending_transcripts(0))

    def test_nested_no_audio_provider_error_is_idle_expired(self):
        classified = classify_doubao_error({
            "type": "error",
            "error": {
                "code": "55000000",
                "message": "sami error: codes=52000033, desc=AudioServerNoAudioInputTooLongError",
            },
        })

        self.assertEqual(classified["outer_code"], "55000000")
        self.assertEqual(classified["inner_code"], "52000033")
        self.assertEqual(classified["retry_class"], "idle-expired")

    def test_unknown_provider_error_is_fatal_by_default(self):
        classified = classify_doubao_error({
            "type": "error",
            "error": {"code": "55000001", "message": "unexpected provider failure"},
        })

        self.assertEqual(classified["retry_class"], "fatal-provider")

    def test_auth_error_is_not_retried(self):
        classified = classify_doubao_error({
            "type": "error",
            "error": {"code": "403", "message": "forbidden"},
        })

        self.assertEqual(classified["retry_class"], "fatal-auth")

    def test_inactive_session_buffers_bounded_pre_roll_and_arms_speech(self):
        bridge = self.make_bridge(max_utterance_ms=100, chunk_ms=20)
        operations = [("append", pcm_chunk(1000)) for _ in range(20)]

        bridge._queue_audio_operations(operations)

        self.assertTrue(bridge._speech_waiting.is_set())
        self.assertEqual(len(bridge._pre_roll), bridge._pre_roll.maxlen)
        self.assertIsNone(bridge.socket)

    def test_idle_expiry_arms_without_stopping_process_or_reconnecting(self):
        bridge = self.make_bridge()
        bridge.running = True
        bridge._session_generation = 4
        bridge._session_active = True
        idle_frame = {
            "type": "error",
            "error": {
                "code": "55000000",
                "message": "codes=52000033, desc=AudioServerNoAudioInputTooLongError",
            },
        }

        bridge._fail_session(4, idle_frame)

        self.assertTrue(bridge._session_done.is_set())
        self.assertEqual(bridge._session_failure["retry_class"], "idle-expired")
        self.assertFalse(bridge._stop_event.is_set())
        self.assertFalse(bridge._speech_waiting.is_set())

    def test_sender_preserves_append_commit_order(self):
        class FakeSocket:
            def __init__(self):
                self.frames = []

            def send(self, frame):
                self.frames.append(decode_frame(frame))

        bridge = self.make_bridge()
        bridge.socket = FakeSocket()
        bridge.audio_queue = queue.Queue()
        bridge.running = True
        bridge._session_generation = 1
        bridge._session_active = True
        bridge.audio_queue.put(("append", b"\x01\x00"))
        bridge.audio_queue.put(("commit", None))
        bridge.audio_queue.put(("stop", None))

        bridge._sender_loop()

        self.assertEqual(
            [frame["type"] for frame in bridge.socket.frames],
            ["input_audio_buffer.append", "input_audio_buffer.commit"],
        )
        self.assertEqual(bridge._pending_transcripts, 1)

    def test_sender_drain_waits_until_commit_has_been_sent(self):
        class FakeSocket:
            def send(self, _frame):
                return None

        bridge = self.make_bridge()
        bridge.socket = FakeSocket()
        bridge.running = True
        bridge._session_generation = 1
        bridge._session_active = True
        bridge.audio_queue.put((1, "commit", None))
        sender = threading.Thread(target=bridge._sender_loop)
        sender.start()

        self.assertTrue(bridge._wait_for_sender_drain(1))
        self.assertEqual(bridge._pending_transcripts, 1)
        bridge.audio_queue.put((1, "stop", None))
        sender.join(timeout=1)

    def test_sender_append_failure_converges_to_session_failure(self):
        def fail_send(_frame):
            raise OSError("closed")

        bridge = self.make_bridge()
        bridge.socket = SimpleNamespace(send=fail_send)
        bridge.audio_queue = queue.Queue()
        bridge.running = True
        bridge._session_generation = 1
        bridge._session_active = True
        bridge.audio_queue.put(("append", b"\x01\x00"))
        bridge.audio_queue.put(("stop", None))

        bridge._sender_loop()

        self.assertTrue(bridge._session_done.is_set())
        self.assertEqual(bridge._session_failure["retry_class"], "transient-network")
        self.assertFalse(bridge._stop_event.is_set())

    def test_buffered_speech_triggers_one_session_and_flushes_pre_roll(self):
        class FakeSessionSocket:
            def __init__(self):
                self.frames = []
                self.responses = queue.Queue()
                self.responses.put(encode_json_event({"type": "session.created"}))
                self.closed = False

            def send(self, frame):
                self.frames.append(decode_frame(frame))

            def recv(self):
                response = self.responses.get(timeout=1)
                if isinstance(response, Exception):
                    raise response
                return response

            def settimeout(self, _timeout):
                return None

            def close(self):
                if not self.closed:
                    self.closed = True
                    self.responses.put(OSError("closed"))

        sockets = []

        def socket_factory(*_args, **_kwargs):
            socket = FakeSessionSocket()
            sockets.append(socket)
            return socket

        bridge = self.make_bridge(socket_factory=socket_factory)
        bridge.running = True
        bridge._queue_audio_operations([("append", b"\x01\x00")])

        session = bridge._connect_for_waiting_speech()
        deadline = time.time() + 1
        while len(sockets[0].frames) < 2 and time.time() < deadline:
            time.sleep(0.005)

        self.assertEqual(len(sockets), 1)
        self.assertEqual(session[0], 1)
        self.assertFalse(bridge._speech_waiting.is_set())
        self.assertEqual(list(bridge._pre_roll), [])
        self.assertEqual(
            [frame["type"] for frame in sockets[0].frames[:2]],
            ["session.create", "input_audio_buffer.append"],
        )
        bridge._finish_session(session)

    def test_stale_generation_audio_is_not_sent(self):
        class FakeSocket:
            def __init__(self):
                self.frames = []

            def send(self, frame):
                self.frames.append(decode_frame(frame))

        bridge = self.make_bridge()
        socket = FakeSocket()
        operations = queue.Queue()
        bridge.running = True
        bridge._session_generation = 2
        bridge._session_active = True
        operations.put((1, "append", b"\x01\x00"))
        operations.put((2, "append", b"\x02\x00"))
        operations.put((2, "stop", None))

        bridge._sender_loop(2, socket, operations)

        self.assertEqual(len(socket.frames), 1)
        self.assertEqual(socket.frames[0]["type"], "input_audio_buffer.append")

    def test_receiver_socketio_emit_failure_converges_to_session_failure(self):
        completed = encode_json_event({
            "type": "input_audio_buffer.transcription.completed",
            "transcript": "你好",
        })

        class FakeSocket:
            def recv(self):
                return completed

        class FailingSio:
            def emit(self, _event, _payload):
                raise OSError("mindserver disconnected")

        bridge = self.make_bridge()
        bridge.sio = FailingSio()
        bridge.running = True
        bridge._session_generation = 3
        bridge._session_active = True

        bridge._receiver_loop(3, FakeSocket())

        self.assertTrue(bridge._session_done.is_set())
        self.assertEqual(bridge._session_failure["retry_class"], "fatal-provider")
        self.assertIn("MindServer transcript emit failed", bridge._session_failure["message"])

    def test_stop_interrupts_connection_backoff(self):
        attempts = []

        def fail_connect(*_args, **_kwargs):
            attempts.append(1)
            raise OSError("network down")

        bridge = self.make_bridge(
            socket_factory=fail_connect,
            session_reconnect_limit=10,
            session_reconnect_backoff_seconds=10,
        )
        bridge._speech_waiting.set()
        result = []
        worker = threading.Thread(target=lambda: result.append(bridge._connect_for_waiting_speech()))
        worker.start()
        deadline = time.time() + 1
        while not attempts and time.time() < deadline:
            time.sleep(0.005)
        bridge._stop_event.set()
        worker.join(timeout=1)

        self.assertFalse(worker.is_alive())
        self.assertEqual(result, [None])
        self.assertEqual(len(attempts), 1)

    def test_rate_limit_uses_longer_interruptible_backoff(self):
        class RateLimitError(OSError):
            status_code = 429

        def fail_connect(*_args, **_kwargs):
            raise RateLimitError("too many requests")

        bridge = self.make_bridge(
            socket_factory=fail_connect,
            session_reconnect_limit=2,
            session_reconnect_backoff_seconds=0.5,
        )
        delays = []
        bridge._interruptible_wait = lambda seconds: delays.append(seconds) or True
        bridge._speech_waiting.set()

        self.assertIsNone(bridge._connect_for_waiting_speech())
        self.assertEqual(delays, [2.0])


if __name__ == "__main__":
    unittest.main()
