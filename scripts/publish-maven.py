"""Publish preverified Release assets and read them back from GitHub Maven Packages."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "W205614/AI-Order-Assistant"
REGISTRY = "https://maven.pkg.github.com/w205614/AI-Order-Assistant"


def authenticated_request(url, authorization, **kwargs):
    request = urllib.request.Request(url, **kwargs)
    request.add_unredirected_header("Authorization", authorization)
    return request


def main():
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("Run the publication workflow in the owning repository")
    tag = os.environ["RELEASE_TAG"]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("An immutable release tag is required")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    token = os.environ["GITHUB_TOKEN"]

    def api(path, binary=False):
        request = authenticated_request(
            "https://api.github.com/repos/" + REPOSITORY + "/" + path,
            "Bearer " + token,
            headers={"Accept": "application/octet-stream" if binary else "application/vnd.github+json",
                     "User-Agent": "ai-order-maven-publication"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            content = response.read()
        return content if binary else json.loads(content)

    runs = api("actions/runs?head_sha=" + revision + "&per_page=100")["workflow_runs"]
    for name in ("CI", "Dependency and image security"):
        matching = [run for run in runs if run["name"] == name and run["head_branch"] == tag]
        if not matching or max(matching, key=lambda run: run["id"])["conclusion"] != "success":
            raise ValueError("The release tag must pass CI and security before package publication")
    release = api("releases/tags/" + tag)
    if release["draft"] or release["prerelease"] or release["tag_name"] != tag:
        raise ValueError("Maven candidates must belong to the published stable release")
    assets = {asset["name"]: asset for asset in release["assets"]}
    manifest = json.loads(api("releases/assets/" + str(assets["maven-manifest.json"]["id"]), True))
    if manifest["sourceRevision"] != revision or manifest["version"] != tag[1:]:
        raise ValueError("Candidate source does not match the checked-out release tag")
    directory = ROOT / ".local/maven-publication"
    directory.mkdir(parents=True, exist_ok=False)
    files = {}
    for kind, entry in manifest["files"].items():
        name = entry["name"]
        if Path(name).name != name:
            raise ValueError("Invalid candidate filename")
        content = api("releases/assets/" + str(assets[name]["id"]), True)
        if hashlib.sha256(content).hexdigest() != entry["sha256"]:
            raise ValueError("Candidate digest mismatch")
        files[kind] = directory / name
        files[kind].write_bytes(content)
    pom = ET.parse(files["pom"]).getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    coordinates = [pom.findtext("m:" + key, namespaces=ns) for key in ("groupId", "artifactId", "version")]
    if coordinates != ["com.ai", "assistant-gateway", tag[1:]]:
        raise ValueError("Unexpected Maven coordinates")
    auth = base64.b64encode((os.environ["GITHUB_ACTOR"] + ":" + token).encode()).decode()

    def download(name):
        url = REGISTRY + "/com/ai/assistant-gateway/" + tag[1:] + "/" + name
        request = authenticated_request(url, "Basic " + auth)
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()

    for path in files.values():
        try:
            download(path.name)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        else:
            raise ValueError("Refuse to overwrite an existing or partially uploaded package version")
    subprocess.run([
        "mvn", "-B", "-ntp", "org.apache.maven.plugins:maven-deploy-plugin:3.2.0:deploy-file",
        "-Dfile=" + str(files["jar"]), "-DpomFile=" + str(files["pom"]),
        "-Dsources=" + str(files["sources"]), "-Dpackaging=jar", "-DrepositoryId=github",
        "-Durl=" + REGISTRY, "-DgeneratePom=false", "-DretryFailedDeploymentCount=1",
    ], cwd=ROOT, check=True)
    verified = {}
    for kind, entry in manifest["files"].items():
        digest = hashlib.sha256(download(entry["name"])).hexdigest()
        if digest != entry["sha256"]:
            raise ValueError("Published Maven content differs from the verified candidate")
        verified[kind] = digest
    receipt = {"passed": True, "sourceRevision": revision, "version": tag[1:],
               "coordinates": ":".join(coordinates), "registry": REGISTRY,
               "downloadedSHA256": verified, "rebuilt": False,
               "authenticationRequired": True}
    (directory / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
