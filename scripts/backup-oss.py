"""Back up or retrieve SQL using official MySQL and ossutil CLIs, then verify SHA-256 by reading OSS back."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

from integration_settings import read_settings, required, secret, write_private_json
from backup_metadata import ensure_metadata, read_metadata

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def checksum(path):
    digest = Path(str(path) + ".sha256").read_text(encoding="utf-8").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest) or digest != sha256(path):
        raise ValueError("Backup SHA-256 verification failed")
    return digest


def backup_allowed(directory):
    if (Path(directory) / ".backup-disabled").exists():
        raise ValueError("Backup is disabled by the selected directory policy")


def run(arguments, environment, timeout=300, stdout=subprocess.PIPE):
    result = subprocess.run(
        arguments, env=environment, stdin=subprocess.DEVNULL, stdout=stdout,
        stderr=subprocess.PIPE, timeout=timeout, check=False,
    )
    if result.returncode:
        # CLI errors can contain endpoints, signed URLs or credentials.
        raise RuntimeError("External backup command failed; inspect provider permissions privately")


def oss_environment(settings):
    environment = dict(os.environ)
    for key in ("OSS_REGION", "OSS_ENDPOINT", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET"):
        environment[key] = secret(settings, key.replace("OSS_", "OSS_BACKUP_", 1)) or required(settings, key)
    token = secret(settings, "OSS_SESSION_TOKEN")
    if token:
        environment["OSS_SESSION_TOKEN"] = token
    return environment


def object_uri(settings, filename):
    bucket = required(settings, "OSS_BACKUP_BUCKET")
    prefix = settings.get("OSS_BACKUP_PREFIX", "ai-order-backups").strip("/")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", bucket):
        raise ValueError("Invalid backup bucket")
    if not re.fullmatch(r"[A-Za-z0-9/_-]+", prefix) or any(x in {".", ".."} for x in prefix.split("/")):
        raise ValueError("Invalid backup object prefix")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+\.sql", filename):
        raise ValueError("Invalid backup filename")
    return "oss://" + bucket + "/" + prefix + "/" + filename


def upload(path, settings, verification_directory):
    original = Path(path).absolute()
    backup_allowed(original.parent)
    if original.is_symlink():
        raise ValueError("Symlink backups are not supported")
    path = original.resolve(strict=True)
    backup_allowed(path.parent)
    if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
        raise ValueError("Backup must be a nonempty regular SQL file")
    digest = checksum(path)
    metadata = ensure_metadata(path, digest)
    binary = shutil.which(settings.get("OSSUTIL_BINARY", "ossutil"))
    if not binary:
        raise ValueError("Install official ossutil 2.x before performing an OSS backup")
    environment = oss_environment(settings)
    uri = object_uri(settings, path.name)
    receipt_dir = Path(verification_directory) / uuid.uuid4().hex
    receipt_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    downloaded = receipt_dir / path.name
    run([binary, "cp", str(path), uri, "--force"], environment)
    run([binary, "cp", str(path) + ".sha256", uri + ".sha256", "--force"], environment)
    run([binary, "cp", str(path) + ".metadata.json", uri + ".metadata.json", "--force"], environment)
    run([binary, "cp", uri, str(downloaded)], environment)
    run([binary, "cp", uri + ".sha256", str(downloaded) + ".sha256"], environment)
    run([binary, "cp", uri + ".metadata.json", str(downloaded) + ".metadata.json"], environment)
    downloaded.chmod(0o600)
    Path(str(downloaded) + ".sha256").chmod(0o600)
    if checksum(downloaded) != digest:
        raise ValueError("Remote backup read-back did not match the source")
    copied_metadata = read_metadata(downloaded, digest, required=True)
    if copied_metadata != metadata:
        raise ValueError("Remote snapshot creation metadata did not match the source")
    receipt = {
        "schemaVersion": 1, "passed": True, "sha256": digest,
        "sourceFile": path.name, "object": uri, "readBackVerified": True,
        "restored": False, "checkedAt": datetime.now(timezone.utc).isoformat(),
        "backupCreatedAt": metadata["createdAt"], "metadataVerified": True,
    }
    write_private_json(receipt_dir / "receipt.json", receipt)
    return receipt


def dump(settings, directory):
    directory = Path(directory).resolve()
    backup_allowed(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    binary = shutil.which(settings.get("MYSQLDUMP_BINARY", "mysqldump"))
    if not binary:
        raise ValueError("Install a compatible MySQL 8.4 client before dumping the cloud database")
    hostname = required(settings, "BACKUP_DB_HOST")
    username = required(settings, "BACKUP_DB_USERNAME")
    database = settings.get("BACKUP_DB_NAME", "ai_order_assistant")
    if not re.fullmatch(r"[A-Za-z0-9_]+", database):
        raise ValueError("Invalid backup database name")
    environment = dict(os.environ)
    environment["MYSQL_PWD"] = required(settings, "BACKUP_DB_PASSWORD")
    filename = "orders-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex + ".sql"
    path = directory / filename
    arguments = [
        binary, "--host=" + hostname, "--port=" + str(int(settings.get("BACKUP_DB_PORT", "3306"))),
        "--user=" + username, "--ssl-mode=VERIFY_IDENTITY", "--single-transaction",
        "--routines", "--triggers", "--events", "--hex-blob", "--no-tablespaces",
        "--set-gtid-purged=OFF", "--default-character-set=utf8mb4", database,
    ]
    ca = settings.get("BACKUP_DB_SSL_CA", "").strip()
    if ca:
        arguments.insert(1, "--ssl-ca=" + str(Path(ca).resolve(strict=True)))
    snapshot_started_at = datetime.now(timezone.utc)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as target:
        run(arguments, environment, timeout=int(settings.get("BACKUP_DUMP_TIMEOUT_SECONDS", "300")), stdout=target)
    if path.stat().st_size == 0:
        raise ValueError("Database dump was empty")
    digest = sha256(path)
    hash_path = Path(str(path) + ".sha256")
    with os.fdopen(os.open(hash_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="ascii") as target:
        target.write(digest + "\n")
    ensure_metadata(path, digest, created_at=snapshot_started_at)
    return path


def download(settings, filename, directory, require_metadata=True):
    directory = Path(directory).resolve()
    backup_allowed(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    uri = object_uri(settings, filename)
    destination = directory / filename
    if destination.exists() or Path(str(destination) + ".sha256").exists():
        raise ValueError("Restore destination already exists; select a new directory")
    binary = shutil.which(settings.get("OSSUTIL_BINARY", "ossutil"))
    if not binary:
        raise ValueError("Install official ossutil 2.x before downloading")
    environment = oss_environment(settings)
    run([binary, "cp", uri, str(destination)], environment)
    run([binary, "cp", uri + ".sha256", str(destination) + ".sha256"], environment)
    try:
        run([binary, "cp", uri + ".metadata.json", str(destination) + ".metadata.json"], environment)
    except RuntimeError:
        if require_metadata:
            raise
    destination.chmod(0o600)
    Path(str(destination) + ".sha256").chmod(0o600)
    digest = checksum(destination)
    read_metadata(destination, digest, required=require_metadata)
    return destination


def metric(directory, success):
    health = Path(directory) / "health"
    health.mkdir(mode=0o755, parents=True, exist_ok=True)
    status_path = health / "oss-backup-status.json"
    previous = 0
    if status_path.exists():
        previous = int(json.loads(status_path.read_text(encoding="utf-8")).get("lastSuccessAt", 0))
    current = int(time.time()) if success else previous
    write_private_json(status_path, {"lastSuccessAt": current, "lastAttemptSuccess": success})
    content = (
        "# TYPE ai_order_oss_backup_last_success_timestamp_seconds gauge\n"
        f"ai_order_oss_backup_last_success_timestamp_seconds {current}\n"
        "# TYPE ai_order_oss_backup_last_attempt_success gauge\n"
        f"ai_order_oss_backup_last_attempt_success {int(success)}\n"
    )
    temporary = health / ("oss-backup.prom." + uuid.uuid4().hex + ".tmp")
    temporary.write_text(content, encoding="ascii", newline="\n")
    temporary.chmod(0o644)
    os.replace(temporary, health / "oss-backup.prom")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("upload", "backup", "download", "restore-check"))
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--file", type=Path)
    parser.add_argument("--object-name")
    parser.add_argument("--directory", type=Path, default=ROOT / "backups")
    parser.add_argument("--verification-directory", type=Path, default=ROOT / ".local/oss-verification")
    parser.add_argument("--schema-mode", choices=("legacy", "campus", "integration"), default="integration")
    args = parser.parse_args()
    try:
        settings = read_settings(args.env_file)
        if args.action in {"upload", "backup"}:
            path = dump(settings, args.directory) if args.action == "backup" else args.file
            if path is None:
                raise ValueError("--file is required for upload")
            result = upload(path, settings, args.verification_directory)
            metric(Path(path).parent, True)
            print(json.dumps({"passed": True, "readBackVerified": True, "restored": False, "sha256": result["sha256"], "backupCreatedAt": result["backupCreatedAt"], "metadataVerified": True}))
        else:
            if not args.object_name:
                raise ValueError("--object-name is required")
            path = download(settings, args.object_name, args.directory, require_metadata=args.schema_mode == "integration")
            if args.action == "restore-check":
                executable = shutil.which("pwsh")
                if not executable:
                    raise ValueError("PowerShell 7 is required by the existing independent restore verifier")
                run([executable, "-NoProfile", "-File", str(ROOT / "scripts/restore-db.ps1"), "-BackupFile", str(path), "-EnvPath", str(args.env_file), "-SchemaMode", args.schema_mode, "-ReportPath", str(path.parent / "restore-report.json")], os.environ.copy(), timeout=1200)
            metadata = read_metadata(path)
            print(json.dumps({"passed": True, "sha256": checksum(path), "restored": args.action == "restore-check", "schemaMode": args.schema_mode, "metadataVerified": metadata is not None, "backupCreatedAt": metadata["createdAt"] if metadata else None}))
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired):
        health_written = False
        if args.action in {"upload", "backup"}:
            selected = args.file.parent if args.action == "upload" and args.file else args.directory
            if not (selected / ".backup-disabled").exists():
                try:
                    metric(selected, False)
                    health_written = True
                except (OSError, ValueError):
                    health_written = False
        print(json.dumps({"passed": False, "healthMetadataWritten": health_written, "error": "Backup operation failed; no verified recovery point is claimed"}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
