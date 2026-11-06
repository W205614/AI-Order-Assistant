"""Canonical, content-addressed inventory shared by database and media recovery checks."""

import hashlib
import json
from pathlib import Path
import re


def canonical_bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def validate_manifest(manifest):
    if not isinstance(manifest, dict) or type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1 or not isinstance(manifest.get("assets"), list):
        raise ValueError("Unsupported media manifest")
    seen_ids, seen_keys = set(), set()
    for asset in manifest["assets"]:
        if not isinstance(asset, dict) or not re.fullmatch(r"[a-f0-9-]{36}", str(asset.get("id", ""))):
            raise ValueError("Invalid media asset identity")
        if not isinstance(asset.get("provider"), str) or not isinstance(asset.get("purpose"), str) or asset["provider"] not in {"local", "oss"} or asset["purpose"] not in {"DISH", "MERCHANT", "AFTERSALE"}:
            raise ValueError("Invalid media provider or purpose")
        key = asset.get("objectKey", "")
        if not isinstance(key, str) or not re.fullmatch(r"[a-f0-9-]{36}\.(?:jpg|png)", key) or not key.startswith(asset["id"] + "."):
            raise ValueError("Invalid media object key")
        if not re.fullmatch(r"[0-9a-f]{64}", str(asset.get("sha256", ""))):
            raise ValueError("Invalid media object digest")
        size = asset.get("byteLength")
        if type(size) is not int or not 0 < size <= 5 * 1024 * 1024:
            raise ValueError("Invalid media object size")
        if not isinstance(asset.get("contentType"), str) or asset["contentType"] not in {"image/jpeg", "image/png"}:
            raise ValueError("Invalid media content type")
        if not isinstance(asset.get("ownerType"), str) or asset["ownerType"] not in {"CUSTOMER", "MANAGEMENT"} or type(asset.get("ownerId")) is not int or asset["ownerId"] <= 0:
            raise ValueError("Invalid media owner")
        if not isinstance(asset.get("resourceId"), str) or not 0 < len(asset["resourceId"]) <= 100:
            raise ValueError("Invalid media resource identity")
        if asset["id"] in seen_ids or key in seen_keys:
            raise ValueError("Duplicate media inventory entry")
        seen_ids.add(asset["id"])
        seen_keys.add(key)
    if [item["id"] for item in manifest["assets"]] != sorted(seen_ids):
        raise ValueError("Media inventory must use stable asset ordering")
    return manifest


def load_manifest(path):
    return validate_manifest(json.loads(Path(path).read_text(encoding="utf-8")))


def manifest_digest(manifest):
    validate_manifest(manifest)
    return hashlib.sha256(canonical_bytes(manifest)).hexdigest()


def verify_object(path, asset):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size != asset["byteLength"]:
        raise ValueError("Media object is missing or has a different size")
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != asset["sha256"]:
        raise ValueError("Media object SHA-256 mismatch")
    return digest
