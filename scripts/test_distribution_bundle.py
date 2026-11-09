import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BUNDLE = load("test_source_bundle", "build-distribution-bundle.py")
PUBLISH = load("test_source_publisher", "publish-source-materials.py")


class DistributionBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.materials = self.root / "materials"
        self.materials.mkdir()
        self.body = b"Copyright Example Author\nPermission to distribute this original text.\n"
        self.source = self.materials / "component.tar.gz"
        with tarfile.open(self.source, "w:gz") as archive:
            for name, body in [("component/LICENSE", self.body), ("component/NOTICE", self.body), ("component/src/main.c", b"int main(void) { return 0; }\n"), ("component/testdata/elf-fixture", b"\x7fELFlocked original source test fixture")]:
                info = tarfile.TarInfo(name)
                info.size = len(body)
                archive.addfile(info, io.BytesIO(body))
        self.lock = self.root / "lock.json"
        self.item = {"component": "Example", "version": "1", "role": "source", "localPath": self.source.name, "sha256": hashlib.sha256(self.source.read_bytes()).hexdigest(), "bytes": self.source.stat().st_size}
        self.write_lock([self.item])

    def write_lock(self, items):
        self.lock.write_text(json.dumps({"scope": "Source fixture only", "materials": items}), encoding="utf-8")

    def build(self, output="bundle", kind="os"):
        return BUNDLE.build_bundle(self.lock, self.materials, self.root / output, kind=kind)

    def paths(self, kind="os"):
        output = self.root / "bundle"
        prefix = "AI-Order-Assistant-0.1.2-" + kind + "-"
        return output / (prefix + "source-materials.tar.gz"), output / (prefix + "source-materials.manifest.json"), output / (prefix + "NOTICES.txt")

    def rewrite_bundle(self, mutate):
        bundle, manifest_path, notices = self.paths()
        with tarfile.open(bundle) as source:
            members = [(member, source.extractfile(member).read()) for member in source]
        with tarfile.open(bundle, "w:gz") as target:
            for member, body in members:
                member, body = mutate(member, body)
                member.size = len(body)
                target.addfile(member, io.BytesIO(body))
        manifest = json.loads(manifest_path.read_text())
        manifest["bundle"].update({"sha256": BUNDLE.digest_file(bundle), "bytes": bundle.stat().st_size})
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return bundle, manifest_path, notices

    def test_source_notices_and_every_file_are_read_back(self):
        result = self.build()
        self.assertTrue(result["readbackVerified"])
        self.assertEqual(result["noticeBodies"], 1)
        bundle, manifest, notices = self.paths()
        text = notices.read_text(encoding="utf-8")
        self.assertIn("component/LICENSE", text)
        self.assertIn("component/NOTICE", text)
        self.assertIn(self.body.decode(), text)
        with tarfile.open(bundle) as archive:
            record = json.loads(archive.extractfile("MANIFEST.json").read())
            self.assertEqual(record["files"], json.loads(manifest.read_text())["payloadManifest"]["files"])
            self.assertEqual(archive.extractfile("materials/component.tar.gz").read(), self.source.read_bytes())
            self.assertFalse(record["includesStandaloneRuntimeELF"])

    def test_identical_inputs_produce_identical_bundle_bytes(self):
        first = self.build()
        second = self.build("second")
        self.assertEqual(first["bundleSHA256"], second["bundleSHA256"])

    def test_nested_capture_and_original_notice_alias_are_in_full_text(self):
        nested = io.BytesIO()
        extra = b"Copyright Nested Legal Author\nOriginal nested notice body.\n"
        with tarfile.open(fileobj=nested, mode="w") as archive:
            info = tarfile.TarInfo("legal/base/LICENSE")
            info.size = len(extra)
            archive.addfile(info, io.BytesIO(extra))
            link = tarfile.TarInfo("legal/other/LICENSE")
            link.type = tarfile.SYMTYPE
            link.linkname = "../base/LICENSE"
            archive.addfile(link)
        captured = self.materials / "captured.tar.gz"
        with tarfile.open(captured, "w:gz") as archive:
            info = tarfile.TarInfo("gateway/legal.tar")
            info.size = len(nested.getvalue())
            archive.addfile(info, io.BytesIO(nested.getvalue()))
        self.write_lock([self.item, {"component": "Captured", "role": "captured-notices-archive", "localPath": captured.name, "sha256": BUNDLE.digest_file(captured)}])
        result = self.build()
        text = self.paths()[2].read_text(encoding="utf-8")
        self.assertEqual(result["noticeBodies"], 2)
        self.assertIn("captured.tar.gz!gateway/legal.tar!legal/base/LICENSE", text)
        self.assertIn("captured.tar.gz!gateway/legal.tar!legal/other/LICENSE", text)
        self.assertIn(extra.decode(), text)

    def test_zip_source_original_notice_bytes_are_preserved(self):
        source = self.materials / "source.zip"
        original = b"Copyright Auteur \xe9\nOriginal ZIP notice\n"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("source/COPYING", original)
        self.write_lock([{"component": "ZIP source", "role": "source", "localPath": source.name, "sha256": BUNDLE.digest_file(source)}])
        self.build()
        with tarfile.open(self.paths()[0]) as archive:
            path = "notices/" + hashlib.sha256(original).hexdigest() + ".txt"
            self.assertEqual(archive.extractfile(path).read(), original)

    def test_jre_exceptions_and_go_patents_are_original_notice_texts(self):
        source = self.materials / "legal-names.tar"
        bodies = {
            "jdk/legal/java.base/ASSEMBLY_EXCEPTION": b"Original JRE assembly exception text\n",
            "jdk/legal/java.base/ADDITIONAL_LICENSE_INFO": b"Original additional JRE license information\n",
            "go/PATENTS": b"Original Go patent grant text\n",
        }
        with tarfile.open(source, "w") as archive:
            for name, body in bodies.items():
                info = tarfile.TarInfo(name)
                info.size = len(body)
                archive.addfile(info, io.BytesIO(body))
        self.write_lock([{"component": "Runtime legal", "role": "source", "localPath": source.name, "sha256": BUNDLE.digest_file(source)}])
        self.build()
        text = self.paths()[2].read_text(encoding="utf-8")
        for name, body in bodies.items():
            self.assertIn(name, text)
            self.assertIn(body.decode(), text)

    def test_complete_legal_tree_keeps_gpl_names_without_collecting_doc_changelogs(self):
        records = []
        for filename, complete in [("common-licenses.tar", True), ("share-doc.tar", False)]:
            source = self.materials / filename
            bodies = {"texts/GPL-2": b"Original GPL-2 full body\n", "texts/Apache-2.0": b"Original Apache-2.0 full body\n"} if complete else {"doc/LICENSE": self.body, "doc/changelog": b"This is not a license notice\n"}
            with tarfile.open(source, "w") as archive:
                for name, body in bodies.items():
                    info = tarfile.TarInfo(name)
                    info.size = len(body)
                    archive.addfile(info, io.BytesIO(body))
            records.append({"component": filename, "role": "legal-archive", "localPath": filename, "sha256": BUNDLE.digest_file(source), "noticeArchiveAllFiles": complete})
        self.write_lock(records)
        self.build()
        text = self.paths()[2].read_text(encoding="utf-8")
        self.assertIn("Original GPL-2 full body", text)
        self.assertIn("Original Apache-2.0 full body", text)
        self.assertNotIn("This is not a license notice", text)

    def test_missing_input_blocks_bundle_creation(self):
        self.write_lock([dict(self.item, localPath="missing.tar.gz")])
        with self.assertRaisesRegex(ValueError, "missing"):
            self.build()
        self.assertFalse((self.root / "bundle").exists())

    def test_corrupted_locked_source_is_rejected(self):
        self.source.write_bytes(b"changed upstream data")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            self.build()

    def test_material_paths_cannot_escape(self):
        for name in ("../outside", "/outside", "C:/outside", "part\\outside"):
            with self.subTest(name=name):
                self.write_lock([dict(self.item, localPath=name)])
                with self.assertRaisesRegex(ValueError, "path"):
                    self.build()

    def test_private_project_configuration_is_rejected(self):
        self.write_lock([dict(self.item, localPath="gateway/.env")])
        with self.assertRaisesRegex(ValueError, "Private project"):
            self.build()

    def test_standalone_runtime_elf_is_rejected(self):
        file = self.materials / "runtime.bin"
        file.write_bytes(b"\x7fELFapplication runtime")
        self.write_lock([dict(self.item, localPath=file.name, bytes=file.stat().st_size, sha256=BUNDLE.digest_file(file))])
        with self.assertRaisesRegex(ValueError, "ELF binary"):
            self.build()

    def test_member_corruption_is_rejected_even_after_outer_hash_is_updated(self):
        self.build()
        def mutate(member, body):
            if member.name.startswith("notices/"):
                body = b"X" + body[1:]
            return member, body
        with self.assertRaisesRegex(ValueError, "member digest"):
            BUNDLE.verify_bundle(*self.rewrite_bundle(mutate))

    def test_bundle_traversal_member_is_rejected(self):
        self.build()
        def mutate(member, body):
            if member.name.startswith("notices/"):
                member.name = "../outside"
            return member, body
        with self.assertRaisesRegex(ValueError, "path"):
            BUNDLE.verify_bundle(*self.rewrite_bundle(mutate))

    def test_default_publisher_only_verifies_three_assets(self):
        self.build()
        result = PUBLISH.publish(self.root / "bundle", lock_path=self.lock)
        self.assertFalse(result["published"])
        self.assertEqual(len(result["assets"]), 3)

    def test_existing_asset_with_different_hash_fails_before_network(self):
        with self.assertRaisesRegex(ValueError, "replacement is forbidden"):
            PUBLISH.require_matching_asset({"name": "existing", "size": 10, "digest": "sha256:" + "0" * 64}, {"bytes": 10, "sha256": "f" * 64})

    def test_additional_go_scope_uses_independent_asset_names(self):
        self.build(kind="go-additional")
        result = PUBLISH.publish(self.root / "bundle", kind="go-additional", lock_path=self.lock)
        self.assertTrue(all("go-additional" in name for name in result["assets"]))

    def test_prepared_bundle_with_a_different_current_lock_is_rejected(self):
        self.build()
        self.write_lock([dict(self.item, component="Different reviewed input")])
        with self.assertRaisesRegex(ValueError, "current fixed source lock"):
            PUBLISH.publish(self.root / "bundle", lock_path=self.lock)


