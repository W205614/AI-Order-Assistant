"""Exercise integration preflight, recovery gates and backup read-back behavior."""

import hashlib
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from integration_settings import read_settings, write_private_json

ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def temporary_directory():
    temporary = tempfile.TemporaryDirectory(prefix="ai-order-integration-test-")
    try:
        yield temporary.name
    finally:
        if os.name == "nt":
            temporary._finalizer.detach()
            path = Path(temporary.name).resolve()
            if path.parent != Path(tempfile.gettempdir()).resolve() or not path.name.startswith("ai-order-integration-test-"):
                raise ValueError("Unexpected test fixture cleanup path")
            environment = dict(os.environ, AI_ORDER_INTEGRATION_TEST_DIRECTORY=str(path))
            subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command",
                 "Add-Type -AssemblyName Microsoft.VisualBasic; "
                 "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory("
                 "$env:AI_ORDER_INTEGRATION_TEST_DIRECTORY,'OnlyErrorDialogs','SendToRecycleBin')"],
                env=environment, check=True, capture_output=True,
            )
        else:
            temporary.cleanup()


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


preflight = load("check-integrations")
alerts = load("render-alertmanager")
backup = load("backup-oss")
release = load("release")


def recovery_fixture(directory, media_count=0):
    path = Path(directory) / "orders.sql"
    path.write_bytes(b"CREATE TABLE example(id INT);\n")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    Path(str(path) + ".sha256").write_text(digest)
    created = (datetime.now(timezone.utc) - timedelta(minutes=2)).isoformat()
    metadata = {"schemaVersion": 1, "backupSHA256": digest, "createdAt": created}
    write_private_json(Path(str(path) + ".metadata.json"), metadata)
    data = {
        "schemaVersion": 2, "schemaMode": "integration", "restoredSchemaVersion": 9,
        "passed": True, "backupSHA256": digest, "checksumVerified": True,
        "independentDatabase": True, "backupMetadataVerified": True,
        "backupCreatedAt": created, "testedAt": datetime.now(timezone.utc).isoformat(),
        "databaseFingerprintSHA256": "1" * 64, "mediaManifestSHA256": "2" * 64,
        "mediaAssetCount": media_count,
        "integrationChecks": {"passed": True, "domains": ["payments", "phone", "media", "notifications"],
                              "invariants": {name: 0 for name in release.RECOVERY_INVARIANTS}},
    }
    report = Path(directory) / "restore.json"
    write_private_json(report, data)
    return path, report, data, metadata


