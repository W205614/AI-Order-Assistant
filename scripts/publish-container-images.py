"""Promote the reviewed Gateway and Agent archive to fixed GHCR SHA tags without rebuilding."""

import argparse
import base64
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "W205614/AI-Order-Assistant"
SOURCE_URL = "https://github.com/" + REPOSITORY
SOURCE_TAG = "v0.1.2"
TARGETS = {service: "ghcr.io/w205614/ai-order-assistant-" + service for service in ("gateway", "agent")}
ACCEPT_MANIFESTS = ", ".join([
    "application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json",
    "application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json",
])
SPEC = importlib.util.spec_from_file_location("distribution_fetcher", Path(__file__).with_name("fetch-distribution-materials.py"))
fetcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetcher)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def valid_digest(value):
    return isinstance(value, str) and bool(re.fullmatch(r"sha256:[0-9a-f]{64}", value))


def archive_name(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise ValueError("Unsafe candidate archive member")
    return path.as_posix().removeprefix("./").rstrip("/")


def validate_candidates(candidate):
    revision = candidate["sourceRevision"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("The application source must be a complete immutable revision")
    archive = candidate["candidateArchive"]
    url = urllib.parse.urlsplit(archive["url"])
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        raise ValueError("Candidate archives must have a public HTTPS URL")
    if not re.fullmatch(r"[0-9a-f]{64}", archive["sha256"]) or not isinstance(archive["bytes"], int) or archive["bytes"] <= 0:
        raise ValueError("Candidate archive SHA-256 and byte size are required")
    images = candidate["images"]
    if len(images) != 2 or {item["service"] for item in images} != set(TARGETS):
        raise ValueError("Publish exactly the Gateway and Agent candidates")
    for item in images:
        if item["repository"] != TARGETS[item["service"]] or item["tag"] != revision or not valid_digest(item["imageId"]):
            raise ValueError("Unexpected target repository, SHA tag or candidate image ID")
    if len({item["imageId"] for item in images}) != 2:
        raise ValueError("Gateway and Agent must have distinct reviewed image identities")
    return candidate


def verify_candidate_archive(path, candidate):
    validate_candidates(candidate)
    expected_archive = candidate["candidateArchive"]
    if path.is_symlink() or path.stat().st_size != expected_archive["bytes"] or digest(path) != expected_archive["sha256"]:
        raise ValueError("Candidate archive size or SHA-256 mismatch")
    expected_ids = {item["imageId"] for item in candidate["images"]}
    observed, configurations, layer_paths = {}, set(), set()
    with tarfile.open(path, "r:*") as archive:
        seen = set()
        members = archive.getmembers()
        for member in members:
            name = archive_name(member.name)
            if name in seen or not (member.isdir() or member.isfile()):
                raise ValueError("Duplicate, linked or special candidate archive member")
            seen.add(name)
        manifests = json.load(archive.extractfile("manifest.json"))
        if len(manifests) != 2:
            raise ValueError("Candidate archive must contain exactly two image manifests")
        for entry in manifests:
            config_path = archive_name(entry["Config"])
            config_bytes = archive.extractfile(config_path).read()
            image_id = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
            if image_id not in expected_ids or image_id in observed:
                raise ValueError("Candidate archive contains an unexpected or duplicate image config")
            config = json.loads(config_bytes)
            if config.get("os") != "linux" or config.get("architecture") != "amd64":
                raise ValueError("Only the reviewed Linux amd64 application candidates are supported")
            diff_ids = config["rootfs"]["diff_ids"]
            if len(diff_ids) != 1 or len(entry["Layers"]) != 1:
                raise ValueError("Application publication requires exactly one filesystem layer per image")
            labels = config.get("config", {}).get("Labels") or {}
            if labels.get("org.opencontainers.image.revision") != candidate["sourceRevision"]:
                raise ValueError("Candidate application revision label does not match the reviewed source")
            if labels.get("org.opencontainers.image.source", "").rstrip("/").lower() != SOURCE_URL.lower():
                raise ValueError("Candidate source label does not match the owning repository")
            layer_name = archive_name(entry["Layers"][0])
            layer_paths.add(layer_name)
            with archive.extractfile(layer_name) as layer:
                blob_sha = hashlib.file_digest(layer, "sha256").hexdigest()
            declared_blob = layer_name.rsplit("/", 1)[-1]
            if re.fullmatch(r"[0-9a-f]{64}", declared_blob) and blob_sha != declared_blob:
                raise ValueError("Candidate layer blob digest mismatch")
            with archive.extractfile(layer_name) as layer:
                magic = layer.read(4)
                layer.seek(0)
                if magic[:2] == b"\x1f\x8b":
                    with gzip.GzipFile(fileobj=layer) as unpacked:
                        diff_id = "sha256:" + hashlib.file_digest(unpacked, "sha256").hexdigest()
                elif magic == b"\x28\xb5\x2f\xfd":
                    raise ValueError("Zstandard candidate layers require explicit reviewed decompression support")
                else:
                    diff_id = "sha256:" + hashlib.file_digest(layer, "sha256").hexdigest()
            if diff_id != diff_ids[0]:
                raise ValueError("Candidate layer differs from its fixed image config")
            configurations.add(image_id)
            observed[image_id] = {"imageId": image_id, "platform": "linux/amd64", "layerCount": 1,
                                  "diffId": diff_id, "layerBlobSHA256": blob_sha,
                                  "runtimeConfigSHA256": hashlib.sha256(json.dumps(config.get("config", {}), sort_keys=True).encode()).hexdigest()}
        # Docker's OCI save format also has JSON blobs outside the legacy manifest.
        # They must not smuggle an additional image config into docker load.
        for member in members:
            name = archive_name(member.name)
            if not member.isfile() or name in layer_paths:
                continue
            if name.rsplit("/", 1)[-1] == "VERSION":
                continue
            with archive.extractfile(member) as stream:
                content = stream.read()
            try:
                metadata = json.loads(content)
            except (json.JSONDecodeError, UnicodeDecodeError):
                raise ValueError("Unrecognized non-layer candidate archive metadata") from None
            if isinstance(metadata, dict) and "rootfs" in metadata and "architecture" in metadata:
                configurations.add("sha256:" + hashlib.sha256(content).hexdigest())
        if configurations != expected_ids or set(observed) != expected_ids:
            raise ValueError("The complete archive contains an unreviewed image configuration")
    return {"candidateVerified": True, "archiveSHA256": expected_archive["sha256"], "archiveBytes": path.stat().st_size,
            "sourceRevision": candidate["sourceRevision"], "images": list(observed.values()), "rebuilt": False}


def github_api(path, token):
    request = urllib.request.Request("https://api.github.com/repos/" + REPOSITORY + "/" + path,
                                     headers={"Accept": "application/vnd.github+json", "User-Agent": "ai-order-container-publication"})
    request.add_unredirected_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError("GitHub publication prerequisite request failed: HTTP " + str(error.code)) from None


def verify_github_prerequisites(packaging_revision, source_revision, token):
    checks = []
    for filename, name in [("ci.yml", "CI"), ("security.yml", "Dependency and image security")]:
        runs = github_api("actions/workflows/" + filename + "/runs?head_sha=" + packaging_revision + "&per_page=100", token)["workflow_runs"]
        matching = [run for run in runs if run["head_sha"] == packaging_revision and run["name"] == name]
        if not matching:
            raise ValueError("Current packaging revision has no required workflow run: " + name)
        latest = max(matching, key=lambda run: (run["id"], run.get("run_attempt", 1)))
        if latest["status"] != "completed" or latest["conclusion"] != "success":
            raise ValueError("Current packaging revision has not completed successful " + name)
        checks.append({"name": name, "runId": latest["id"], "headSHA": latest["head_sha"],
                       "status": latest["status"], "conclusion": latest["conclusion"], "url": latest["html_url"]})
    tagged = github_api("git/ref/tags/" + SOURCE_TAG, token)["object"]
    for _ in range(5):
        if tagged["type"] == "commit":
            break
        if tagged["type"] != "tag":
            raise ValueError("The application release tag does not resolve to a commit")
        tagged = github_api("git/tags/" + tagged["sha"], token)["object"]
    if tagged["type"] != "commit" or tagged["sha"] != source_revision:
        raise ValueError("Application source differs from the existing peeled " + SOURCE_TAG + " tag")
    return {"packagingWorkflowRevision": packaging_revision, "applicationSourceRevision": source_revision,
            "applicationTag": SOURCE_TAG, "applicationTagMatchVerified": True, "requiredCI": checks}


def registry_token(repository, actor, token):
    if repository not in TARGETS.values():
        raise ValueError("Unsupported registry namespace")
    query = urllib.parse.urlencode({"service": "ghcr.io", "scope": "repository:" + repository.removeprefix("ghcr.io/") + ":pull,push"})
    request = urllib.request.Request("https://ghcr.io/token?" + query, headers={"User-Agent": "ai-order-container-publication"})
    request.add_unredirected_header("Authorization", "Basic " + base64.b64encode((actor + ":" + token).encode()).decode())
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError("GHCR authorization failed: HTTP " + str(error.code)) from None
    value = payload.get("token") or payload.get("access_token")
    if not value:
        raise ValueError("GHCR did not issue a scoped registry token")
    return value


def registry_manifest(repository, reference, bearer, missing_ok=False):
    if repository not in TARGETS.values():
        raise ValueError("Unsupported registry namespace")
    url = "https://ghcr.io/v2/" + repository.removeprefix("ghcr.io/") + "/manifests/" + reference
    request = urllib.request.Request(url, headers={"Accept": ACCEPT_MANIFESTS, "User-Agent": "ai-order-container-publication"})
    request.add_unredirected_header("Authorization", "Bearer " + bearer)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            content = response.read()
            identity = response.headers.get("Docker-Content-Digest")
    except urllib.error.HTTPError as error:
        if error.code == 404 and missing_ok:
            try:
                errors = json.loads(error.read()).get("errors", [])
            except (json.JSONDecodeError, UnicodeDecodeError):
                errors = []
            if errors and all(item.get("code") in {"MANIFEST_UNKNOWN", "NAME_UNKNOWN"} for item in errors):
                return None
        raise RuntimeError("GHCR manifest request failed: HTTP " + str(error.code)) from None
    if not valid_digest(identity) or identity != "sha256:" + hashlib.sha256(content).hexdigest():
        raise ValueError("GHCR manifest digest header does not match the downloaded content")
    if valid_digest(reference) and identity != reference:
        raise ValueError("GHCR returned a different immutable manifest digest")
    manifest = json.loads(content)
    root_digest = identity
    if "manifests" in manifest:
        descriptors = manifest["manifests"]
        if len(descriptors) != 1 or not valid_digest(descriptors[0].get("digest")):
            raise ValueError("Only a single reviewed platform manifest may occupy the SHA tag")
        platform = descriptors[0].get("platform") or {}
        if platform and (platform.get("os"), platform.get("architecture")) != ("linux", "amd64"):
            raise ValueError("The registry tag contains an unexpected platform")
        nested = registry_manifest(repository, descriptors[0]["digest"], bearer)
        return {**nested, "registryDigest": root_digest}
    config_digest = (manifest.get("config") or {}).get("digest")
    if manifest.get("schemaVersion") != 2 or not valid_digest(config_digest) or len(manifest.get("layers", [])) != 1:
        raise ValueError("Registry tag is not the reviewed single-layer image format")
    return {"registryDigest": root_digest, "imageManifestDigest": identity, "imageId": config_digest}


def existing_tag_action(remote, expected_image_id):
    if remote is None:
        return "push"
    if remote["imageId"] != expected_image_id:
        raise ValueError("Refuse to overwrite an existing SHA tag with different image content")
    return "reuse"


def run_docker(config, arguments, secret, input_text=None):
    result = subprocess.run(["docker", "--config", str(config), *arguments], input=input_text,
                            text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        message = result.stderr.replace(secret, "[REDACTED]").strip()
        raise RuntimeError("Docker " + arguments[0] + " failed: " + message)
    return result.stdout.strip()


def publish(candidate_path, output):
    if (os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or os.environ.get("GITHUB_REPOSITORY") != REPOSITORY or os.name != "posix"):
        raise ValueError("Publication runs only in the owning repository's manually dispatched Linux workflow")
    if candidate_path.resolve() != ROOT / "licenses/ghcr-candidates.json":
        raise ValueError("Publish only the checked-in GHCR candidate manifest")
    packaging_revision = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", packaging_revision):
        raise ValueError("Invalid packaging workflow revision")
    checked_out = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    committed = subprocess.check_output(["git", "show", packaging_revision + ":licenses/ghcr-candidates.json"], cwd=ROOT)
    if checked_out != packaging_revision or committed != candidate_path.read_bytes():
        raise ValueError("Candidate manifest must match the checked-out packaging workflow commit")
    candidate = validate_candidates(json.loads(candidate_path.read_text(encoding="utf-8")))
    token, actor = os.environ["GITHUB_TOKEN"], os.environ["GITHUB_ACTOR"]
    prerequisites = verify_github_prerequisites(packaging_revision, candidate["sourceRevision"], token)
    output.mkdir(parents=True, exist_ok=False)
    archive = candidate["candidateArchive"]
    fetcher.acquire({"localPath": "candidate-images.tar.gz", "sourceURL": archive["url"],
                     "sha256": archive["sha256"], "bytes": archive["bytes"]}, output)
    archive_path = output / "candidate-images.tar.gz"
    verification = verify_candidate_archive(archive_path, candidate)
    write_json(output / "candidate-verification.json", verification)
    bearers = {}
    for item in candidate["images"]:
        repository = item["repository"]
        bearers[repository] = registry_token(repository, actor, token)
        remote = registry_manifest(repository, item["tag"], bearers[repository], missing_ok=True)
        existing_tag_action(remote, item["imageId"])
    progress = {**prerequisites, "archiveSHA256": archive["sha256"], "rebuilt": False, "images": [],
                "publicVisibilityVerified": False, "startedServices": False}
    write_json(output / "publication-progress.json", progress)
    with tempfile.TemporaryDirectory(prefix="ai-order-ghcr-auth-", dir=os.environ["RUNNER_TEMP"]) as directory:
        auth = Path(directory)
        run_docker(auth, ["login", "ghcr.io", "--username", actor, "--password-stdin"], token, token + "\n")
        run_docker(auth, ["load", "--input", str(archive_path)], token)
        baseline = {}
        for item in candidate["images"]:
            image = json.loads(run_docker(auth, ["image", "inspect", item["imageId"]], token))[0]
            if image["Id"] != item["imageId"] or len(image["RootFS"]["Layers"]) != 1:
                raise ValueError("Docker loaded a different candidate identity or layer count")
            baseline[item["imageId"]] = image["Config"]
        for item in candidate["images"]:
            repository = item["repository"]
            reference = repository + ":" + item["tag"]
            # Re-read immediately before pushing; job-level concurrency serializes this workflow.
            remote = registry_manifest(repository, item["tag"], bearers[repository], missing_ok=True)
            action = existing_tag_action(remote, item["imageId"])
            if action == "push":
                run_docker(auth, ["image", "tag", item["imageId"], reference], token)
                run_docker(auth, ["image", "push", reference], token)
            record = {"service": item["service"], "repository": repository, "tag": item["tag"],
                      "imageId": item["imageId"], "action": action, "authenticatedPullVerified": False}
            progress["images"].append(record)
            write_json(output / "publication-progress.json", progress)
            remote = registry_manifest(repository, item["tag"], bearers[repository])
            existing_tag_action(remote, item["imageId"])
            run_docker(auth, ["image", "pull", reference], token)
            pulled = json.loads(run_docker(auth, ["image", "inspect", reference], token))[0]
            expected_repo_digest = repository + "@" + remote["registryDigest"]
            if pulled["Id"] != item["imageId"] or expected_repo_digest not in (pulled.get("RepoDigests") or []):
                raise ValueError("Authenticated pull did not match the pushed registry digest and candidate image ID")
            if pulled["Config"] != baseline[item["imageId"]]:
                raise ValueError("Publication changed the candidate runtime configuration")
            record.update(registryDigest=remote["registryDigest"], repoDigest=expected_repo_digest,
                          authenticatedPullVerified=True, runtimeConfigurationUnchanged=True)
            write_json(output / "publication-progress.json", progress)
    receipt = {**progress, "passed": True, "authenticationRequiredForThisVerification": True,
               "anonymousPullVerified": False, "credentialsDirectoryRemoved": True}
    write_json(output / "receipt.json", receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "licenses/ghcr-candidates.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--candidate-archive", type=Path)
    args = parser.parse_args()
    if args.verify_only:
        if args.candidate_archive is None:
            raise ValueError("Local verification requires an explicitly supplied candidate archive")
        candidate = json.loads(args.manifest.read_text(encoding="utf-8"))
        result = verify_candidate_archive(args.candidate_archive, candidate)
        result.update(published=False, ciChecked=False, applicationTagMatchVerified=False,
                      registryAuthenticationPerformed=False, loadedDockerImages=False)
        write_json(args.output / "candidate-verification.json", result)
        print(json.dumps(result))
    else:
        if args.candidate_archive is not None:
            raise ValueError("Publication fetches only the fixed public candidate asset")
        receipt = publish(args.manifest, args.output.resolve())
        print(json.dumps({"passed": receipt["passed"], "images": receipt["images"],
                          "publicVisibilityVerified": False, "anonymousPullVerified": False}))


if __name__ == "__main__":
    main()
