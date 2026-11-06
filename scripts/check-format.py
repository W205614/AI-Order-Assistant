"""Check the working copies of all tracked text files from the repository root."""

import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_SUFFIXES = {".py", ".ps1", ".sh", ".bash", ".bat", ".cmd", ".js", ".cjs", ".mjs"}
SPACE_INDENT = {".java", ".js", ".mjs", ".cjs", ".html", ".css", ".yml", ".yaml", ".json", ".py"}


def main():
    entries = subprocess.check_output(["git", "ls-files", "--eol", "-z"], cwd=ROOT)
    checked = 0
    errors = []
    for entry in entries.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        relative = Path(os.fsdecode(raw_path))
        path = ROOT / relative
        index_eol, _, attributes = metadata.split(maxsplit=2)
        if (
            relative.as_posix() == "java-gateway/src/main/resources/static/assets/vendor/qrcodegen.js"
            # Preserve the upstream generated distribution byte-for-byte; check-static-js verifies its SHA-256.
            or
            attributes.strip() == b"attr/-text"
            or (index_eol == b"i/-text" and attributes.strip() == b"attr/text=auto")
            or path.is_symlink()
            or not path.exists()
            or (
                relative.is_relative_to("docs/verification")
                and path.suffix.lower() in SCRIPT_SUFFIXES
            )
        ):
            continue
        checked += 1
        name = relative.as_posix()
        if index_eol in {b"i/crlf", b"i/mixed"} and b"eol=crlf" not in attributes:
            errors.append(f"{name}: Git index contains CRLF; review .gitattributes and re-stage")
        try:
            source = path.read_bytes().decode("utf-8").replace("\r\n", "\n")
        except UnicodeDecodeError as exc:
            errors.append(f"{name}: invalid UTF-8 at byte {exc.start}")
            continue
        if source.startswith("\ufeff"):
            errors.append(f"{name}: unexpected UTF-8 BOM")
        if source and not source.endswith("\n"):
            errors.append(f"{name}: missing final newline")
        for number, line in enumerate(source.split("\n"), 1):
            if "\r" in line:
                errors.append(f"{name}:{number}: bare carriage return")
            # Unified diff context and Markdown hard breaks use significant trailing spaces.
            if path.suffix not in {".md", ".patch"} and line.endswith((" ", "\t")):
                errors.append(f"{name}:{number}: trailing whitespace")
            indent = line[: len(line) - len(line.lstrip(" \t"))]
            if path.suffix in SPACE_INDENT and "\t" in indent:
                errors.append(f"{name}:{number}: tab in space indentation")
    if errors:
        print("\n".join(errors), file=sys.stderr)
    print(f"Checked {checked} tracked text files; {len(errors)} format errors.")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())
