"""Read deployment settings as data; never evaluate dotenv content as shell code."""

import json
import os
from pathlib import Path


def read_settings(path=None):
    settings = {}
    if path is not None:
        for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                raise ValueError("Invalid dotenv assignment")
            name, value = line.split("=", 1)
            name, value = name.strip(), value.strip()
            if not name.replace("_", "").isalnum() or not name[0].isalpha():
                raise ValueError("Invalid dotenv setting name")
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            settings[name] = value
    settings.update(os.environ)
    return settings


def secret(settings, name):
    filename = settings.get(name + "_FILE", "").strip()
    if filename:
        path = Path(filename)
        if not path.is_file() and filename.replace("\\", "/").startswith("/run/integration-secrets/"):
            root = Path(__file__).resolve().parents[1]
            path = Path(settings.get("INTEGRATION_SECRETS_DIRECTORY", str(root / ".local/integration-secrets"))) / path.name
        return path.read_text(encoding="utf-8").strip()
    return settings.get(name, "").strip()


def write_private_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write("\n")
    path.chmod(0o600)


def required(settings, name):
    value = secret(settings, name)
    if not value:
        raise ValueError(name + " is required")
    return value
