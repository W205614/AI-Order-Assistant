"""Bind snapshot creation time to SQL content; downloads never reset snapshot age."""

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

from integration_settings import write_private_json


def file_sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_metadata(path, digest=None, required=False):
    path = Path(path)
    metadata_path = Path(str(path) + ".metadata.json")
    if not metadata_path.exists():
        if required:
            raise ValueError("Snapshot creation metadata is missing")
        return None
    value = json.loads(metadata_path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1:
        raise ValueError("Snapshot creation metadata is invalid")
    if not isinstance(value.get("backupSHA256"), str) or not re.fullmatch(r"[0-9a-f]{64}", value["backupSHA256"]):
        raise ValueError("Snapshot creation metadata is invalid")
    if value["backupSHA256"] != (digest or file_sha256(path)).lower():
        raise ValueError("Snapshot creation metadata belongs to different SQL content")
    if not isinstance(value.get("createdAt"), str):
        raise ValueError("Snapshot creation time is invalid")
    created = datetime.fromisoformat(value["createdAt"].replace("Z", "+00:00"))
    if created.tzinfo is None or created.timestamp() < 0 or created > datetime.now(timezone.utc):
        raise ValueError("Snapshot creation time is invalid")
    value["createdAt"] = created.astimezone(timezone.utc).isoformat()
    return value


def ensure_metadata(path, digest=None, created_at=None):
    path = Path(path)
    digest = digest or file_sha256(path)
    existing = read_metadata(path, digest)
    if existing:
        return existing
    created = created_at or datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
    if created.tzinfo is None or created.timestamp() < 0 or created > datetime.now(timezone.utc):
        raise ValueError("Snapshot creation time is invalid")
    value = {"schemaVersion": 1, "backupSHA256": digest.lower(), "createdAt": created.astimezone(timezone.utc).isoformat()}
    write_private_json(Path(str(path) + ".metadata.json"), value)
    return value
