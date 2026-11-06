"""Recovery metadata, ownership manifests, real local bytes and isolated OSS-command contracts."""

from datetime import datetime, timedelta, timezone
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
import uuid

from backup_metadata import ensure_metadata, read_metadata
from media_manifest import manifest_digest, validate_manifest

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


media = load("backup-media")
oss = load("backup-oss")
recovery = load("test-recovery-integration")


class RecoveryArtifactTest(unittest.TestCase):
    def setUp(self):
        # Retain tiny synthetic fixtures for inspection; never permanently delete Windows files.
        self.directory = ROOT / ".local/recovery-artifact-tests" / uuid.uuid4().hex
        self.directory.mkdir(parents=True)

    def asset(self, provider="local"):
        content = b"synthetic immutable media object"
        asset = {
            "id": "88888888-8888-8888-8888-888888888888", "purpose": "DISH", "resourceId": "1",
            "ownerType": "MANAGEMENT", "ownerId": 1, "provider": provider,
            "objectKey": "88888888-8888-8888-8888-888888888888.png", "contentType": "image/png",
            "byteLength": len(content), "sha256": hashlib.sha256(content).hexdigest(),
        }
        return asset, content

    def manifest(self, provider="local"):
        asset, content = self.asset(provider)
        value = {"schemaVersion": 1, "assets": [asset]}
        path = self.directory / "manifest.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path, value, content

    def test_snapshot_creation_age_is_preserved_when_mtime_changes(self):
        path = self.directory / "orders.sql"
        path.write_bytes(b"SELECT 1;\n")
        old = datetime.now(timezone.utc) - timedelta(days=4)
        os.utime(path, (old.timestamp(), old.timestamp()))
        metadata = ensure_metadata(path)
        os.utime(path, None)
        self.assertEqual(ensure_metadata(path), metadata)
        self.assertLess(datetime.fromisoformat(metadata["createdAt"]), datetime.now(timezone.utc) - timedelta(days=3))

    def test_metadata_rejects_damaged_shapes_hash_and_dates(self):
        path = self.directory / "orders.sql"
        path.write_bytes(b"SELECT 1;\n")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        valid = {"schemaVersion": 1, "backupSHA256": digest, "createdAt": datetime.now(timezone.utc).isoformat()}
        for invalid in ([], None, {**valid, "schemaVersion": True}, {**valid, "backupSHA256": "0" * 64}, {**valid, "createdAt": None}, {**valid, "createdAt": "2026-01-01T00:00:00"}, {**valid, "createdAt": (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()}):
            Path(str(path) + ".metadata.json").write_text(json.dumps(invalid))
            with self.assertRaises(ValueError):
                read_metadata(path)

    def test_local_objects_are_restored_and_rehashed(self):
        manifest_path, manifest, content = self.manifest()
        source = self.directory / "source"
        source.mkdir()
        (source / manifest["assets"][0]["objectKey"]).write_bytes(content)
        archive = self.directory / "archive"
        backup = media.backup(manifest_path, archive, {}, local_directory=source)
        result = media.restore(archive, {}, local_directory=self.directory / "restored")
        self.assertFalse(backup["restored"])
        self.assertTrue(result["restored"])
        self.assertEqual(result["manifestSHA256"], manifest_digest(manifest))
        self.assertEqual(result["verifiedAssets"], 1)
        self.assertEqual((self.directory / "restored" / manifest["assets"][0]["objectKey"]).read_bytes(), content)

    def test_media_does_not_overwrite_existing_restore_files(self):
        manifest_path, manifest, content = self.manifest()
        source = self.directory / "source"
        source.mkdir()
        (source / manifest["assets"][0]["objectKey"]).write_bytes(content)
        archive = self.directory / "archive"
        media.backup(manifest_path, archive, {}, local_directory=source)
        destination = self.directory / "existing"
        destination.mkdir()
        sentinel = destination / "keep.txt"
        sentinel.write_text("preserved")
        with self.assertRaises(ValueError):
            media.restore(archive, {}, local_directory=destination)
        self.assertEqual(sentinel.read_text(), "preserved")

    def test_corrupt_object_and_path_traversal_cannot_be_restored(self):
        manifest_path, manifest, content = self.manifest()
        source = self.directory / "source"
        source.mkdir()
        key = manifest["assets"][0]["objectKey"]
        (source / key).write_bytes(content)
        archive = self.directory / "archive"
        media.backup(manifest_path, archive, {}, local_directory=source)
        (archive / "objects" / key).write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            media.restore(archive, {}, local_directory=self.directory / "restored")
        manifest["assets"][0]["objectKey"] = "../../escape.png"
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_manifest_binds_resource_ownership_to_object_inventory(self):
        _, manifest, _ = self.manifest()
        original = manifest_digest(manifest)
        manifest["assets"][0]["resourceId"] = "2"
        self.assertNotEqual(original, manifest_digest(manifest))
        for invalid in ([], None, {"schemaVersion": True, "assets": []}):
            with self.assertRaises(ValueError):
                validate_manifest(invalid)

    def test_controlled_oss_backup_restore_uses_no_overwrite_and_readback(self):
        manifest_path, manifest, content = self.manifest("oss")
        key = manifest["assets"][0]["objectKey"]
        objects = {"oss://fixture-source/" + key: content}
        settings = {"OSS_ENDPOINT": "https://oss-cn-hangzhou.aliyuncs.com", "OSS_REGION": "cn-hangzhou", "OSS_BUCKET": "fixture-source", "OSS_ACCESS_KEY_ID": "fixture-id", "OSS_ACCESS_KEY_SECRET": "fixture-secret"}

        def service(arguments, environment=None):
            if arguments[1:3] == ["api", "get-bucket-versioning"]:
                return b'{"Status":""}'
            if arguments[1:3] == ["api", "put-object"]:
                self.assertEqual(arguments[arguments.index("--forbid-overwrite") + 1], "true")
                bucket = arguments[arguments.index("--bucket") + 1]
                resource = arguments[arguments.index("--key") + 1]
                path = Path(arguments[arguments.index("--body") + 1].removeprefix("file://"))
                objects["oss://" + bucket + "/" + resource] = path.read_bytes()
                return b"{}"
            if arguments[1] == "cp":
                Path(arguments[3]).write_bytes(objects[arguments[2]])
                return b""
            raise AssertionError("Unexpected OSS contract command")

        with patch.object(media.shutil, "which", return_value="ossutil"), patch.object(media, "command", side_effect=service):
            archive = self.directory / "archive"
            media.backup(manifest_path, archive, settings)
            result = media.restore(archive, settings, target_bucket="fixture-restored")
        self.assertTrue(result["restored"])
        self.assertEqual(result["ossAssets"], 1)
        self.assertEqual(objects["oss://fixture-restored/" + key], content)

    def test_oss_upload_and_download_keep_original_creation_metadata(self):
        path = self.directory / "orders.sql"
        path.write_bytes(b"SELECT 1;\n")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        Path(str(path) + ".sha256").write_text(digest)
        old = datetime.now(timezone.utc) - timedelta(days=4)
        os.utime(path, (old.timestamp(), old.timestamp()))
        settings = {"OSS_BACKUP_BUCKET": "fixture-backups", "OSS_ENDPOINT": "https://oss-cn-hangzhou.aliyuncs.com", "OSS_REGION": "cn-hangzhou", "OSS_ACCESS_KEY_ID": "fixture-id", "OSS_ACCESS_KEY_SECRET": "fixture-secret"}
        objects = {}

        def copy(arguments, environment, **kwargs):
            origin, destination = arguments[2:4]
            if origin.startswith("oss://"):
                Path(destination).write_bytes(objects[origin])
            else:
                objects[destination] = Path(origin).read_bytes()

        with patch.object(oss.shutil, "which", return_value="ossutil"), patch.object(oss, "run", side_effect=copy):
            oss.upload(path, settings, self.directory / "verification")
            downloaded = oss.download(settings, path.name, self.directory / "download")
        metadata = read_metadata(downloaded)
        self.assertGreater(downloaded.stat().st_mtime, old.timestamp())
        self.assertLess(datetime.fromisoformat(metadata["createdAt"]), datetime.now(timezone.utc) - timedelta(days=3))

    def test_docker_failure_reports_operation_and_redacts_private_context(self):
        private_key = "88888888-8888-8888-8888-888888888888.png"
        script = "import os; print('private-command-content')"
        stderr = ("PermissionError: [Errno 1] Operation not permitted: '/media/" + private_key + "'\n"
                  "credential=fixture-secret https://fixture.invalid/private?token=value\n" + script + "\n" + "detail " * 400).encode()
        result = subprocess.CompletedProcess(["docker"], 17, b"private stdout", stderr)
        with patch.object(media.subprocess, "run", return_value=result):
            with self.assertRaises(media.MediaCommandError) as caught:
                media.command(["docker", "run", "-c", script], {"OSS_ACCESS_KEY_SECRET": "fixture-secret"}, operation="ownership")
        error = caught.exception
        self.assertEqual((error.error_code, error.operation, error.exit_code), ("MEDIA_DOCKER_COMMAND_FAILED", "ownership", 17))
        self.assertIn("Operation not permitted", error.stderr)
        self.assertIn("[truncated]", error.stderr)
        self.assertLessEqual(len(error.stderr), 1212)
        for hidden in (private_key, "/media/", "fixture-secret", "fixture.invalid", "private-command-content", "private stdout"):
            self.assertNotIn(hidden, str(error))

    def test_oss_command_failure_still_withholds_provider_output(self):
        result = subprocess.CompletedProcess(["ossutil"], 1, b"private stdout", b"fixture-secret private object URL")
        with patch.object(media.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "provider output is withheld") as caught:
                media.command(["ossutil", "cp", "oss://private/object", "local"])
        self.assertNotIn("fixture-secret", str(caught.exception))
        self.assertNotIsInstance(caught.exception, media.MediaCommandError)

    def test_recovery_receipt_identifies_the_failed_stage(self):
        output = io.StringIO()
        result = subprocess.CompletedProcess(["docker"], 1, b"", b"Synthetic source image is unavailable")
        arguments = ["test-recovery-integration.py", "--gateway-image", "fixture-gateway", "--output-directory", str(self.directory)]
        with patch.object(sys, "argv", arguments), patch.object(recovery.subprocess, "run", return_value=result), redirect_stdout(output):
            self.assertEqual(recovery.main(), 1)
        receipt = json.loads(output.getvalue())
        self.assertEqual(receipt["stage"], "inspect-source-image")
        self.assertEqual(receipt["errorCode"], "RECOVERY_STAGE_FAILED")
        self.assertIn("command failed", receipt["error"])
        self.assertFalse(receipt["passed"])
        self.assertTrue(receipt["cleanupComplete"])

    def test_recovery_command_log_records_snapshot_digest_without_sql(self):
        snapshot = b"INSERT INTO private_fixture VALUES ('fixture-sql-content');\n"
        owner = ""

        def execute(arguments, **kwargs):
            nonlocal owner
            if "--label" in arguments:
                owner = arguments[arguments.index("--label") + 1].split("=", 1)[1]
            if any("mysqldump" in argument for argument in arguments):
                return subprocess.CompletedProcess(arguments, 0, snapshot, b"")
            if arguments[0] == "pwsh":
                return subprocess.CompletedProcess(arguments, 1, b"", b"Synthetic restore boundary failure")
            result = owner.encode() if arguments[:2] == ["docker", "inspect"] else b"fixture-id\n"
            return subprocess.CompletedProcess(arguments, 0, result, b"")

        output = io.StringIO()
        arguments = ["test-recovery-integration.py", "--gateway-image", "fixture-gateway", "--output-directory", str(self.directory)]
        with patch.object(sys, "argv", arguments), patch.object(recovery.subprocess, "run", side_effect=execute), redirect_stdout(output):
            self.assertEqual(recovery.main(), 1)
        receipt = json.loads(output.getvalue())
        directory = Path(receipt["reportDirectory"])
        log = (directory / "commands.log").read_text(encoding="utf-8")
        self.assertEqual(receipt["stage"], "verify-database-restore")
        self.assertEqual((directory / "fixture.sql").read_bytes(), snapshot)
        self.assertIn("SQL snapshot bytes=" + str(len(snapshot)) + " sha256=" + hashlib.sha256(snapshot).hexdigest(), log)
        self.assertNotIn("fixture-sql-content", log)


if __name__ == "__main__":
    unittest.main()
