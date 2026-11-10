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
    if not isinstance(candidate.get("version"), str) or not re.fullmatch(r"\d+\.\d+\.\d+", candidate["version"]):
        raise ValueError("A product version is required for the application source tag")
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
    observed, runtime_configs = {}, {}
    referenced_configs, referenced_manifests, layer_paths = set(), set(), set()
    verified_layers = {}
    staging = {"ai-order-assistant-staging-" + item["service"] + ":" + candidate["version"]
               for item in candidate["images"]}
    with tarfile.open(path, "r:*") as archive:
        members = {}
        for member in archive.getmembers():
            name = archive_name(member.name)
            if name in members or not (member.isdir() or member.isfile()):
                raise ValueError("Duplicate, linked or special candidate archive member")
            members[name] = member

        def content(name):
            name = archive_name(name)
            member = members.get(name)
            if member is None or not member.isfile():
                raise ValueError("Missing regular candidate archive member: " + name)
            return archive.extractfile(member).read()

        def blob(descriptor):
            identity = descriptor.get("digest")
            if not valid_digest(identity):
                raise ValueError("Invalid OCI descriptor digest")
            data = content("blobs/sha256/" + identity.removeprefix("sha256:"))
            if descriptor["size"] != len(data) or identity != "sha256:" + hashlib.sha256(data).hexdigest():
                raise ValueError("OCI descriptor size or digest mismatch")
            return data

        def configuration(data):
            config_digest = "sha256:" + hashlib.sha256(data).hexdigest()
            config = json.loads(data)
            if config.get("os") != "linux" or config.get("architecture") != "amd64":
                raise ValueError("Only the reviewed Linux amd64 application candidates are supported")
            if len(config["rootfs"]["diff_ids"]) != 1:
                raise ValueError("Application publication requires exactly one filesystem layer per image")
            labels = config.get("config", {}).get("Labels") or {}
            if labels.get("org.opencontainers.image.revision") != candidate["sourceRevision"]:
                raise ValueError("Candidate application revision label does not match the reviewed source")
            if labels.get("org.opencontainers.image.version") != candidate["version"]:
                raise ValueError("Candidate application version label differs from the product version")
            if labels.get("org.opencontainers.image.source", "").rstrip("/").lower() != SOURCE_URL.lower():
                raise ValueError("Candidate source label does not match the owning repository")
            return config_digest, config

        def layer(name, diff_id, descriptor=None):
            name = archive_name(name)
            layer_paths.add(name)
            member = members.get(name)
            if member is None or not member.isfile():
                raise ValueError("Missing regular candidate layer")
            if name not in verified_layers:
                with archive.extractfile(member) as stream:
                    compressed_sha = hashlib.file_digest(stream, "sha256").hexdigest()
                declared = name.rsplit("/", 1)[-1]
                if re.fullmatch(r"[0-9a-f]{64}", declared) and declared != compressed_sha:
                    raise ValueError("Candidate layer blob digest mismatch")
                with archive.extractfile(member) as stream:
                    magic = stream.read(4)
                    stream.seek(0)
                    if magic[:2] == b"\x1f\x8b":
                        with gzip.GzipFile(fileobj=stream) as unpacked:
                            unpacked_sha = hashlib.file_digest(unpacked, "sha256").hexdigest()
                    elif magic == b"\x28\xb5\x2f\xfd":
                        raise ValueError("Zstandard candidate layers require explicit reviewed decompression support")
                    else:
                        unpacked_sha = hashlib.file_digest(stream, "sha256").hexdigest()
                verified_layers[name] = {"blobSHA256": compressed_sha, "bytes": member.size, "diffId": "sha256:" + unpacked_sha}
            result = verified_layers[name]
            if result["diffId"] != diff_id:
                raise ValueError("Candidate layer differs from its fixed image config")
            if descriptor and (descriptor["digest"] != "sha256:" + result["blobSHA256"] or descriptor["size"] != result["bytes"]):
                raise ValueError("OCI layer descriptor differs from its original compressed blob")
            return result

        legacy = json.loads(content("manifest.json"))
        if len(legacy) != 2:
            raise ValueError("Candidate archive must contain exactly two image manifests")
        full_configs = {}
        if "index.json" in members:
            index = json.loads(content("index.json"))
            descriptors = index.get("manifests", [])
            if index.get("schemaVersion") != 2 or len(descriptors) != 2 or {item.get("digest") for item in descriptors} != expected_ids:
                raise ValueError("OCI index must reference exactly the two reviewed image manifests")
            for descriptor in descriptors:
                identity = descriptor["digest"]
                manifest_bytes = blob(descriptor)
                manifest = json.loads(manifest_bytes)
                if manifest.get("schemaVersion") != 2 or len(manifest.get("layers", [])) != 1:
                    raise ValueError("Application publication requires exactly one filesystem layer per image")
                config_bytes = blob(manifest["config"])
                config_digest, config = configuration(config_bytes)
                diff_ids = config["rootfs"]["diff_ids"]
                layer_descriptor = manifest["layers"][0]
                layer_result = layer("blobs/sha256/" + layer_descriptor["digest"].removeprefix("sha256:"), diff_ids[0], layer_descriptor)
                referenced_configs.add(config_digest)
                referenced_manifests.add(identity)
                full_configs[identity] = config
                runtime_configs[identity] = config.get("config", {})
                observed[identity] = {"imageId": identity, "identityKind": "oci-manifest", "configDigest": config_digest,
                                      "ociManifestDigest": identity, "platform": "linux/amd64", "layerCount": 1,
                                      "diffId": diff_ids[0], "diffIds": diff_ids, "layerBlobSHA256": layer_result["blobSHA256"],
                                      "loadReferences": [], "legacyConfigAliases": [],
                                      "runtimeConfigSHA256": hashlib.sha256(json.dumps(config.get("config", {}), sort_keys=True).encode()).hexdigest()}
        legacy_ids = set()
        for entry in legacy:
            if len(entry["Layers"]) != 1:
                raise ValueError("Application publication requires exactly one filesystem layer per image")
            config_bytes = content(entry["Config"])
            config_digest, config = configuration(config_bytes)
            filename = archive_name(entry["Config"]).rsplit("/", 1)[-1].removesuffix(".json")
            if re.fullmatch(r"[0-9a-f]{64}", filename) and config_digest != "sha256:" + filename:
                raise ValueError("Legacy config path digest mismatch")
            if "index.json" in members:
                matches = [identity for identity, expected in full_configs.items() if expected == config
                           and expected["rootfs"]["diff_ids"] == config["rootfs"]["diff_ids"]]
                if len(matches) != 1:
                    raise ValueError("Legacy compatibility config is not equivalent to a referenced OCI image")
                identity = matches[0]
            else:
                identity = config_digest
                if identity not in expected_ids or identity in observed:
                    raise ValueError("Candidate archive contains an unexpected or duplicate image config")
                full_configs[identity] = config
                runtime_configs[identity] = config.get("config", {})
                observed[identity] = {"imageId": identity, "identityKind": "config", "configDigest": config_digest,
                                      "platform": "linux/amd64", "layerCount": 1, "diffId": config["rootfs"]["diff_ids"][0],
                                      "diffIds": config["rootfs"]["diff_ids"], "loadReferences": [], "legacyConfigAliases": [],
                                      "runtimeConfigSHA256": hashlib.sha256(json.dumps(config.get("config", {}), sort_keys=True).encode()).hexdigest()}
            if identity in legacy_ids:
                raise ValueError("Duplicate legacy view of one candidate image")
            legacy_ids.add(identity)
            layer_result = layer(entry["Layers"][0], config["rootfs"]["diff_ids"][0])
            observed[identity].setdefault("layerBlobSHA256", layer_result["blobSHA256"])
            referenced_configs.add(config_digest)
            observed[identity]["legacyConfigAliases"].append(config_digest)
            for tag in entry.get("RepoTags") or []:
                if tag not in staging:
                    raise ValueError("Candidate archive may load only its dedicated staging tags")
                observed[identity]["loadReferences"].append(tag)
        if legacy_ids != expected_ids:
            raise ValueError("Legacy and OCI views do not cover the same two candidates")
        actual_configs, actual_manifests = set(), set()
        for name, member in members.items():
            if not member.isfile() or name in layer_paths or name.rsplit("/", 1)[-1] == "VERSION":
                continue
            data = content(name)
            try:
                metadata = json.loads(data)
            except (json.JSONDecodeError, UnicodeDecodeError):
                raise ValueError("Unrecognized non-layer candidate archive metadata") from None
            if isinstance(metadata, dict) and "rootfs" in metadata and "architecture" in metadata:
                actual_configs.add("sha256:" + hashlib.sha256(data).hexdigest())
            if isinstance(metadata, dict) and "config" in metadata and "layers" in metadata:
                actual_manifests.add("sha256:" + hashlib.sha256(data).hexdigest())
        if actual_configs != referenced_configs or actual_manifests != referenced_manifests or set(observed) != expected_ids:
            raise ValueError("The complete archive contains an unreviewed or unreferenced image configuration/manifest")
    return {"candidateVerified": True, "archiveSHA256": expected_archive["sha256"], "archiveBytes": path.stat().st_size,
            "sourceRevision": candidate["sourceRevision"], "images": list(observed.values()), "rebuilt": False,
            "_runtimeConfigs": runtime_configs}