class IntegrationOperationsTest(unittest.TestCase):
    def test_local_defaults_do_not_require_paid_services(self):
        self.assertTrue(all(item["passed"] for item in preflight.inspect({})))

    def test_production_rejects_simulation_and_missing_services(self):
        report = preflight.inspect({}, production=True)
        failures = {item["name"] for item in report if not item["passed"]}
        self.assertIn("production.payment", failures)
        self.assertIn("production.sms", failures)
        self.assertIn("production.storage", failures)

    def test_cloud_rejects_insecure_transports(self):
        settings = {"CLOUD_DB_URL": "jdbc:mysql://cloud/db?useSSL=false", "CLOUD_REDIS_URL": "redis://cloud:6379/0"}
        report = {item["name"]: item for item in preflight.inspect(settings, cloud=True)}
        self.assertFalse(report["cloud.mysql_tls"]["passed"])
        self.assertFalse(report["cloud.redis_tls"]["passed"])

    def test_preflight_never_includes_credentials(self):
        settings = {"SMS_PROVIDER": "aliyun", "SMS_ACCESS_KEY_SECRET": "sentinel-private-value"}
        self.assertNotIn("sentinel-private-value", json.dumps(preflight.inspect(settings)))

    def test_alert_receiver_is_required_and_webhook_requires_https(self):
        with self.assertRaises(ValueError):
            alerts.build_config({})
        with self.assertRaises(ValueError):
            alerts.build_config({"ALERT_WEBHOOK_URL": "http://receiver.invalid"})
        config = alerts.build_config({"ALERT_WEBHOOK_URL": "https://receiver.invalid/alert"})
        self.assertEqual(config["route"]["receiver"], config["receivers"][0]["name"])

    def test_release_rejects_mutable_tags(self):
        with self.assertRaises(ValueError):
            release.validated_images("example/gateway:latest", "example/agent:latest")

    def test_release_requires_matching_restored_backup(self):
        with temporary_directory() as directory:
            path, report, data, metadata = recovery_fixture(directory)
            digest = metadata["backupSHA256"]
            self.assertEqual(release.verify_recovery(path, report), digest)
            write_private_json(report, {**data, "backupSHA256": "0" * 64})
            with self.assertRaises(ValueError):
                release.verify_recovery(path, report)

    def test_legacy_success_report_cannot_authorize_new_payment_release(self):
        with temporary_directory() as directory:
            path, report, data, _ = recovery_fixture(directory)
            write_private_json(report, {"passed": True, "backupSHA256": data["backupSHA256"]})
            with self.assertRaises(ValueError):
                release.verify_recovery(path, report)

    def test_integration_recovery_must_cover_all_domains_and_zero_invariants(self):
        with temporary_directory() as directory:
            path, report, data, _ = recovery_fixture(directory)
            incomplete = copy.deepcopy(data)
            incomplete["integrationChecks"]["invariants"].pop("provider_refund_bounds")
            violation = copy.deepcopy(data)
            violation["integrationChecks"]["invariants"]["provider_refund_bounds"] = 1
            invalid_type = copy.deepcopy(data)
            invalid_type["integrationChecks"]["invariants"]["provider_refund_bounds"] = False
            missing_domain = copy.deepcopy(data)
            missing_domain["integrationChecks"]["domains"].remove("payments")
            for invalid in (incomplete, violation, invalid_type, missing_domain,
                            {**data, "restoredSchemaVersion": 6},
                            {**data, "independentDatabase": False},
                            {**data, "databaseFingerprintSHA256": ""}):
                with self.subTest(report=invalid.get("restoredSchemaVersion")):
                    write_private_json(report, invalid)
                    with self.assertRaises(ValueError):
                        release.verify_recovery(path, report)

    def test_old_snapshot_is_not_fresh_when_download_resets_file_mtime(self):
        with temporary_directory() as directory:
            path, report, data, metadata = recovery_fixture(directory)
            old = (datetime.now(timezone.utc) - timedelta(days=3)).isoformat()
            write_private_json(Path(str(path) + ".metadata.json"), {**metadata, "createdAt": old})
            write_private_json(report, {**data, "backupCreatedAt": old})
            os.utime(path, None)
            with self.assertRaises(ValueError):
                release.verify_recovery(path, report)

    def test_database_assets_require_the_matching_independent_media_restore(self):
        with temporary_directory() as directory:
            path, report, data, metadata = recovery_fixture(directory, media_count=2)
            with self.assertRaises(ValueError):
                release.verify_recovery(path, report)
            media_path = Path(directory) / "media-restore.json"
            media = {"schemaVersion": 1, "passed": True, "restored": True,
                     "manifestSHA256": data["mediaManifestSHA256"], "verifiedAssets": 2,
                     "testedAt": datetime.now(timezone.utc).isoformat()}
            write_private_json(media_path, media)
            self.assertEqual(release.verify_recovery(path, report, media_restore_report=media_path), metadata["backupSHA256"])
            for invalid in ({**media, "manifestSHA256": "0" * 64}, {**media, "verifiedAssets": 1}, {**media, "restored": False}):
                write_private_json(media_path, invalid)
                with self.assertRaises(ValueError):
                    release.verify_recovery(path, report, media_restore_report=media_path)

    def test_missing_backup_metadata_cannot_pass_and_disabled_directory_stays_disabled(self):
        with temporary_directory() as directory:
            path, report, _, _ = recovery_fixture(directory)
            metadata = Path(str(path) + ".metadata.json")
            metadata.rename(Path(directory) / "retained-metadata.json")
            with self.assertRaises(ValueError):
                release.verify_recovery(path, report)
            (path.parent / ".backup-disabled").write_text("disabled")
            with self.assertRaises(ValueError):
                release.verify_recovery(path, report)

    def test_disabled_backup_is_never_uploaded(self):
        with temporary_directory() as directory:
            path = Path(directory) / "orders.sql"
            path.write_bytes(b"SQL")
            (path.parent / ".backup-disabled").write_text("disabled")
            with patch.object(backup, "run") as command:
                with self.assertRaises(ValueError):
                    backup.upload(path, {}, Path(directory) / "verification")
                command.assert_not_called()

    def test_upload_verifies_remote_content_not_only_upload_exit_status(self):
        with temporary_directory() as directory:
            root = Path(directory)
            source = root / "orders.sql"
            source.write_bytes(b"CREATE TABLE example(id INT);\n")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            Path(str(source) + ".sha256").write_text(digest)
            remote = {}

            def remote_copy(arguments, environment, **kwargs):
                origin, destination = arguments[2:4]
                if origin.startswith("oss://"):
                    Path(destination).write_bytes(remote[origin])
                else:
                    remote[destination] = Path(origin).read_bytes()

            settings = {
                "OSS_BACKUP_BUCKET": "example-backups", "OSS_REGION": "cn-hangzhou",
                "OSS_ENDPOINT": "https://oss-cn-hangzhou.aliyuncs.com",
                "OSS_ACCESS_KEY_ID": "test-id", "OSS_ACCESS_KEY_SECRET": "test-secret",
            }
            with patch.object(backup.shutil, "which", return_value="ossutil"), patch.object(backup, "run", side_effect=remote_copy):
                receipt = backup.upload(source, settings, root / "verification")
            self.assertTrue(receipt["readBackVerified"])
            self.assertFalse(receipt["restored"])
            self.assertEqual(receipt["sha256"], digest)

            def corrupted_copy(arguments, environment, **kwargs):
                remote_copy(arguments, environment, **kwargs)
                if arguments[2].startswith("oss://") and arguments[2].endswith(".sql"):
                    Path(arguments[3]).write_bytes(b"corrupt content")

            with patch.object(backup.shutil, "which", return_value="ossutil"), patch.object(backup, "run", side_effect=corrupted_copy):
                with self.assertRaises(ValueError):
                    backup.upload(source, settings, root / "verification")

    def test_dotenv_is_data_and_file_secrets_are_not_printed(self):
        with temporary_directory() as directory:
            path = Path(directory) / "settings.env"
            path.write_text("TEST_UNIQUE_OPERATIONS_SETTING='$(unexpected-command)'\n", encoding="utf-8")
            self.assertEqual(read_settings(path)["TEST_UNIQUE_OPERATIONS_SETTING"], "$(unexpected-command)")


if __name__ == "__main__":
    unittest.main()
