"""Verify reviewed materials and every distributed infrastructure-image layer before upload."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]


def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


inspector = load_script("distribution_inspector", "inspect-distribution-images.py")
fetcher = load_script("distribution_fetcher", "fetch-distribution-materials.py")


def safe_path(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise ValueError("Unsafe distribution material path: " + name)
    return path.as_posix().removeprefix("./").rstrip("/")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_build_inputs(lock, repository):
    records = []
    for relative, expected in lock["buildInputs"].items():
        relative = safe_path(relative)
        path = repository / relative
        if not path.resolve().is_relative_to(repository.resolve()) or path.is_symlink() or not path.is_file():
            raise ValueError("Build input is missing or escapes the repository: " + relative)
        observed = sha256(path)
        if observed != expected:
            raise ValueError("Build input changed: " + relative)
        records.append({"path": relative, "sha256": observed})
    for directory, expected in lock.get("buildInputDirectories", {}).items():
        root = repository / safe_path(directory)
        if not root.resolve().is_relative_to(repository.resolve()):
            raise ValueError("Build input directory escapes the repository")
        actual = []
        pending = [root]
        while pending:
            path = pending.pop()
            metadata = path.lstat()
            if path.is_symlink() or getattr(metadata, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
                raise ValueError("Unsupported symlink/reparse build input boundary: " + str(path))
            if path.is_dir():
                pending.extend(path.iterdir())
            elif path.is_file():
                relative = path.relative_to(repository).as_posix()
                if relative not in lock["buildInputs"]:
                    raise ValueError("A build context file lacks its locked content hash: " + relative)
                actual.append(relative)
            else:
                raise ValueError("Unsupported build input file type")
        if sorted(actual) != sorted(expected):
            raise ValueError("Build input directory file set changed: " + directory)
    return records


def verify_bundle(bundle, output):
    identifier = bundle["id"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", identifier):
        raise ValueError("Invalid material bundle ID")
    url = urllib.parse.urlsplit(bundle["url"])
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        raise ValueError("Material bundles require public HTTPS URLs")
    item = {"localPath": "bundles/" + identifier + ".tar.gz", "sourceURL": bundle["url"],
            "sha256": bundle["sha256"], "bytes": bundle["bytes"]}
    acquired = fetcher.acquire(item, output)
    required = {}
    for record in bundle["requiredMembers"]:
        name = safe_path(record["path"])
        if name in required or not re.fullmatch(r"[0-9a-f]{64}", record["sha256"]):
            raise ValueError("Duplicate or invalid required material member: " + name)
        required[name] = record
    if not required:
        raise ValueError("A reviewed material bundle cannot have an empty required member list")
    manifest = safe_path(bundle["manifestPath"])
    seen, files = set(), {}
    with tarfile.open(output / item["localPath"], "r:*") as archive:
        for member in archive:
            name = safe_path(member.name)
            if name in seen:
                raise ValueError("Duplicate material archive member: " + name)
            seen.add(name)
            if member.isdir():
                continue
            if not member.isfile():
                raise ValueError("Material archive contains a link or non-file: " + name)
            observed = hashlib.file_digest(archive.extractfile(member), "sha256").hexdigest()
            if name in required:
                expected = required[name]
                if expected["bytes"] != member.size or expected["sha256"] != observed:
                    raise ValueError("Required material hash or size mismatch: " + name)
            elif name != manifest:
                raise ValueError("Unreviewed file in material bundle: " + name)
            files[name] = {"sha256": observed, "bytes": member.size}
    missing = set(required) - set(files)
    if missing or manifest not in files:
        raise ValueError("Missing required material or manifest: " + repr(sorted(missing | ({manifest} - set(files)))))
    return {"id": identifier, "url": bundle["url"], "sha256": acquired["digest"], "bytes": acquired["bytes"],
            "manifestPath": manifest, "manifestSHA256": files[manifest]["sha256"],
            "requiredMembersVerified": True, "verifiedMemberCount": len(files), "verifiedMembers": files}


def require_bundles(identifiers, bundles):
    if not identifiers or any(identifier not in bundles for identifier in identifiers):
        raise ValueError("A component is missing its verified source/notice bundle")


def verify_go_binary(binary, lock, bundles):
    if binary.get("error") or not binary.get("buildInfo"):
        raise ValueError("Go build information could not be read: " + binary["path"])
    info = binary["buildInfo"]
    toolchains = [item for item in lock["goToolchains"]
                  if item["version"].removeprefix("go") == info["GoVersion"].removeprefix("go")]
    if not toolchains:
        raise ValueError("Unreviewed Go toolchain: " + info["GoVersion"])
    for toolchain in toolchains:
        require_bundles([toolchain["bundle"]], bundles)
    main = info.get("Main", {})
    main_records = [item for item in lock["mainModules"] if item["path"] == main.get("Path")
                    and ("version" not in item or item["version"] == main.get("Version"))]
    if not main_records:
        raise ValueError("Unreviewed Go main module: " + repr(main))
    for record in main_records:
        require_bundles([record["bundle"]], bundles)
        materials = record.get("materials", [])
        if not materials or any(safe_path(path) not in bundles[record["bundle"]]["verifiedMembers"] for path in materials):
            raise ValueError("Go main module lacks its verified source materials")
    dependencies = []
    for dependency in info.get("Deps") or []:
        effective = dependency.get("Replace") or dependency
        if dependency.get("Replace") and effective.get("Version") in {None, "", "(devel)"}:
            replacements = [item for item in lock["localReplacements"]
                            if item["path"] == dependency["Path"] and item["directory"] == effective["Path"]]
            if not replacements or not lock["buildInputs"]:
                raise ValueError("Unreviewed local Go replacement: " + repr(dependency))
            for replacement in replacements:
                require_bundles([replacement["bundle"]], bundles)
            dependencies.append({"path": dependency["Path"], "version": dependency.get("Version"),
                                 "localReplacement": effective["Path"]})
            continue
        matches = [item for item in lock["goModules"] if item["path"] == effective.get("Path")
                   and item["version"] == effective.get("Version") and item["h1"] == effective.get("Sum")]
        if not matches or not effective.get("Sum", "").startswith("h1:"):
            raise ValueError("Unreviewed Go dependency/version/h1: " + repr(effective))
        for record in matches:
            require_bundles([record["bundle"]], bundles)
        dependencies.append({"path": dependency["Path"], "effectivePath": effective["Path"],
                             "version": effective["Version"], "h1": effective["Sum"]})
    return {"path": binary["path"], "sha256": binary["sha256"], "toolchain": info["GoVersion"],
            "main": main, "dependencies": dependencies}


def verify_layer(layer, lock, bundles):
    if layer["errors"]:
        raise ValueError("Unparsed Go binaries in distributed layer")
    go_records = [verify_go_binary(binary, lock, bundles) for binary in layer["goBinaries"]]
    go_paths = {safe_path(item["path"].lstrip("/")) for item in go_records}
    known_databases = {"lib/apk/db/installed", "var/lib/apk/db/installed", "var/lib/dpkg/status"}
    known_databases.update(item["path"] for item in lock["approvedOsDatabases"])
    databases = []
    approved_native = set(lock["approvedNativeElfSHA256"])
    for file in layer["files"]:
        name = safe_path(file["path"])
        if name in known_databases and file.get("sha256"):
            matches = [item for item in lock["approvedOsDatabases"] if item["path"] == name and item["sha256"] == file["sha256"]]
            if not matches:
                raise ValueError("Unreviewed OS package database: " + name + " " + file["sha256"])
            for item in matches:
                require_bundles(item["bundles"], bundles)
            databases.append({"path": name, "sha256": file["sha256"]})
        if file.get("elf") and name not in go_paths and file["sha256"] not in approved_native:
            raise ValueError("Unreviewed native ELF: " + name + " " + file["sha256"])
        if file.get("goBuildInfoDetected") and name not in go_paths:
            raise ValueError("Detected Go binary lacks verified build information: " + name)
    approvals = [item for item in lock["approvedLayers"] if item["diffId"] == layer["diffId"]]
    if approvals:
        for item in approvals:
            require_bundles(item["bundles"], bundles)
        approval = "exact-reviewed-layer"
    elif databases:
        approval = "reviewed-os-database-upgrade"
    else:
        allowed = {safe_path(name) for name in lock["compiledGoPaths"]}
        if not go_paths or not go_paths.issubset(allowed):
            raise ValueError("Unknown layer without an approved OS database or approved Go-copy contents")
        for file in layer["files"]:
            name = safe_path(file["path"])
            if file["type"] == tarfile.DIRTYPE.decode():
                continue
            if name not in go_paths or name not in allowed or not file.get("goBuildInfoDetected"):
                raise ValueError("Unreviewed file in new Go-copy layer: " + name)
        approval = "verified-go-copy-layer"
    return {"diffId": layer["diffId"], "approval": approval, "osDatabases": databases, "goBinaries": go_records,
            "filesInspected": len(layer["files"])}


def verify_archive(archive_path, lock_path, output, repository=ROOT):
    output.mkdir(parents=True, exist_ok=True)
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    inputs = verify_build_inputs(lock, repository)
    bundles = {}
    for item in lock["bundles"]:
        if item["id"] in bundles:
            raise ValueError("Duplicate material bundle ID")
        bundles[item["id"]] = verify_bundle(item, output)
    if not bundles:
        raise ValueError("No reviewed distribution material bundles")
    inspection = output / "inspection"
    inspection.mkdir(exist_ok=False)
    scratch = output / "scratch"
    scratch.mkdir(exist_ok=False)
    images, layers = [], {}
    with tarfile.open(archive_path, "r:*") as archive:
        seen = set()
        for member in archive.getmembers():
            name = safe_path(member.name)
            if name in seen or not (member.isfile() or member.isdir()):
                raise ValueError("Duplicate or linked Docker archive member: " + name)
            seen.add(name)
        manifests = json.load(archive.extractfile("manifest.json"))
        if not manifests:
            raise ValueError("No images in distribution archive")
        for entry in manifests:
            image = inspector.inspect_image(archive, entry, inspection, scratch)
            if not image["platform"].startswith("linux/"):
                raise ValueError("Only reviewed Linux infrastructure images are supported")
            for item in image["layers"]:
                if item["diffId"] not in layers:
                    inventory = json.loads((inspection / item["inventoryPath"]).read_text(encoding="utf-8"))
                    layers[item["diffId"]] = verify_layer(inventory, lock, bundles)
            images.append({"imageId": image["imageId"], "repoTags": image["repoTags"], "diffIds": image["rootfsDiffIds"]})
    receipt = {"verified": True, "archiveSHA256": sha256(archive_path), "archiveBytes": archive_path.stat().st_size,
               "lockSHA256": sha256(lock_path), "buildInputs": inputs,
               "buildInputDirectories": lock.get("buildInputDirectories", {}), "images": images,
               "layers": list(layers.values()), "bundles": [{key: value for key, value in item.items() if key != "verifiedMembers"} for item in bundles.values()],
               "inspectionScope": "Every file in every distributed layer, including overwritten and whiteout-hidden files",
               "startedContainers": False}
    inspector.write_json(output / "verification.json", receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", type=Path, default=ROOT)
    args = parser.parse_args()
    result = verify_archive(args.archive, args.lock, args.output.resolve(), args.repository.resolve())
    print(json.dumps({"verified": True, "images": len(result["images"]), "layers": len(result["layers"]), "archiveSHA256": result["archiveSHA256"]}))


if __name__ == "__main__":
    main()
