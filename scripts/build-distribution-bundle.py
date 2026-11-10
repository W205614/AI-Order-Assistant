"""Build and read back the locked OS sources and original-notices distribution."""

import argparse
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import zipfile


SPEC = importlib.util.spec_from_file_location("distribution_inputs", Path(__file__).with_name("fetch-distribution-materials.py"))
INPUTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INPUTS)
KINDS = ("os", "go-additional", "release-delta")

ARCHIVE_SUFFIXES = (".tar", ".tar.gz", ".tgz", ".tar.xz", ".tar.bz2", ".zip", ".jar")


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def digest_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def reject_elf(stream, path):
    if stream.read(4) == b"\x7fELF":
        raise ValueError("ELF binary is not a source/notices material: " + path)
    stream.seek(0)


def collect_notices(path, origin, notices):
    """Read original texts without extracting upstream paths or following links."""
    def remember(body, source):
        digest = hashlib.sha256(body).hexdigest()
        entry = notices.setdefault(digest, {"body": body, "sources": set()})
        entry["sources"].add(source)

    def legal_name(name):
        return PurePosixPath(name).name.lower().startswith(("license", "licence", "copying", "notice", "copyright", "patents", "assembly_exception", "additional_license_info"))

    all_files = origin.get("noticeArchiveAllFiles", False)

    def remember_text(body, source):
        if all_files and b"\0" in body:
            raise ValueError("Non-text member in the declared complete legal tree: " + source)
        remember(body, source)

    def visit(stream, name, source, direct=False):
        reject_elf(stream, source)
        if name.lower().endswith((".zip", ".jar")):
            with zipfile.ZipFile(stream) as archive:
                for member in archive.infolist():
                    if member.is_dir() or (member.external_attr >> 16) & 0o170000 == 0o120000:
                        continue
                    with archive.open(member) as entry:
                        prefix = entry.read(4)
                        if all_files or legal_name(member.filename):
                            remember_text(prefix + entry.read(), source + "!" + member.filename)
                        elif name.lower().endswith("-sources.jar") and member.filename.endswith((".java", ".kt")):
                            # Maven source JARs often keep attribution only in source
                            # headers. Preserve exact comment bytes and member paths.
                            body = prefix + entry.read()
                            for match in re.finditer(rb"/\*.*?\*/|(?m:(?://[^\n]*\n)+)", body, re.S):
                                comment = match.group()
                                lower = comment.lower()
                                if b"copyright" in lower or (b"redistribution and use" in lower and b"disclaimer" in lower):
                                    remember(comment, source + "!" + member.filename)
            return
        if name.lower().endswith(ARCHIVE_SUFFIXES):
            with tarfile.open(fileobj=stream, mode="r:*") as archive:
                for member in archive:
                    if not member.isfile():
                        if (member.issym() or member.islnk()) and (all_files or legal_name(member.name)):
                            # Resolve only another member of this locked archive. No
                            # archive path or link is materialized on the filesystem.
                            try:
                                with archive.extractfile(member) as linked:
                                    remember_text(linked.read(), source + "!" + member.name)
                            except KeyError as error:
                                raise ValueError("Unresolved original notice link: " + source + "!" + member.name) from error
                        continue
                    with archive.extractfile(member) as entry:
                        member_source = source + "!" + member.name
                        # Locked upstream source archives retain original test fixtures;
                        # they are inspected as data, never extracted or executed.
                        if all_files or legal_name(member.name):
                            remember_text(entry.read(), member_source)
                        elif member.name.lower().endswith(ARCHIVE_SUFFIXES) and len(PurePosixPath(member.name).parts) <= 2:
                            # GCC source and the captured legal trees wrap another source
                            # archive; deeply nested test-fixture archives remain source data.
                            visit(entry, member.name, member_source)
            return
        if direct or legal_name(name):
            remember(stream.read(), source)

    with path.open("rb") as stream:
        visit(stream, path.name, origin["path"], direct="license-notice-body" in origin["roles"])


def notices_text(notices, version, kind):
    sections = ["AI-Order-Assistant " + version + " — " + kind + " original licenses and notices\n",
                "Original texts are deduplicated by their exact bytes. Every observed source path is retained below.\n"
                "The unmodified bytes are also in notices/<SHA-256>.txt; source archives and build recipes are included.\n"]
    records = []
    for digest, entry in sorted(notices.items()):
        try:
            text, encoding = entry["body"].decode("utf-8"), "utf-8"
        except UnicodeDecodeError:
            text, encoding = entry["body"].decode("latin-1"), "latin-1"
        sources = sorted(entry["sources"])
        sections.append("\n" + "=" * 78 + "\nSHA-256: " + digest + "\nSources:\n" + "\n".join("- " + source for source in sources) + "\nOriginal text (" + encoding + "):\n\n" + text + "\n")
        records.append({"path": "notices/" + digest + ".txt", "sha256": digest, "bytes": len(entry["body"]), "textEncoding": encoding, "sourcePaths": sources})
    return "".join(sections).encode("utf-8"), records


