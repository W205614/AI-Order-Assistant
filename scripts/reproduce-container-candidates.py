"""Reassemble and promote the same reviewed OCI archive without rebuilding applications."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


fetcher = module("candidate_material_fetcher", "fetch-distribution-materials.py")
sources = module("candidate_asset_publisher", "rebuild-reviewed-source.py")
containers = module("candidate_identity_verifier", "publish-container-images.py")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "licenses/ghcr-candidates.json").read_text(encoding="utf-8"))
    seed = json.loads((ROOT / "licenses/container-candidate-seed.json").read_text(encoding="utf-8"))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    rebuilt = output / "rebuilt"
    target = rebuilt / "AI-Order-Assistant-0.1.2-container-candidates.tar.gz"
    if not args.publish:
        fetcher.acquire({"localPath": "seed.tar.gz", "sourceURL": seed["url"], "sha256": seed["sha256"], "bytes": seed["bytes"]}, output)
        directory = output / "seed"
        directory.mkdir(exist_ok=False)
        with tarfile.open(output / "seed.tar.gz", "r:gz") as archive:
            seen = set()
            for member in archive:
                if not member.isfile() or member.name in seen:
                    raise ValueError("Duplicate or non-file candidate seed member")
                seen.add(member.name)
                path = fetcher.material_path(directory, member.name)
                path.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as source, path.open("wb") as destination:
                    shutil.copyfileobj(source, destination)
        subprocess.run([sys.executable, "-B", str(directory / "rebuild-candidates.py"), "--output", str(rebuilt)], check=True)
    verification = containers.verify_candidate_archive(target, manifest)
    containers.write_json(output / "candidate-verification.json", verification)
    if args.publish:
        prerequisites = containers.verify_github_prerequisites(os.environ["GITHUB_SHA"], manifest["sourceRevision"], os.environ["GITHUB_TOKEN"])
        item = {"target": {"name": target.name, "sha256": manifest["candidateArchive"]["sha256"], "bytes": manifest["candidateArchive"]["bytes"]}}
        receipt = sources.publish(item, rebuilt)
        containers.write_json(output / "publication.json", {**receipt, **prerequisites})
    print(json.dumps({"archiveVerified": True, "published": args.publish, "sha256": manifest["candidateArchive"]["sha256"]}), flush=True)


if __name__ == "__main__":
    main()
