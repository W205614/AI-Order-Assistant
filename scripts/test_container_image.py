"""Single-layer image preparation contracts and an opt-in real Docker fixture."""

import argparse
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
import uuid

SPEC = importlib.util.spec_from_file_location("container_image", Path(__file__).with_name("prepare-container-image.py"))
images = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(images)
REVISION = "1a368baeb5c351764135754c089b2fe802ce56ba"


class FileFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ai-order-container-image-test-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        if self.root.resolve().parent != Path(tempfile.gettempdir()).resolve() or not self.root.name.startswith("ai-order-container-image-test-"):
            raise ValueError("Refusing to clean a path outside this test's temporary directory")
        if os.name == "nt":
            self.temp._finalizer.detach()
            environment = dict(os.environ, AI_ORDER_CONTAINER_TEST_DIRECTORY=str(self.root))
            subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command",
                 "Add-Type -AssemblyName Microsoft.VisualBasic; "
                 "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory("
                 "$env:AI_ORDER_CONTAINER_TEST_DIRECTORY,'OnlyErrorDialogs','SendToRecycleBin')"],
                env=environment, check=True, capture_output=True,
            )
        else:
            self.temp.cleanup()


class ContainerImageUnitTest(FileFixture):
    def test_effective_unsupported_fields_fail_instead_of_disappearing(self):
        for field, value in [("Shell", ["/bin/sh", "-c"]), ("Volumes", {"/data": {}}), ("OnBuild", ["RUN echo unexpected"]), ("StopTimeout", 60)]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, field):
                images.runtime_config({field: value})

    def test_base_labels_are_replaced_and_json_commands_keep_boundaries(self):
        config = {"Labels": {"base.description": "old\nbase description"},
                  "Cmd": ["command", "space value", "$literal", "quote\"value"],
                  "Entrypoint": ["entry", "argument"]}
        changes = images.import_changes(config, {"org.opencontainers.image.title": "Application"})
        self.assertIn('CMD ["command", "space value", "$literal", "quote\\\"value"]', changes)
        self.assertIn('ENTRYPOINT ["entry", "argument"]', changes)
        self.assertFalse(any("old" in value or "base.description" in value for value in changes))

    def test_multiline_environment_value_is_explicitly_unsupported(self):
        with self.assertRaisesRegex(ValueError, "Multiline"):
            images.import_changes({"Env": ["TEXT=first\nsecond"]}, {})

    def test_inventory_preserves_file_hash_permissions_owners_and_links(self):
        path = self.root / "rootfs.tar"
        with tarfile.open(path, "w") as archive:
            content = b"real file bytes\n"
            file = tarfile.TarInfo("app/file")
            file.size, file.mode, file.uid, file.gid = len(content), 0o640, 1234, 2345
            file.pax_headers = {"SCHILY.xattr.user.fixture": "retained attribute"}
            archive.addfile(file, io.BytesIO(content))
            link = tarfile.TarInfo("app/symlink")
            link.type, link.linkname, link.mode = tarfile.SYMTYPE, "file", 0o777
            archive.addfile(link)
            hardlink = tarfile.TarInfo("app/hardlink")
            hardlink.type, hardlink.linkname, hardlink.mode = tarfile.LNKTYPE, "app/file", 0o640
            hardlink.uid, hardlink.gid = 1234, 2345
            archive.addfile(hardlink)
        before = images.tar_inventory(path)
        notices = self.root / "notices"
        notices.mkdir()
        (notices / "NOTICE.txt").write_text("fixture notice", encoding="utf-8")
        images.append_notices(path, notices)
        after = images.tar_inventory(path)
        self.assertEqual(before, {name: value for name, value in after.items() if not name.startswith("licenses")})
        self.assertEqual(before["app/file"]["sha256"], before["app/hardlink"]["sha256"])
        self.assertEqual((before["app/file"]["mode"], before["app/file"]["uid"], before["app/file"]["gid"]), (0o640, 1234, 2345))
        self.assertEqual(before["app/file"]["extendedAttributes"], {"SCHILY.xattr.user.fixture": "retained attribute"})
        self.assertEqual(after["licenses/NOTICE.txt"]["mode"], 0o644)

    def test_tar_path_escape_fails_without_extracting_files(self):
        path = self.root / "unsafe.tar"
        with tarfile.open(path, "w") as archive:
            archive.addfile(tarfile.TarInfo("../outside"))
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            images.tar_inventory(path)