def validate_identity(version, kind):
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("A fixed numeric release version is required")
    if kind not in KINDS:
        raise ValueError("Unsupported source material scope")
    if kind == "release-delta" and version != "0.1.3":
        raise ValueError("release-delta targets the explicitly reviewed version 0.1.3")


def validate_lock(lock, version, kind):
    if kind == "release-delta":
        if type(lock.get("schemaVersion")) is not int or lock["schemaVersion"] != 1 or lock.get("version") != version or lock.get("kind") != kind:
            raise ValueError("release-delta requires a typed source lock for the selected version and kind")
        if not isinstance(lock.get("materials"), list) or not lock["materials"]:
            raise ValueError("release-delta requires reviewed source materials")


def build_bundle(lock_path, materials, output, version="0.1.2", kind="os"):
    validate_identity(version, kind)
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    validate_lock(lock, version, kind)
    payload, seen, notices = {}, {}, {}
    for item in lock["materials"]:
        path = INPUTS.material_path(materials, item["localPath"])
        if any(part in (".env", ".git", ".local", "backups") for part in PurePosixPath(item["localPath"]).parts):
            raise ValueError("Private project configuration is not a distribution input")
        INPUTS.verify_file(path, item)
        for algorithm in ("sha256", "sha512"):
            if item.get(algorithm):
                with path.open("rb") as stream:
                    if hashlib.file_digest(stream, algorithm).hexdigest() != item[algorithm]:
                        raise ValueError("Material digest mismatch: " + item["localPath"])
        sha256 = digest_file(path)
        previous = seen.get(item["localPath"])
        if previous and previous != sha256:
            raise ValueError("Conflicting material content")
        seen[item["localPath"]] = sha256
        payload["materials/" + item["localPath"]] = path
        roles = item.get("roles", [item.get("role")])
        reference = "materials/" + item["localPath"]
        collect_notices(path, {"path": reference, "roles": roles, "noticeArchiveAllFiles": item.get("noticeArchiveAllFiles", False)}, notices)
        if "license-notice-body" in roles:
            body_digest = hashlib.sha256(path.read_bytes()).hexdigest()
            for origin in item.get("origins", [item]):
                origin_path = origin.get("archivePath", item["localPath"])
                if origin.get("archiveMember"):
                    origin_path += "!" + origin["archiveMember"]
                label = origin.get("component", item.get("component", "")) + " " + origin.get("version", item.get("version", ""))
                notices[body_digest]["sources"].add(label.strip() + ": materials/" + origin_path)
    if not notices:
        raise ValueError("No original license/NOTICE text was collected")
    text, notice_records = notices_text(notices, version, kind)
    payload["NOTICES.txt"] = text
    payload["sources.lock.json"] = lock_path.read_bytes()
    repository = Path(__file__).resolve().parents[1]
    payload["PROJECT-LICENSE"] = (repository / "LICENSE").read_bytes()
    for script in ("build-distribution-bundle.py", "fetch-distribution-materials.py", "publish-source-materials.py"):
        payload["build-tools/" + script] = (repository / "scripts" / script).read_bytes()
    for digest, entry in notices.items():
        payload["notices/" + digest + ".txt"] = entry["body"]
    files = []
    for name, value in sorted(payload.items()):
        INPUTS.material_path(Path("payload"), name)
        files.append({"path": name, "sha256": digest_file(value) if isinstance(value, Path) else hashlib.sha256(value).hexdigest(), "bytes": value.stat().st_size if isinstance(value, Path) else len(value)})
    internal = {"schemaVersion": 1, "version": version, "kind": kind, "scope": lock.get("scope"), "sourceLockSHA256": hashlib.sha256(lock_path.read_bytes()).hexdigest(), "files": files, "notices": notice_records, "metadataFiles": ["MANIFEST.json", "SHA256SUMS.txt"], "includesStandaloneRuntimeELF": False, "upstreamSourceArchivesPreserved": True}
    payload["MANIFEST.json"] = json_bytes(internal)
    checksums = {entry["path"]: entry["sha256"] for entry in files}
    checksums["MANIFEST.json"] = hashlib.sha256(payload["MANIFEST.json"]).hexdigest()
    payload["SHA256SUMS.txt"] = "".join(checksums[name] + "  " + name + "\n" for name in sorted(checksums)).encode("utf-8")
    output.mkdir(parents=True, exist_ok=True)
    prefix = "AI-Order-Assistant-" + version + "-" + kind + "-"
    bundle = output / (prefix + "source-materials.tar.gz")
    with bundle.open("wb") as destination, gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0, compresslevel=6) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
            for name, value in sorted(payload.items()):
                info = tarfile.TarInfo(name)
                info.size = value.stat().st_size if isinstance(value, Path) else len(value)
                info.mode = 0o644
                if isinstance(value, Path):
                    with value.open("rb") as stream:
                        archive.addfile(info, stream)
                else:
                    archive.addfile(info, io.BytesIO(value))
    notice_asset = output / (prefix + "NOTICES.txt")
    notice_asset.write_bytes(text)
    publication = {"schemaVersion": 1, "version": version, "kind": kind, "bundle": {"name": bundle.name, "sha256": digest_file(bundle), "bytes": bundle.stat().st_size}, "notices": {"name": notice_asset.name, "sha256": digest_file(notice_asset), "bytes": notice_asset.stat().st_size}, "payloadManifest": internal}
    manifest = output / (prefix + "source-materials.manifest.json")
    manifest.write_bytes(json_bytes(publication))
    result = verify_bundle(bundle, manifest, notice_asset)
    (output / "verification.json").write_bytes(json_bytes(result))
    return result


