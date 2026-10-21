"""Validate deployment boundaries without starting containers or calling a model."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = {"mysql", "redis", "agent", "gateway"}
OPERATIONS = {"backup", "prometheus"}
MODEL_ENV = ("CAMPUS_EVAL_LLM_API_KEY", "CAMPUS_EVAL_LLM_BASE_URL", "CAMPUS_EVAL_LLM_MODEL", "CAMPUS_EVAL_RUN_ID")


def config(overrides=(), *, profiles=(), model=False, expect_failure=False):
    env = dict(os.environ)
    for key in ("COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_PROJECT_NAME", *MODEL_ENV):
        env.pop(key, None)
    env.update(LLM_API_KEY="", APP_DOMAIN="example.invalid")
    if model:
        env.update(zip(MODEL_ENV, ("config-validation-only", "https://example.invalid/v1", "config-validation-only", "config-validation-only")))
    command = ["docker", "compose", "--env-file", str(ROOT / ".github/compose-ci.env"), "-p", "ai-order-config", "-f", str(ROOT / "docker-compose.yml")]
    for override in overrides:
        command += ["-f", str(ROOT / "deploy/compose" / override)]
    for profile in profiles:
        command += ["--profile", profile]
    result = subprocess.run(command + ["config", "--format", "json"], cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8")
    if expect_failure:
        assert result.returncode != 0, "Model override unexpectedly accepted missing credentials"
        assert "CAMPUS_EVAL_LLM_API_KEY" in result.stderr, "Model override failed for an unrelated reason"
        return None
    # Raw config/stderr may contain credentials; never print them.
    if result.returncode:
        raise RuntimeError("Compose validation failed; inspect the selected files locally")
    return json.loads(result.stdout)


def ports(service, expected):
    actual = {(item.get("host_ip", ""), str(item["published"])) for item in service.get("ports", [])}
    assert actual == expected, "Unexpected published ports"


def root_bind(service, target, relative):
    mount = next(item for item in service["volumes"] if item["target"] == target)
    assert mount["type"] == "bind" and Path(mount["source"]).resolve() == (ROOT / relative).resolve(), "Bind path no longer resolves from root"


def main():
    default = config()["services"]
    assert set(default) == BASE | OPERATIONS
    ports(default["gateway"], {("127.0.0.1", "9090")})
    ports(default["prometheus"], {("127.0.0.1", "19091")})
    for name in ("mysql", "redis", "agent"):
        ports(default[name], set())
    for name, directory in (("gateway", "java-gateway"), ("agent", "agent-service")):
        assert Path(default[name]["build"]["context"]).resolve() == (ROOT / directory).resolve()
    root_bind(default["backup"], "/backups", "backups")
    root_bind(default["backup"], "/backup.sh", "deploy/backup.sh")
    root_bind(default["agent"], "/app/backup-health", "backups/health")
    health = next(item for item in default["agent"]["volumes"] if item["target"] == "/app/backup-health")
    assert health.get("read_only") is True
    assert all(item["target"] != "/backups" for item in default["agent"]["volumes"]), "Agent must not read database dumps"
    for name in ("gateway", "agent", "backup"):
        assert default[name]["logging"]["options"] == {"max-size": "10m", "max-file": "3"}
    print("PASS default: 6 services, root paths and local ports")

    isolated = config(("isolated.yml",))["services"]
    assert set(isolated) == BASE
    ports(isolated["gateway"], {("127.0.0.1", "19090")})
    assert isolated["agent"]["environment"]["LLM_API_KEY"] == ""
    assert isolated["agent"]["environment"]["BACKUP_HEALTH_DIRECTORY"] == ""
    health = next(item for item in isolated["agent"]["volumes"] if item["target"] == "/app/backup-health")
    assert health["type"] == "volume" and health["source"] == "isolated-backup-health"
    operations = config(("isolated.yml",), profiles=("operations",))["services"]
    assert set(operations) == BASE | OPERATIONS
    mount = next(item for item in operations["backup"]["volumes"] if item["target"] == "/backups")
    assert mount["type"] == "volume" and mount["source"] == "isolated-backups"
    for target, source in (("/health", "isolated-backup-health"), ("/extra-backups", "isolated-extra-backups")):
        mount = next(item for item in operations["backup"]["volumes"] if item["target"] == target)
        assert mount["type"] == "volume" and mount["source"] == source
    assert operations["backup"]["environment"]["BACKUP_EXTRA_ENABLED"] == ""
    ports(operations["prometheus"], set())
    print("PASS isolated: 4 services, no model key; optional operations cannot use production backup or port")

    demo = config(("isolated.yml", "demo.yml"))["services"]
    assert set(demo) == BASE
    ports(demo["gateway"], {("127.0.0.1", "19092")})
    assert demo["gateway"]["labels"]["com.ai-order.demo"] == "true"
    assert demo["agent"]["environment"]["LLM_API_KEY"] == ""
    assert not any(v.get("type") == "bind" and v["target"] == "/app/backup-health"
                   for v in demo["agent"]["volumes"])
    print("PASS demo: dedicated local port/identity and isolated health volume, model disabled")

    public = config(("public.yml",))["services"]
    assert set(public) == BASE | OPERATIONS | {"proxy"}
    ports(public["gateway"], set())
    assert public["gateway"]["environment"]["COOKIE_SECURE"] == "true"
    assert public["gateway"]["environment"]["DEMO_SEED_ENABLED"] == "false"
    root_bind(public["proxy"], "/etc/caddy/Caddyfile", "deploy/Caddyfile")
    print("PASS public: 7 services, HTTPS cookie, no direct gateway port")

    files = ("isolated.yml", "model-eval.yml")
    config(files, expect_failure=True)
    model = config(files, model=True)["services"]
    assert set(model) == BASE and model["agent"]["environment"]["LLM_API_KEY"] == "config-validation-only"
    assert model["agent"]["environment"]["AGENT_EVALUATION_RUN_ID"] == "config-validation-only"
    assert str(model["agent"]["environment"]["AGENT_EVALUATION_MAX_CALLS"]) == "60"
    assert str(model["agent"]["environment"]["AGENT_EVALUATION_MAX_UNITS"]) == "150000"
    print("PASS model: explicit credentials required, config only, no provider request")


if __name__ == "__main__":
    main()
