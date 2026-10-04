"""Checks that the diagnostic cannot confuse HTTP success with playable music."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import wave


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "probe_music_apis.py"
PROBE = None
if SCRIPT.exists():
    spec = importlib.util.spec_from_file_location("music_api_probe", SCRIPT)
    PROBE = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(PROBE)


class ProbeResultTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(PROBE, "The reusable API probe has not been implemented")

    def test_http_200_auth_error_is_not_success(self):
        self.assertEqual(PROBE.classify_response(200, {"error_code": 152}), "requires_auth")
        self.assertEqual(PROBE.classify_response(200, {"ok": False, "error": "no_playable_url"}), "unavailable")
        self.assertEqual(PROBE.classify_response(400, {"detail": "Value of `source` is not supported."}), "unsupported")
        self.assertEqual(PROBE.classify_response(200, {"error": "需要付费", "url": ""}), "requires_auth")

    def test_guest_session_error_is_not_a_music_result(self):
        self.assertEqual(PROBE.classify_response(200, {"success": False, "message": "csrf verification failed"}), "api_error")

    def test_modern_qq_nested_error_is_not_success(self):
        data = {"code": 0, "music.search.SearchCgiService": {"code": 10000, "data": {}}}
        self.assertEqual(PROBE.classify_response(200, data), "api_error")

    def test_rejects_html_and_image_masquerading_as_audio(self):
        self.assertIsNone(PROBE.audio_signature(b"<!DOCTYPE html><title>Error</title>"))
        self.assertIsNone(PROBE.audio_signature(b'{"code": 200, "url": ""}'))
        self.assertIsNone(PROBE.audio_signature(b"RIFF0000WEBPpayload"))
        self.assertEqual(PROBE.audio_signature(b"ID3\x04\x00\x00"), "mp3")
        self.assertEqual(PROBE.audio_signature(b"fLaC\x00\x00"), "flac")

    def test_report_does_not_expose_signed_query(self):
        value = PROBE.url_label("https://cdn.example/song.mp3?vkey=private&token=private&uin=42")
        self.assertEqual(value, "https://cdn.example/song.mp3")
        self.assertNotIn("private", value)

    def test_audio_report_also_redacts_signatures_in_path(self):
        value = PROBE.url_label("https://kw.example/private-signature/expiry/song.mp3?token=private", media=True)
        self.assertEqual(value, "https://kw.example/<audio>.mp3")

    def test_legacy_form_post_matches_the_anonymous_protocol(self):
        class Reply(io.BytesIO):
            status = 200
            headers = {"Content-Type": "application/json"}

        class Capture:
            request = None

            def open(self, request, timeout):
                self.request = request
                return Reply(b'{"url":""}')

        transport = PROBE.Transport("", 2, 0)
        capture = Capture()
        transport.opener = capture
        transport.fetch("http://m.kugou.com/app/i/getSongInfo.php", payload={"cmd": "playInfo", "hash": "abc", "from": "mkugou"}, form=True)
        self.assertEqual(capture.request.get_method(), "POST")
        self.assertEqual(capture.request.get_header("Content-type"), "application/x-www-form-urlencoded")
        self.assertEqual(capture.request.data, b"cmd=playInfo&hash=abc&from=mkugou")
        self.assertIsNone(capture.request.get_header("Cookie"))

    def test_jsonp_is_parsed_as_data_without_executing_code(self):
        self.assertEqual(PROBE.parse_payload(b'MusicJsonCallback({"code":0,"data":[]});'), {"code": 0, "data": []})
        with self.assertRaises(ValueError):
            PROBE.parse_payload(b'alert("not json")')

    def test_private_redirect_targets_are_rejected(self):
        for url in ("file:///C:/secret", "http://127.0.0.1:8080/", "http://[::1]/", "https://localhost/", "https://u:p@public.example/"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                PROBE.validate_url(url)
        PROBE.validate_url("https://music.yuncan.xyz/api")


@unittest.skipUnless(os.environ.get("FLOATMUSIC_QT_PROBE"), "Optional compiled Qt playback probe")
class QtPlaybackProbeTests(unittest.TestCase):
    def fixture_directory(self):
        root = SCRIPT.parents[1].resolve()
        parent = (root / "artifacts" / "music-api-probes" / "fixtures").resolve()
        self.assertTrue(parent.is_relative_to(root))
        parent.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=parent)
        self.assertTrue(Path(directory.name).resolve().is_relative_to(parent))
        return directory

    def run_probe(self, path):
        executable = Path(os.environ["FLOATMUSIC_QT_PROBE"])
        self.assertTrue(executable.is_file(), "The Qt playback probe has not been built")
        result = subprocess.run([str(executable)], input=json.dumps({"url": path.as_uri(), "timeout_ms": 5000}), text=True, capture_output=True, timeout=8)
        return json.loads(result.stdout)

    def test_real_decoded_pcm_and_pause_seek_resume(self):
        with self.fixture_directory() as directory:
            path = Path(directory) / "fixture.wav"
            with wave.open(str(path), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(48000)
                wav.writeframes(b"\0\0" * 48000 * 2)
            result = self.run_probe(path)
            self.assertTrue(result["decoded"])
            self.assertTrue(result["paused"])
            self.assertTrue(result["resumed"])
            self.assertTrue(result["seek_ok"])
            self.assertGreater(result["decoded_frames"], 0)

    def test_html_file_is_not_decodable_music(self):
        with self.fixture_directory() as directory:
            path = Path(directory) / "error.mp3"
            path.write_text("<html>not music</html>", encoding="utf-8")
            result = self.run_probe(path)
            self.assertFalse(result["decoded"])


if __name__ == "__main__":
    unittest.main()