def verify_bundle(bundle, manifest_path, notices_path):
    publication = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_identity(publication.get("version"), publication.get("kind"))
    if type(publication.get("schemaVersion")) is not int or publication["schemaVersion"] != 1:
        raise ValueError("Unsupported publication manifest schema")
    for key, path in (("bundle", bundle), ("notices", notices_path)):
        expected = publication[key]
        if path.name != expected["name"] or path.stat().st_size != expected["bytes"] or digest_file(path) != expected["sha256"]:
            raise ValueError("Published asset digest or size mismatch: " + key)
    with tarfile.open(bundle, "r:gz") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        if len(set(names)) != len(names):
            raise ValueError("Duplicate bundle member")
        for member in members:
            INPUTS.material_path(Path("bundle"), member.name)
            if not member.isfile():
                raise ValueError("Bundle contains a non-file member")
        internal_bytes = archive.extractfile("MANIFEST.json").read()
        internal = json.loads(internal_bytes)
        if internal != publication["payloadManifest"]:
            raise ValueError("Internal manifest differs from the publication manifest")
        if type(internal.get("schemaVersion")) is not int or internal["schemaVersion"] != 1 or internal.get("version") != publication["version"] or internal.get("kind") != publication["kind"]:
            raise ValueError("Bundle manifest version, kind or schema differs from publication")
        lock_bytes = archive.extractfile("sources.lock.json").read()
        if hashlib.sha256(lock_bytes).hexdigest() != internal.get("sourceLockSHA256"):
            raise ValueError("Bundled source lock differs from the manifest")
        validate_lock(json.loads(lock_bytes), publication["version"], publication["kind"])
        expected = {entry["path"]: entry for entry in internal["files"]}
        if len(expected) != len(internal["files"]) or set(names) != set(expected) | {"MANIFEST.json", "SHA256SUMS.txt"}:
            raise ValueError("Bundle members differ from the complete manifest")
        hashes = {"MANIFEST.json": hashlib.sha256(internal_bytes).hexdigest()}
        for name, record in expected.items():
            member = archive.getmember(name)
            with archive.extractfile(member) as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if member.size != record["bytes"] or digest != record["sha256"]:
                raise ValueError("Bundle member digest or size mismatch: " + name)
            hashes[name] = digest
        checksums = "".join(hashes[name] + "  " + name + "\n" for name in sorted(hashes)).encode("utf-8")
        if archive.extractfile("SHA256SUMS.txt").read() != checksums:
            raise ValueError("Bundle checksum list differs from the manifest")
        if archive.extractfile("NOTICES.txt").read() != notices_path.read_bytes():
            raise ValueError("External notices differ from the bundled original notices")
    return {"bundleSHA256": publication["bundle"]["sha256"], "filesVerified": len(expected), "noticeBodies": len(internal["notices"]), "internalChecksumsVerified": True, "readbackVerified": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path)
    parser.add_argument("--materials", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--version", default="0.1.2")
    parser.add_argument("--kind", choices=KINDS, default="os")
    parser.add_argument("--verify-bundle", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--notices", type=Path)
    args = parser.parse_args()
    if args.verify_bundle:
        result = verify_bundle(args.verify_bundle, args.manifest, args.notices)
    else:
        if not all((args.lock, args.materials, args.output)):
            parser.error("--lock, --materials and --output are required for a build")
        result = build_bundle(args.lock, args.materials.resolve(), args.output.resolve(), args.version, args.kind)
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
