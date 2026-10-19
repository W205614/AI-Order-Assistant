"""Exercise backup.sh with a fake mysqldump and temporary storage only."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHELL = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
         if os.name == "nt" else "/bin/sh")


class BackupScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ai-order-shell-test-")
        self.base = Path(self.temp.name)
        self.primary = self.base / "primary"
        self.extra = self.base / "extra"
        self.health = self.base / "health"
        self.bin = self.base / "bin"
        for directory in (self.primary, self.extra, self.health, self.bin):
            directory.mkdir()
        fake = self.bin / "mysqldump"
        fake.write_text("#!/bin/sh\nif [ \"${FAKE_DUMP_FAIL:-}\" = 1 ]; then exit 7; fi\nprintf '%s\\n' '-- fixture' '-- Dump completed'\n", encoding="utf-8")
        fake.chmod(0o755)
        self.env = dict(os.environ, MYSQL_ROOT_PASSWORD="fixture-only", BACKUP_DIRECTORY=self.primary.as_posix(),
                        BACKUP_HEALTH_DIRECTORY=self.health.as_posix(), BACKUP_EXTRA_MOUNT=self.extra.as_posix(),
                        BACKUP_EXTRA_ENABLED="true")
        # Git Bash imports Windows PATH lists at startup; Unix sh uses ':' lists.
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env["PATH"]

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, **overrides):
        return subprocess.run([SHELL, (ROOT / "deploy/backup.sh").as_posix()], env=dict(self.env, **overrides),
                              capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=45)

    def state(self):
        return json.loads((self.primary / "backup-status.json").read_text(encoding="utf-8"))

    def test_verified_extra_copy_and_failure_preserves_success(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.state()
        self.assertEqual(state["status"], "ok")
        self.assertTrue(state["extraCopyVerified"])
        source, copy = self.primary / state["backupFile"], self.extra / state["backupFile"]
        self.assertEqual(source.read_bytes(), copy.read_bytes())
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), state["sha256"])
        self.assertEqual(Path(str(copy) + ".sha256").read_text().strip(), state["sha256"])
        self.assertIn("ai_order_backup_last_attempt_success 1\n", (self.health / "backup.prom").read_text())
        if os.name != "nt":
            self.assertEqual(self.health.stat().st_mode & 0o777, 0o755)
            self.assertEqual((self.health / "backup.prom").stat().st_mode & 0o777, 0o644)
            self.assertEqual(source.stat().st_mode & 0o777, 0o600)
        result = self.run_script(FAKE_DUMP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        failed = self.state()
        self.assertEqual(failed["status"], "failed")
        self.assertEqual(failed["lastSuccessAt"], state["lastSuccessAt"])
        self.assertIn("ai_order_backup_last_attempt_success 0\n", (self.health / "backup.prom").read_text())
        self.assertFalse((self.primary / ".backup-lock").exists())
        self.assertFalse(list(self.primary.glob("*.tmp")))

    def test_extra_copy_failure_is_not_success(self):
        blocked = self.base / "not-a-directory"
        blocked.write_text("fixture", encoding="utf-8")
        result = self.run_script(BACKUP_EXTRA_MOUNT=blocked.as_posix())
        self.assertNotEqual(result.returncode, 0)
        state = self.state()
        self.assertEqual(state["status"], "failed")
        self.assertEqual(state["lastSuccessAt"], 0)
        self.assertFalse(state["extraCopyVerified"])
        self.assertEqual(len(list(self.primary.glob("*.sql"))), 1, "Valid primary dump must remain available")

    def test_retention_preserves_unverified_old_backup(self):
        for index in range(9):
            path = self.primary / f"orders-fixture-{index}.sql"
            path.write_text(str(index), encoding="utf-8")
            Path(str(path) + ".sha256").write_text(hashlib.sha256(path.read_bytes()).hexdigest())
            os.utime(path, (1000 + index, 1000 + index))
        unverified = self.primary / "orders-unverified.sql"
        unverified.write_text("must survive")
        os.utime(unverified, (1, 1))
        blocked = self.base / "failed-extra-directory"
        blocked.write_text("fixture", encoding="utf-8")
        for _ in range(3):
            result = self.run_script(BACKUP_EXTRA_MOUNT=blocked.as_posix())
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(unverified.exists())
            self.assertEqual(len(list(self.primary.glob("orders-*.sql"))), 8)
            self.assertEqual(self.state()["status"], "failed")
        self.assertFalse(self.state()["extraCopyVerified"])

    def test_lock_rejects_overlap_without_overwriting_active_status(self):
        (self.primary / ".backup-lock").mkdir()
        sentinel = '{"active":"unchanged"}'
        (self.primary / "backup-status.json").write_text(sentinel)
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.primary / "backup-status.json").read_text(), sentinel)
        self.assertTrue((self.primary / ".backup-lock").exists(), "Another invocation owns this lock")

    def test_extra_directory_alias_cannot_count_as_independent_copy(self):
        alias = self.primary.as_posix() + "/../primary"
        result = self.run_script(BACKUP_EXTRA_MOUNT=alias)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["status"], "failed")
        self.assertFalse(self.state()["extraCopyVerified"])


if __name__ == "__main__":
    if not Path(SHELL).is_file():
        raise SystemExit("A POSIX shell is required (Git Bash on Windows)")
    unittest.main(verbosity=2)