@unittest.skipUnless(os.environ.get("AI_ORDER_CONTAINER_IMAGE_DOCKER_TEST") == "1", "Set AI_ORDER_CONTAINER_IMAGE_DOCKER_TEST=1 for the isolated real Docker fixture")
class ContainerImageDockerTest(FileFixture):
    def test_runtime_config_hidden_layers_and_rootfs_equivalence(self):
        nonce = uuid.uuid4().hex[:12]
        base_tag = images.STAGING_PREFIX + "fixture-" + nonce + ":base"
        source_tag = images.STAGING_PREFIX + "fixture-" + nonce + ":source"
        target_tag = images.STAGING_PREFIX + "fixture-" + nonce + ":verified"
        owned_images = []
        source_id = None
        fixture_label = "io.github.w205614.ai-order-assistant.fixture"
        try:
            lower = self.root / "lower"
            lower.mkdir()
            (lower / "version.txt").write_text("hidden old fixture bytes", encoding="utf-8")
            (lower / "keep.txt").write_text("preserve this file", encoding="utf-8")
            with tarfile.open(self.root / "lower.tar", "w") as archive:
                for path in sorted(lower.iterdir()):
                    item = archive.gettarinfo(str(path), path.name)
                    item.mode, item.uid, item.gid = 0o640, 1234, 2345
                    with path.open("rb") as source:
                        archive.addfile(item, source)
                link = tarfile.TarInfo("keep-link")
                link.type, link.linkname, link.mode = tarfile.SYMTYPE, "keep.txt", 0o777
                archive.addfile(link)
            (self.root / "Dockerfile.base").write_text(
                'FROM scratch\nADD lower.tar /\n'
                'ENV SIMPLE="two words" CASH="literal\\$value" QUOTED="a \\"quoted\\" word" SLASH="one\\\\two"\n'
                'USER 1234:2345\nWORKDIR /working\nEXPOSE 19876/tcp 19877/udp\n'
                'ENTRYPOINT ["entry", "space argument"]\nCMD ["command", "dollar$literal"]\n'
                'HEALTHCHECK --interval=2s --timeout=1s --start-period=3s --start-interval=1s --retries=4 CMD ["command", "probe"]\n'
                'STOPSIGNAL SIGTERM\n'
                'LABEL org.opencontainers.image.revision="' + REVISION + '" ' + fixture_label + '="' + nonce + '"\n', encoding="utf-8")
            subprocess.run(["docker", "build", "--file", str(self.root / "Dockerfile.base"), "--tag", base_tag, str(self.root)], check=True, capture_output=True)
            base_id = images.inspect_image(base_tag)["Id"]
            owned_images.append((base_id, fixture_label, nonce))
            (self.root / "version.txt").write_text("current visible fixture bytes", encoding="utf-8")
            (self.root / "Dockerfile.source").write_text("FROM " + base_tag + "\nCOPY version.txt /version.txt\n", encoding="utf-8")
            subprocess.run(["docker", "build", "--file", str(self.root / "Dockerfile.source"), "--tag", source_tag, str(self.root)], check=True, capture_output=True)
            source = images.inspect_image(source_tag)
            source_id = source["Id"]
            owned_images.append((source["Id"], fixture_label, nonce))
            environment = dict(entry.split("=", 1) for entry in source["Config"]["Env"])
            self.assertEqual(environment["CASH"], "literal$value")
            self.assertEqual(environment["QUOTED"], 'a "quoted" word')
            self.assertEqual(environment["SLASH"], "one\\two")
            notices = self.root / "notices"
            notices.mkdir()
            (notices / "THIRD-PARTY-NOTICES.txt").write_text("fixture attribution and license", encoding="utf-8")
            (notices / "source-materials.json").write_text('{"materials": []}', encoding="utf-8")
            args = argparse.Namespace(image=source["Id"], tag=target_tag, source_revision=REVISION,
                                      source_url="https://github.com/W205614/AI-Order-Assistant",
                                      materials_url="https://example.invalid/exact-fixture-materials.tar.gz",
                                      version="fixture", title="Isolated fixture", notices=notices,
                                      notice_file="THIRD-PARTY-NOTICES.txt", source_manifest="source-materials.json",
                                      output=self.root / "prepared")
            try:
                report = images.prepare(args)
            except ValueError:
                mismatch = args.output / "runtime-config-mismatch.json"
                if mismatch.is_file():
                    self.fail(mismatch.read_text(encoding="utf-8"))
                raise
            owned_images.append((report["preparedImageId"], images.SOURCE_IMAGE_LABEL, source["Id"]))
            self.assertGreater(report["sourceLayerCount"], 1)
            self.assertEqual(report["preparedLayerCount"], 1)
            self.assertTrue(report["runtimeConfigEquivalent"])
            self.assertTrue(report["rootfsEquivalentExceptAddedLicenses"])
            self.assertEqual(images.inspect_image(source_tag)["Id"], source["Id"])
            with tarfile.open(args.output / "verified-rootfs.tar") as archive:
                self.assertEqual(archive.extractfile("version.txt").read(), b"current visible fixture bytes")
            archive_path = self.root / "prepared-image.tar"
            images.docker("image", "save", "--output", archive_path, report["preparedImageId"])
            with tarfile.open(archive_path) as archive:
                manifest = json.load(archive.extractfile("manifest.json"))
                self.assertEqual(len(manifest[0]["Layers"]), 1)
                layer = archive.extractfile(manifest[0]["Layers"][0])
                with tarfile.open(fileobj=layer, mode="r|*") as rootfs:
                    for entry in rootfs:
                        if entry.name == "version.txt":
                            self.assertEqual(rootfs.extractfile(entry).read(), b"current visible fixture bytes")
                            break
                    else:
                        self.fail("The saved single layer does not contain the visible fixture file")
        finally:
            # Image preparation can fail after import; only this unique fixture tag is eligible.
            candidate = subprocess.run(["docker", "image", "inspect", target_tag], capture_output=True, text=True)
            if candidate.returncode == 0:
                image = json.loads(candidate.stdout)[0]
                if image["Config"].get("Labels", {}).get(images.SOURCE_IMAGE_LABEL) == source_id and not any(value == image["Id"] for value, _, _ in owned_images):
                    owned_images.append((image["Id"], images.SOURCE_IMAGE_LABEL, source_id))
            for image_id, key, expected in reversed(owned_images):
                image = images.inspect_image(image_id)
                if image["Config"].get("Labels", {}).get(key) != expected:
                    raise ValueError("Refusing to remove an image without this test's ownership label")
                images.docker("image", "rm", image_id)


if __name__ == "__main__":
    unittest.main()