def public_verification(result):
    return {key: value for key, value in result.items() if not key.startswith("_")}


def github_api(path, token):
    request = urllib.request.Request("https://api.github.com/repos/" + REPOSITORY + "/" + path,
                                     headers={"Accept": "application/vnd.github+json", "User-Agent": "ai-order-container-publication"})
    request.add_unredirected_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError("GitHub publication prerequisite request failed: HTTP " + str(error.code)) from None


def verify_github_prerequisites(packaging_revision, source_revision, version, token):
    source_tag = "v" + version
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
    tagged = github_api("git/ref/tags/" + source_tag, token)["object"]
    for _ in range(5):
        if tagged["type"] == "commit":
            break
        if tagged["type"] != "tag":
            raise ValueError("The application release tag does not resolve to a commit")
        tagged = github_api("git/tags/" + tagged["sha"], token)["object"]
    if tagged["type"] != "commit" or tagged["sha"] != source_revision:
        raise ValueError("Application source differs from the existing peeled " + source_tag + " tag")
    return {"packagingWorkflowRevision": packaging_revision, "applicationSourceRevision": source_revision,
            "applicationTag": source_tag, "applicationTagMatchVerified": True, "requiredCI": checks}


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
    return {"registryDigest": root_digest, "imageManifestDigest": identity, "configDigest": config_digest}


