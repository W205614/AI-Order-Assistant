"""Append verified OS source/notices assets without replacing published assets."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import urllib.parse
import urllib.request


SPEC = importlib.util.spec_from_file_location("source_bundle", Path(__file__).with_name("build-distribution-bundle.py"))
BUNDLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUNDLE)
REPOSITORY = "W205614/AI-Order-Assistant"
RELEASE_ID = 439024565
WORKFLOW_PATH = ".github/workflows/prepare-license-materials.yml"


def public_digest(url):
    if urllib.parse.urlsplit(url).scheme != "https":
        raise ValueError("Published asset must use HTTPS")
    digest, size = hashlib.sha256(), 0
    with urllib.request.urlopen(url, timeout=120) as response:
        if urllib.parse.urlsplit(response.url).scheme != "https":
            raise ValueError("Published asset redirected away from HTTPS")
        while block := response.read(131072):
            digest.update(block)
            size += len(block)
    return digest.hexdigest(), size


def api(path, token):
    request = urllib.request.Request("https://api.github.com/repos/" + REPOSITORY + "/" + path, headers={"Accept": "application/vnd.github+json", "User-Agent": "AI-Order-source-materials"})
    request.add_unredirected_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.load(response)


def source_selection(prepared_run_id, publish_requested, event, current_sha, current_run_id, run=None):
    if not prepared_run_id:
        if not re.fullmatch(r"[0-9a-f]{40}", current_sha) or not re.fullmatch(r"[1-9][0-9]{0,19}", current_run_id):
            raise ValueError("Invalid current workflow identity")
        # Artifacts of this running workflow become available after each prepare job.
        return {"source_run_id": current_run_id, "artifact_sha": current_sha, "reuse_prepared": "false"}
    if not re.fullmatch(r"[1-9][0-9]{0,19}", prepared_run_id):
        raise ValueError("prepared_run_id must be a positive decimal GitHub run ID")
    if not publish_requested or event != "workflow_dispatch":
        raise ValueError("prepared_run_id is only valid for an explicit manual publication")
    if run is None:
        token = os.environ.get("GITHUB_TOKEN")
        if not token:
            raise ValueError("GITHUB_TOKEN with actions:read is required to validate the prepared run")
        run = api("actions/runs/" + prepared_run_id, token)
    if run.get("id") != int(prepared_run_id) or run.get("path") != WORKFLOW_PATH:
        raise ValueError("Prepared run belongs to a different workflow")
    if run.get("event") not in ("push", "workflow_dispatch"):
        raise ValueError("Prepared run has an unsupported event")
    if run.get("repository", {}).get("full_name") != REPOSITORY or (run.get("head_repository") or {}).get("full_name") != REPOSITORY:
        raise ValueError("Prepared run does not originate from this repository")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise ValueError("Prepared run has not completed successfully")
    sha = run.get("head_sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Prepared run has an invalid source SHA")
    return {"source_run_id": prepared_run_id, "artifact_sha": sha, "reuse_prepared": "true"}


def require_matching_asset(asset, expected):
    if asset["size"] != expected["bytes"]:
        raise ValueError("Existing asset name has different bytes; replacement is forbidden: " + asset["name"])
    supplied = asset.get("digest")
    if supplied and supplied != "sha256:" + expected["sha256"]:
        raise ValueError("Existing asset name has a different digest; replacement is forbidden: " + asset["name"])
    observed, size = public_digest(asset["browser_download_url"])
    if observed != expected["sha256"] or size != expected["bytes"]:
        raise ValueError("Published asset readback differs; replacement is forbidden: " + asset["name"])


def publish(directory, publish_requested=False, kind="os", lock_path=None):
    if kind not in ("os", "go-additional"):
        raise ValueError("Unsupported source material scope")
    prefix = "AI-Order-Assistant-0.1.2-" + kind + "-"
    manifest = directory / (prefix + "source-materials.manifest.json")
    publication = json.loads(manifest.read_text(encoding="utf-8"))
    if publication["version"] != "0.1.2" or publication["kind"] != kind:
        raise ValueError("This append targets only the existing v0.1.2 release")
    if publication["bundle"]["name"] != prefix + "source-materials.tar.gz" or publication["notices"]["name"] != prefix + "NOTICES.txt":
        raise ValueError("Unexpected source material asset name")
    if lock_path is None:
        lock_name = "container-os-sources.lock.json" if kind == "os" else "go-additional-source-inputs.json"
        lock_path = Path(__file__).resolve().parents[1] / "licenses" / lock_name
    lock_sha = hashlib.sha256(lock_path.read_bytes()).hexdigest()
    if publication["payloadManifest"]["sourceLockSHA256"] != lock_sha:
        raise ValueError("Prepared bundle does not match the current fixed source lock")
    bundle = directory / publication["bundle"]["name"]
    notices = directory / publication["notices"]["name"]
    BUNDLE.verify_bundle(bundle, manifest, notices)
    paths = [bundle, notices, manifest]
    expected = {path.name: {"sha256": BUNDLE.digest_file(path), "bytes": path.stat().st_size} for path in paths}
    if not publish_requested:
        return {"verified": True, "published": False, "repository": REPOSITORY, "releaseId": RELEASE_ID, "sourceLockSHA256": lock_sha, "assets": expected}
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise ValueError("GITHUB_TOKEN is required only for an explicitly requested publication")
    release = api("releases/" + str(RELEASE_ID), token)
    if release["id"] != RELEASE_ID or release["tag_name"] != "v0.1.2" or release["draft"] or release["prerelease"]:
        raise ValueError("Target is not the existing public v0.1.2 release")
    current = {}
    for asset in release["assets"]:
        if asset["name"] in expected:
            if asset["name"] in current:
                raise ValueError("Duplicate existing asset name")
            current[asset["name"]] = asset
            require_matching_asset(asset, expected[asset["name"]])
    # All name collisions have been checked before the first mutation.
    upload = release["upload_url"].split("{", 1)[0]
    parsed = urllib.parse.urlsplit(upload)
    if parsed.scheme != "https" or parsed.netloc != "uploads.github.com" or parsed.path != "/repos/" + REPOSITORY + "/releases/" + str(RELEASE_ID) + "/assets":
        raise ValueError("Unexpected release asset upload endpoint")
    results = []
    for path in paths:
        reused = path.name in current
        if reused:
            asset = current[path.name]
        else:
            url = upload + "?name=" + urllib.parse.quote(path.name)
            with path.open("rb") as stream:
                request = urllib.request.Request(url, data=stream, method="POST", headers={"Content-Type": "application/octet-stream", "Content-Length": str(path.stat().st_size), "Accept": "application/vnd.github+json", "User-Agent": "AI-Order-source-materials"})
                request.add_unredirected_header("Authorization", "Bearer " + token)
                with urllib.request.urlopen(request, timeout=120) as response:
                    asset = json.load(response)
            require_matching_asset(asset, expected[path.name])
        results.append({"name": path.name, "id": asset["id"], "url": asset["browser_download_url"], **expected[path.name], "reusedSameBytes": reused, "anonymousReadbackVerified": True})
    result = {"verified": True, "published": True, "repository": REPOSITORY, "releaseId": RELEASE_ID, "tag": "v0.1.2", "sourceLockSHA256": lock_sha, "sourceRunId": os.environ.get("SOURCE_RUN_ID"), "sourceHeadSHA": os.environ.get("SOURCE_HEAD_SHA"), "assets": results, "existingAssetsReplaced": False}
    (directory / "source-publication.json").write_bytes(BUNDLE.json_bytes(result))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--resolve-source-run", action="store_true", help="Validate a prepared run or select this running workflow")
    parser.add_argument("--publish", action="store_true", help="Explicitly append to the existing v0.1.2 Release")
    parser.add_argument("--kind", choices=("os", "go-additional"), default="os")
    args = parser.parse_args()
    if args.resolve_source_run:
        result = source_selection(os.environ.get("PREPARED_RUN_ID", ""), os.environ.get("PUBLISH_REQUESTED") == "true", os.environ.get("GITHUB_EVENT_NAME", ""), os.environ.get("GITHUB_SHA", ""), os.environ.get("GITHUB_RUN_ID", ""))
        if os.environ.get("GITHUB_OUTPUT"):
            with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
                for key, value in result.items():
                    stream.write(key + "=" + value + "\n")
    else:
        if not args.input:
            parser.error("--input is required for candidate verification or publication")
        result = publish(args.input.resolve(), args.publish, args.kind)
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
