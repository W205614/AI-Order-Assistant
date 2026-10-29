"""Validate deployment boundaries without starting containers or calling a model."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = {"mysql", "redis", "agent", "gateway"}
OPERATIONS = {"backup", "prometheus"}
DAILY = BASE | {"prometheus"}
MODEL_ENV = ("CAMPUS_EVAL_LLM_API_KEY", "CAMPUS_EVAL_LLM_BASE_URL", "CAMPUS_EVAL_LLM_MODEL", "CAMPUS_EVAL_RUN_ID")


def config(overrides=(), *, profiles=(), model=False, missing_model=None):
    env = dict(os.environ)
    for key in ("COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_PROJECT_NAME", *MODEL_ENV):
        env.pop(key, None)
    env.update(LLM_API_KEY="", APP_DOMAIN="example.invalid")
    if model or missing_model:
        env.update(zip(MODEL_ENV, ("config-validation-only", "https://example.invalid/v1", "config-validation-only", "config-validation-only")))
    if missing_model:
        env.pop(missing_model)
    command = ["docker", "compose", "--env-file", str(ROOT / ".github/compose-ci.env"), "-p", "ai-order-config", "-f", str(ROOT / "docker-compose.yml")]
    for override in overrides:
        command += ["-f", str(ROOT / "deploy/compose" / override)]
    for profile in profiles:
        command += ["--profile", profile]
    result = subprocess.run(command + ["config", "--format", "json"], cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8")
    if missing_model:
        assert result.returncode != 0, "Model override unexpectedly accepted missing credentials"
        assert missing_model in result.stderr, "Model override failed for an unrelated reason"
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


def agent_security(services):
    agent = services["agent"]
    assert agent["read_only"] is True
    assert agent.get("cap_drop") == ["ALL"], "Agent must drop every Linux capability"
    assert agent.get("security_opt") == ["no-new-privileges:true"], "Agent must not gain privileges through setuid programs"


def main():
    default = config()["services"]
    agent_security(default)
    assert set(default) == DAILY
    ports(default["gateway"], {("127.0.0.1", "9090")})
    ports(default["prometheus"], {("127.0.0.1", "19091")})
    for name in ("mysql", "redis", "agent"):
        ports(default[name], set())
    for name, directory in (("gateway", "java-gateway"), ("agent", "agent-service")):
        assert Path(default[name]["build"]["context"]).resolve() == (ROOT / directory).resolve()
    root_bind(default["agent"], "/app/backup-health", "backups/health")
    health = next(item for item in default["agent"]["volumes"] if item["target"] == "/app/backup-health")
    assert health.get("read_only") is True
    assert all(item["target"] != "/backups" for item in default["agent"]["volumes"]), "Agent must not read database dumps"
    for name in ("gateway", "agent"):
        assert default[name]["logging"]["options"] == {"max-size": "10m", "max-file": "3"}
    print("PASS default: 5 services, backup opt-in, root paths and local ports")

    manual_backup = config(profiles=("manual-backup",))["services"]
    agent_security(manual_backup)
    assert set(manual_backup) == BASE | OPERATIONS
    assert manual_backup["backup"]["profiles"] == ["manual-backup"]
    root_bind(manual_backup["backup"], "/backups", "backups")
    root_bind(manual_backup["backup"], "/backup.sh", "deploy/backup.sh")
    assert manual_backup["backup"]["logging"]["options"] == {"max-size": "10m", "max-file": "3"}
    print("PASS manual-backup: 6 services, explicitly enabled backup retains root paths")

    isolated = config(("isolated.yml",))["services"]
    agent_security(isolated)
    assert set(isolated) == BASE
    ports(isolated["gateway"], {("127.0.0.1", "19090")})
    assert isolated["agent"]["environment"]["LLM_API_KEY"] == ""
    assert isolated["agent"]["environment"]["BACKUP_HEALTH_DIRECTORY"] == ""
    health = next(item for item in isolated["agent"]["volumes"] if item["target"] == "/app/backup-health")
    assert health["type"] == "volume" and health["source"] == "isolated-backup-health"
    operations = config(("isolated.yml",), profiles=("operations",))["services"]
    agent_security(operations)
    assert set(operations) == BASE | OPERATIONS
    mount = next(item for item in operations["backup"]["volumes"] if item["target"] == "/backups")
    assert mount["type"] == "volume" and mount["source"] == "isolated-backups"
    for target, source in (("/health", "isolated-backup-health"), ("/extra-backups", "isolated-extra-backups")):
        mount = next(item for item in operations["backup"]["volumes"] if item["target"] == target)
        assert mount["type"] == "volume" and mount["source"] == source
    assert operations["backup"]["environment"]["BACKUP_EXTRA_ENABLED"] == ""
    ports(operations["prometheus"], set())
    print("PASS isolated: 4 services, operations profile enables 6; no production backup or monitoring port")

    demo = config(("isolated.yml", "demo.yml"))["services"]
    agent_security(demo)
    assert set(demo) == BASE
    ports(demo["gateway"], {("127.0.0.1", "19092")})
    assert demo["gateway"]["labels"]["com.ai-order.demo"] == "true"
    assert demo["agent"]["environment"]["LLM_API_KEY"] == ""
    assert not any(v.get("type") == "bind" and v["target"] == "/app/backup-health"
                   for v in demo["agent"]["volumes"])
    print("PASS demo: dedicated local port/identity and isolated health volume, model disabled")

    public = config(("public.yml",))["services"]
    agent_security(public)
    assert set(public) == DAILY | {"proxy"}
    ports(public["gateway"], set())
    assert public["gateway"]["environment"]["COOKIE_SECURE"] == "true"
    assert public["gateway"]["environment"]["DEMO_SEED_ENABLED"] == "false"
    root_bind(public["proxy"], "/etc/caddy/Caddyfile", "deploy/Caddyfile")
    print("PASS public: 6 services, backup opt-in, HTTPS cookie, no direct gateway port")

    files = ("isolated.yml", "model-eval.yml")
    # Compose may report any missing interpolation first. Isolate each required
    # value instead of assuming iteration order when all four are absent.
    for key in MODEL_ENV:
        config(files, missing_model=key)
    model = config(files, model=True)["services"]
    agent_security(model)
    assert set(model) == BASE and model["agent"]["environment"]["LLM_API_KEY"] == "config-validation-only"
    assert model["agent"]["environment"]["AGENT_EVALUATION_RUN_ID"] == "config-validation-only"
    assert str(model["agent"]["environment"]["AGENT_EVALUATION_MAX_CALLS"]) == "60"
    assert str(model["agent"]["environment"]["AGENT_EVALUATION_MAX_UNITS"]) == "150000"
    print("PASS model: explicit credentials required, config only, no provider request")


if __name__ == "__main__":
    main()