def existing_tag_action(remote, expected):
    if remote is None:
        return "push"
    if remote["configDigest"] != expected["configDigest"]:
        raise ValueError("Refuse to overwrite an existing SHA tag with different image content")
    if expected["identityKind"] == "oci-manifest" and (
            remote["imageManifestDigest"] != expected["imageId"] or remote["registryDigest"] != expected["imageId"]):
        raise ValueError("Registry manifest bytes differ from the approved OCI candidate; re-encapsulation is not automatically approved")
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
    prerequisites = verify_github_prerequisites(packaging_revision, candidate["sourceRevision"], candidate["version"], token)
    output.mkdir(parents=True, exist_ok=False)
    archive = candidate["candidateArchive"]
    fetcher.acquire({"localPath": "candidate-images.tar.gz", "sourceURL": archive["url"],
                     "sha256": archive["sha256"], "bytes": archive["bytes"]}, output)
    archive_path = output / "candidate-images.tar.gz"
    verification = verify_candidate_archive(archive_path, candidate)
    write_json(output / "candidate-verification.json", public_verification(verification))
    expected_images = {item["imageId"]: item for item in verification["images"]}

    def check_remote(remote, expected, phase):
        try:
            return existing_tag_action(remote, expected)
        except ValueError:
            write_json(output / "registry-manifest-difference.json", {"phase": phase,
                       "expectedImageId": expected["imageId"], "expectedConfigDigest": expected["configDigest"],
                       "expectedDiffIds": expected["diffIds"], "observedRegistry": remote})
            raise
    bearers = {}
    for item in candidate["images"]:
        repository = item["repository"]
        bearers[repository] = registry_token(repository, actor, token)
        remote = registry_manifest(repository, item["tag"], bearers[repository], missing_ok=True)
        check_remote(remote, expected_images[item["imageId"]], "preflight")
    progress = {**prerequisites, "archiveSHA256": archive["sha256"], "rebuilt": False, "images": [],
                "publicVisibilityVerified": False, "startedServices": False}
    write_json(output / "publication-progress.json", progress)
    with tempfile.TemporaryDirectory(prefix="ai-order-ghcr-auth-", dir=os.environ["RUNNER_TEMP"]) as directory:
        auth = Path(directory)
        run_docker(auth, ["login", "ghcr.io", "--username", actor, "--password-stdin"], token, token + "\n")
        run_docker(auth, ["load", "--input", str(archive_path)], token)
        baseline = {}
        for item in candidate["images"]:
            expected = expected_images[item["imageId"]]
            if not expected["loadReferences"]:
                raise ValueError("Publication requires the candidate's explicit dedicated staging tag")
            image = json.loads(run_docker(auth, ["image", "inspect", expected["loadReferences"][0]], token))[0]
            if image["Id"] not in {expected["imageId"], expected["configDigest"]}:
                raise ValueError("Docker loaded an identity outside the reviewed OCI manifest/config pair")
            if image["RootFS"]["Layers"] != expected["diffIds"] or image["Config"] != verification["_runtimeConfigs"][item["imageId"]]:
                raise ValueError("Docker load changed the exact candidate rootfs or runtime Config")
            baseline[item["imageId"]] = image
        for item in candidate["images"]:
            repository = item["repository"]
            expected = expected_images[item["imageId"]]
            reference = repository + ":" + item["tag"]
            # Re-read immediately before pushing; job-level concurrency serializes this workflow.
            remote = registry_manifest(repository, item["tag"], bearers[repository], missing_ok=True)
            action = check_remote(remote, expected, "before-push")
            if action == "push":
                run_docker(auth, ["image", "tag", baseline[item["imageId"]]["Id"], reference], token)
                run_docker(auth, ["image", "push", reference], token)
            record = {"service": item["service"], "repository": repository, "tag": item["tag"],
                      "imageId": item["imageId"], "configDigest": expected["configDigest"], "diffIds": expected["diffIds"],
                      "actualLoadedInspectId": baseline[item["imageId"]]["Id"],
                      "action": action, "authenticatedPullVerified": False}
            progress["images"].append(record)
            write_json(output / "publication-progress.json", progress)
            remote = registry_manifest(repository, item["tag"], bearers[repository])
            check_remote(remote, expected, "after-push")
            run_docker(auth, ["image", "pull", reference], token)
            pulled = json.loads(run_docker(auth, ["image", "inspect", reference], token))[0]
            expected_repo_digest = repository + "@" + remote["registryDigest"]
            if pulled["Id"] not in {expected["imageId"], expected["configDigest"]} or expected_repo_digest not in (pulled.get("RepoDigests") or []):
                raise ValueError("Authenticated pull did not match the pushed registry digest and candidate image ID")
            if pulled["Config"] != baseline[item["imageId"]]["Config"] or pulled["RootFS"]["Layers"] != expected["diffIds"]:
                raise ValueError("Publication changed the candidate runtime configuration")
            record.update(registryDigest=remote["registryDigest"], repoDigest=expected_repo_digest,
                          actualPulledInspectId=pulled["Id"], authenticatedPullVerified=True, runtimeConfigurationUnchanged=True)
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
        write_json(args.output / "candidate-verification.json", public_verification(result))
        print(json.dumps(public_verification(result)))
    else:
        if args.candidate_archive is not None:
            raise ValueError("Publication fetches only the fixed public candidate asset")
        receipt = publish(args.manifest, args.output.resolve())
        print(json.dumps({"passed": receipt["passed"], "images": receipt["images"],
                          "publicVisibilityVerified": False, "anonymousPullVerified": False}))


if __name__ == "__main__":
    main()
