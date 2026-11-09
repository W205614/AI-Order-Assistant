import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("distribution_materials", Path(__file__).with_name("fetch-distribution-materials.py"))
MATERIALS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MATERIALS)


class DistributionMaterialTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.body = b"Copyright Example Author\nPermission to use this software.\n"
        self.archive = self.root / "component.tar.gz"
        with tarfile.open(self.archive, "w:gz") as archive:
            item = tarfile.TarInfo("component/LICENSE")
            item.size = len(self.body)
            archive.addfile(item, io.BytesIO(self.body))
        self.source = {"localPath": "component.tar.gz", "sha256": hashlib.sha256(self.archive.read_bytes()).hexdigest()}
        self.notice = {
            "localPath": "notices/LICENSE", "archivePath": "component.tar.gz", "archiveMember": "component/LICENSE",
            "sha256": hashlib.sha256(self.body).hexdigest(), "bytes": len(self.body),
        }

    def test_complete_source_and_exact_notice_are_verified(self):
        records = MATERIALS.fetch_all({"materials": [self.source, self.notice]}, self.root)
        self.assertEqual(len(records), 2)
        self.assertEqual((self.root / "notices/LICENSE").read_bytes(), self.body)

    def test_wrong_source_digest_blocks_notice_generation(self):
        source = dict(self.source, sha256="0" * 64)
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            MATERIALS.fetch_all({"materials": [source, self.notice]}, self.root)
        self.assertFalse((self.root / "notices/LICENSE").exists())

    def test_conflicting_inventory_entries_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            MATERIALS.fetch_all({"materials": [self.source, dict(self.source, sha256="0" * 64)]}, self.root)

    def test_missing_source_has_no_implicit_fallback(self):
        with self.assertRaisesRegex(ValueError, "fixed HTTPS source URL"):
            MATERIALS.acquire({"localPath": "missing.tar", "sha256": "0" * 64}, self.root)

    def test_traversal_path_is_rejected(self):
        for path in ("../outside", "/outside", "C:/outside", "notice\\outside"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                MATERIALS.material_path(self.root, path)

    def test_notice_symlink_is_rejected_without_following_it(self):
        linked = self.root / "linked.tar"
        with tarfile.open(linked, "w") as archive:
            item = tarfile.TarInfo("component/LICENSE")
            item.type = tarfile.SYMTYPE
            item.linkname = "../../outside"
            archive.addfile(item)
        notice = dict(self.notice, archivePath="linked.tar")
        with self.assertRaisesRegex(ValueError, "non-file TAR"):
            MATERIALS.acquire(notice, self.root)
        self.assertFalse((self.root / "notices/LICENSE").exists())


if __name__ == "__main__":
    unittest.main()
