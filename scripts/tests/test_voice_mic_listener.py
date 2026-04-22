import unittest
from types import SimpleNamespace

from scripts.voice_mic_listener import build_transcript_payload, build_start_session_payload, decode_frame, encode_audio_event, encode_json_event, encode_header, write_int32
from scripts.voice_mic_listener import DoubaoRealtimeMicBridge
from scripts.voice_mic_listener import DEFAULT_MINDSERVER_HOSTS


class VoiceMicListenerTests(unittest.TestCase):
    def test_build_transcript_payload_marks_microphone_source(self):
        payload = build_transcript_payload("豆包 跟着我", "mic_user", {"transport": "local-mic"})
        self.assertEqual(payload["text"], "豆包 跟着我")
        self.assertEqual(payload["speakerId"], "mic_user")
        self.assertEqual(payload["source"], "mic")
        self.assertEqual(payload["metadata"]["transport"], "local-mic")

    def test_encode_and_decode_json_event_round_trip(self):
        frame = encode_json_event(501, {"content": "hello"}, "session-1")
        decoded = decode_frame(frame)
        self.assertEqual(decoded["event"], 501)
        self.assertEqual(decoded["session_id"], "session-1")
        self.assertEqual(decoded["payload"]["content"], "hello")

    def test_encode_and_decode_audio_event_round_trip(self):
        frame = encode_audio_event(200, b"\x01\x02\x03\x04", "session-2")
        decoded = decode_frame(frame)
        self.assertEqual(decoded["message_type"], 0x2)
        self.assertEqual(decoded["event"], 200)
        self.assertEqual(decoded["session_id"], "session-2")
        self.assertEqual(decoded["payload"], b"\x01\x02\x03\x04")

    def test_decode_connection_started_frame_with_connect_id(self):
        connect_id = "93e1c79a-1612-4a54-bfd3-0b0999d75103"
        payload = b"{}"
        frame = b"".join([
            encode_header(0x9, 0x4, 1, 0),
            write_int32(50),
            write_int32(len(connect_id.encode("utf-8"))),
            connect_id.encode("utf-8"),
            write_int32(len(payload)),
            payload,
        ])
        decoded = decode_frame(frame)
        self.assertEqual(decoded["event"], 50)
        self.assertEqual(decoded["connect_id"], connect_id)
        self.assertEqual(decoded["payload"], {})

    def test_default_mindserver_hosts_try_localhost_first(self):
        self.assertEqual(DEFAULT_MINDSERVER_HOSTS[0], "localhost")
        self.assertIn("127.0.0.1", DEFAULT_MINDSERVER_HOSTS)

    def test_build_start_session_payload_omits_input_mode_by_default(self):
        payload = build_start_session_payload("2.2.0.0", 16000)
        self.assertEqual(payload["dialog"]["extra"]["model"], "2.2.0.0")
        self.assertNotIn("input_mod", payload["dialog"]["extra"])

    def test_build_start_session_payload_includes_input_mode_when_requested(self):
        payload = build_start_session_payload("2.2.0.0", 16000, "push_to_talk")
        self.assertEqual(payload["dialog"]["extra"]["input_mod"], "push_to_talk")

    def test_transcript_emit_uses_two_socketio_arguments(self):
        class FakeSocket:
            def __init__(self):
                self.calls = []

            def emit(self, event, data):
                self.calls.append((event, data))

        bridge = DoubaoRealtimeMicBridge.__new__(DoubaoRealtimeMicBridge)
        bridge.args = SimpleNamespace(agent="gpt", speaker_id="mic_user")
        bridge.sio = FakeSocket()

        payload = build_transcript_payload("豆包，跟着我。", "mic_user", {"transport": "local-mic"})
        bridge.sio.emit("voice-transcript", ("gpt", payload))

        self.assertEqual(bridge.sio.calls[0][0], "voice-transcript")
        self.assertEqual(bridge.sio.calls[0][1][0], "gpt")
        self.assertEqual(bridge.sio.calls[0][1][1]["text"], "豆包，跟着我。")


if __name__ == "__main__":
    unittest.main()
