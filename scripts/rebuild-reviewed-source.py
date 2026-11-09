"""Reproduce frozen source assets from small seeds and their locked public inputs."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fetcher = load("reviewed_input_fetcher", ROOT / "scripts/fetch-distribution-materials.py")
publisher = load("reviewed_source_publisher", ROOT / "scripts/publish-source-materials.py")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(item, output):
    target = output / item["target"]["name"]
    if target.exists():
        fetcher.verify_file(target, dict(item["target"], localPath=target.name))
        return target
    fetcher.acquire(dict(item["seed"], localPath="seed.tar.gz", sourceURL=item["seed"]["url"]), output)
    seed = output / "seed"
    seed.mkdir(exist_ok=False)
    with tarfile.open(output / "seed.tar.gz", "r:gz") as archive:
        seen = set()
        for member in archive:
            if not member.isfile() or member.name in seen:
                raise ValueError("Duplicate or non-file rebuild seed member")
            seen.add(member.name)
            path = fetcher.material_path(seed, member.name)
            path.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, path.open("wb") as destination:
                shutil.copyfileobj(source, destination)
    recipe = json.loads((seed / "REBUILD.json").read_text(encoding="utf-8"))
    if any(recipe["target"][key] != item["target"][key] for key in ("sha256", "bytes")):
        raise ValueError("Rebuild seed describes a different frozen target")
    subprocess.run([sys.executable, "-B", str(seed / "rebuild-source-bundle.py"), "--materials", str(output / "public-source-cache"), "--output", str(target)], check=True)
    fetcher.verify_file(target, dict(item["target"], localPath=target.name))
    return target


def publish(item, output):
    target = output / item["target"]["name"]
    fetcher.verify_file(target, dict(item["target"], localPath=target.name))
    token = os.environ["GITHUB_TOKEN"]
    release = publisher.api("releases/439024565", token)
    if release["tag_name"] != "v0.1.2" or release["draft"]:
        raise ValueError("Append only to the existing public v0.1.2 release")
    existing = [asset for asset in release["assets"] if asset["name"] == target.name]
    if len(existing) > 1:
        raise ValueError("Duplicate target asset name")
    if existing:
        asset = existing[0]
    else:
        url = release["upload_url"].split("{", 1)[0] + "?name=" + urllib.parse.quote(target.name)
        with target.open("rb") as stream:
            request = urllib.request.Request(url, data=stream, method="POST", headers={"Content-Type": "application/octet-stream", "Content-Length": str(target.stat().st_size), "Accept": "application/vnd.github+json", "User-Agent": "reviewed-source-rebuild"})
            request.add_unredirected_header("Authorization", "Bearer " + token)
            with urllib.request.urlopen(request, timeout=180) as response:
                asset = json.load(response)
    publisher.require_matching_asset(asset, item["target"])
    result = {"name": asset["name"], "assetId": asset["id"], "url": asset["browser_download_url"], "sha256": sha256(target), "bytes": target.stat().st_size, "rebuiltFrozenBytes": True, "anonymousDownloadVerified": True, "replacedExistingAsset": False}
    (output / "publication.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=("go-base", "agent"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    lock = json.loads((ROOT / "licenses/source-rebuild-seeds.json").read_text(encoding="utf-8"))
    item = lock[args.kind]
    if item["target"]["name"] != "AI-Order-Assistant-0.1.2-" + args.kind + "-source-materials.tar.gz":
        raise ValueError("Unexpected source asset target")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    result = publish(item, output) if args.publish else {"prepared": str(prepare(item, output)), "sha256": item["target"]["sha256"]}
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
