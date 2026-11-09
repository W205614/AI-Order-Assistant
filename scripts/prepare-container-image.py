"""Prepare a single-layer Linux application image with verified runtime config and notices."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tarfile
import uuid

OWNER_LABEL = "io.github.w205614.ai-order-assistant.preparation"
SOURCE_IMAGE_LABEL = "io.github.w205614.ai-order-assistant.source-image"
MATERIALS_LABEL = "io.github.w205614.ai-order-assistant.materials"
STAGING_PREFIX = "ai-order-assistant-staging-"
RUNTIME_DEFAULTS = {
    "User": "",
    "ExposedPorts": {},
    "Env": [],
    "Cmd": [],
    "Entrypoint": [],
    "WorkingDir": "",
    "Healthcheck": {},
    "StopSignal": "",
}


def docker(*arguments):
    return subprocess.check_output(["docker", *map(str, arguments)], text=True, encoding="utf-8").strip()


def inspect_image(reference):
    return json.loads(docker("image", "inspect", reference))[0]


def runtime_config(config):
    # ArgsEscaped is a deprecated Windows-only compatibility field; raw changes are reported below.
    for field, value in config.items():
        if field not in RUNTIME_DEFAULTS and field not in {"Labels", "ArgsEscaped"} and value:
            raise ValueError(f"Cannot preserve effective image Config field {field!r}: {value!r}")
    return {field: config.get(field) or default for field, default in RUNTIME_DEFAULTS.items()}


def dockerfile_value(value):
    if "\n" in value or "\r" in value or "\x00" in value:
        raise ValueError("Multiline or NUL values cannot be represented by supported import instructions")
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def import_changes(config, labels):
    runtime = runtime_config(config)
    changes = []
    for entry in runtime["Env"]:
        key, separator, value = entry.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError(f"Unsupported environment entry {entry!r}")
        changes.append("ENV " + key + "=" + dockerfile_value(value))
    if runtime["User"]:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?", runtime["User"]):
            raise ValueError("Unsupported image user")
        changes.append("USER " + runtime["User"])
    if runtime["WorkingDir"]:
        if not runtime["WorkingDir"].startswith("/"):
            raise ValueError("The image working directory must be absolute")
        changes.append("WORKDIR " + dockerfile_value(runtime["WorkingDir"]))
    for port in sorted(runtime["ExposedPorts"]):
        if not re.fullmatch(r"[0-9]+/(tcp|udp|sctp)", port) or not 1 <= int(port.split("/")[0]) <= 65535:
            raise ValueError("Unsupported exposed port " + port)
        if runtime["ExposedPorts"][port]:
            raise ValueError("Unsupported nonempty exposed-port metadata")
        changes.append("EXPOSE " + port)
    for field, instruction in [("Entrypoint", "ENTRYPOINT"), ("Cmd", "CMD")]:
        if runtime[field]:
            if not isinstance(runtime[field], list) or not all(isinstance(value, str) for value in runtime[field]):
                raise ValueError("Only JSON-array commands are supported")
            changes.append(instruction + " " + json.dumps(runtime[field], ensure_ascii=False))
    if runtime["StopSignal"]:
        if not re.fullmatch(r"SIG[A-Z0-9]+|[0-9]+", runtime["StopSignal"]):
            raise ValueError("Unsupported stop signal")
        changes.append("STOPSIGNAL " + runtime["StopSignal"])
    health = runtime["Healthcheck"]
    if health:
        unsupported = set(health) - {"Test", "Interval", "Timeout", "StartPeriod", "StartInterval", "Retries"}
        if unsupported:
            raise ValueError("Unsupported health-check fields: " + repr(sorted(unsupported)))
        test = health.get("Test")
        if test == ["NONE"]:
            if any(value for key, value in health.items() if key != "Test"):
                raise ValueError("Disabled health checks with additional effective fields are unsupported")
            changes.append("HEALTHCHECK NONE")
        elif isinstance(test, list) and test and test[0] in {"CMD", "CMD-SHELL"}:
            options = []
            for field, option in [("Interval", "interval"), ("Timeout", "timeout"), ("StartPeriod", "start-period"), ("StartInterval", "start-interval"), ("Retries", "retries")]:
                value = health.get(field, 0)
                if not isinstance(value, int) or value < 0:
                    raise ValueError("Invalid health-check " + field)
                if value:
                    options.append("--" + option + "=" + str(value) + ("" if field == "Retries" else "ns"))
            if test[0] == "CMD":
                command = json.dumps(test[1:], ensure_ascii=False)
            elif len(test) == 2 and "\n" not in test[1] and "\r" not in test[1]:
                command = test[1]
            else:
                raise ValueError("Unsupported shell health check")
            changes.append("HEALTHCHECK " + " ".join(options) + " CMD " + command)
        else:
            raise ValueError("Unsupported health-check command")
    for key, value in sorted(labels.items()):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", key):
            raise ValueError("Unsupported label name")
        changes.append("LABEL " + key + "=" + dockerfile_value(value))
    return changes


def export_rootfs(image_id, destination, owner):
    name = "ai-order-license-export-" + uuid.uuid4().hex[:16]
    container_id = docker("create", "--name", name, "--label", OWNER_LABEL + "=" + owner,
                          "--network", "none", "--hostname", "ai-order-license-export",
                          "--entrypoint", "/__never_started__", image_id)
    try:
        container = json.loads(docker("container", "inspect", container_id))[0]
        if container["Config"].get("Labels", {}).get(OWNER_LABEL) != owner:
            raise ValueError("The export container ownership label does not match")
        if container["Image"] != image_id or container.get("Mounts"):
            raise ValueError("The export container must use the exact image without any mounts")
        if container["State"]["Status"] != "created" or container["State"].get("Pid"):
            raise ValueError("The export container must never be started")
        docker("container", "export", "--output", destination, container_id)
    finally:
        container = json.loads(docker("container", "inspect", container_id))[0]
        if container["Config"].get("Labels", {}).get(OWNER_LABEL) == owner and not container["State"]["Running"]:
            docker("container", "rm", container_id)


def tar_inventory(path):
    entries = {}
    with tarfile.open(path, "r|") as archive:
        for item in archive:
            pure = PurePosixPath(item.name)
            if pure.is_absolute() or ".." in pure.parts:
                raise ValueError("Unsafe exported rootfs path: " + item.name)
            name = pure.as_posix().removeprefix("./").rstrip("/")
            if name in {"", "."}:
                continue
            if name in entries:
                raise ValueError("Duplicate rootfs path: " + name)
            record = {"type": item.type.decode("ascii"), "mode": item.mode, "uid": item.uid, "gid": item.gid}
            if item.isfile():
                record.update(size=item.size, sha256=hashlib.file_digest(archive.extractfile(item), "sha256").hexdigest())
            elif item.issym() or item.islnk():
                record["linkname"] = item.linkname
            elif item.ischr() or item.isblk():
                record.update(devmajor=item.devmajor, devminor=item.devminor)
            elif not (item.isdir() or item.isfifo()):
                raise ValueError("Unsupported rootfs entry type: " + repr(item.type))
            if item.pax_headers:
                attributes = {key: value for key, value in item.pax_headers.items()
                              if key not in {"mtime", "atime", "ctime", "path", "linkpath", "size", "uid", "gid", "uname", "gname"}}
                if attributes:
                    record["extendedAttributes"] = attributes
            entries[name] = record
    for name, record in entries.items():
        if record["type"] == tarfile.LNKTYPE.decode():
            target = PurePosixPath(record["linkname"]).as_posix().removeprefix("./")
            seen = {name}
            while entries.get(target, {}).get("type") == tarfile.LNKTYPE.decode():
                if target in seen:
                    raise ValueError("Cyclic exported hard links")
                seen.add(target)
                target = PurePosixPath(entries[target]["linkname"]).as_posix().removeprefix("./")
            if target not in entries or "sha256" not in entries[target]:
                raise ValueError("Unresolved exported hard link " + name)
            record["sha256"] = entries[target]["sha256"]
            record["size"] = entries[target]["size"]
    return entries


def append_notices(rootfs_path, notices):
    paths = sorted(notices.rglob("*"))
    for path in [notices, *paths]:
        metadata = path.lstat()
        if path.is_symlink() or getattr(metadata, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise ValueError("Notice inputs must not contain symlinks or reparse points")
        if not (path.is_file() or path.is_dir()):
            raise ValueError("Notice inputs must contain only regular files and directories")
    with tarfile.open(rootfs_path, "a") as archive:
        for path in [notices, *paths]:
            name = "licenses" + ("/" + path.relative_to(notices).as_posix() if path != notices else "")
            item = tarfile.TarInfo(name)
            item.uid = item.gid = 0
            item.mtime = 0
            item.mode = 0o755 if path.is_dir() else 0o644
            if path.is_dir():
                item.type = tarfile.DIRTYPE
                archive.addfile(item)
            else:
                item.size = path.stat().st_size
                with path.open("rb") as source:
                    archive.addfile(item, source)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True), encoding="utf-8")


def prepare(args):
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.image):
        raise ValueError("Use the complete immutable local source image ID")
    if not args.tag.startswith(STAGING_PREFIX) or ":" not in args.tag or "/" in args.tag:
        raise ValueError("Use a new local tag starting with " + STAGING_PREFIX)
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_revision):
        raise ValueError("A complete application source revision is required")
    for value in [args.source_url, args.materials_url]:
        if not value.startswith("https://") or any(character.isspace() for character in value):
            raise ValueError("Source and material URLs must be HTTPS URLs without whitespace")
    source = inspect_image(args.image)
    if source["Os"] != "linux":
        raise ValueError("Only Linux application images are supported")
    config = source["Config"]
    before_config = runtime_config(config)
    if config.get("Labels", {}).get("org.opencontainers.image.revision") != args.source_revision:
        raise ValueError("The application image source-revision label does not match")
    # Docker export excludes volume contents, so images declaring volumes cannot use this path.
    if config.get("Volumes"):
        raise ValueError("Images declaring volumes cannot be exported losslessly")
    existing = subprocess.run(["docker", "image", "inspect", args.tag], capture_output=True, text=True)
    if existing.returncode == 0:
        raise ValueError("The local staging tag already exists")
    if "No such image" not in existing.stderr and "No such object" not in existing.stderr:
        raise RuntimeError(existing.stderr.strip())
    notices = args.notices.resolve()
    for name in [args.notice_file, args.source_manifest]:
        if PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
            raise ValueError("Notice filenames must be relative to the notice input directory")
        if not (notices / name).is_file() or not (notices / name).stat().st_size:
            raise ValueError("Missing nonempty notice input " + name)
    json.loads((notices / args.source_manifest).read_text(encoding="utf-8"))
    owner = uuid.uuid4().hex
    labels = {
        "org.opencontainers.image.source": args.source_url,
        "org.opencontainers.image.revision": args.source_revision,
        "org.opencontainers.image.version": args.version,
        "org.opencontainers.image.title": args.title,
        "org.opencontainers.image.created": datetime.now(timezone.utc).isoformat(),
        "org.opencontainers.image.licenses": "MIT AND LicenseRef-Third-Party",
        "org.opencontainers.image.description": "Application code MIT; independent third-party licenses, notices and source acquisition details in /licenses.",
        SOURCE_IMAGE_LABEL: args.image,
        MATERIALS_LABEL: args.materials_url,
        OWNER_LABEL: owner,
    }
    changes = import_changes(config, labels)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "source-image.json", source)
    rootfs = output / "prepared-rootfs.tar"
    export_rootfs(args.image, rootfs, owner)
    before_files = tar_inventory(rootfs)
    if any(name == "licenses" or name.startswith("licenses/") for name in before_files):
        raise ValueError("The source already contains /licenses; refusing to overwrite it")
    write_json(output / "source-rootfs-manifest.json", before_files)
    append_notices(rootfs, notices)
    platform = source["Os"] + "/" + source["Architecture"] + ("/" + source["Variant"] if source.get("Variant") else "")
    command = ["image", "import", "--platform", platform, "--message", "Verified final rootfs with distribution materials"]
    for change in changes:
        command.extend(["--change", change])
    command.extend([str(rootfs), args.tag])
    image_id = docker(*command).splitlines()[-1]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
        raise ValueError("Docker import did not return a complete image ID")
    prepared = inspect_image(image_id)
    write_json(output / "prepared-image.json", prepared)
    after_config = runtime_config(prepared["Config"])
    if before_config != after_config:
        write_json(output / "runtime-config-mismatch.json", {"before": before_config, "after": after_config})
        fields = sorted(field for field in before_config if before_config[field] != after_config[field])
        raise ValueError("Docker import changed runtime Config fields " + repr(fields) + "; inspect runtime-config-mismatch.json")
    if (prepared["Os"], prepared["Architecture"], prepared.get("Variant")) != (source["Os"], source["Architecture"], source.get("Variant")):
        raise ValueError("Docker import changed the image platform")
    if len(prepared["RootFS"]["Layers"]) != 1:
        raise ValueError("The prepared image must contain exactly one filesystem layer")
    verified_rootfs = output / "verified-rootfs.tar"
    export_rootfs(image_id, verified_rootfs, owner)
    after_files = tar_inventory(verified_rootfs)
    write_json(output / "prepared-rootfs-manifest.json", after_files)
    expected = tar_inventory(rootfs)
    if expected != after_files:
        differences = sorted(name for name in set(expected) | set(after_files) if expected.get(name) != after_files.get(name))
        write_json(output / "rootfs-mismatch.json", {"paths": differences})
        raise ValueError("The imported rootfs changed file content, permissions, ownership or links")
    preserved = {name: value for name, value in after_files.items() if name != "licenses" and not name.startswith("licenses/")}
    if preserved != before_files:
        raise ValueError("The prepared image changed files outside /licenses")
    report = {
        "sourceImageId": args.image,
        "preparedImageId": image_id,
        "stagingTag": args.tag,
        "sourceRevision": args.source_revision,
        "materialsURL": args.materials_url,
        "platform": platform,
        "sourceLayerCount": len(source["RootFS"]["Layers"]),
        "preparedLayerCount": 1,
        "runtimeConfigBefore": before_config,
        "runtimeConfigAfter": after_config,
        "runtimeConfigEquivalent": True,
        "nonRuntimeMetadataChanges": ([{
            "field": "ArgsEscaped",
            "before": config.get("ArgsEscaped"),
            "after": prepared["Config"].get("ArgsEscaped"),
            "reason": "Deprecated Windows-only pre-escaped command compatibility field; no Linux runtime effect",
            "reference": "https://github.com/opencontainers/image-spec/blob/main/config.md",
        }] if config.get("ArgsEscaped") != prepared["Config"].get("ArgsEscaped") else []),
        "rootfsEquivalentExceptAddedLicenses": True,
        "rootfsComparisonFields": ["path", "type", "sha256", "size", "mode", "uid", "gid", "linkname", "device", "extendedAttributes"],
        "rootfsTimestampComparison": "Not part of the content, permissions, ownership and link comparison",
        "preservedPathCount": len(before_files),
        "addedLicensePathCount": len(after_files) - len(before_files),
        "sourceImageUnchanged": inspect_image(args.image)["Id"] == source["Id"],
        "temporaryExportContainersRemoved": True,
    }
    write_json(output / "container-image-verification.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--materials-url", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--notices", type=Path, required=True)
    parser.add_argument("--notice-file", default="THIRD-PARTY-NOTICES.txt")
    parser.add_argument("--source-manifest", default="source-materials.json")
    parser.add_argument("--output", type=Path, required=True)
    print(json.dumps(prepare(parser.parse_args())))


if __name__ == "__main__":
    main()
