"""Validate cloud/operations Compose shapes using synthetic settings, without starting services."""

import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    environment = dict(os.environ)
    for name in ("COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_PROJECT_NAME"):
        environment.pop(name, None)
    environment.update(
        APP_DOMAIN="example.invalid",
        CLOUD_DB_URL="jdbc:mysql://db.example.invalid:3306/ai_order_assistant?sslMode=VERIFY_IDENTITY",
        CLOUD_DB_USERNAME="app", CLOUD_DB_PASSWORD="configuration-only-app-secret",
        CLOUD_DB_MIGRATION_USERNAME="migrator", CLOUD_DB_MIGRATION_PASSWORD="configuration-only-migration-secret",
        CLOUD_REDIS_URL="rediss://cache.example.invalid:6379/0", CLOUD_REDIS_HOST="cache.example.invalid",
        CLOUD_REDIS_GATEWAY_USERNAME="gateway", CLOUD_REDIS_GATEWAY_PASSWORD="configuration-only-gateway-secret",
        CLOUD_REDIS_AGENT_USERNAME="agent", CLOUD_REDIS_AGENT_PASSWORD="configuration-only-agent-secret",
        OPERATIONS_UID="1000", OPERATIONS_GID="1000",
    )
    cases = (("cloud.yml",), ("operations.yml",), ("cloud.yml", "public.yml", "operations.yml"))
    for overrides in cases:
        command = ["docker", "compose", "--env-file", str(ROOT / ".github/compose-ci.env"), "-p", "ai-order-configuration", "-f", str(ROOT / "docker-compose.yml")]
        for override in overrides:
            command += ["-f", str(ROOT / "deploy/compose" / override)]
        result = subprocess.run(command + ["config", "--format", "json"], env=environment, capture_output=True, text=True, encoding="utf-8", timeout=30)
        if result.returncode:
            raise RuntimeError("Compose could not resolve " + ",".join(overrides))
        services = json.loads(result.stdout)["services"]
        if "cloud.yml" in overrides:
            assert "mysql" not in services and "redis" not in services, "Cloud mode must exclude local data services"
            assert set(services["gateway"]["depends_on"]) == {"migrate"}
            assert not services["agent"].get("depends_on") and not services["migrate"].get("depends_on")
            assert services["gateway"]["environment"]["SPRING_DATA_REDIS_SSL_ENABLED"] == "true"
            assert services["agent"]["environment"]["REDIS_SSL_REQUIRED"] == "true"
            for component in ("gateway", "agent", "migrate"):
                assert any(item["target"] == "/run/cloud-certs" and item.get("read_only") for item in services[component]["volumes"])
            assert "egress" in services["gateway"]["networks"] and "egress" in services["migrate"]["networks"]
        if "operations.yml" in overrides:
            alertmanager = services["alertmanager"]
            assert not alertmanager.get("ports"), "Operations interfaces must not expose a host port"
            assert Path(alertmanager["build"]["context"]).resolve() == ROOT / "deploy/alertmanager"
            assert alertmanager["read_only"] and alertmanager["cap_drop"] == ["ALL"]
            assert set(alertmanager["networks"]) == {"monitoring", "egress"}
            mounts = {item["target"]: item for item in services["prometheus"]["volumes"]}
            assert mounts["/etc/prometheus/prometheus.yml"]["source"].endswith("prometheus-operations.yml")
        print("PASS " + ",".join(overrides))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
