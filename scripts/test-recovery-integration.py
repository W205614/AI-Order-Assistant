"""Exercise V9 SQL restoration, invariant failures and real local media-volume recovery in isolated containers."""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid

from backup_metadata import ensure_metadata
from integration_settings import write_private_json

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", type=Path, default=ROOT / ".local/recovery-v9-verification")
    parser.add_argument("--mysql-image", default="ai-order-assistant-mysql-runtime:8.4.13")
    parser.add_argument("--gateway-image", required=True, help="The current build containing V7-V9; never silently use an older daily runtime")
    parser.add_argument("--agent-image", default="ai-order-assistant-agent:latest")
    args = parser.parse_args()
    token = uuid.uuid4().hex
    name = "ai-order-recovery-test-" + token[:10]
    volume = name + "-media"
    directory = args.output_directory.resolve() / token[:10]
    directory.mkdir(parents=True, exist_ok=False)
    log = directory / "commands.log"
    environment = dict(os.environ, MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(32), DB_APP_PASSWORD=secrets.token_urlsafe(32), DB_MIGRATION_PASSWORD=secrets.token_urlsafe(32), DB_URL="jdbc:mysql://127.0.0.1:3306/ai_order_assistant?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai")
    receipt = {"schemaVersion": 1, "passed": False, "fixtureOnly": True, "productionServicesVerified": False, "cleanupComplete": False}
    created = volume_created = False

    def run(arguments, data=None, check=True, timeout=180):
        result = subprocess.run(arguments, input=data, env=environment, capture_output=True, timeout=timeout)
        with log.open("a", encoding="utf-8") as target:
            target.write("\n$ " + " ".join(str(value) for value in arguments) + "\nexit=" + str(result.returncode) + "\n")
            if data is None and "image" not in arguments:
                target.write(result.stdout.decode("utf-8", errors="replace"))
            target.write(result.stderr.decode("utf-8", errors="replace"))
        if check and result.returncode:
            raise RuntimeError("Recovery verification command failed; inspect the private command log")
        return result

    def sql(statement):
        return run(["docker", "exec", "-i", name, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 ai_order_assistant'], statement.encode())

    try:
        mysql_id = run(["docker", "image", "inspect", args.mysql_image, "--format", "{{.Id}}" ]).stdout.decode().strip()
        run(["docker", "run", "-d", "--name", name, "--network", "none", "--label", "com.ai-order.recovery-test=" + token, "-e", "MYSQL_ROOT_PASSWORD", "-e", "DB_APP_PASSWORD", "-e", "DB_MIGRATION_PASSWORD", "-e", "MYSQL_DATABASE=ai_order_assistant", mysql_id])
        created = True
        deadline = time.monotonic() + 180
        while True:
            response = run(["docker", "exec", name, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h 127.0.0.1 --protocol=TCP -uroot --silent'], check=False)
            if response.returncode == 0:
                break
            if time.monotonic() > deadline:
                raise RuntimeError("Isolated source database did not become ready")
            time.sleep(1)
        run(["docker", "run", "--rm", "--network", "container:" + name, "-e", "DB_URL", "-e", "DB_MIGRATION_PASSWORD", "--entrypoint", "java", args.gateway_image, "-Dloader.main=com.ai.assistant.config.DatabaseMigration", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"])
        image = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=")
        image_hash = hashlib.sha256(image).hexdigest()
        fixture = (ROOT / "scripts/fixtures/recovery-v9.sql").read_text(encoding="utf-8").replace("__MEDIA_LENGTH__", str(len(image))).replace("__MEDIA_SHA256__", image_hash)
        sql(fixture)
        backup = directory / "fixture.sql"
        snapshot_time = datetime.now(timezone.utc)
        result = run(["docker", "exec", name, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 ai_order_assistant'])
        backup.write_bytes(result.stdout)
        digest = hashlib.sha256(result.stdout).hexdigest()
        Path(str(backup) + ".sha256").write_text(digest + "\n", encoding="ascii")
        ensure_metadata(backup, digest, snapshot_time)
        report_path = directory / "restore-report.json"
        run(["pwsh", "-NoProfile", "-File", str(ROOT / "scripts/restore-db.ps1"), "-BackupFile", str(backup), "-SourceContainer", name, "-IntegrationSchema", "-RestoreImage", args.mysql_image, "-ReportPath", str(report_path)], timeout=900)
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if not report["passed"] or not report["sourceFingerprintMatched"] or report["mediaAssetCount"] != 2 or report["restoredSchemaVersion"] < 9:
            raise RuntimeError("Restored V9 report did not cover the complete fixture")
        manifest_path = directory / report["mediaManifestFile"]
        spec = importlib.util.spec_from_file_location("backup_media", ROOT / "scripts/backup-media.py")
        media = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(media)
        source_media = directory / "source-media"
        source_media.mkdir()
        for asset in json.loads(manifest_path.read_text(encoding="utf-8"))["assets"]:
            (source_media / asset["objectKey"]).write_bytes(image)
        archive = directory / "media-archive"
        media.backup(manifest_path, archive, {}, local_directory=source_media)
        local_result = media.restore(archive, {}, local_directory=directory / "restored-media")
        run(["docker", "volume", "create", "--label", "com.ai-order.recovery-test=" + token, volume])
        volume_created = True
        volume_result = media.restore(archive, {}, local_volume=volume, helper_image=args.agent_image)
        media.backup(manifest_path, directory / "media-volume-readback", {}, local_volume=volume, helper_image=args.agent_image)
        if local_result["manifestSHA256"] != report["mediaManifestSHA256"] or volume_result["verifiedAssets"] != 2:
            raise RuntimeError("Media restoration did not match the restored database manifest")
        write_private_json(directory / "media-restore-report.json", volume_result)
        release_spec = importlib.util.spec_from_file_location("release", ROOT / "scripts/release.py")
        release = importlib.util.module_from_spec(release_spec)
        release_spec.loader.exec_module(release)
        if release.verify_recovery(backup, report_path, media_restore_report=directory / "media-restore-report.json") != digest:
            raise RuntimeError("Release gate did not accept the actual recovery evidence")
        receipt["releaseGatePassed"] = True
        cases = [
            ("provider_refund_links", "UPDATE orders SET checkout_id=NULL WHERE id=1", "UPDATE orders SET checkout_id='22222222-2222-2222-2222-222222222222' WHERE id=1"),
            ("provider_refund_bounds", "UPDATE provider_refund SET amount=1100", "UPDATE provider_refund SET amount=500"),
            ("phone_account_ownership", "UPDATE verified_phone SET account_id=999 WHERE account_type='CUSTOMER'", "UPDATE verified_phone SET account_id=1 WHERE account_type='CUSTOMER'"),
            ("media_resource_ownership", "UPDATE media_asset SET owner_id=999 WHERE owner_type='CUSTOMER'", "UPDATE media_asset SET owner_id=1 WHERE owner_type='CUSTOMER'"),
            ("notification_task_ownership", "UPDATE notification_task SET notification_id=999 WHERE notification_id=1", "UPDATE notification_task SET notification_id=1 WHERE notification_id=999"),
            ("notification_cursor_bounds", "UPDATE notification_cursor SET last_id=999 WHERE source='order'", "UPDATE notification_cursor SET last_id=1 WHERE source='order'"),
        ]
        negative = []
        for key, damage, repair in cases:
            sql(damage)
            broken_report = directory / ("rejected-" + key + ".json")
            response = run([sys.executable, "-B", str(ROOT / "scripts/restore-integrity.py"), "--container", name, "--schema-mode", "integration", "--report", str(broken_report)], check=False)
            rejected = json.loads(broken_report.read_text(encoding="utf-8"))
            if response.returncode == 0 or rejected.get("invariants", {}).get(key, 0) == 0:
                raise RuntimeError("Corruption was not detected: " + key)
            negative.append(key)
            sql(repair)
        receipt.update(passed=True, restoredSchemaVersion=report["restoredSchemaVersion"], tablesVerified=len(report["tableFingerprints"]), invariantChecks=len(report["integrationChecks"]["invariants"]), sourceFingerprintMatched=True, mediaAssetsVerified=2, localDirectoryRestored=True, localVolumeRestored=True, corruptionsRejected=negative, databaseReport=report_path.name, mediaReport="media-restore-report.json")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyError) as error:
        receipt["errorType"] = type(error).__name__
        receipt["error"] = "Isolated recovery verification did not complete; inspect private artifacts"
    finally:
        cleaned = True
        if created:
            label = run(["docker", "inspect", name, "--format", '{{index .Config.Labels "com.ai-order.recovery-test"}}'], check=False).stdout.decode().strip()
            if label == token:
                cleaned &= run(["docker", "rm", "-fv", name], check=False).returncode == 0
            else:
                cleaned = False
        if volume_created:
            label = run(["docker", "volume", "inspect", volume, "--format", '{{index .Labels "com.ai-order.recovery-test"}}'], check=False).stdout.decode().strip()
            if label == token:
                cleaned &= run(["docker", "volume", "rm", volume], check=False).returncode == 0
            else:
                cleaned = False
        receipt["cleanupComplete"] = cleaned
        receipt["passed"] &= cleaned
        receipt["testedAt"] = datetime.now(timezone.utc).isoformat()
        write_private_json(directory / "receipt.json", receipt)
    print(json.dumps({**receipt, "reportDirectory": str(directory)}))
    return int(not receipt["passed"])


if __name__ == "__main__":
    raise SystemExit(main())
