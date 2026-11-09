"""Fetch exact, reviewed source inputs and preserve their digest verification."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile


def material_path(root, name):
    relative = PurePosixPath(name)
    if not name or relative.is_absolute() or ".." in relative.parts or "\\" in name or ":" in name:
        raise ValueError("Material path must stay within the output directory")
    result = root / relative
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError("Material path escapes the output directory")
    return result


def verify_file(path, item):
    algorithm = "sha512" if item.get("sha512") else "sha256"
    expected = item.get(algorithm)
    if not expected or len(expected) != hashlib.new(algorithm).digest_size * 2:
        raise ValueError("Missing fixed source digest: " + item["localPath"])
    if not path.is_file() or path.is_symlink():
        raise ValueError("Material is missing or is a symbolic link: " + item["localPath"])
    if item.get("bytes") is not None and path.stat().st_size != item["bytes"]:
        raise ValueError("Material size mismatch: " + item["localPath"])
    with path.open("rb") as stream:
        observed = hashlib.file_digest(stream, algorithm).hexdigest()
    if observed != expected:
        raise ValueError("Material digest mismatch: " + item["localPath"])
    return {"path": item["localPath"], "algorithm": algorithm, "digest": observed, "bytes": path.stat().st_size}


def acquire(item, root):
    target = material_path(root, item["localPath"])
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return verify_file(target, item)
    partial = target.with_name(target.name + ".download")
    if item.get("archiveMember"):
        source = material_path(root, item["archivePath"])
        member = item["archiveMember"]
        material_path(root, member)
        if zipfile.is_zipfile(source):
            with zipfile.ZipFile(source) as archive:
                if archive.namelist().count(member) != 1:
                    raise ValueError("Missing or duplicate ZIP notice member")
                with archive.open(member) as src, partial.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
        else:
            with tarfile.open(source, "r:*") as archive:
                members = [x for x in archive.getmembers() if x.name == member]
                if len(members) != 1 or not members[0].isfile():
                    raise ValueError("Missing, duplicate or non-file TAR notice member")
                with archive.extractfile(members[0]) as src, partial.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
    else:
        url = item.get("sourceURL")
        parsed = urllib.parse.urlsplit(url or "")
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("A public fixed HTTPS source URL is required: " + item["localPath"])
        for attempt in range(4):
            offset = partial.stat().st_size if partial.exists() else 0
            request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-"} if offset else {})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    if urllib.parse.urlsplit(response.url).scheme != "https":
                        raise ValueError("Source redirected away from HTTPS")
                    append = offset and response.status == 206
                    if append and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                        raise ValueError("Unexpected source resume range")
                    received = 0
                    with partial.open("ab" if append else "wb") as dst:
                        while block := response.read(131072):
                            dst.write(block)
                            received += len(block)
                    if response.headers.get("Content-Length") and received != int(response.headers["Content-Length"]):
                        raise OSError("Truncated source response")
                verify_file(partial, item)
                break
            except (urllib.error.URLError, OSError):
                if attempt == 3:
                    raise
    result = verify_file(partial, item)
    partial.rename(target)
    return result


def fetch_all(lock, output):
    items = {}
    for item in lock["materials"]:
        previous = items.get(item["localPath"])
        if previous and any(previous.get(key) != item.get(key) for key in ("sha256", "sha512", "bytes")):
            raise ValueError("Conflicting material records: " + item["localPath"])
        items[item["localPath"]] = item
    records = []
    for derived in (False, True):
        selected = [x for x in items.values() if bool(x.get("archiveMember")) == derived]
        with ThreadPoolExecutor(max_workers=6) as executor:
            for result in executor.map(lambda item: acquire(item, output), selected):
                records.append(result)
                print(json.dumps(result), flush=True)
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    records = fetch_all(lock, output)
    receipt = {"scope": lock.get("scope"), "lockSHA256": hashlib.sha256(args.lock.read_bytes()).hexdigest(), "requestedMaterialsVerified": True, "files": records}
    (output / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
