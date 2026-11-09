"""Layer inventory and identity checks without loading or running Docker images."""

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
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "distribution_inspector", Path(__file__).with_name("inspect-distribution-images.py")
)
inspector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inspector)


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def make_tar(files):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w") as archive:
        for name, content in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
    return data.getvalue()


class DistributionImageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ai-order-distribution-test-")
        self.base = Path(self.temp.name)
        self.output = self.base / "output"
        self.scratch = self.base / "scratch"
        self.output.mkdir()
        self.scratch.mkdir()

    def tearDown(self):
        if os.name == "nt":
            self.temp._finalizer.detach()
            env = dict(os.environ, AI_ORDER_DISTRIBUTION_TEST_DIRECTORY=str(self.base))
            subprocess.run(
                [
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Add-Type -AssemblyName Microsoft.VisualBasic; "
                    "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory("
                    "$env:AI_ORDER_DISTRIBUTION_TEST_DIRECTORY,'OnlyErrorDialogs','SendToRecycleBin')",
                ],
                env=env,
                check=True,
                capture_output=True,
            )
        else:
            self.temp.cleanup()

    def inspect(self, layers, *, wrong_config=False, wrong_blob=False, wrong_diff_id=False):
        diff_ids = ["sha256:" + sha256(layer) for layer in layers]
        if wrong_diff_id:
            diff_ids[0] = "sha256:" + "0" * 64
        config = json.dumps(
            {
                "os": "linux",
                "architecture": "amd64",
                "rootfs": {"type": "layers", "diff_ids": diff_ids},
                "config": {},
            }
        ).encode()
        config_path = "blobs/sha256/" + ("0" * 64 if wrong_config else sha256(config))
        paths = ["blobs/sha256/" + sha256(layer) for layer in layers]
        if wrong_blob:
            paths[0] = "blobs/sha256/" + "0" * 64
        entry = {"Config": config_path, "Layers": paths, "RepoTags": ["fixture:test"]}
        payload = make_tar({config_path: config, **dict(zip(paths, layers))})
        with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
            return inspector.inspect_image(archive, entry, self.output, self.scratch)

    def test_overwritten_layers_preserve_files_notices_and_go_metadata(self):
        old_binary = b"\x7fELF\xff Go buildinf: old-layer-marker"
        layers = [
            make_tar(
                {
                    "bin/server": old_binary,
                    "old/NOTICE": b"original notice",
                    "lib/apk/db/installed": b"P:busybox\nV:1.0\n",
                    "assets/LICENSE": b"\x7fELF binary-must-not-be-exported",
                }
            ),
            make_tar({"bin/server": b"replacement", "old/.wh.NOTICE": b""}),
        ]
        build_info = {"GoVersion": "go1.27.3", "Path": "fixture", "Main": {}, "Deps": []}
        with patch.object(inspector, "command", return_value=json.dumps(build_info)) as command:
            result = self.inspect(layers)
        command.assert_called_once()
        self.assertEqual(len(result["layers"]), 2)
        first = json.loads((self.output / result["layers"][0]["inventoryPath"]).read_text())
        old = next(record for record in first["files"] if record["path"] == "bin/server")
        self.assertEqual(old["sha256"], sha256(old_binary))
        self.assertEqual(result["layers"][0]["goBinaries"][0]["buildInfo"], build_info)
        self.assertEqual(result["layers"][1]["goBinaries"], [])
        self.assertTrue(any(path.read_bytes() == b"original notice" for path in self.output.rglob("NOTICE")))
        self.assertTrue(any(path.name == "installed" for path in self.output.rglob("*")))
        for path in self.output.rglob("*"):
            if path.is_file():
                self.assertFalse(path.read_bytes().startswith(b"\x7fELF"), path)
        second = json.loads((self.output / result["layers"][1]["inventoryPath"]).read_text())
        self.assertIn("old/.wh.NOTICE", [record["path"] for record in second["files"]])

    def test_config_digest_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "config digest mismatch"):
            self.inspect([make_tar({"file": b"value"})], wrong_config=True)

    def test_layer_blob_digest_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "blob digest mismatch"):
            self.inspect([make_tar({"file": b"value"})], wrong_blob=True)

    def test_uncompressed_diff_id_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "uncompressed digest"):
            self.inspect([make_tar({"file": b"value"})], wrong_diff_id=True)

    def test_layer_path_traversal_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unsafe archive member"):
            self.inspect([make_tar({"../outside/NOTICE": b"not allowed"})])


if __name__ == "__main__":
    unittest.main()