class PreparedSourcePromotionTest(unittest.TestCase):
    def setUp(self):
        self.run = {
            "id": 42628765274,
            "path": ".github/workflows/prepare-license-materials.yml",
            "event": "push",
            "repository": {"full_name": PUBLISH.REPOSITORY},
            "head_repository": {"full_name": PUBLISH.REPOSITORY},
            "status": "completed",
            "conclusion": "success",
            "head_sha": "7a78f584861362f8005c9fbb1ebea750fd647c71",
        }

    def select(self, run=None, run_id="42628765274", publish=True, event="workflow_dispatch"):
        return PUBLISH.source_selection(run_id, publish, event, "f" * 40, "12345", self.run if run is None else run)

    def test_successful_same_workflow_run_uses_its_artifact_identity(self):
        selected = self.select()
        self.assertEqual(selected["artifact_sha"], self.run["head_sha"])
        self.assertEqual(selected["source_run_id"], str(self.run["id"]))
        self.assertEqual(selected["reuse_prepared"], "true")

    def test_fresh_workflow_does_not_require_whole_run_completion(self):
        selected = self.select(run={"status": "in_progress"}, run_id="", publish=False, event="push")
        self.assertEqual(selected, {"source_run_id": "12345", "artifact_sha": "f" * 40, "reuse_prepared": "false"})

    def test_unvalidated_run_id_cannot_be_used_as_an_api_path(self):
        for run_id in ("../runs", "123;echo injected", "0", "-1", "123\nother=1", " 123"):
            with self.subTest(run_id=run_id), self.assertRaisesRegex(ValueError, "positive decimal"):
                self.select(run_id=run_id)

    def test_prepared_id_requires_explicit_manual_publication(self):
        for publish, event in ((False, "workflow_dispatch"), (True, "push")):
            with self.subTest(publish=publish, event=event), self.assertRaisesRegex(ValueError, "explicit manual"):
                self.select(publish=publish, event=event)

    def test_other_workflow_or_event_cannot_supply_artifacts(self):
        for changed in ({"path": ".github/workflows/security.yml"}, {"id": 123}, {"event": "pull_request"}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.select(run={**self.run, **changed})

    def test_fork_head_or_other_repository_is_rejected(self):
        for key in ("head_repository", "repository"):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "this repository"):
                self.select(run={**self.run, key: {"full_name": "someone/fork"}})

    def test_incomplete_or_failed_runs_are_not_promoted(self):
        for changed in ({"status": "in_progress"}, {"conclusion": "failure"}, {"conclusion": None}):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, "completed successfully"):
                self.select(run={**self.run, **changed})

    def test_artifact_name_cannot_use_an_invalid_head_sha(self):
        with self.assertRaisesRegex(ValueError, "invalid source SHA"):
            self.select(run={**self.run, "head_sha": "../../artifact"})


if __name__ == "__main__":
    unittest.main()
