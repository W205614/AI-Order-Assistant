"""Render a private Alertmanager configuration without printing credentials."""

import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit

from integration_settings import read_settings, required, secret, write_private_json

ROOT = Path(__file__).resolve().parents[1]


def build_config(settings):
    receiver = {"name": "trial-operators"}
    global_config = {"resolve_timeout": "5m"}
    if settings.get("ALERT_SMTP_TO", "").strip():
        global_config.update(
            smtp_smarthost=required(settings, "ALERT_SMTP_HOST"),
            smtp_from=required(settings, "ALERT_SMTP_FROM"),
            smtp_require_tls=True,
        )
        username = secret(settings, "ALERT_SMTP_USERNAME")
        if username:
            global_config["smtp_auth_username"] = username
            global_config["smtp_auth_password"] = required(settings, "ALERT_SMTP_PASSWORD")
        receiver["email_configs"] = [
            {"to": settings["ALERT_SMTP_TO"], "send_resolved": True}
        ]
    webhook = secret(settings, "ALERT_WEBHOOK_URL")
    if webhook:
        parsed = urlsplit(webhook)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username:
            raise ValueError("ALERT_WEBHOOK_URL must be an HTTPS URL without userinfo")
        receiver["webhook_configs"] = [{"url": webhook, "send_resolved": True}]
    if not any(key in receiver for key in ("email_configs", "webhook_configs")):
        raise ValueError("Configure an SMTP recipient or an HTTPS webhook receiver")
    return {
        "global": global_config,
        "route": {
            "receiver": receiver["name"],
            "group_by": ["alertname", "severity"],
            "group_wait": "30s",
            "group_interval": "5m",
            "repeat_interval": "4h",
        },
        "receivers": [receiver],
        "inhibit_rules": [
            {
                "source_matchers": ['severity="critical"'],
                "target_matchers": ['severity="warning"'],
                "equal": ["alertname"],
            }
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--output", type=Path, default=ROOT / ".local/operations/alertmanager.yml")
    args = parser.parse_args()
    try:
        settings = read_settings(args.env_file)
        config = build_config(settings)
        write_private_json(args.output, config)
        data = args.output.parent / "alertmanager-data"
        data.mkdir(mode=0o700, parents=True, exist_ok=True)
        data.chmod(0o700)
        print(json.dumps({"rendered": True, "receivers": len(config["receivers"])}))
        return 0
    except (OSError, ValueError):
        print(json.dumps({"rendered": False, "error": "Invalid or unavailable alert receiver settings"}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
