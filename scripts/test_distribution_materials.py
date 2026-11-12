import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile


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

    def test_go_module_hash_matches_official_dirhash_fixture(self):
        target = self.root / "module.zip"
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("example.org/module@v1.0.0/LICENSE", self.body)
        # Cross-checked with golang.org/x/mod/sumdb/dirhash.HashZip(Hash1).
        expected = "h1:hMs8cM38fOLySl+6o47XtzF/5kSs3Olk4ebWezcDCzc="
        item = {"localPath": "module.zip", "goH1": expected}
        self.assertEqual(MATERIALS.verify_file(target, item)["digest"], expected)
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            MATERIALS.verify_file(target, dict(item, goH1="h1:" + "0" * 44))

    def test_nested_captured_archive_is_verified_before_notice_extraction(self):
        capture = self.root / "capture.tar"
        inner = self.archive.read_bytes()
        with tarfile.open(capture, "w") as archive:
            member = tarfile.TarInfo("captured/component.tar.gz")
            member.size = len(inner)
            archive.addfile(member, io.BytesIO(inner))
        source = dict(self.source, localPath="inner/component.tar.gz", archivePath="capture.tar", archiveMember="captured/component.tar.gz")
        notice = dict(self.notice, archivePath="inner/component.tar.gz")
        top = {"localPath": "capture.tar", "sha256": hashlib.sha256(capture.read_bytes()).hexdigest()}
        records = MATERIALS.fetch_all({"materials": [notice, source, top]}, self.root)
        self.assertEqual([record["path"] for record in records], ["capture.tar", "inner/component.tar.gz", "notices/LICENSE"])

    def test_missing_derived_archive_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "source archive dependencies"):
            MATERIALS.fetch_all({"materials": [self.notice]}, self.root)

    def test_conflicting_inventory_entries_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            MATERIALS.fetch_all({"materials": [self.source, dict(self.source, sha256="0" * 64)]}, self.root)

    def test_missing_source_has_no_implicit_fallback(self):
        with self.assertRaisesRegex(ValueError, "fixed HTTPS source URL"):
            MATERIALS.acquire({"localPath": "missing.tar", "sha256": "0" * 64}, self.root)

    def test_relocated_ubuntu_packaging_uses_fixed_launchpad_sources(self):
        for filename in ("perl_5.40.1-7ubuntu0.3.debian.tar.xz", "perl_5.40.1-7ubuntu0.3.dsc"):
            with self.subTest(filename=filename):
                original = "https://archive.ubuntu.com/ubuntu/pool/main/p/perl/" + filename
                expected = "https://launchpad.net/ubuntu/+archive/primary/+sourcefiles/perl/5.40.1-7ubuntu0.3/" + filename
                item = {"localPath": filename, "sourceURL": original, "sha256": hashlib.sha256(self.body).hexdigest(), "bytes": len(self.body)}
                response = io.BytesIO(self.body)
                response.url, response.status, response.headers = expected, 200, {"Content-Length": str(len(self.body))}
                with patch.object(MATERIALS.urllib.request, "urlopen", return_value=response) as request:
                    result = MATERIALS.acquire(item, self.root)
                self.assertEqual(request.call_args.args[0].full_url, expected)
                self.assertEqual(result["sha256"], item["sha256"])
                self.assertEqual(item["sourceURL"], original)

    def test_unmapped_source_keeps_its_original_url(self):
        original = "https://archive.ubuntu.com/ubuntu/pool/main/p/perl/perl_5.40.1.orig.tar.xz"
        item = {"localPath": "original-source.tar.xz", "sourceURL": original, "sha256": hashlib.sha256(self.body).hexdigest(), "bytes": len(self.body)}
        response = io.BytesIO(self.body)
        response.url, response.status, response.headers = original, 200, {"Content-Length": str(len(self.body))}
        with patch.object(MATERIALS.urllib.request, "urlopen", return_value=response) as request:
            MATERIALS.acquire(item, self.root)
        self.assertEqual(request.call_args.args[0].full_url, original)

    def test_relocated_source_with_different_content_is_rejected(self):
        item = {"localPath": "wrong-source.tar.xz", "sourceURL": "https://archive.ubuntu.com/ubuntu/pool/main/p/perl/perl_5.40.1-7ubuntu0.3.debian.tar.xz", "sha256": "0" * 64, "bytes": len(self.body)}
        response = io.BytesIO(self.body)
        response.url, response.status, response.headers = "https://launchpadlibrarian.net/fixture", 200, {"Content-Length": str(len(self.body))}
        with patch.object(MATERIALS.urllib.request, "urlopen", return_value=response) as request:
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                MATERIALS.acquire(item, self.root)
        self.assertEqual(request.call_count, 1)
        self.assertFalse((self.root / item["localPath"]).exists())

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
