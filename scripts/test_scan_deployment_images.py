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
    for deployment in ("root", "public"):
        names = ["gateway", "migrate", "agent", "mysql", "redis", "backup", "prometheus"]
        if deployment == "public":
            names.append("proxy")
        for name in names:
            image = "fixture/mysql:other-tag" if name == "backup" and backup_same else f"fixture/{name}:version"
            entries.append({"deployment": deployment, "service": name, "configuredImage": image,
                            "builtLocally": name in {"gateway", "agent"}})
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
            subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                            "Add-Type -AssemblyName Microsoft.VisualBasic; "
                            "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory("
                            "$env:AI_ORDER_SCANNER_TEST_DIRECTORY,'OnlyErrorDialogs','SendToRecycleBin')"],
                           env=env, check=True, capture_output=True)
        else:
            self.temp.cleanup()

    def resolve(self, reference, _built, _pull):
        service = reference.split("/")[1].split(":")[0]
        number = {"migrate": 1, "gateway": 1, "agent": 2, "mysql": 3, "redis": 4,
                  "backup": 5, "prometheus": 6, "proxy": 7}[service]
        return identity(number)

    def run_scan(self, entries=None, image_scan=None, sbom_scan=None):
        with patch.object(scanner, "compose_inventory", return_value=entries or inventory()), \
                patch.object(scanner, "image_identity", side_effect=self.resolve), \
                patch.object(scanner, "scan_image", side_effect=image_scan or (lambda *_: [])) as scanned, \
                patch.object(scanner, "scan_gateway_sbom", side_effect=sbom_scan or (lambda *_: ("java.json", []))) as sbom:
            directory, manifest = scanner.scan_deployment("fixture", self.base / "fixture.env", self.base)
            return directory, manifest, scanned.call_count, sbom.call_count

    def test_same_actual_image_under_different_tags_is_scanned_once_and_mapped_to_each_service(self):
        _, manifest, count, sbom = self.run_scan()
        self.assertEqual(manifest["status"], "passed")
        self.assertEqual(len(manifest["services"]), 15)
        self.assertEqual(count, 6)
        self.assertEqual(sbom, 1)
        mysql = next(s for s in manifest["services"] if s["service"] == "mysql")
        backup = next(s for s in manifest["services"] if s["service"] == "backup")
        self.assertNotEqual(mysql["configuredImage"], backup["configuredImage"])
        self.assertEqual(mysql["imageId"], backup["imageId"])
        self.assertEqual(mysql["reports"], backup["reports"])
        self.assertTrue(all(s["status"] == "scanned" and s["repoDigests"] and s["reports"] for s in manifest["services"]))

    def test_distinct_mysql_and_backup_images_are_not_deduplicated_by_service_name(self):
        _, manifest, count, _ = self.run_scan(entries=inventory(backup_same=False))
        self.assertEqual(count, 7)
        self.assertEqual(manifest["status"], "passed")
        self.assertEqual(len(manifest["images"]), 7)

    def test_one_scan_failure_fails_the_gate_and_other_reports_are_still_attempted(self):
        def scan(_directory, image_id, *_):
            if image_id == identity(6)["imageId"]:
                raise scanner.ScanError("fixture scanner failure")
            return []
        _, manifest, count, _ = self.run_scan(image_scan=scan)
        self.assertEqual(count, 6)
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(len(manifest["errors"]), 1)
        self.assertEqual(next(s for s in manifest["services"] if s["service"] == "proxy")["status"], "scanned")
        self.assertEqual(next(s for s in manifest["services"] if s["service"] == "prometheus")["status"], "failed")

    def test_missing_java_sbom_fails_even_when_every_image_report_passes(self):
        def missing(*_):
            raise scanner.ScanError("fixture missing Java SBOM")
        _, manifest, _, count = self.run_scan(sbom_scan=missing)
        self.assertEqual(count, 1)
        self.assertEqual(manifest["status"], "failed")
        gateway = next(s for s in manifest["services"] if s["service"] == "gateway")
        self.assertEqual(len(gateway["reports"]), 2)
        self.assertEqual(gateway["status"], "failed")

    def test_unfixed_critical_remains_visible_but_only_fixed_version_blocks(self):
        finding = {"package": "fixture", "vulnerability": "CVE-fixture", "severity": "CRITICAL",
                   "installedVersion": "1", "fixedVersion": ""}
        _, manifest, _, _ = self.run_scan(image_scan=lambda *_: [finding.copy()])
        self.assertEqual(manifest["status"], "passed")
        self.assertEqual(manifest["unfixedHighCritical"], 6)
        finding["fixedVersion"] = "2"
        _, manifest, _, _ = self.run_scan(image_scan=lambda *_: [finding.copy()])
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(manifest["fixableHighCritical"], 6)

    def test_compose_projection_keeps_only_image_inventory_and_requires_public_proxy(self):
        config = {"name": "fixture", "services": {name: {"image": f"fixture/{name}:tag",
                  "environment": {"PRIVATE_SETTING": "never-save-this"}} for name in scanner.REQUIRED | {"proxy"}}}
        with patch.object(scanner, "command", return_value=json.dumps(config)) as command:
            entries = scanner.compose_inventory("fixture", self.base / "fixture.env", True)
            self.assertNotIn("never-save-this", json.dumps(entries))
            self.assertEqual(command.call_count, 2)
            self.assertEqual(command.call_args.kwargs["env"]["APP_DOMAIN"], "example.invalid")
            for call in command.call_args_list:
                arguments = call.args[0]
                self.assertEqual(arguments[arguments.index("--profile") + 1], "manual-backup")
        del config["services"]["proxy"]
        with patch.object(scanner, "command", return_value=json.dumps(config)):
            with self.assertRaises(scanner.ScanError):
                scanner.compose_inventory("fixture", self.base / "fixture.env", True)

    def test_malformed_truncated_and_missing_reports_fail_closed(self):
        path = self.base / "fixture-report.json"
        for raw in ("{", "{}", '{"SchemaVersion":2,"ArtifactName":"image"}',
                    '{"SchemaVersion":2,"ArtifactName":"image","Results":[{"Vulnerabilities":{}}]}',
                    '{"SchemaVersion":2,"ArtifactName":"image","Results":[{}]}'):
            path.write_text(raw, encoding="utf-8")
            with self.assertRaises(scanner.ScanError):
                scanner.findings_from_report(path)
        with self.assertRaises(scanner.ScanError):
            scanner.findings_from_report(self.base / "missing.json")
        path.write_text('{"SchemaVersion":2,"ArtifactName":"image","Results":[]}', encoding="utf-8")
        self.assertEqual(scanner.findings_from_report(path), [])

    def test_successful_scanner_exit_without_new_report_cannot_reuse_stale_output(self):
        (self.base / "image-stale.json").write_text('{"SchemaVersion":2,"ArtifactName":"old","Results":[]}', encoding="utf-8")
        with patch.object(scanner, "compose_inventory", return_value=inventory()), \
                patch.object(scanner, "image_identity", side_effect=self.resolve), \
                patch.object(scanner, "command", return_value=""), \
                patch.object(scanner, "scan_gateway_sbom", return_value=("fixture-java.json", [])):
            directory, manifest = scanner.scan_deployment("fixture", self.base / "fixture.env", self.base)
        self.assertEqual(manifest["status"], "failed")
        self.assertEqual(len(manifest["errors"]), 6)
        self.assertNotEqual(directory, self.base)
        self.assertTrue((directory / "manifest.json").is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
