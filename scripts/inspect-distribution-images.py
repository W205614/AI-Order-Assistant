"""Read historical CI images without starting their programs or business services."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def command(*args):
    return subprocess.check_output(args, text=True, encoding="utf-8")


def selected_metadata(name):
    return name in {
        "lib/apk/db/installed", "var/lib/dpkg/status", "etc/os-release",
        "etc/alpine-release", "etc/issue", "etc/services", "etc/protocols",
        "etc/ssl/cert.pem", "etc/ssl/certs/ca-certificates.crt",
        "usr/share/zoneinfo/tzdata.zi", "etc/buildroot-release",
    } or re.search(r"(?:license|licence|copyright|copying|notice)", name.rsplit("/", 1)[-1], re.I)


def safe_name(name):
    name = name.removeprefix("./")
    if name.startswith("/") or ".." in name.split("/") or "\\" in name:
        raise ValueError("Unsafe archive member: " + name)
    return name


def inspect_layer(layer_path, layer_sha, expected_diff_id, output, scratch):
    """Inspect each distributed layer, including files later overwritten or removed."""
    receipt = output / "layers" / layer_sha / "inventory.json"
    if receipt.exists():
        previous = json.loads(receipt.read_text(encoding="utf-8"))
        if previous["diffId"] != expected_diff_id:
            raise ValueError("Previously inspected layer identity changed")
        return previous
    with layer_path.open("rb") as stream:
        magic = stream.read(4)
    if magic[:2] == b"\x1f\x8b":
        import gzip

        uncompressed = scratch / (layer_sha + ".tar")
        with gzip.open(layer_path, "rb") as src, uncompressed.open("wb") as dst:
            shutil.copyfileobj(src, dst)
    elif magic == b"\x28\xb5\x2f\xfd":
        raise ValueError("Zstandard OCI layer needs explicit decompression support")
    else:
        uncompressed = layer_path
    diff_id = "sha256:" + digest(uncompressed)
    if diff_id != expected_diff_id:
        raise ValueError("Layer uncompressed digest does not match image config")
    files = []
    go_binaries = []
    errors = []
    metadata_root = receipt.parent / "metadata"
    with tarfile.open(uncompressed, "r:") as archive:
        for index, member in enumerate(archive):
            name = safe_name(member.name)
            record = {"path": name, "type": member.type.decode("ascii"), "mode": member.mode}
            if member.issym() or member.islnk():
                record["link"] = member.linkname
            if member.isfile():
                sha = hashlib.sha256()
                is_elf = False
                has_go_buildinfo = False
                carry = b""
                version_data = bytearray()
                collect_versions = name.rsplit("/", 1)[-1] == "busybox" or re.search(r"(?:^|/)libc(?:[.-]|$)", name)
                with archive.extractfile(member) as stream:
                    first = True
                    while chunk := stream.read(1024 * 1024):
                        if first:
                            is_elf = chunk.startswith(b"\x7fELF")
                            first = False
                        sha.update(chunk)
                        if is_elf and b"\xff Go buildinf:" in carry + chunk:
                            has_go_buildinfo = True
                        carry = chunk[-32:]
                        if collect_versions:
                            version_data.extend(chunk)
                record.update(sha256=sha.hexdigest(), bytes=member.size, elf=is_elf)
                if selected_metadata(name) and not is_elf:
                    dest = metadata_root / str(index) / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    with archive.extractfile(member) as src, dest.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    record["metadataPath"] = dest.relative_to(output).as_posix()
                if has_go_buildinfo:
                    binary = scratch / (layer_sha + "-" + str(index) + ".elf")
                    with archive.extractfile(member) as src, binary.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    entry = {"path": "/" + name, "sha256": record["sha256"], "layer": "sha256:" + layer_sha}
                    try:
                        entry["buildInfo"] = json.loads(command("go", "version", "-m", "-json", str(binary)))
                    except (subprocess.CalledProcessError, json.JSONDecodeError) as exc:
                        entry["error"] = str(exc)
                        errors.append(entry)
                    go_binaries.append(entry)
                    record["goBuildInfoDetected"] = True
                if version_data:
                    record["versionStrings"] = sorted({
                        value.decode("ascii") for value in re.findall(rb"(?:BusyBox v|uClibc|GLIBC_)[ -~]{0,100}", version_data)
                    })
            files.append(record)
    result = {"layerSHA256": layer_sha, "diffId": diff_id, "files": files, "goBinaries": go_binaries, "errors": errors}
    write_json(receipt, result)
    return result


def inspect_image(archive, entry, output, scratch):
    config_name = safe_name(entry["Config"])
    config_bytes = archive.extractfile(config_name).read()
    config_sha = hashlib.sha256(config_bytes).hexdigest()
    declared = config_name.rsplit("/", 1)[-1].removesuffix(".json")
    if re.fullmatch(r"[0-9a-f]{64}", declared) and declared != config_sha:
        raise ValueError("Image config digest mismatch")
    config = json.loads(config_bytes)
    diff_ids = config["rootfs"]["diff_ids"]
    if len(diff_ids) != len(entry["Layers"]):
        raise ValueError("Image layer count does not match config")
    layers = []
    scratch.mkdir(parents=True, exist_ok=True)
    for index, (name, diff_id) in enumerate(zip(entry["Layers"], diff_ids)):
        name = safe_name(name)
        filename = name.rsplit("/", 1)[-1]
        with archive.extractfile(name) as src:
            layer_sha = hashlib.file_digest(src, "sha256").hexdigest()
        if re.fullmatch(r"[0-9a-f]{64}", filename) and filename != layer_sha:
            raise ValueError("OCI layer blob digest mismatch")
        layer_path = scratch / (layer_sha + ".blob")
        if not layer_path.exists():
            with archive.extractfile(name) as src, layer_path.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            if digest(layer_path) != layer_sha:
                raise ValueError("Cached layer blob digest mismatch")
        layer = inspect_layer(layer_path, layer_sha, diff_id, output, scratch)
        layers.append({"index": index, "archivePath": name, "sha256": layer_sha, "diffId": diff_id, "inventoryPath": "layers/" + layer_sha + "/inventory.json", "goBinaries": layer["goBinaries"], "errors": layer["errors"]})
    result = {
        "imageId": "sha256:" + config_sha, "platform": config["os"] + "/" + config["architecture"],
        "revision": (config.get("config", {}).get("Labels") or {}).get("org.opencontainers.image.revision"),
        "repoTags": entry.get("RepoTags"), "rootfsDiffIds": diff_ids, "layers": layers,
    }
    image_output = output / "images" / config_sha
    write_json(image_output / "config.json", config)
    write_json(image_output / "inventory.json", result)
    return result


def download_artifact(repository, item, scratch):
    target = scratch / "artifact.zip"
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/actions/artifacts/{item['id']}/zip",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "distribution-inspection"},
    )
    request.add_unredirected_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    with urllib.request.urlopen(request, timeout=90) as src, target.open("wb") as dst:
        shutil.copyfileobj(src, dst)
    if target.stat().st_size != item["size_in_bytes"]:
        raise ValueError("Artifact size mismatch")
    image_tar = scratch / "images.tar.gz"
    with zipfile.ZipFile(target) as archive:
        if archive.namelist() != ["infrastructure-images.tar.gz"] or archive.testzip() is not None:
            raise ValueError("Unexpected or corrupt infrastructure image artifact")
        with archive.open("infrastructure-images.tar.gz") as src, image_tar.open("wb") as dst:
            shutil.copyfileobj(src, dst)
    return target, image_tar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--repository", default="W205614/AI-Order-Assistant")
    args = parser.parse_args()
    all_go_binaries = []
    all_errors = []
    for item in json.loads(args.inventory.read_text(encoding="utf-8")):
        if not re.fullmatch(r"infrastructure-images-[0-9a-f]{40}", item["name"]):
            raise ValueError("Unexpected artifact name")
        work = args.scratch / str(item["id"])
        work.mkdir(parents=True, exist_ok=False)
        target, image_tar = download_artifact(args.repository, item, work)
        images = []
        with tarfile.open(image_tar, "r:gz") as archive:
            manifests = json.load(archive.extractfile("manifest.json"))
            for entry in manifests:
                result = inspect_image(archive, entry, args.output, args.scratch / "layers")
                images.append({key: result[key] for key in ("imageId", "platform", "revision", "rootfsDiffIds", "repoTags", "layers")})
                for layer in result["layers"]:
                    for binary in layer["goBinaries"]:
                        all_go_binaries.append({"artifactId": item["id"], "artifactName": item["name"], "image": result["imageId"], "layerIndex": layer["index"], **binary})
                    all_errors.extend(layer["errors"])
        receipt = {
            "artifact": item, "zipSHA256": digest(target), "imageArchiveSHA256": digest(image_tar),
            "images": images, "startedApplicationContainers": False,
            "loadedDockerImages": False, "scanScope": "every file in every distributed layer, including overwritten and whiteout-hidden files",
        }
        write_json(args.output / "artifacts" / (str(item["id"]) + ".json"), receipt)
        write_json(args.output / "go-buildinfo.json", {"schemaVersion": 1, "binaries": all_go_binaries, "errors": all_errors})
        print(json.dumps({"artifactId": item["id"], "images": len(images), "status": "inspected", "goBinaryRecords": len(all_go_binaries)}), flush=True)
    if all_errors:
        raise ValueError("Some Go buildinfo records could not be parsed; inspect go-buildinfo.json errors")


if __name__ == "__main__":
    main()
