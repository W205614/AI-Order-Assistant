"""Prepare, deploy or roll back immutable application images without deleting project volumes."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from backup_metadata import read_metadata
from integration_settings import write_private_json

ROOT = Path(__file__).resolve().parents[1]
IMAGE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-f]{64}")
LOCAL_IMAGE = re.compile(r"sha256:[0-9a-f]{64}")
SHA256 = re.compile(r"[0-9a-f]{64}")
RECOVERY_INVARIANTS = {
    "provider_payment_ownership", "provider_part_totals", "provider_refund_links",
    "provider_refund_bounds", "provider_payment_success", "merchant_payment_ownership",
    "platform_coupon_ownership", "oauth_account_ownership", "phone_account_ownership",
    "phone_challenge_bounds", "media_resource_ownership", "media_metadata",
    "notification_account_ownership", "notification_task_ownership",
    "notification_cursor_bounds", "reconciliation_ownership",
}


def validated_images(gateway, agent, proxy=None):
    images = {"gateway": gateway, "migrate": gateway, "agent": agent}
    if proxy:
        images["proxy"] = proxy
    if not all(IMAGE.fullmatch(value or "") or LOCAL_IMAGE.fullmatch(value or "") for value in images.values()):
        raise ValueError("Every release image must use its immutable sha256 digest")
    return images


def recent_evidence(value, max_hours):
    if not isinstance(value, str):
        raise ValueError("Recovery evidence needs an original timestamp")
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("Recovery timestamps require a timezone")
    age = (datetime.now(timezone.utc) - timestamp).total_seconds()
    if not 0 <= age <= max_hours * 3600:
        raise ValueError("Recovery evidence is outside the release freshness window")
    return timestamp


def verify_recovery(backup, restore_report, max_hours=30, media_restore_report=None):
    backup, restore_report = Path(backup).resolve(strict=True), Path(restore_report).resolve(strict=True)
    if (backup.parent / ".backup-disabled").exists():
        raise ValueError("The selected backup directory is disabled")
    with backup.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    sidecar = Path(str(backup) + ".sha256").read_text(encoding="utf-8").strip().lower()
    report = json.loads(restore_report.read_text(encoding="utf-8"))
    if not isinstance(report, dict):
        raise ValueError("Restore report must be an object")
    if not backup.stat().st_size or sidecar != digest:
        raise ValueError("Backup checksum is invalid")
    if report.get("passed") is not True or str(report.get("backupSHA256", "")).lower() != digest:
        raise ValueError("Independent restore report does not match this backup")
    if (
        report.get("schemaVersion") != 2
        or report.get("schemaMode") != "integration"
        or type(report.get("restoredSchemaVersion")) is not int
        or report["restoredSchemaVersion"] < 9
        or report.get("independentDatabase") is not True
        or report.get("checksumVerified") is not True
        or report.get("backupMetadataVerified") is not True
    ):
        raise ValueError("This release requires an independent V9 integration restore")
    checks = report.get("integrationChecks")
    if not isinstance(checks, dict) or checks.get("passed") is not True:
        raise ValueError("Integration recovery checks are missing")
    domains = checks.get("domains")
    invariants = checks.get("invariants")
    if (
        not isinstance(domains, list)
        or not all(isinstance(value, str) for value in domains)
        or not {"payments", "phone", "media", "notifications"}.issubset(domains)
        or not isinstance(invariants, dict)
        or not RECOVERY_INVARIANTS.issubset(invariants)
        or any(type(value) is not int or value != 0 for value in invariants.values())
    ):
        raise ValueError("Recovery must verify all integration ownership and money invariants")
    if any(not isinstance(report.get(key), str) or not SHA256.fullmatch(report[key]) for key in ("databaseFingerprintSHA256", "mediaManifestSHA256")):
        raise ValueError("Recovery fingerprints are missing or invalid")
    metadata = read_metadata(backup, digest, required=True)
    created = recent_evidence(metadata.get("createdAt"), max_hours)
    if recent_evidence(report.get("backupCreatedAt"), max_hours) != created:
        raise ValueError("Restored snapshot creation time does not match backup metadata")
    tested = recent_evidence(report.get("testedAt"), max_hours)
    if tested < created:
        raise ValueError("The restore test cannot precede the backup snapshot")
    count = report.get("mediaAssetCount")
    if type(count) is not int or count < 0:
        raise ValueError("Recovery media asset count is invalid")
    if count:
        if not media_restore_report:
            raise ValueError("This backup also requires a matching media restore report")
        media = json.loads(Path(media_restore_report).read_text(encoding="utf-8"))
        if (
            not isinstance(media, dict)
            or media.get("schemaVersion") != 1
            or media.get("passed") is not True
            or media.get("restored") is not True
            or media.get("manifestSHA256") != report["mediaManifestSHA256"]
            or type(media.get("verifiedAssets")) is not int
            or media["verifiedAssets"] != count
        ):
            raise ValueError("Media restore does not match the restored database manifest")
        recent_evidence(media.get("testedAt"), max_hours)
    return digest


def override(images):
    # JSON is a YAML subset; build:null overrides root build objects only with !reset.
    return "services:\n" + "".join(
        f"  {service}:\n    image: {image}\n    build: !reset null\n"
        + ("    pull_policy: never\n" if LOCAL_IMAGE.fullmatch(image) else "")
        for service, image in images.items()
    )


def run(arguments, timeout=600):
    result = subprocess.run(arguments, cwd=ROOT, stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError("Release command failed; use the selected Compose project logs for diagnosis")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "deploy", "rollback"))
    parser.add_argument("--project", default="ai-order-assistant")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--gateway-image")
    parser.add_argument("--agent-image")
    parser.add_argument("--proxy-image")
    parser.add_argument("--public", action="store_true")
    parser.add_argument("--cloud", action="store_true")
    parser.add_argument("--operations", action="store_true")
    parser.add_argument("--receipt", type=Path, help="Prior successful receipt used for rollback")
    parser.add_argument("--schema-compatible", action="store_true", help="Operator verified the old application supports the currently migrated schema")
    parser.add_argument("--backup-file", type=Path)
    parser.add_argument("--restore-report", type=Path)
    parser.add_argument("--media-restore-report", type=Path, help="Required when the database backup contains media assets")
    args = parser.parse_args()
    mutation_started = False
    try:
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", args.project):
            raise ValueError("Invalid Compose project name")
        if args.action == "rollback":
            if not args.receipt or not args.schema_compatible:
                raise ValueError("Rollback requires a previous successful receipt and --schema-compatible")
            prior = json.loads(args.receipt.read_text(encoding="utf-8"))
            if prior.get("deployed") is not True or prior.get("project") != args.project:
                raise ValueError("Receipt does not describe a successful release of this project")
            images = validated_images(prior["images"]["gateway"], prior["images"]["agent"], prior["images"].get("proxy"))
            args.public, args.cloud, args.operations = prior["public"], prior["cloud"], prior["operations"]
        else:
            images = validated_images(args.gateway_image, args.agent_image, args.proxy_image)
        if args.public and "proxy" not in images:
            raise ValueError("Public releases also require --proxy-image with the patched Caddy digest")
        preflight = [sys.executable, str(ROOT / "scripts/check-integrations.py"), "--env-file", str(args.env_file)]
        if args.public or args.cloud:
            preflight.append("--production")
        if args.cloud:
            preflight.append("--cloud")
        run(preflight, timeout=30)
        backup_digest = None
        if args.action != "prepare":
            if not args.backup_file or not args.restore_report:
                raise ValueError("Deployment requires --backup-file and its independent --restore-report")
            backup_digest = verify_recovery(args.backup_file, args.restore_report, media_restore_report=args.media_restore_report)
        release_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        directory = ROOT / ".local/releases" / args.project
        release_directory = directory / release_id
        release_directory.mkdir(parents=True, exist_ok=False)
        override_path = release_directory / "compose.yml"
        override_path.write_text(override(images), encoding="utf-8", newline="\n")
        command = ["docker", "compose", "--env-file", str(args.env_file.resolve()), "-p", args.project, "-f", str(ROOT / "docker-compose.yml")]
        for enabled, filename in ((args.cloud, "cloud.yml"), (args.public, "public.yml"), (args.operations, "operations.yml")):
            if enabled:
                command.extend(["-f", str(ROOT / "deploy/compose" / filename)])
        command.extend(["-f", str(override_path)])
        run(command + ["config", "--quiet"], timeout=30)
        deployed = args.action != "prepare"
        if deployed:
            registry_services = [service for service, image in images.items() if IMAGE.fullmatch(image)]
            if registry_services:
                run(command + ["pull", *registry_services])
            if args.action == "deploy":
                mutation_started = True
                if not args.cloud:
                    run(command + ["up", "-d", "--wait", "mysql", "redis"])
                run(command + ["run", "--rm", "migrate"])
            services = ["gateway", "agent", "prometheus"]
            if args.public:
                services.append("proxy")
            if args.operations:
                services.append("alertmanager")
            # Migrations were explicitly completed above. Rollback never runs historical DDL.
            mutation_started = True
            run(command + ["up", "-d", "--no-build", "--no-deps", "--wait", "--wait-timeout", "180", *services])
        receipt = {
            "schemaVersion": 1, "project": args.project, "deployed": deployed,
            "action": args.action, "images": images, "public": args.public,
            "cloud": args.cloud, "operations": args.operations,
            "backupSHA256": backup_digest, "schemaDowngraded": False,
            "recordedAt": datetime.now(timezone.utc).isoformat(),
        }
        write_private_json(release_directory / "receipt.json", receipt)
        if deployed:
            write_private_json(directory / "last-successful.json", receipt)
        print(json.dumps({"passed": True, "deployed": deployed, "receipt": str(release_directory / "receipt.json"), "volumesRemoved": False}))
        return 0
    except (OSError, ValueError, RuntimeError, KeyError, subprocess.TimeoutExpired):
        print(json.dumps({"passed": False, "deployed": False, "deploymentMayHaveChanged": mutation_started, "error": "Release did not complete; inspect configuration, recovery evidence and Compose logs", "automaticRollbackAttempted": False}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
