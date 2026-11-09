"""Fixed candidate, CI, registry collision and credential-boundary publication tests."""

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import uuid

SPEC = importlib.util.spec_from_file_location("container_publication", Path(__file__).with_name("publish-container-images.py"))
publication = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publication)
APP_REVISION = "a" * 40
PACKAGING_REVISION = "b" * 40


def sha(data):
    return hashlib.sha256(data).hexdigest()


def make_tar(files, link=False):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w") as archive:
        for name, content in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        if link:
            member = tarfile.TarInfo("linked-config")
            member.type, member.linkname = tarfile.SYMTYPE, "manifest.json"
            archive.addfile(member)
    return data.getvalue()


def fixture(*, source_url=publication.SOURCE_URL, two_layers=False, extra_config=False, link=False, corrupt_diff_id=False,
            oci=False, legacy_alias=False, inconsistent_legacy=False):
    files, manifests, images = {}, [], []
    for service in ("gateway", "agent"):
        layer = make_tar({"app/name": service.encode(), "licenses/NOTICE": b"isolated fixture attribution"})
        layer_path = "blobs/sha256/" + sha(layer)
        diff_id = "sha256:" + ("0" * 64 if corrupt_diff_id else sha(layer))
        config = {"os": "linux", "architecture": "amd64", "config": {"User": "10001:10001", "Env": ["MODE=fixture", "SERVICE=" + service],
                  "Labels": {"org.opencontainers.image.source": source_url, "org.opencontainers.image.revision": APP_REVISION}},
                  "rootfs": {"type": "layers", "diff_ids": [diff_id] * (2 if two_layers else 1)}}
        content = json.dumps(config).encode()
        image_id = "sha256:" + sha(content)
        config_path = "blobs/sha256/" + sha(content)
        files.update({config_path: content, layer_path: layer})
        manifests.append({"Config": config_path, "Layers": [layer_path] * (2 if two_layers else 1), "RepoTags": None})
        images.append({"service": service, "repository": publication.TARGETS[service], "tag": APP_REVISION, "imageId": image_id})
    if extra_config:
        content = b" \n" + json.dumps({"os": "linux", "architecture": "amd64", "rootfs": {"diff_ids": []}}).encode()
        files["blobs/sha256/" + sha(content)] = content
    files["manifest.json"] = json.dumps(manifests).encode()
    if oci:
        descriptors = []
        for item, image in zip(manifests, images):
            config_bytes = files[item["Config"]]
            layer_name = item["Layers"][0]
            body = json.dumps({"schemaVersion": 2, "mediaType": "application/vnd.oci.image.manifest.v1+json",
                               "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "digest": image["imageId"], "size": len(config_bytes)},
                               "layers": [{"mediaType": "application/vnd.oci.image.layer.v1.tar", "digest": "sha256:" + sha(files[layer_name]), "size": len(files[layer_name])}]}).encode()
            identity = "sha256:" + sha(body)
            files["blobs/sha256/" + sha(body)] = body
            descriptors.append({"mediaType": "application/vnd.oci.image.manifest.v1+json", "digest": identity, "size": len(body)})
            image["imageId"] = identity
            item["RepoTags"] = ["ai-order-assistant-staging-" + image["service"] + ":0.1.2"]
            if legacy_alias:
                alias = json.loads(config_bytes)
                if inconsistent_legacy:
                    alias["config"]["Env"].append("UNREVIEWED=changed-runtime")
                alias_bytes = json.dumps(alias, sort_keys=True).encode()
                alias_name = "blobs/sha256/" + sha(alias_bytes)
                files[alias_name] = alias_bytes
                item["Config"] = alias_name
        files["index.json"] = json.dumps({"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": descriptors}).encode()
        files["oci-layout"] = b'{"imageLayoutVersion":"1.0.0"}'
        files["manifest.json"] = json.dumps(manifests).encode()
    data = make_tar(files, link=link)
    candidate = {"sourceRevision": APP_REVISION, "candidateArchive": {"url": "https://example.invalid/reviewed-candidates.tar.gz",
                 "sha256": sha(data), "bytes": len(data)}, "images": images}
    return candidate, data


class CandidateArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ai-order-container-publication-test-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        if self.root.resolve().parent != Path(tempfile.gettempdir()).resolve() or not self.root.name.startswith("ai-order-container-publication-test-"):
            raise ValueError("Refusing to clean an unrelated test directory")
        if os.name == "nt":
            self.temp._finalizer.detach()
            environment = dict(os.environ, AI_ORDER_CONTAINER_PUBLICATION_TEST=str(self.root))
            subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                            "Add-Type -AssemblyName Microsoft.VisualBasic; "
                            "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory("
                            "$env:AI_ORDER_CONTAINER_PUBLICATION_TEST,'OnlyErrorDialogs','SendToRecycleBin')"],
                           env=environment, check=True, capture_output=True)
        else:
            self.temp.cleanup()

    def verify(self, candidate, content):
        path = self.root / "candidate.tar.gz"
        path.write_bytes(content)
        return publication.verify_candidate_archive(path, candidate)

    def test_exact_two_single_layer_candidates_are_verified_without_docker(self):
        candidate, data = fixture()
        result = self.verify(candidate, data)
        self.assertTrue(result["candidateVerified"])
        self.assertEqual({item["imageId"] for item in result["images"]}, {item["imageId"] for item in candidate["images"]})
        self.assertTrue(all(item["layerCount"] == 1 for item in result["images"]))

    def test_wrong_archive_digest_fails(self):
        candidate, data = fixture()
        candidate["candidateArchive"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.verify(candidate, data)

    def test_unexpected_image_id_fails(self):
        candidate, data = fixture()
        candidate["images"][0]["imageId"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ValueError, "unexpected or duplicate image config"):
            self.verify(candidate, data)

    def test_wrong_source_label_fails(self):
        candidate, data = fixture(source_url="https://github.com/another/unapproved-project")
        with self.assertRaisesRegex(ValueError, "source label"):
            self.verify(candidate, data)

    def test_multiple_layers_fail(self):
        candidate, data = fixture(two_layers=True)
        with self.assertRaisesRegex(ValueError, "exactly one filesystem layer"):
            self.verify(candidate, data)

    def test_extra_oci_image_config_outside_legacy_manifest_fails(self):
        candidate, data = fixture(extra_config=True)
        with self.assertRaisesRegex(ValueError, "unreviewed or unreferenced"):
            self.verify(candidate, data)

    def test_dual_format_oci_manifest_and_config_identities_are_distinct_and_preserved(self):
        candidate, data = fixture(oci=True)
        result = self.verify(candidate, data)
        for item in result["images"]:
            self.assertEqual(item["identityKind"], "oci-manifest")
            self.assertNotEqual(item["imageId"], item["configDigest"])
            self.assertEqual(item["imageId"], item["ociManifestDigest"])
            self.assertEqual(item["legacyConfigAliases"], [item["configDigest"]])

    def test_only_dictionary_and_diffid_equivalent_legacy_config_aliases_are_allowed(self):
        candidate, data = fixture(oci=True, legacy_alias=True)
        result = self.verify(candidate, data)
        self.assertTrue(all(item["legacyConfigAliases"][0] != item["configDigest"] for item in result["images"]))
        candidate, data = fixture(oci=True, legacy_alias=True, inconsistent_legacy=True)
        with self.assertRaisesRegex(ValueError, "Legacy compatibility config is not equivalent"):
            self.verify(candidate, data)

    def test_dual_format_unreferenced_config_is_rejected(self):
        candidate, data = fixture(oci=True, extra_config=True)
        with self.assertRaisesRegex(ValueError, "unreviewed or unreferenced"):
            self.verify(candidate, data)

    def test_oci_descriptor_bytes_are_verified_before_config_use(self):
        candidate, data = fixture(oci=True)
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            files = {member.name: archive.extractfile(member).read() for member in archive if member.isfile()}
        identity = candidate["images"][0]["imageId"]
        files["blobs/sha256/" + identity.removeprefix("sha256:")] += b" "
        data = make_tar(files)
        candidate["candidateArchive"].update(sha256=sha(data), bytes=len(data))
        with self.assertRaisesRegex(ValueError, "OCI descriptor size or digest"):
            self.verify(candidate, data)

    def test_layer_content_must_match_config_diff_id(self):
        candidate, data = fixture(corrupt_diff_id=True)
        with self.assertRaisesRegex(ValueError, "layer differs"):
            self.verify(candidate, data)

    def test_linked_outer_archive_member_fails(self):
        candidate, data = fixture(link=True)
        with self.assertRaisesRegex(ValueError, "linked or special"):
            self.verify(candidate, data)

    def test_local_verify_only_never_authenticates_or_loads(self):
        candidate, data = fixture()
        manifest, archive = self.root / "candidates.json", self.root / "images.tar"
        manifest.write_text(json.dumps(candidate), encoding="utf-8")
        archive.write_bytes(data)
        arguments = ["publish-container-images.py", "--verify-only", "--manifest", str(manifest),
                     "--candidate-archive", str(archive), "--output", str(self.root / "verification")]
        with patch.object(sys, "argv", arguments), patch.object(publication, "registry_token", side_effect=AssertionError("No authentication")), patch.object(publication, "run_docker", side_effect=AssertionError("No Docker changes")), contextlib.redirect_stdout(io.StringIO()):
            publication.main()
        result = json.loads((self.root / "verification/candidate-verification.json").read_text())
        self.assertFalse(result["published"])
        self.assertFalse(result["registryAuthenticationPerformed"])
        self.assertFalse(result["loadedDockerImages"])
        self.assertNotIn("_runtimeConfigs", result)


class PublicationPolicyTest(unittest.TestCase):
    def test_only_two_owned_repositories_and_sha_tags_are_accepted(self):
        candidate, _ = fixture()
        for field, value in [("repository", "ghcr.io/another/project"), ("tag", "latest"), ("tag", "0.1.2")]:
            changed = copy.deepcopy(candidate)
            changed["images"][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, "Unexpected target"):
                publication.validate_candidates(changed)

    def ci_api(self, *, latest_status="completed", head=PACKAGING_REVISION, annotated=False, tag_revision=APP_REVISION):
        def call(path, token):
            if path.startswith("actions/workflows/"):
                name = "CI" if "/ci.yml/" in path else "Dependency and image security"
                return {"workflow_runs": [{"id": 10, "head_sha": head, "name": name, "status": latest_status,
                                          "conclusion": "success" if latest_status == "completed" else None,
                                          "html_url": "https://github.com/fixture/actions/runs/10"}]}
            if path.startswith("git/ref/") and annotated:
                return {"object": {"type": "tag", "sha": "c" * 40}}
            return {"object": {"type": "commit", "sha": tag_revision}}
        return call

    def test_current_packaging_ci_and_peeled_application_tag_have_distinct_roles(self):
        with patch.object(publication, "github_api", side_effect=self.ci_api(annotated=True)):
            result = publication.verify_github_prerequisites(PACKAGING_REVISION, APP_REVISION, "fixture-secret")
        self.assertEqual(result["packagingWorkflowRevision"], PACKAGING_REVISION)
        self.assertEqual(result["applicationSourceRevision"], APP_REVISION)
        self.assertTrue(result["applicationTagMatchVerified"])
        self.assertEqual(len(result["requiredCI"]), 2)

    def test_running_ci_and_old_source_ci_are_not_passes(self):
        for api in [self.ci_api(latest_status="in_progress"), self.ci_api(head=APP_REVISION)]:
            with self.subTest(api=api), patch.object(publication, "github_api", side_effect=api), self.assertRaises(ValueError):
                publication.verify_github_prerequisites(PACKAGING_REVISION, APP_REVISION, "fixture-secret")

    def test_newer_failed_ci_cannot_borrow_an_older_success(self):
        def api(path, token):
            result = self.ci_api()(path, token)
            if "workflow_runs" in result:
                newer = {**result["workflow_runs"][0], "id": 11, "conclusion": "failure"}
                result["workflow_runs"].append(newer)
            return result
        with patch.object(publication, "github_api", side_effect=api), self.assertRaisesRegex(ValueError, "successful CI"):
            publication.verify_github_prerequisites(PACKAGING_REVISION, APP_REVISION, "fixture-secret")

    def test_application_tag_mismatch_fails(self):
        with patch.object(publication, "github_api", side_effect=self.ci_api(tag_revision="d" * 40)), self.assertRaisesRegex(ValueError, "peeled"):
            publication.verify_github_prerequisites(PACKAGING_REVISION, APP_REVISION, "fixture-secret")

    def test_existing_same_image_is_reused_and_different_image_is_never_overwritten(self):
        expected = "sha256:" + "1" * 64
        record = {"imageId": expected, "configDigest": expected, "identityKind": "config"}
        self.assertEqual(publication.existing_tag_action(None, record), "push")
        self.assertEqual(publication.existing_tag_action({"configDigest": expected}, record), "reuse")
        with self.assertRaisesRegex(ValueError, "Refuse to overwrite"):
            publication.existing_tag_action({"configDigest": "sha256:" + "2" * 64}, record)

    def test_oci_manifest_reencapsulation_is_an_explicit_failure_even_with_same_config(self):
        expected = {"imageId": "sha256:" + "1" * 64, "configDigest": "sha256:" + "2" * 64, "identityKind": "oci-manifest"}
        remote = {"registryDigest": expected["imageId"], "imageManifestDigest": expected["imageId"], "configDigest": expected["configDigest"]}
        self.assertEqual(publication.existing_tag_action(remote, expected), "reuse")
        remote["registryDigest"] = remote["imageManifestDigest"] = "sha256:" + "3" * 64
        with self.assertRaisesRegex(ValueError, "re-encapsulation"):
            publication.existing_tag_action(remote, expected)

    def test_registry_401_and_403_are_not_absence(self):
        body = json.dumps({"errors": [{"code": "MANIFEST_UNKNOWN"}]}).encode()
        for code in [401, 403]:
            error = urllib.error.HTTPError("https://ghcr.io", code, "denied", {}, io.BytesIO(body))
            with self.subTest(code=code), patch.object(publication.urllib.request, "urlopen", side_effect=error), self.assertRaisesRegex(RuntimeError, "HTTP " + str(code)):
                publication.registry_manifest(publication.TARGETS["agent"], APP_REVISION, "fixture-secret", missing_ok=True)

    def test_only_structured_registry_404_is_absence(self):
        error = urllib.error.HTTPError("https://ghcr.io", 404, "missing", {}, io.BytesIO(b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'))
        with patch.object(publication.urllib.request, "urlopen", side_effect=error):
            self.assertIsNone(publication.registry_manifest(publication.TARGETS["gateway"], APP_REVISION, "fixture-secret", missing_ok=True))

    def registry_response(self, manifest, wrong_digest=False):
        content = json.dumps(manifest).encode()
        response = io.BytesIO(content)
        response.headers = {"Docker-Content-Digest": "sha256:" + ("0" * 64 if wrong_digest else sha(content))}
        return response

    def test_registry_single_manifest_identity_is_bound_to_body_digest(self):
        manifest = {"schemaVersion": 2, "config": {"digest": "sha256:" + "1" * 64},
                    "layers": [{"digest": "sha256:" + "2" * 64}]}
        with patch.object(publication.urllib.request, "urlopen", return_value=self.registry_response(manifest)):
            result = publication.registry_manifest(publication.TARGETS["agent"], APP_REVISION, "fixture-secret")
        self.assertEqual(result["configDigest"], manifest["config"]["digest"])
        with patch.object(publication.urllib.request, "urlopen", return_value=self.registry_response(manifest, wrong_digest=True)), self.assertRaisesRegex(ValueError, "digest header"):
            publication.registry_manifest(publication.TARGETS["agent"], APP_REVISION, "fixture-secret")
        with patch.object(publication.urllib.request, "urlopen", return_value=self.registry_response(manifest)), self.assertRaisesRegex(ValueError, "different immutable"):
            publication.registry_manifest(publication.TARGETS["agent"], "sha256:" + "0" * 64, "fixture-secret")

    def test_registry_single_platform_index_retains_root_and_image_digests(self):
        manifest = {"schemaVersion": 2, "config": {"digest": "sha256:" + "1" * 64},
                    "layers": [{"digest": "sha256:" + "2" * 64}]}
        image_digest = "sha256:" + sha(json.dumps(manifest).encode())
        index = {"schemaVersion": 2, "manifests": [{"digest": image_digest, "platform": {"os": "linux", "architecture": "amd64"}}]}
        root_digest = "sha256:" + sha(json.dumps(index).encode())
        with patch.object(publication.urllib.request, "urlopen", side_effect=[self.registry_response(index), self.registry_response(manifest)]):
            result = publication.registry_manifest(publication.TARGETS["agent"], APP_REVISION, "fixture-secret")
        self.assertEqual(result["registryDigest"], root_digest)
        self.assertEqual(result["imageManifestDigest"], image_digest)

    def test_docker_login_receives_secret_only_via_stdin_and_errors_redact_it(self):
        secret = "fixture-private-token"
        successful = subprocess.CompletedProcess([], 0, stdout="Login Succeeded", stderr="")
        with patch.object(publication.subprocess, "run", return_value=successful) as run:
            publication.run_docker(Path("private-auth"), ["login", "ghcr.io", "--username", "fixture", "--password-stdin"], secret, secret + "\n")
        self.assertNotIn(secret, " ".join(run.call_args.args[0]))
        self.assertEqual(run.call_args.kwargs["input"], secret + "\n")
        failed = subprocess.CompletedProcess([], 1, stdout="", stderr="failure " + secret)
        with patch.object(publication.subprocess, "run", return_value=failed), self.assertRaises(RuntimeError) as captured:
            publication.run_docker(Path("private-auth"), ["login", "ghcr.io"], secret)
        self.assertNotIn(secret, str(captured.exception))
        self.assertIn("[REDACTED]", str(captured.exception))


@unittest.skipUnless(os.environ.get("AI_ORDER_CONTAINER_PUBLICATION_DOCKER_TEST") == "1", "Set AI_ORDER_CONTAINER_PUBLICATION_DOCKER_TEST=1 for real Docker save format verification")
class ActualDockerSaveTest(unittest.TestCase):
    setUp = CandidateArchiveTest.setUp
    tearDown = CandidateArchiveTest.tearDown

    def test_actual_docker_save_descriptor_chain_and_engine_identity(self):
        nonce = uuid.uuid4().hex
        marker = "io.github.w205614.ai-order-assistant.publication-fixture"
        created = []
        try:
            for service in ("gateway", "agent"):
                rootfs = self.root / (service + ".tar")
                rootfs.write_bytes(make_tar({"app/fixture": (service + nonce).encode()}))
                output = subprocess.check_output(["docker", "image", "import", "--platform", "linux/amd64",
                                                  "--change", "LABEL org.opencontainers.image.source=" + publication.SOURCE_URL,
                                                  "--change", "LABEL org.opencontainers.image.revision=" + APP_REVISION,
                                                  "--change", "LABEL " + marker + "=" + nonce,
                                                  "--change", "ENV FIXTURE_SERVICE=" + service, str(rootfs)], text=True).strip()
                self.assertTrue(publication.valid_digest(output))
                created.append(output)
            saved = self.root / "actual-docker-save.tar"
            subprocess.run(["docker", "image", "save", "--output", str(saved), *created], check=True, capture_output=True)
            images = []
            with tarfile.open(saved) as archive:
                if "index.json" in archive.getnames():
                    for descriptor in json.load(archive.extractfile("index.json"))["manifests"]:
                        manifest = json.load(archive.extractfile("blobs/sha256/" + descriptor["digest"].removeprefix("sha256:")))
                        config = json.load(archive.extractfile("blobs/sha256/" + manifest["config"]["digest"].removeprefix("sha256:")))
                        service = dict(item.split("=", 1) for item in config["config"]["Env"])["FIXTURE_SERVICE"]
                        images.append({"service": service, "repository": publication.TARGETS[service], "tag": APP_REVISION, "imageId": descriptor["digest"]})
                else:
                    for entry in json.load(archive.extractfile("manifest.json")):
                        data = archive.extractfile(entry["Config"]).read()
                        service = dict(item.split("=", 1) for item in json.loads(data)["config"]["Env"])["FIXTURE_SERVICE"]
                        images.append({"service": service, "repository": publication.TARGETS[service], "tag": APP_REVISION, "imageId": "sha256:" + sha(data)})
            candidate = {"sourceRevision": APP_REVISION, "candidateArchive": {"url": "https://example.invalid/actual-save.tar",
                         "sha256": publication.digest(saved), "bytes": saved.stat().st_size}, "images": images}
            result = publication.verify_candidate_archive(saved, candidate)
            self.assertEqual(len(result["images"]), 2)
            self.assertTrue(all(any(identifier in {item["imageId"], item["configDigest"]} for identifier in created) for item in result["images"]))
        finally:
            for identifier in reversed(created):
                image = json.loads(subprocess.check_output(["docker", "image", "inspect", identifier], text=True))[0]
                if image["Config"].get("Labels", {}).get(marker) != nonce:
                    raise ValueError("Refusing to remove an image not owned by the current fixture")
                subprocess.run(["docker", "image", "rm", identifier], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
