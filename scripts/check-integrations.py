"""Read-only integration preflight; no SMS, payment, upload or model request is sent."""

import argparse
import json
from pathlib import Path
import re
import ssl
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from integration_settings import read_settings, secret

ROOT = Path(__file__).resolve().parents[1]


def inspect(settings, production=False, cloud=False):
    checks = []

    def check(name, passed, detail):
        checks.append({"name": name, "passed": bool(passed), "detail": detail})

    def has(name):
        try:
            return bool(secret(settings, name))
        except OSError:
            return False

    def complete(names):
        return all(has(name) for name in names)

    payment = settings.get("PAYMENT_MODE", "SIMULATED")
    check("payment.mode", payment in {"SIMULATED", "WECHAT_PARTNER"}, "Known payment implementation")
    if payment == "WECHAT_PARTNER":
        check(
            "payment.configuration",
            complete(
                (
                    "WECHAT_MCH_ID", "WECHAT_APP_ID", "WECHAT_APP_SECRET",
                    "WECHAT_PRIVATE_KEY_PATH", "WECHAT_CERTIFICATE_SERIAL",
                    "WECHAT_PUBLIC_KEY_ID", "WECHAT_PUBLIC_KEY_PATH", "WECHAT_API_V3_KEY",
                )
            ),
            "Service-provider, OAuth and signing settings are present; permissions remain unverified",
        )
        notify = urlsplit(settings.get("WECHAT_NOTIFY_BASE_URL", ""))
        check("payment.callback", notify.scheme == "https" and bool(notify.hostname), "Public HTTPS callback origin")
        for key in ("WECHAT_PRIVATE_KEY_PATH", "WECHAT_PUBLIC_KEY_PATH"):
            key_path = Path(settings.get(key, ""))
            if str(key_path).replace("\\", "/").startswith("/run/integration-secrets/"):
                key_path = Path(settings.get("INTEGRATION_SECRETS_DIRECTORY", str(ROOT / ".local/integration-secrets"))) / key_path.name
            check("payment." + key.lower(), key_path.is_file(), "Signing key file exists on this host or its configured secret mount")
        try:
            valid_v3 = len(secret(settings, "WECHAT_API_V3_KEY").encode("utf-8")) == 32
        except OSError:
            valid_v3 = False
        check("payment.api_v3_key", valid_v3, "API v3 key is exactly 32 bytes")
    sms = settings.get("SMS_PROVIDER", "disabled")
    check("sms.provider", sms in {"disabled", "aliyun"}, "Known SMS implementation")
    if sms == "aliyun":
        check("sms.configuration", complete(("SMS_REGION_ID", "SMS_ACCESS_KEY_ID", "SMS_ACCESS_KEY_SECRET", "SMS_SIGN_NAME")) and (has("SMS_OTP_TEMPLATE_CODE") or has("SMS_TEMPLATE_CODE")), "SMS credentials, signature and OTP template are present; approval and delivery remain unverified")
    storage = settings.get("STORAGE_PROVIDER", "local")
    check("storage.provider", storage in {"local", "oss"}, "Known storage implementation")
    if storage == "oss":
        check("storage.configuration", complete(("OSS_ENDPOINT", "OSS_REGION", "OSS_BUCKET", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET")), "OSS configuration is present; bucket access remains unverified")
        endpoint = urlsplit(settings.get("OSS_ENDPOINT", ""))
        check("storage.https", endpoint.scheme == "https" and bool(endpoint.hostname), "OSS uses HTTPS")
    if production:
        check("production.payment", payment == "WECHAT_PARTNER", "Production cannot use simulated payments")
        check("production.demo", settings.get("DEMO_SEED_ENABLED", "false").lower() == "false", "Demo seed disabled")
        check("production.cookies", settings.get("COOKIE_SECURE", "false").lower() == "true", "Secure cookies enabled")
        check("production.domain", bool(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", settings.get("APP_DOMAIN", ""))), "A DNS domain is configured")
        check("production.sms", sms == "aliyun", "Verified-phone recovery requires the real SMS provider")
        check("production.storage", storage == "oss", "Production images use private OSS storage")
        check("production.maps", has("AMAP_API_KEY"), "Amap service key configured; quota remains unverified")
        check("production.alerts", has("ALERT_WEBHOOK_URL") or has("ALERT_SMTP_TO"), "Alert receiver configured; receipt remains unverified")
    if cloud:
        jdbc = settings.get("CLOUD_DB_URL", "")
        check("cloud.mysql_tls", jdbc.startswith("jdbc:mysql://") and bool(re.search(r"(?:[?&])sslMode=VERIFY_IDENTITY(?:&|$)", jdbc)), "MySQL requires certificate and hostname verification")
        check("cloud.mysql_accounts", complete(("CLOUD_DB_USERNAME", "CLOUD_DB_PASSWORD", "CLOUD_DB_MIGRATION_USERNAME", "CLOUD_DB_MIGRATION_PASSWORD")), "Independent DML and migration account settings")
        check("cloud.account_separation", settings.get("CLOUD_DB_USERNAME") != settings.get("CLOUD_DB_MIGRATION_USERNAME"), "Application and migration identities differ")
        redis = urlsplit(settings.get("CLOUD_REDIS_URL", ""))
        check("cloud.redis_tls", redis.scheme == "rediss" and bool(redis.hostname) and not redis.username and not redis.password, "Agent Redis URL uses TLS; credentials are supplied separately")
        check("cloud.redis_accounts", complete(("CLOUD_REDIS_HOST", "CLOUD_REDIS_GATEWAY_USERNAME", "CLOUD_REDIS_GATEWAY_PASSWORD", "CLOUD_REDIS_AGENT_USERNAME", "CLOUD_REDIS_AGENT_PASSWORD")), "Separate Redis ACL users configured")
    return checks


def live_checks(base_url):
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username:
        raise ValueError("Invalid application base URL")
    if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Non-local applications require HTTPS")
    checks = []
    for path in ("/actuator/health/readiness", "/integrations/capabilities"):
        try:
            request = urllib.request.Request(base_url.rstrip("/") + path, headers={"Accept": "application/json"})
            with urllib.request.urlopen(request, timeout=10, context=ssl.create_default_context()) as response:
                body = json.load(response)
            passed = body.get("status") == "UP" if "health" in path else isinstance(body.get("data", body), dict)
        except (OSError, ValueError, urllib.error.URLError):
            passed = False
        checks.append({"name": "application" + path, "passed": passed, "detail": "Read-only application HTTP probe; third-party transactions were not exercised"})
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--production", action="store_true")
    parser.add_argument("--cloud", action="store_true")
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--base-url", default="http://127.0.0.1:9090")
    args = parser.parse_args()
    try:
        checks = inspect(read_settings(args.env_file), args.production, args.cloud)
        if args.live:
            checks.extend(live_checks(args.base_url))
        passed = all(item["passed"] for item in checks)
        print(json.dumps({"schemaVersion": 1, "passed": passed, "checks": checks, "productionServicesVerified": False, "sideEffectingRequestsSent": False}, ensure_ascii=False))
        return int(not passed)
    except (OSError, ValueError):
        print(json.dumps({"passed": False, "error": "Settings unavailable or invalid", "productionServicesVerified": False}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
