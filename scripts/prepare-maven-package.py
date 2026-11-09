"""Prepare the application-only Maven JAR and sources from a clean release checkout."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip():
        raise ValueError("Prepare candidates only from a clean release checkout")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    module = ROOT / "java-gateway"
    pom = module / "pom.xml"
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = ET.parse(pom).getroot().findtext("m:version", namespaces=ns)
    stem = "assistant-gateway-" + version
    original = module / "target" / (stem + ".jar.original")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    notices = [ROOT / "LICENSE", ROOT / "THIRD_PARTY_NOTICES.md", *sorted((ROOT / "licenses").glob("*"))]
    notice_names = {"META-INF/" + path.relative_to(ROOT).as_posix() for path in notices}
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(output / (stem + ".jar"), "w", zipfile.ZIP_DEFLATED) as target:
        for entry in source.infolist():
            if entry.filename.startswith("BOOT-INF/lib/"):
                raise ValueError("The Maven package must not bundle third-party dependencies")
            if entry.filename in notice_names:
                continue
            target.writestr(entry, source.read(entry.filename))
        for notice in notices:
            target.write(notice, "META-INF/" + notice.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(output / (stem + "-sources.jar"), "w", zipfile.ZIP_DEFLATED) as target:
        for path in sorted((module / "src/main/java").rglob("*.java")):
            target.write(path, path.relative_to(module / "src/main/java").as_posix())
        for notice in notices:
            target.write(notice, "META-INF/" + notice.relative_to(ROOT).as_posix())
    (output / (stem + ".pom")).write_bytes(pom.read_bytes())
    manifest = {"sourceRevision": revision, "version": version, "bundledDependencies": False, "files": {}}
    for kind, name in (("jar", stem + ".jar"), ("sources", stem + "-sources.jar"), ("pom", stem + ".pom")):
        manifest["files"][kind] = {"name": name, "sha256": hashlib.sha256((output / name).read_bytes()).hexdigest()}
    (output / "maven-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest))


if __name__ == "__main__":
    main()
