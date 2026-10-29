"""Scan resolved Compose runtime images without starting application services.

Only image references leave the captured Compose configuration. Every invocation
uses a fresh report directory so stale/partial reports cannot satisfy the gate.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
SCANNER = "aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa"
REQUIRED = {"gateway", "agent", "mysql", "redis", "backup", "prometheus"}


class ScanError(RuntimeError):
    pass


def command(args: list[str], stage: str, *, env: dict | None = None, timeout: int = 1200,
            diagnostic: Path | None = None) -> str:
    try:
        result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        if diagnostic:
            diagnostic.write_text(f"{stage}: {type(exc).__name__}\n", encoding="utf-8")
        raise ScanError(f"{stage}: command unavailable or timed out") from exc
    if diagnostic and result.stderr:
        diagnostic.write_text(result.stderr, encoding="utf-8")
    if result.returncode:
        # Compose output may contain expanded environment values. Never echo it.
        log = f"; see {diagnostic.name}" if diagnostic else ""
        raise ScanError(f"{stage}: command exited {result.returncode}{log}")
    return result.stdout


def compose_inventory(project: str, env_file: Path, include_public: bool) -> list[dict]:
    entries = []
    deployments = [("root", [])]
    if include_public:
        deployments.append(("public", ["-f", str(ROOT / "deploy/compose/public.yml")]))
    for deployment, overlay in deployments:
        environment = dict(os.environ, APP_DOMAIN="example.invalid")
        raw = command(["docker", "compose", "--env-file", str(env_file), "-p", project,
                       "-f", str(ROOT / "docker-compose.yml"), *overlay,
                       "--profile", "manual-backup",
                       "config", "--format", "json"], f"{deployment} Compose resolution", env=environment)
        try:
            config = json.loads(raw)
            services = config["services"]
            if not isinstance(services, dict) or not REQUIRED.issubset(services):
                raise ValueError("incomplete service set")
            if deployment == "public" and "proxy" not in services:
                raise ValueError("public proxy missing")
            for service, definition in services.items():
                if not re.fullmatch(r"[a-zA-Z0-9_.-]+", service) or not isinstance(definition, dict):
                    raise ValueError("invalid service definition")
                image = definition.get("image")
                built = bool(definition.get("build"))
                if not image and built:
                    image = f"{config.get('name', project)}-{service}:latest"
                if not isinstance(image, str) or not image.strip():
                    raise ValueError("service image missing")
                entries.append({"deployment": deployment, "service": service,
                                "configuredImage": image, "builtLocally": built})
        except (KeyError, TypeError, ValueError) as exc:
            raise ScanError(f"{deployment} Compose result is invalid or incomplete") from exc
    return entries


def image_identity(reference: str, built: bool, pull_missing: bool) -> dict:
    try:
        raw = command(["docker", "image", "inspect", reference], "Image inspection", timeout=60)
    except ScanError:
        if built or not pull_missing:
            raise
        command(["docker", "pull", reference], "Missing runtime image pull", timeout=900)
        raw = command(["docker", "image", "inspect", reference], "Pulled image inspection", timeout=60)
    try:
        details = json.loads(raw)
        if not isinstance(details, list) or len(details) != 1:
            raise ValueError("unexpected image count")
        identifier = details[0]["Id"]
        digests = details[0].get("RepoDigests") or []
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", identifier):
            raise ValueError("invalid image ID")
        if not isinstance(digests, list) or not all(isinstance(d, str) for d in digests):
            raise ValueError("invalid repository digests")
        return {"imageId": identifier, "repoDigests": sorted(set(digests))}
    except (KeyError, TypeError, ValueError) as exc:
        raise ScanError("Image inspection returned invalid identity metadata") from exc


def findings_from_report(path: Path) -> list[dict]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
        if (not isinstance(report, dict) or report.get("SchemaVersion") != 2
                or not isinstance(report.get("ArtifactName"), str)
                or not report["ArtifactName"] or not isinstance(report.get("Results"), list)):
            raise ValueError("incomplete Trivy report")
        findings = []
        for result in report["Results"]:
            if not isinstance(result, dict) or not isinstance(result.get("Target"), str) or not result["Target"]:
                raise ValueError("invalid Trivy result")
            vulnerabilities = result.get("Vulnerabilities", [])
            if vulnerabilities is None:
                vulnerabilities = []
            if not isinstance(vulnerabilities, list):
                raise ValueError("invalid vulnerability list")
            for vuln in vulnerabilities:
                if not isinstance(vuln, dict) or not isinstance(vuln.get("Severity"), str):
                    raise ValueError("invalid vulnerability")
                if vuln["Severity"] not in {"HIGH", "CRITICAL"}:
                    continue
                if not isinstance(vuln.get("VulnerabilityID"), str) or not isinstance(vuln.get("PkgName"), str):
                    raise ValueError("incomplete vulnerability")
                fixed = vuln.get("FixedVersion")
                if fixed is not None and not isinstance(fixed, str):
                    raise ValueError("invalid fixed version")
                findings.append({"package": vuln["PkgName"], "vulnerability": vuln["VulnerabilityID"],
                                 "severity": vuln["Severity"], "installedVersion": vuln.get("InstalledVersion"),
                                 "fixedVersion": fixed or ""})
        return findings
    except (OSError, TypeError, ValueError) as exc:
        raise ScanError(f"Report {path.name} is missing, malformed or incomplete") from exc


def scanner_args(directory: Path, cache_volume: str) -> list[str]:
    return ["docker", "run", "--rm", "-v", "/var/run/docker.sock:/var/run/docker.sock",
            "-v", f"{cache_volume}:/cache", "-v", f"{directory}:/reports", SCANNER]


def scan_image(directory: Path, image_id: str, report_name: str, gateway: bool, cache_volume: str) -> list[dict]:
    extra = ["--pkg-types", "os", "--skip-files", "**/*.jar"] if gateway else []
    command([*scanner_args(directory, cache_volume), "image", "--quiet", "--cache-dir", "/cache",
             "--scanners", "vuln", *extra, "--severity", "HIGH,CRITICAL", "--timeout", "15m",
             "--format", "json", "--output", f"/reports/{report_name}", image_id], "Image scan",
            diagnostic=directory / f"{report_name}.stderr.log")
    return findings_from_report(directory / report_name)


def scan_gateway_sbom(directory: Path, image_id: str, cache_volume: str) -> tuple[str, list[dict]]:
    suffix = image_id.split(":", 1)[1][:16]
    bom_name = f"runtime-bom-{suffix}.json"
    report_name = f"gateway-dependencies-{suffix}.json"
    container = None
    try:
        candidate = command(["docker", "create", "--name", f"ai-order-sbom-{uuid.uuid4().hex}", image_id],
                            "Gateway SBOM container creation", timeout=60).strip()
        if not re.fullmatch(r"[a-f0-9]{12,64}", candidate):
            raise ScanError("Gateway SBOM helper container identity is invalid")
        container = candidate
        command(["docker", "cp", f"{container}:/app/runtime-bom.json", str(directory / bom_name)],
                "Gateway build SBOM extraction", timeout=60)
    finally:
        if container:
            command(["docker", "rm", container], "Gateway SBOM helper container removal", timeout=60)
    command([*scanner_args(directory, cache_volume), "sbom", "--quiet", "--cache-dir", "/cache",
             "--severity", "HIGH,CRITICAL", "--timeout", "15m", "--format", "json",
             "--output", f"/reports/{report_name}", f"/reports/{bom_name}"], "Java dependency SBOM scan",
            diagnostic=directory / f"{report_name}.stderr.log")
    return report_name, findings_from_report(directory / report_name)


def scan_deployment(project: str, env_file: Path, output: Path, *, include_public: bool = True,
                    pull_missing: bool = True, cache_volume: str = "ai-order-trivy-cache") -> tuple[Path, dict]:
    run_id = datetime.now(timezone.utc).strftime("scan-%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    directory = output.resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {"schemaVersion": 1, "runId": run_id, "scannerImage": SCANNER,
                "status": "failed", "services": [], "images": [], "errors": [],
                "fixableHighCritical": 0, "unfixedHighCritical": 0}
    try:
        entries = compose_inventory(project, env_file, include_public)
        identities = {}
        groups = {}
        for entry in entries:
            try:
                reference = entry["configuredImage"]
                if reference not in identities:
                    identities[reference] = image_identity(reference, entry["builtLocally"], pull_missing)
                entry.update(identities[reference])
                group = groups.setdefault(entry["imageId"], {"imageId": entry["imageId"], "repoDigests": set(),
                                                             "services": set(), "reports": [], "status": "failed"})
                group["repoDigests"].update(entry["repoDigests"])
                group["services"].add(entry["service"])
                entry["status"] = "resolved"
            except ScanError as exc:
                entry["status"] = "failed"
                manifest["errors"].append({"stage": "inventory", "service": entry["service"], "message": str(exc)})
            manifest["services"].append(entry)
        for image_id, group in groups.items():
            group["repoDigests"] = sorted(group["repoDigests"])
            group["services"] = sorted(group["services"])
            report_name = f"image-{image_id.split(':', 1)[1]}.json"
            group["reports"].append(report_name)
            if "gateway" in group["services"]:
                group["reports"].append(f"gateway-dependencies-{image_id.split(':', 1)[1][:16]}.json")
            print(f"Scanning {', '.join(group['services'])}: {image_id}", flush=True)
            findings = []
            complete = True
            try:
                findings += scan_image(directory, image_id, report_name, "gateway" in group["services"], cache_volume)
            except ScanError as exc:
                complete = False
                manifest["errors"].append({"stage": "image-scan", "imageId": image_id, "message": str(exc)})
            if "gateway" in group["services"]:
                try:
                    _, dependencies = scan_gateway_sbom(directory, image_id, cache_volume)
                    findings += dependencies
                except ScanError as exc:
                    complete = False
                    manifest["errors"].append({"stage": "java-sbom-scan", "imageId": image_id, "message": str(exc)})
            group["fixableFindings"] = [v for v in findings if v["fixedVersion"].strip()]
            group["unfixedFindings"] = [v for v in findings if not v["fixedVersion"].strip()]
            manifest["fixableHighCritical"] += len(group["fixableFindings"])
            manifest["unfixedHighCritical"] += len(group["unfixedFindings"])
            if complete:
                group["status"] = "scanned"
            manifest["images"].append(group)
        for entry in manifest["services"]:
            if entry.get("imageId") in groups:
                group = groups[entry["imageId"]]
                entry["reports"] = group["reports"]
                entry["status"] = group["status"]
        if not manifest["errors"] and not manifest["fixableHighCritical"]:
            manifest["status"] = "passed"
    except ScanError as exc:
        manifest["errors"].append({"stage": "configuration", "message": str(exc)})
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return directory, manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-name", default="ai-order-assistant")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".github/compose-ci.env")
    parser.add_argument("--output-directory", type=Path, default=ROOT / ".local/security")
    parser.add_argument("--skip-public", action="store_true")
    parser.add_argument("--no-pull-missing", action="store_true")
    args = parser.parse_args()
    directory, manifest = scan_deployment(args.project_name, args.env_file, args.output_directory,
                                          include_public=not args.skip_public, pull_missing=not args.no_pull_missing)
    print(f"Reports: {directory}")
    print(f"Gate: {manifest['status']}; fixable HIGH/CRITICAL={manifest['fixableHighCritical']}; "
          f"unfixed HIGH/CRITICAL={manifest['unfixedHighCritical']}; errors={len(manifest['errors'])}")
    return 0 if manifest["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
