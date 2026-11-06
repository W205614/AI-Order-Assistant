"""Back up immutable media objects selected by a restored database manifest, then restore and read them back."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid
from urllib.parse import urlsplit

from integration_settings import read_settings, required, secret, write_private_json
from media_manifest import load_manifest, manifest_digest, verify_object

ROOT = Path(__file__).resolve().parents[1]


def safe_diagnostic(value, arguments=(), environment=None):
    value = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", value)
    for name, content in (environment or os.environ).items():
        if content and re.search(r"PASSWORD|SECRET|TOKEN|CREDENTIAL|(?:API|ACCESS|PRIVATE)_KEY|MEDIA_RECOVERY_KEYS", name, re.I):
            value = value.replace(content, "<redacted>")
    # Docker may quote the Python command in its error. Keep the diagnostic, never its script or private paths.
    for argument in arguments:
        if len(argument) > 128 or argument.startswith("import "):
            value = value.replace(argument, "<redacted-argument>")
    value = re.sub(r"(?:https?|oss)://[^\s'\"]+", "<redacted-url>", value)
    value = re.sub(r"[A-Za-z]:[\\/][^\s'\"]*|/[^\s'\"]*", "<redacted-path>", value)
    value = re.sub(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\.[A-Za-z0-9]+)?", "<redacted-object>", value, flags=re.I)
    value = "".join(character for character in value if character in "\n\t" or ord(character) >= 32).strip()
    return value[:1200] + (" [truncated]" if len(value) > 1200 else "")


class MediaCommandError(RuntimeError):
    error_code = "MEDIA_DOCKER_COMMAND_FAILED"

    def __init__(self, operation, exit_code, stderr):
        self.operation = operation
        self.exit_code = exit_code
        self.stderr = stderr
        super().__init__(f"Docker media operation {operation} failed (exit {exit_code}): {stderr or 'no stderr'}")


def command(arguments, environment=None, operation=None):
    result = subprocess.run(arguments, env=environment, stdin=subprocess.DEVNULL, capture_output=True, timeout=300, check=False)
    if result.returncode:
        if arguments[0] == "docker":
            raise MediaCommandError(operation or "unspecified", result.returncode, safe_diagnostic(result.stderr.decode("utf-8", errors="replace"), arguments, environment))
        raise RuntimeError("Media copy command failed; provider output is withheld to protect private URLs")
    return result.stdout


def backup_allowed(directory):
    for path in (Path(directory).absolute(), *Path(directory).absolute().parents):
        if (path / ".backup-disabled").exists():
            raise ValueError("Media backup is disabled by the selected directory policy")


def oss_context(settings):
    binary = shutil.which(settings.get("OSSUTIL_BINARY", "ossutil"))
    if not binary:
        raise ValueError("Install the official ossutil 2.x CLI")
    environment = dict(os.environ)
    for name in ("OSS_ENDPOINT", "OSS_REGION", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET"):
        environment[name] = required(settings, name)
    endpoint = urlsplit(environment["OSS_ENDPOINT"])
    if not endpoint.hostname or not (endpoint.scheme == "https" or (endpoint.scheme == "http" and endpoint.hostname in {"127.0.0.1", "localhost"})):
        raise ValueError("Media OSS endpoint must use HTTPS")
    token = secret(settings, "OSS_SESSION_TOKEN")
    if token:
        environment["OSS_SESSION_TOKEN"] = token
    return binary, environment


def bucket_name(value):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", value or ""):
        raise ValueError("Invalid media bucket name")
    return value


class MediaVolume:
    def __init__(self, volume, helper_image, writable=False):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", volume):
            raise ValueError("Invalid media volume name")
        command(["docker", "volume", "inspect", volume], operation="volume-inspect")
        image = command(["docker", "image", "inspect", helper_image, "--format", "{{.Id}}" ], operation="image-inspect").decode().strip()
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
            raise ValueError("Media helper image is unavailable")
        self.image = image
        self.volume = volume
        self.token = uuid.uuid4().hex
        self.name = "ai-order-media-recovery-" + self.token[:12]
        self.writable = writable

    def __enter__(self):
        if self.writable:
            script = "import os; assert not os.listdir('/media'), 'Restore target volume must be empty'"
            command(["docker", "run", "--rm", "--network", "none", "--read-only", "--mount", "type=volume,src=" + self.volume + ",dst=/media,readonly,volume-nocopy", "--entrypoint", "/usr/local/bin/python", self.image, "-c", script], operation="volume-empty-check")
        mount = "type=volume,src=" + self.volume + ",dst=/media,volume-nocopy" + ("" if self.writable else ",readonly")
        command(["docker", "create", "--name", self.name, "--network", "none", "--read-only", "--label", "com.ai-order.media-recovery=" + self.token, "--mount", mount, "--entrypoint", "/bin/true", self.image], operation="helper-create")
        return self

    def get(self, key, destination):
        command(["docker", "cp", self.name + ":/media/" + key, str(destination)], operation="copy-out")

    def put(self, key, source):
        if not self.writable:
            raise ValueError("Read-only media source")
        command(["docker", "cp", str(source), self.name + ":/media/" + key], operation="copy-in")

    def fix_ownership(self, assets, uid=10001, gid=10001):
        keys = [asset["objectKey"] for asset in assets]
        script = "import os,json; keys=json.loads(os.environ['MEDIA_RECOVERY_KEYS']); uid=int(os.environ['MEDIA_RECOVERY_UID']); gid=int(os.environ['MEDIA_RECOVERY_GID']); [(os.chmod('/media/'+key,0o640),os.chown('/media/'+key,uid,gid)) for key in keys]; os.chmod('/media',0o750); os.chown('/media',uid,gid)"
        environment = dict(os.environ, MEDIA_RECOVERY_KEYS=json.dumps(keys), MEDIA_RECOVERY_UID=str(uid), MEDIA_RECOVERY_GID=str(gid))
        command(["docker", "run", "--rm", "--network", "none", "--read-only", "--user", "0:0", "--cap-drop", "ALL", "--cap-add", "CHOWN", "--cap-add", "FOWNER", "--mount", "type=volume,src=" + self.volume + ",dst=/media,volume-nocopy", "-e", "MEDIA_RECOVERY_KEYS", "-e", "MEDIA_RECOVERY_UID", "-e", "MEDIA_RECOVERY_GID", "--entrypoint", "/usr/local/bin/python", self.image, "-c", script], environment, operation="ownership")

    def __exit__(self, kind, value, traceback):
        owner = command(["docker", "inspect", self.name, "--format", '{{index .Config.Labels "com.ai-order.media-recovery"}}'], operation="helper-ownership-check").decode().strip()
        if owner != self.token:
            raise RuntimeError("Media helper ownership changed; container retained for inspection")
        command(["docker", "rm", "-v", self.name], operation="helper-remove")


def copy_new(source, destination):
    if source.is_symlink() or not source.is_file():
        raise ValueError("Media source must be a regular file")
    with source.open("rb") as incoming, destination.open("xb") as target:
        shutil.copyfileobj(incoming, target)
    destination.chmod(0o600)


def verify_archive(directory):
    directory = Path(directory)
    manifest = load_manifest(directory / "manifest.json")
    for asset in manifest["assets"]:
        verify_object(directory / "objects" / asset["objectKey"], asset)
    return manifest


def backup(manifest_path, directory, settings, local_directory=None, local_volume=None, helper_image="ai-order-assistant-agent:latest"):
    manifest = load_manifest(manifest_path)
    directory = Path(directory).absolute()
    backup_allowed(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    objects = directory / "objects"
    objects.mkdir(mode=0o700)
    write_private_json(directory / "manifest.json", manifest)
    local = [asset for asset in manifest["assets"] if asset["provider"] == "local"]
    if local and bool(local_directory) == bool(local_volume):
        raise ValueError("Choose exactly one local media directory or volume")
    if local_volume and local:
        with MediaVolume(local_volume, helper_image) as source:
            for asset in local:
                target = objects / asset["objectKey"]
                source.get(asset["objectKey"], target)
                target.chmod(0o600)
                verify_object(target, asset)
    elif local:
        source = Path(local_directory)
        if source.is_symlink() or not source.is_dir():
            raise ValueError("Invalid local media source directory")
        for asset in local:
            target = objects / asset["objectKey"]
            copy_new(source / asset["objectKey"], target)
            verify_object(target, asset)
    remote = [asset for asset in manifest["assets"] if asset["provider"] == "oss"]
    if remote:
        binary, environment = oss_context(settings)
        bucket = bucket_name(required(settings, "OSS_BUCKET"))
        for asset in remote:
            target = objects / asset["objectKey"]
            command([binary, "cp", "oss://" + bucket + "/" + asset["objectKey"], str(target)], environment)
            target.chmod(0o600)
            verify_object(target, asset)
    verify_archive(directory)
    receipt = {"schemaVersion": 1, "passed": True, "backedUp": True, "restored": False, "manifestSHA256": manifest_digest(manifest), "verifiedAssets": len(manifest["assets"]), "testedAt": datetime.now(timezone.utc).isoformat()}
    write_private_json(directory / "backup-report.json", receipt)
    return receipt


def restore(directory, settings, local_directory=None, local_volume=None, target_bucket=None, helper_image="ai-order-assistant-agent:latest", uid=10001, gid=10001):
    directory = Path(directory).absolute()
    manifest = verify_archive(directory)
    local = [asset for asset in manifest["assets"] if asset["provider"] == "local"]
    if local and bool(local_directory) == bool(local_volume):
        raise ValueError("Choose exactly one local restore directory or empty volume")
    check_directory = directory / ("restore-readback-" + uuid.uuid4().hex)
    check_directory.mkdir(mode=0o700)
    if local_volume and local:
        with MediaVolume(local_volume, helper_image, writable=True) as target:
            for asset in local:
                target.put(asset["objectKey"], directory / "objects" / asset["objectKey"])
            target.fix_ownership(local, uid, gid)
            for asset in local:
                path = check_directory / asset["objectKey"]
                target.get(asset["objectKey"], path)
                path.chmod(0o600)
                verify_object(path, asset)
    elif local:
        target = Path(local_directory)
        if target.is_symlink() or (target.exists() and any(target.iterdir())):
            raise ValueError("Local media restore target must be empty")
        target.mkdir(mode=0o700, parents=True, exist_ok=True)
        for asset in local:
            destination = target / asset["objectKey"]
            copy_new(directory / "objects" / asset["objectKey"], destination)
            verify_object(destination, asset)
    remote = [asset for asset in manifest["assets"] if asset["provider"] == "oss"]
    if remote:
        binary, environment = oss_context(settings)
        bucket = bucket_name(target_bucket)
        if bucket == settings.get("OSS_BUCKET"):
            raise ValueError("OSS recovery requires an independent target bucket")
        versioning = json.loads(command([binary, "api", "get-bucket-versioning", "--bucket", bucket, "--output-format", "json"], environment))
        if isinstance(versioning, dict) and "VersioningConfiguration" in versioning:
            versioning = versioning["VersioningConfiguration"]
        if not isinstance(versioning, dict) or (versioning and "Status" not in versioning and "status" not in versioning):
            raise ValueError("Unknown OSS bucket versioning response")
        status = versioning.get("Status", versioning.get("status", ""))
        if status is not None and not isinstance(status, str):
            raise ValueError("Unknown OSS bucket versioning response")
        if status not in {"", None}:
            raise ValueError("OSS recovery target must have versioning disabled so forbid-overwrite is enforced")
        for asset in remote:
            path = directory / "objects" / asset["objectKey"]
            command([binary, "api", "put-object", "--bucket", bucket, "--key", asset["objectKey"], "--body", "file://" + str(path), "--forbid-overwrite", "true", "--object-acl", "private"], environment)
            target = check_directory / asset["objectKey"]
            command([binary, "cp", "oss://" + bucket + "/" + asset["objectKey"], str(target)], environment)
            target.chmod(0o600)
            verify_object(target, asset)
    return {"schemaVersion": 1, "passed": True, "restored": True, "manifestSHA256": manifest_digest(manifest), "verifiedAssets": len(manifest["assets"]), "localAssets": len(local), "ossAssets": len(remote), "testedAt": datetime.now(timezone.utc).isoformat()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "verify", "restore"))
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--backup-directory", type=Path, required=True)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--local-directory", type=Path)
    parser.add_argument("--local-volume")
    parser.add_argument("--target-bucket")
    parser.add_argument("--helper-image", default="ai-order-assistant-agent:latest")
    parser.add_argument("--target-uid", type=int, default=10001)
    parser.add_argument("--target-gid", type=int, default=10001)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    receipt = {"schemaVersion": 1, "passed": False, "restored": False, "testedAt": datetime.now(timezone.utc).isoformat()}
    try:
        settings = read_settings(args.env_file)
        if args.action == "backup":
            if not args.manifest:
                raise ValueError("Backup requires a manifest from the restored database")
            receipt = backup(args.manifest, args.backup_directory, settings, args.local_directory, args.local_volume, args.helper_image)
        elif args.action == "restore":
            if args.target_uid < 0 or args.target_gid < 0:
                raise ValueError("Invalid restore ownership")
            receipt = restore(args.backup_directory, settings, args.local_directory, args.local_volume, args.target_bucket, args.helper_image, args.target_uid, args.target_gid)
        else:
            manifest = verify_archive(args.backup_directory)
            receipt = {"schemaVersion": 1, "passed": True, "restored": False, "manifestSHA256": manifest_digest(manifest), "verifiedAssets": len(manifest["assets"]), "testedAt": datetime.now(timezone.utc).isoformat()}
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        receipt["error"] = "Media verification failed; no complete object recovery is claimed"
        if isinstance(error, MediaCommandError):
            receipt.update(errorCode=error.error_code, operation=error.operation, exitCode=error.exit_code, stderr=error.stderr)
    if args.report:
        write_private_json(args.report, receipt)
    print(json.dumps(receipt))
    return int(not receipt["passed"])


if __name__ == "__main__":
    raise SystemExit(main())
