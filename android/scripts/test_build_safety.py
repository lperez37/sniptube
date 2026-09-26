"""Regression tests for the disk-full incident and artifact preservation."""
import hashlib
import os
import signal
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import disk_guard
import preserve_apk


class DiskGuardTests(unittest.TestCase):
    def test_low_space_prevents_command_start(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "must-not-exist"
            code, reason = disk_guard.run_guarded(
                [sys.executable, "-c", f"open({str(marker)!r}, 'w').close()"], tmp,
                minimum=100, probe=lambda _: 99, interval=0.01,
            )
            self.assertEqual((code, reason), (75, "low_disk"))
            self.assertFalse(marker.exists())

    def test_low_space_during_work_terminates_actual_child(self):
        samples = iter([101, 101, 99])
        heartbeats = []
        code, reason = disk_guard.run_guarded(
            [sys.executable, "-c", "import time; time.sleep(60)"], ".",
            minimum=100, probe=lambda _: next(samples), tick=heartbeats.append,
            interval=0.02, grace=0.1,
        )
        self.assertEqual((code, reason), (75, "low_disk"))
        pid = next(item["child_pid"] for item in heartbeats if item["child_pid"])
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        self.assertEqual(heartbeats[-1]["reason"], "low_disk")

    def test_deadline_stops_child_and_preserves_original_signal_handlers(self):
        original = signal.getsignal(signal.SIGTERM)
        code, reason = disk_guard.run_guarded(
            [sys.executable, "-c", "import time; time.sleep(60)"], ".",
            probe=lambda _: 1000, minimum=100, interval=0.01,
            timeout=0.05, grace=0.1,
        )
        self.assertEqual((code, reason), (124, "time_limit"))
        self.assertEqual(signal.getsignal(signal.SIGTERM), original)

    def test_command_exit_code_is_preserved(self):
        code, reason = disk_guard.run_guarded(
            [sys.executable, "-c", "raise SystemExit(7)"], ".",
            minimum=100, probe=lambda _: 1000, interval=0.01,
        )
        self.assertEqual((code, reason), (7, "exited"))


class ArtifactTests(unittest.TestCase):
    def test_invalid_or_missing_new_apk_never_removes_previous(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "app/build/outputs/apk/debug/app-debug.apk"
            source.parent.mkdir(parents=True)
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("AndroidManifest.xml", "test fixture only")
            saved = preserve_apk.preserve(root)
            assert saved is not None
            digest = hashlib.sha256(saved.read_bytes()).hexdigest()
            source.unlink()
            self.assertIsNone(preserve_apk.preserve(root))
            self.assertTrue(saved.exists())
            source.write_bytes(b"interrupted download")
            with self.assertRaises(zipfile.BadZipFile):
                preserve_apk.preserve(root)
            self.assertEqual(hashlib.sha256(saved.read_bytes()).hexdigest(), digest)


if __name__ == "__main__":
    unittest.main()
