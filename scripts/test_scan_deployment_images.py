"""Scanner inventory, deduplication and fail-closed gate tests; no Docker calls."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import scan_deployment_images as scanner


def identity(number):
    value = f"sha256:{number:064x}"
    return {"imageId": value, "repoDigests": [f"fixture@{value}"]}


def inventory(backup_same=True):
    entries = []
    for deployment in ("root", "public", "operations"):
        names = ["gateway", "migrate", "agent", "mysql", "redis", "backup", "prometheus"]
        if deployment == "public":
            names.append("proxy")
        if deployment == "operations":
            names.append("alertmanager")
        for name in names:
            image = (
                "fixture/mysql:other-tag"
                if name == "backup" and backup_same
                else f"fixture/{name}:version"
            )
            entries.append(
                {
                    "deployment": deployment,
                    "service": name,
                    "configuredImage": image,
                    "builtLocally": name in {"gateway", "agent"},
                }
            )
    return entries


class DeploymentScannerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ai-order-scanner-test-")
        self.base = Path(self.temp.name)

    def tearDown(self):
        if os.name == "nt":
            # Keep the Windows deletion contract even for disposable fixtures.
            self.temp._finalizer.detach()
            env = dict(os.environ, AI_ORDER_SCANNER_TEST_DIRECTORY=str(self.base))
            subprocess.run(
                [
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Add-Type -AssemblyName Microsoft.VisualBasic; "
                    "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory("
                    "$env:AI_ORDER_SCANNER_TEST_DIRECTORY,'OnlyErrorDialogs','SendToRecycleBin')",
                ],
                env=env,
                check=True,
                capture_output=True,
            )
        else:
            self.temp.cleanup()

    def resolve(self, reference, _built, _pull):
        service = reference.split("/")[1].split(":")[0]
        number = {
            "migrate": 1,
            "gateway": 1,
            "agent": 2,
            "mysql": 3,
            "redis": 4,
            "backup": 5,
            "prometheus": 6,
            "proxy": 7,
            "alertmanager": 8,
        }[service]
        return identity(number)

    def run_scan(self, entries=None, image_scan=None, sbom_scan=None):
        with patch.object(
            scanner, "compose_inventory", return_value=entries or inventory()
        ), patch.object(scanner, "image_identity", side_effect=self.resolve), patch.object(
            scanner, "scan_image", side_effect=image_scan or (lambda *_: [])
        ) as scanned, patch.object(
            scanner, "scan_gateway_sbom", side_effect=sbom_scan or (lambda *_: ("java.json", []))
        ) as sbom:
            directory, manifest = scanner.scan_deployment(
                "fixture", self.base / "fixture.env", self.base
            )
            return directory, manifest, scanned.call_count, sbom.call_count

    def test_same_actual_image_under_different_tags_is_scanned_once_and_mapped_to_each_service(
        self,
    ):
        _, manifest, count, sbom = self.run_scan()
        self.assertEqual(manifest["status"], "passed")
        self.assertEqual(len(manifest["services"]), 23)
        self.assertEqual(count, 7)
        self.assertEqual(sbom, 1)
        mysql = next(s for s in manifest["services"] if s["service"] == "mysql")
        backup = next(s for s in manifest["services"] if s["service"] == "backup")
        self.assertNotEqual(mysql["configuredImage"], backup["configuredImage"])
        self.assertEqual(mysql["imageId"], backup["imageId"])
        self.assertEqual(mysql["reports"], backup["reports"])
        self.assertTrue(
            all(
                s["status"] == "scanned" and s["repoDigests"] and s["reports"]
                for s in manifest["services"]
            )
        )

    def test_distinct_mysql_and_backup_images_are_not_deduplicated_by_service_name(self):
        _, manifest, count, _ = self.run_scan(entries=inventory(backup_same=False))
        self.assertEqual(count, 8)
        self.assertEqual(manifest["status"], "passed")
        self.assertEqual(len(manifest["images"]), 8)

    def test_one_scan_failure_fails_the_gate_and_other_reports_are_still_attempted(self):
        def scan(_directory, image_id, *_):
            if image_id == identity(6)["imageId"]:
                raise scanner.ScanError("fixture scanner failure")
            return []

        _, manifest, count, _ = self.run_scan(image_scan=scan)
        self.assertEqual(count, 7)
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(len(manifest["errors"]), 1)
        self.assertEqual(
            next(s for s in manifest["services"] if s["service"] == "proxy")["status"], "scanned"
        )
        self.assertEqual(
            next(s for s in manifest["services"] if s["service"] == "prometheus")["status"],
            "failed",
        )

    def test_missing_java_sbom_fails_even_when_every_image_report_passes(self):
        def missing(*_):
            raise scanner.ScanError("fixture missing Java SBOM")

        _, manifest, _, count = self.run_scan(sbom_scan=missing)
        self.assertEqual(count, 1)
        self.assertEqual(manifest["status"], "failed")
        gateway = next(s for s in manifest["services"] if s["service"] == "gateway")
        self.assertEqual(len(gateway["reports"]), 2)
        self.assertEqual(gateway["status"], "failed")

    def test_unfixed_critical_blocks_the_gate_and_remains_visible(self):
        finding = {
            "package": "fixture",
            "vulnerability": "CVE-fixture",
            "severity": "CRITICAL",
            "installedVersion": "1",
            "fixedVersion": "",
        }
        _, manifest, _, _ = self.run_scan(image_scan=lambda *_: [finding.copy()])
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(manifest["unfixedVulnerabilities"], 7)
        finding["fixedVersion"] = "2"
        _, manifest, _, _ = self.run_scan(image_scan=lambda *_: [finding.copy()])
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(manifest["fixableVulnerabilities"], 7)

    def test_every_severity_blocks_the_gate_with_or_without_a_fixed_version(self):
        for severity in ("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"):
            for fixed in ("", "2"):
                with self.subTest(severity=severity, fixedVersion=fixed):
                    finding = {
                        "package": "fixture",
                        "vulnerability": "CVE-fixture",
                        "severity": severity,
                        "installedVersion": "1",
                        "fixedVersion": fixed,
                    }
                    directory, manifest, _, _ = self.run_scan(
                        image_scan=lambda *_: [finding.copy()]
                    )
                    self.assertEqual(manifest["status"], "failed")
                    self.assertEqual(manifest["unresolvedVulnerabilities"], 7)
                    self.assertEqual(manifest["vulnerabilitiesBySeverity"][severity], 7)
                    self.assertEqual(sum(manifest["vulnerabilitiesBySeverity"].values()), 7)
                    self.assertEqual(manifest["fixableVulnerabilities"], 7 if fixed else 0)
                    self.assertEqual(manifest["unfixedVulnerabilities"], 0 if fixed else 7)
                    self.assertEqual(manifest["errors"], [])
                    self.assertEqual(
                        json.loads((directory / "manifest.json").read_text())["status"],
                        "failed",
                    )

    def test_report_parser_keeps_all_five_severities_and_original_report_bytes(self):
        path = self.base / "all-severities.json"
        severities = ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]
        raw = json.dumps({
            "SchemaVersion": 2,
            "ArtifactName": "fixture",
            "Results": [{"Target": "runtime", "Vulnerabilities": [
                {"VulnerabilityID": "CVE-fixture-" + level, "PkgName": "package-" + level,
                 "Severity": level, "InstalledVersion": "1", "FixedVersion": None}
                for level in severities
            ]}],
        }).encode()
        path.write_bytes(raw)
        findings = scanner.findings_from_report(path)
        self.assertEqual([item["severity"] for item in findings], severities)
        self.assertEqual(len(findings), 5)
        self.assertTrue(all(item["fixedVersion"] == "" for item in findings))
        self.assertEqual(path.read_bytes(), raw)

    def test_image_and_sbom_scanner_commands_include_every_severity_and_unfixed(self):
        seen = []

        def transport(arguments, *_args, **_kwargs):
            if arguments[:2] == ["docker", "create"]:
                return "a" * 64
            if scanner.SCANNER not in arguments:
                return ""
            seen.append(arguments)
            report = self.base / Path(arguments[arguments.index("--output") + 1]).name
            report.write_text(json.dumps({
                "SchemaVersion": 2,
                "ArtifactName": "fixture",
                "Results": [{"Target": "runtime", "Vulnerabilities": [{
                    "VulnerabilityID": "CVE-low-unfixed", "PkgName": "fixture",
                    "Severity": "LOW", "InstalledVersion": "1",
                }]}],
            }), encoding="utf-8")
            return ""

        with patch.object(scanner, "command", side_effect=transport):
            regular = scanner.scan_image(self.base, identity(2)["imageId"], "regular.json", False, "cache")
            gateway = scanner.scan_image(self.base, identity(1)["imageId"], "gateway.json", True, "cache")
            _, java = scanner.scan_gateway_sbom(self.base, identity(1)["imageId"], "cache")
        self.assertEqual(len(seen), 3)
        for arguments in seen:
            self.assertEqual(
                arguments[arguments.index("--severity") + 1],
                "UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL",
            )
            self.assertNotIn("--ignore-unfixed", arguments)
        self.assertEqual(regular[0]["severity"], "LOW")
        self.assertEqual(gateway[0]["severity"], "LOW")
        self.assertEqual(java[0]["severity"], "LOW")

    def test_compose_projection_keeps_only_image_inventory_and_requires_public_proxy(self):
        config = {
            "name": "fixture",
            "services": {
                name: {
                    "image": f"fixture/{name}:tag",
                    "environment": {"PRIVATE_SETTING": "never-save-this"},
                }
                for name in scanner.REQUIRED | {"proxy", "alertmanager"}
            },
        }
        with patch.object(scanner, "command", return_value=json.dumps(config)) as command:
            entries = scanner.compose_inventory("fixture", self.base / "fixture.env", True)
            self.assertNotIn("never-save-this", json.dumps(entries))
            self.assertEqual(command.call_count, 3)
            self.assertEqual(command.call_args.kwargs["env"]["APP_DOMAIN"], "example.invalid")
            for call in command.call_args_list:
                arguments = call.args[0]
                self.assertEqual(arguments[arguments.index("--profile") + 1], "manual-backup")
        del config["services"]["proxy"]
        with patch.object(scanner, "command", return_value=json.dumps(config)):
            with self.assertRaises(scanner.ScanError):
                scanner.compose_inventory("fixture", self.base / "fixture.env", True)

    def test_operations_image_is_required_even_when_public_scope_is_skipped(self):
        config = {
            "name": "fixture",
            "services": {name: {"image": "fixture/" + name + ":tag"} for name in scanner.REQUIRED | {"alertmanager"}},
        }
        with patch.object(scanner, "command", return_value=json.dumps(config)) as command:
            entries = scanner.compose_inventory("fixture", self.base / "fixture.env", False)
            self.assertEqual(command.call_count, 2)
            self.assertTrue(any(item["deployment"] == "operations" and item["service"] == "alertmanager" for item in entries))
        del config["services"]["alertmanager"]
        with patch.object(scanner, "command", return_value=json.dumps(config)):
            with self.assertRaises(scanner.ScanError):
                scanner.compose_inventory("fixture", self.base / "fixture.env", False)

    def test_alertmanager_scan_failure_cannot_be_hidden_by_application_results(self):
        def scan(_directory, image_id, *_):
            if image_id == identity(8)["imageId"]:
                raise scanner.ScanError("fixture operations scan failure")
            return []
        _, manifest, count, _ = self.run_scan(image_scan=scan)
        self.assertEqual(count, 7)
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(next(item for item in manifest["services"] if item["service"] == "alertmanager")["status"], "failed")

    def test_malformed_truncated_and_missing_reports_fail_closed(self):
        path = self.base / "fixture-report.json"
        for raw in (
            "{",
            "{}",
            '{"SchemaVersion":2,"ArtifactName":"image"}',
            '{"SchemaVersion":2,"ArtifactName":"image","Results":[{"Vulnerabilities":{}}]}',
            '{"SchemaVersion":2,"ArtifactName":"image","Results":[{}]}',
            json.dumps({"SchemaVersion": 2, "ArtifactName": "image", "Results": [
                {"Target": "runtime", "Vulnerabilities": [
                    {"Severity": "unrecognized", "VulnerabilityID": "CVE-fixture", "PkgName": "fixture"}
                ]}
            ]}),
        ):
            path.write_text(raw, encoding="utf-8")
            with self.assertRaises(scanner.ScanError):
                scanner.findings_from_report(path)
        with self.assertRaises(scanner.ScanError):
            scanner.findings_from_report(self.base / "missing.json")
        path.write_text('{"SchemaVersion":2,"ArtifactName":"image","Results":[]}', encoding="utf-8")
        self.assertEqual(scanner.findings_from_report(path), [])

    def test_successful_scanner_exit_without_new_report_cannot_reuse_stale_output(self):
        (self.base / "image-stale.json").write_text(
            '{"SchemaVersion":2,"ArtifactName":"old","Results":[]}', encoding="utf-8"
        )
        with patch.object(scanner, "compose_inventory", return_value=inventory()), patch.object(
            scanner, "image_identity", side_effect=self.resolve
        ), patch.object(scanner, "command", return_value=""), patch.object(
            scanner, "scan_gateway_sbom", return_value=("fixture-java.json", [])
        ):
            directory, manifest = scanner.scan_deployment(
                "fixture", self.base / "fixture.env", self.base
            )
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(len(manifest["errors"]), 7)
        self.assertNotEqual(directory, self.base)
        self.assertTrue((directory / "manifest.json").is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
