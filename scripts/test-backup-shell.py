"""Exercise backup.sh with a fake mysqldump and temporary storage only."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHELL = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
         if os.name == "nt" else "/bin/sh")


@unittest.skipIf(os.name == "nt", "Run Linux backup shell tests in a disposable Linux container")
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
        fake.write_text('''#!/bin/sh
if [ "${FAKE_DUMP_FAIL:-}" = 1 ]; then exit 7; fi
if [ "${FAKE_REPLACE_LOCK_OWNER:-}" = 1 ]; then printf replacement > "$BACKUP_DIRECTORY/.backup-lock/owner"; fi
if [ "${FAKE_DUMP_HANG:-}" = 1 ]; then
    trap '' TERM
    printf '%s' "$$" > "$FAKE_WORKER_PID"
    while :; do printf x >> "$FAKE_WORKER_TRACE"; /usr/bin/sleep 0.05; done
fi
printf '%s\\n' '-- fixture' '-- Dump completed'
''', encoding="utf-8")
        fake.chmod(0o755)
        self.fake("mysql", '''#!/bin/sh
count=0
[ ! -f "$FAKE_READY_COUNT_FILE" ] || count=$(cat "$FAKE_READY_COUNT_FILE")
count=$((count + 1))
printf '%s' "$count" > "$FAKE_READY_COUNT_FILE"
if [ "${FAKE_READY_FAIL:-}" = 1 ] || [ "$count" -le "${FAKE_READY_FAILURES:-0}" ]; then exit 1; fi
printf '1\\n'
''')
        # Use real GNU timeout; hanging fixtures below verify termination, not
        # merely that timeout options appeared in a command string.
        self.fake("sleep", '''#!/bin/sh
if [ -n "${FAKE_SLEEP_TRACE:-}" ]; then
    printf '%s\\n' "$1" >> "$FAKE_SLEEP_TRACE"
    count=$(wc -l < "$FAKE_SLEEP_TRACE")
    if [ "$count" -ge "${FAKE_SLEEP_STOP_AFTER:-100}" ]; then exit 99; fi
    exit 0
fi
if [ "${FAKE_SKIP_SLEEP:-}" = 1 ]; then exit 0; fi
exec /usr/bin/sleep "$@"
''')
        self.env = dict(os.environ, MYSQL_ROOT_PASSWORD="fixture-only", BACKUP_DIRECTORY=self.primary.as_posix(),
                        BACKUP_HEALTH_DIRECTORY=self.health.as_posix(), BACKUP_EXTRA_MOUNT=self.extra.as_posix(),
                        BACKUP_EXTRA_ENABLED="true", FAKE_READY_COUNT_FILE=(self.base / "ready-count").as_posix())
        # Git Bash imports Windows PATH lists at startup; Unix sh uses ':' lists.
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env["PATH"]

    def tearDown(self):
        self.temp.cleanup()

    def fake(self, name, content):
        path = self.bin / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)
        return path

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

    def test_dns_or_database_delay_is_retried_before_dump(self):
        result = self.run_script(FAKE_READY_FAILURES="2", FAKE_SKIP_SLEEP="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.base / "ready-count").read_text(), "3")
        self.assertEqual(self.state()["status"], "ok")
        self.assertEqual(len(list(self.primary.glob("*.sql"))), 1)

    def test_readiness_deadline_keeps_failure_visible_and_recovers(self):
        self.assertEqual(self.run_script().returncode, 0)
        good = self.state()
        result = self.run_script(FAKE_READY_FAIL="1", BACKUP_READY_TIMEOUT_SECONDS="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("readiness deadline", result.stderr)
        self.assertEqual(self.state()["lastSuccessAt"], good["lastSuccessAt"])
        self.assertEqual(self.state()["status"], "failed")
        self.assertTrue((self.primary / good["backupFile"]).is_file())
        self.assertFalse((self.primary / ".backup-lock").exists())
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.state()["status"], "ok")

    def assert_worker_stopped(self, trace):
        before = trace.read_bytes()
        time.sleep(0.3)
        self.assertEqual(trace.read_bytes(), before, "Worker kept writing after backup released its lock")
        self.assertFalse((self.primary / ".backup-lock").exists())
        self.assertFalse(list(self.primary.glob("*.tmp")))
        self.assertFalse(list(self.extra.glob("*.tmp")))

    def test_dump_timeout_kills_term_resistant_worker_then_recovers(self):
        self.assertEqual(self.run_script().returncode, 0)
        good = self.state()
        trace = self.base / "dump-heartbeat"
        started = time.monotonic()
        result = self.run_script(FAKE_DUMP_HANG="1", BACKUP_DUMP_TIMEOUT_SECONDS="1",
                                 FAKE_WORKER_PID=(self.base / "dump-pid").as_posix(),
                                 FAKE_WORKER_TRACE=trace.as_posix())
        self.assertNotEqual(result.returncode, 0)
        self.assertLess(time.monotonic() - started, 10)
        self.assertEqual(self.state()["status"], "failed")
        self.assertEqual(self.state()["lastSuccessAt"], good["lastSuccessAt"])
        self.assertTrue((self.primary / good["backupFile"]).exists())
        self.assert_worker_stopped(trace)
        self.assertEqual(self.run_script().returncode, 0)

    def test_copy_timeout_stops_descendant_and_preserves_last_success(self):
        self.assertEqual(self.run_script().returncode, 0)
        good = self.state()
        trace = self.base / "copy-heartbeat"
        self.fake("cp", '''#!/bin/sh
if [ "${FAKE_COPY_HANG:-}" = 1 ]; then
    trap '' TERM
    while :; do printf x >> "$FAKE_WORKER_TRACE"; /usr/bin/sleep 0.05; done
fi
exec /bin/cp "$@"
''')
        result = self.run_script(FAKE_COPY_HANG="1", BACKUP_COPY_TIMEOUT_SECONDS="1",
                                 FAKE_WORKER_TRACE=trace.as_posix())
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["status"], "failed")
        self.assertEqual(self.state()["lastSuccessAt"], good["lastSuccessAt"])
        self.assertFalse(self.state()["extraCopyVerified"])
        self.assert_worker_stopped(trace)
        self.assertEqual(self.run_script().returncode, 0)

    def test_uutils_default_uses_gnu_for_real_hanging_copy(self):
        real_gnu = None
        for name in ("timeout", "gnutimeout"):
            candidate = shutil.which(name)
            if candidate and "(GNU coreutils)" in subprocess.run(
                    [candidate, "--version"], capture_output=True, text=True, timeout=3).stdout:
                real_gnu = candidate
                break
        self.assertIsNotNone(real_gnu, "Production backup supervision requires GNU timeout")
        self.assertEqual(self.run_script().returncode, 0)
        good = self.state()
        self.fake("timeout", '''#!/bin/sh
if [ "$1" = --version ]; then printf 'timeout (uutils coreutils) fixture\\n'; exit 0; fi
printf unsupported > "$FAKE_UNSUPPORTED_TIMER_TRACE"
exit 99
''')
        self.fake("gnutimeout", '''#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_GNU_TIMER_TRACE"
exec "$REAL_GNU_TIMEOUT" "$@"
''')
        self.fake("cp", '''#!/bin/sh
trap '' TERM
while :; do printf x >> "$FAKE_WORKER_TRACE"; /usr/bin/sleep 0.05; done
''')
        trace = self.base / "gnu-copy-heartbeat"
        unsupported = self.base / "unsupported-timer"
        timer_trace = self.base / "gnu-timer"
        started = time.monotonic()
        result = self.run_script(BACKUP_COPY_TIMEOUT_SECONDS="1", REAL_GNU_TIMEOUT=real_gnu,
                                 FAKE_WORKER_TRACE=trace.as_posix(),
                                 FAKE_GNU_TIMER_TRACE=timer_trace.as_posix(),
                                 FAKE_UNSUPPORTED_TIMER_TRACE=unsupported.as_posix())
        self.assertNotEqual(result.returncode, 0)
        self.assertLess(time.monotonic() - started, 10)
        self.assertFalse(unsupported.exists(), "Unverified timeout implementation ran a backup stage")
        self.assertIn("--kill-after=5 1 /bin/sh", timer_trace.read_text())
        self.assertEqual(self.state()["status"], "failed")
        self.assertEqual(self.state()["lastSuccessAt"], good["lastSuccessAt"])
        self.assert_worker_stopped(trace)

    def test_missing_gnu_supervisor_fails_before_starting_a_worker(self):
        self.assertEqual(self.run_script().returncode, 0)
        good = self.state()
        before = set(self.primary.glob("*.sql"))
        for name in ("timeout", "gnutimeout"):
            self.fake(name, "#!/bin/sh\nprintf 'timeout (uutils coreutils) fixture\\n'\n")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("GNU timeout is required", result.stderr)
        self.assertEqual(set(self.primary.glob("*.sql")), before)
        self.assertEqual(self.state()["status"], "failed")
        self.assertEqual(self.state()["lastSuccessAt"], good["lastSuccessAt"])
        self.assertFalse((self.primary / ".backup-lock").exists())

    def test_invalid_timeouts_fail_without_releasing_another_lock(self):
        for key, value in (("BACKUP_DUMP_TIMEOUT_SECONDS", "0"),
                           ("BACKUP_DUMP_TIMEOUT_SECONDS", "3601"),
                           ("BACKUP_COPY_TIMEOUT_SECONDS", "1801"),
                           ("BACKUP_COPY_TIMEOUT_SECONDS", "oops")):
            result = self.run_script(**{key: value})
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(self.state()["status"], "failed")
            self.assertFalse((self.primary / ".backup-lock").exists())

    def test_replaced_lock_owner_is_never_removed(self):
        result = self.run_script(FAKE_REPLACE_LOCK_OWNER="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.primary / ".backup-lock/owner").read_text(), "replacement")

    def test_loop_shutdown_waits_for_active_worker_before_releasing_lock(self):
        trace = self.base / "shutdown-heartbeat"
        process = subprocess.Popen(
            [SHELL, (ROOT / "deploy/backup-loop.sh").as_posix(), (ROOT / "deploy/backup.sh").as_posix()],
            env=dict(self.env, FAKE_DUMP_HANG="1", BACKUP_DUMP_TIMEOUT_SECONDS="30",
                     FAKE_WORKER_PID=(self.base / "shutdown-pid").as_posix(),
                     FAKE_WORKER_TRACE=trace.as_posix()),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while not trace.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(trace.exists(), "Worker never entered dump phase")
            process.terminate()
            process.communicate(timeout=12)
            self.assert_worker_stopped(trace)
            self.assertEqual(self.state()["status"], "failed")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()

    def test_sequential_loop_backoff_and_success_reset(self):
        plan = self.base / "plan"
        plan.write_text("fail\nfail\nfail\nfail\nfail\nok\nfail\n", encoding="utf-8")
        counter = self.base / "attempts"
        trace = self.base / "sleeps"
        fake_backup = self.fake("backup-once", '''#!/bin/sh
count=0
[ ! -f "$FAKE_LOOP_COUNT_FILE" ] || count=$(cat "$FAKE_LOOP_COUNT_FILE")
count=$((count + 1))
printf '%s' "$count" > "$FAKE_LOOP_COUNT_FILE"
[ "$(sed -n "${count}p" "$FAKE_LOOP_PLAN_FILE")" = ok ]
''')
        result = subprocess.run([SHELL, (ROOT / "deploy/backup-loop.sh").as_posix(), fake_backup.as_posix()],
            env=dict(self.env, FAKE_LOOP_COUNT_FILE=counter.as_posix(), FAKE_LOOP_PLAN_FILE=plan.as_posix(),
                     FAKE_SLEEP_TRACE=trace.as_posix(), FAKE_SLEEP_STOP_AFTER="7",
                     BACKUP_SLEEP_COMMAND=(self.bin / "sleep").as_posix()),
            capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 99, result.stderr)
        self.assertEqual(trace.read_text().splitlines(), ["30", "60", "120", "300", "300", "86400", "30"])
        self.assertEqual(counter.read_text(), "7")


if __name__ == "__main__":
    if not Path(SHELL).is_file():
        raise SystemExit("A POSIX shell is required (Git Bash on Windows)")
    unittest.main(verbosity=2)
