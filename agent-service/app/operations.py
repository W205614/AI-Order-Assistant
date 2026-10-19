"""Read backup health metadata only; never mount or read database dumps here."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import time


def prometheus_operations() -> str:
    directory = os.getenv("BACKUP_HEALTH_DIRECTORY", "").strip()
    if not directory:
        return ""
    available = 0
    success = 0.0
    attempt = 0.0
    free = 0
    try:
        path = Path(directory)
        free = shutil.disk_usage(path).free
        with (path / "backup.prom").open(encoding="utf-8") as source:
            content = source.read(4097)
        if len(content) > 4096:
            raise ValueError("Oversized backup health metadata")
        values = {}
        expected = {
            "ai_order_backup_last_success_timestamp_seconds",
            "ai_order_backup_last_attempt_success",
        }
        for line in content.splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            name, raw = line.split()
            if name in expected:
                if name in values:
                    raise ValueError("Duplicate backup metric")
                values[name] = float(raw)
        success = values["ai_order_backup_last_success_timestamp_seconds"]
        attempt = values["ai_order_backup_last_attempt_success"]
        if not 0 <= success <= time.time() + 60 or attempt not in (0, 1):
            raise ValueError("Invalid backup health metadata")
        available = 1
    except (OSError, ValueError, KeyError):
        success = attempt = 0.0
    return (
        "# TYPE ai_order_backup_status_available gauge\n"
        f"ai_order_backup_status_available {available}\n"
        "# TYPE ai_order_backup_last_success_timestamp_seconds gauge\n"
        f"ai_order_backup_last_success_timestamp_seconds {success:.17g}\n"
        "# TYPE ai_order_backup_last_attempt_success gauge\n"
        f"ai_order_backup_last_attempt_success {attempt:g}\n"
        "# TYPE ai_order_backup_filesystem_available_bytes gauge\n"
        f"ai_order_backup_filesystem_available_bytes {free}\n"
    )
