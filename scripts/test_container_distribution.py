"""Distribution gate tests with verified source archives and a real compiled Linux Go binary."""

import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("container_distribution", Path(__file__).with_name("verify-container-distribution.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def tar_bytes(files, links=None):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w") as archive:
        for name, content in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        for name, target in (links or {}).items():
            member = tarfile.TarInfo(name)
            member.type, member.linkname = tarfile.SYMTYPE, target
            archive.addfile(member)
    return data.getvalue()


def recycle_temporary(temporary):
    root = Path(temporary.name)
    if root.resolve().parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith("ai-order-distribution-gate-"):
        raise ValueError("Refusing to clean an unrelated temporary directory")
    if os.name == "nt":
        temporary._finalizer.detach()
        environment = dict(os.environ, AI_ORDER_GATE_TEST_DIRECTORY=str(root))
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "Add-Type -AssemblyName Microsoft.VisualBasic; "
                        "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory("
                        "$env:AI_ORDER_GATE_TEST_DIRECTORY,'OnlyErrorDialogs','SendToRecycleBin')"],
                       env=environment, check=True, capture_output=True)
    else:
        temporary.cleanup()


class ContainerDistributionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler_temp = tempfile.TemporaryDirectory(prefix="ai-order-distribution-gate-go-")
        root = Path(cls.compiler_temp.name)
        cls.source = b'package main\nfunc main() { println("isolated fixture") }\n'
        (root / "main.go").write_bytes(cls.source)
        (root / "go.mod").write_text("module example.test/distribution-fixture\n\ngo 1.21\n", encoding="utf-8")
        environment = dict(os.environ, GOOS="linux", GOARCH="amd64", CGO_ENABLED="0", GOTOOLCHAIN="local", GOPROXY="off")
        subprocess.run(["go", "build", "-trimpath", "-o", str(root / "fixture.elf"), "."],
                       cwd=root, env=environment, check=True, capture_output=True)
        cls.binary = (root / "fixture.elf").read_bytes()
        cls.buildinfo = json.loads(subprocess.check_output(["go", "version", "-m", "-json", str(root / "fixture.elf")], text=True))

    @classmethod
    def tearDownClass(cls):
        recycle_temporary(cls.compiler_temp)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ai-order-distribution-gate-")
        self.root = Path(self.temp.name)
        self.repository = self.root / "repository"
        (self.repository / "context").mkdir(parents=True)
        self.input = self.repository / "context/Dockerfile"
        self.input.write_text("FROM scratch\n", encoding="utf-8")
        self.output = self.root / "output"
        (self.output / "bundles").mkdir(parents=True)
        self.material_files = {"SOURCE-MANIFEST.json": b'{"scope":"isolated fixture"}',
                               "source/main.go": self.source, "LICENSE": b"fixture source attribution"}
        self.bundle = tar_bytes(self.material_files)
        (self.output / "bundles/fixture.tar.gz").write_bytes(self.bundle)
        self.database = b"P:fixture-package\nV:1.0\n"
        self.native = b"\x7fELFisolated non-Go fixture bytes"
        self.base_layer = tar_bytes({"lib/apk/db/installed": self.database, "lib/native.so": self.native,
                                     "etc/base-notice": b"reviewed original base contents"})
        self.lock = {
            "buildInputs": {"context/Dockerfile": digest(self.input.read_bytes())},
            "buildInputDirectories": {"context": ["context/Dockerfile"]},
            "bundles": [{"id": "fixture", "url": "https://example.invalid/reviewed-materials.tar.gz",
                         "sha256": digest(self.bundle), "bytes": len(self.bundle),
                         "manifestPath": "SOURCE-MANIFEST.json", "requiredMembers": [
                             {"path": name, "sha256": digest(content), "bytes": len(content)}
                             for name, content in self.material_files.items()]}],
            "goModules": [], "goToolchains": [{"version": self.buildinfo["GoVersion"], "bundle": "fixture"}],
            "mainModules": [{"path": self.buildinfo["Main"]["Path"], "version": self.buildinfo["Main"]["Version"],
                             "bundle": "fixture", "materials": ["source/main.go"]}],
            "localReplacements": [],
            "approvedLayers": [{"diffId": "sha256:" + digest(self.base_layer), "bundles": ["fixture"]}],
            "approvedOsDatabases": [{"path": "lib/apk/db/installed", "sha256": digest(self.database), "bundles": ["fixture"]}],
            "approvedNativeElfSHA256": [digest(self.native)], "compiledGoPaths": ["bin/server"],
        }

    def tearDown(self):
        recycle_temporary(self.temp)

    def verify(self, layers=None):
        layers = layers if layers is not None else [self.base_layer, tar_bytes({"bin/server": self.binary})]
        config = json.dumps({"os": "linux", "architecture": "amd64", "config": {},
                             "rootfs": {"type": "layers", "diff_ids": ["sha256:" + digest(layer) for layer in layers]}}).encode()
        config_name = "blobs/sha256/" + digest(config)
        layer_names = ["blobs/sha256/" + digest(layer) for layer in layers]
        manifest = [{"Config": config_name, "RepoTags": ["isolated-fixture:test"], "Layers": layer_names}]
        payload = {"manifest.json": json.dumps(manifest).encode(), config_name: config}
        payload.update(dict(zip(layer_names, layers)))
        archive = self.root / "images.tar.gz"
        archive.write_bytes(tar_bytes(payload))
        lock = self.root / "lock.json"
        lock.write_text(json.dumps(self.lock), encoding="utf-8")
        return gate.verify_archive(archive, lock, self.output, self.repository)

    def test_valid_real_go_binary_and_reviewed_base_pass(self):
        result = self.verify()
        self.assertTrue(result["verified"])
        self.assertEqual([layer["approval"] for layer in result["layers"]], ["exact-reviewed-layer", "verified-go-copy-layer"])
        self.assertEqual(result["layers"][1]["goBinaries"][0]["main"]["Path"], self.buildinfo["Main"]["Path"])
        self.assertEqual(result["bundles"][0]["sha256"], digest(self.bundle))
        self.assertFalse(result["startedContainers"])

    def test_missing_required_source_member_fails(self):
        self.lock["bundles"][0]["requiredMembers"].append({"path": "missing-source.go", "sha256": "0" * 64, "bytes": 1})
        with self.assertRaisesRegex(ValueError, "Missing required"):
            self.verify()

    def test_wrong_cached_bundle_hash_fails(self):
        (self.output / "bundles/fixture.tar.gz").write_bytes(b"incorrect cached content")
        with self.assertRaisesRegex(ValueError, "size mismatch|digest mismatch"):
            self.verify()

    def test_required_source_content_hash_is_read_and_checked(self):
        self.lock["bundles"][0]["requiredMembers"][1]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "Required material hash"):
            self.verify()

    def test_new_go_dependency_fails_at_h1_boundary(self):
        binary = {"path": "/bin/server", "sha256": digest(self.binary), "buildInfo": copy.deepcopy(self.buildinfo)}
        binary["buildInfo"]["Deps"] = [{"Path": "example.test/new-dependency", "Version": "v1.0.0", "Sum": "h1:unreviewed"}]
        bundles = {"fixture": gate.verify_bundle(self.lock["bundles"][0], self.output)}
        with self.assertRaisesRegex(ValueError, "Unreviewed Go dependency"):
            gate.verify_go_binary(binary, self.lock, bundles)

    def test_reviewed_remote_dependency_requires_the_exact_h1(self):
        binary = {"path": "/bin/server", "sha256": digest(self.binary), "buildInfo": copy.deepcopy(self.buildinfo)}
        dependency = {"Path": "example.test/reviewed-dependency", "Version": "v1.0.0", "Sum": "h1:reviewed-fixture-sum"}
        binary["buildInfo"]["Deps"] = [dependency]
        self.lock["goModules"].append({"path": dependency["Path"], "version": dependency["Version"],
                                       "h1": dependency["Sum"], "bundle": "fixture"})
        bundles = {"fixture": gate.verify_bundle(self.lock["bundles"][0], self.output)}
        result = gate.verify_go_binary(binary, self.lock, bundles)
        self.assertEqual(result["dependencies"][0]["h1"], dependency["Sum"])
        dependency["Sum"] = "h1:different-unreviewed-sum"
        with self.assertRaisesRegex(ValueError, "Unreviewed Go dependency"):
            gate.verify_go_binary(binary, self.lock, bundles)

    def test_local_replacement_directory_must_match_the_reviewed_lock(self):
        binary = {"path": "/bin/server", "sha256": digest(self.binary), "buildInfo": copy.deepcopy(self.buildinfo)}
        binary["buildInfo"]["Deps"] = [{"Path": "example.test/replaced", "Version": "v1.0.0",
                                           "Replace": {"Path": "/patched-fixture", "Version": "(devel)"}}]
        self.lock["localReplacements"].append({"path": "example.test/replaced", "directory": "/patched-fixture", "bundle": "fixture"})
        bundles = {"fixture": gate.verify_bundle(self.lock["bundles"][0], self.output)}
        result = gate.verify_go_binary(binary, self.lock, bundles)
        self.assertEqual(result["dependencies"][0]["localReplacement"], "/patched-fixture")
        binary["buildInfo"]["Deps"][0]["Replace"]["Path"] = "/unreviewed-copy"
        with self.assertRaisesRegex(ValueError, "Unreviewed local"):
            gate.verify_go_binary(binary, self.lock, bundles)

    def test_changed_os_database_fails_even_in_otherwise_known_image(self):
        layer = tar_bytes({"lib/apk/db/installed": b"P:fixture-package\nV:2.0\n"})
        with self.assertRaisesRegex(ValueError, "Unreviewed OS package database"):
            self.verify([self.base_layer, layer])

    def test_unknown_plaintext_layer_fails(self):
        with self.assertRaisesRegex(ValueError, "Unknown layer"):
            self.verify([self.base_layer, tar_bytes({"new/unreviewed-file": b"new contents"})])

    def test_go_copy_layer_with_an_extra_asset_fails(self):
        layer = tar_bytes({"bin/server": self.binary, "ui/unreviewed.js": b"new bundled software"})
        with self.assertRaisesRegex(ValueError, "Unreviewed file in new Go-copy"):
            self.verify([self.base_layer, layer])

    def test_unknown_native_elf_fails_before_os_upgrade_allowance(self):
        layer = tar_bytes({"lib/apk/db/installed": self.database, "lib/new.so": b"\x7fELFnew unreviewed native component"})
        with self.assertRaisesRegex(ValueError, "Unreviewed native ELF"):
            self.verify([self.base_layer, layer])

    def test_unknown_os_upgrade_with_reviewed_database_and_native_files_passes(self):
        layer = tar_bytes({"lib/apk/db/installed": self.database, "lib/native.so": self.native,
                           "etc/upgraded-config": b"new package installation metadata"})
        result = self.verify([self.base_layer, layer])
        self.assertEqual(result["layers"][1]["approval"], "reviewed-os-database-upgrade")

    def test_new_build_context_file_and_changed_known_input_fail(self):
        (self.repository / "context/new.go").write_text("package unreviewed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "lacks its locked content hash"):
            self.verify()

    def test_changed_known_build_input_fails(self):
        self.input.write_text("FROM another-image\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Build input changed"):
            self.verify()

    def test_linked_material_member_fails(self):
        self.bundle = tar_bytes(self.material_files, {"linked-notice": "LICENSE"})
        (self.output / "bundles/fixture.tar.gz").write_bytes(self.bundle)
        self.lock["bundles"][0].update(sha256=digest(self.bundle), bytes=len(self.bundle))
        with self.assertRaisesRegex(ValueError, "contains a link"):
            self.verify()

    def test_unsafe_material_path_fails_without_extracting_it(self):
        self.bundle = tar_bytes({**self.material_files, "../outside": b"must not escape"})
        (self.output / "bundles/fixture.tar.gz").write_bytes(self.bundle)
        self.lock["bundles"][0].update(sha256=digest(self.bundle), bytes=len(self.bundle))
        with self.assertRaisesRegex(ValueError, "Unsafe distribution"):
            self.verify()

    def test_unlisted_file_in_material_bundle_fails(self):
        self.bundle = tar_bytes({**self.material_files, "unreviewed-source": b"not declared in reviewed members"})
        (self.output / "bundles/fixture.tar.gz").write_bytes(self.bundle)
        self.lock["bundles"][0].update(sha256=digest(self.bundle), bytes=len(self.bundle))
        with self.assertRaisesRegex(ValueError, "Unreviewed file in material"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
