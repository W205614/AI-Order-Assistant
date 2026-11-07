"""Verify real Alertmanager webhook and SMTP delivery on a private, disposable Docker network."""

import argparse
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
ALERTMANAGER = "ai-order-assistant-alertmanager:0.34.2-security1"
PROMETHEUS = "ai-order-assistant-prometheus:3.15.1-security1"

RECEIVER = r'''
import email.policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socketserver
import threading

output = Path("/output")
events, mails = [], []
lock = threading.Lock()

class Receiver(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        with lock:
            body = json.dumps({"webhooks": events, "emails": mails}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        if self.path != "/events" or not 0 < size <= 1024 * 1024:
            self.send_error(400)
            return
        body = json.loads(self.rfile.read(size))
        with lock:
            events.append(body)
            (output / ("webhook-%02d.json" % len(events))).write_text(json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8")
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

class SMTP(socketserver.StreamRequestHandler):
    def reply(self, message):
        self.wfile.write(message.encode("ascii") + b"\r\n")
        self.wfile.flush()

    def handle(self):
        self.request.settimeout(30)
        self.reply("220 receiver.fixture.invalid local verification SMTP")
        while True:
            line = self.rfile.readline(8193)
            if not line:
                return
            command = line.decode("ascii", errors="replace").strip().split(" ", 1)[0].upper()
            if command in {"EHLO", "HELO"}:
                self.reply("250-receiver.fixture.invalid")
                self.reply("250-8BITMIME")
                self.reply("250 SIZE 1048576")
            elif command in {"MAIL", "RCPT", "RSET", "NOOP"}:
                self.reply("250 OK")
            elif command == "DATA":
                self.reply("354 End with <CRLF>.<CRLF>")
                chunks, size = [], 0
                while True:
                    part = self.rfile.readline(65537)
                    if part in {b".\r\n", b".\n"}:
                        break
                    if not part:
                        return
                    if part.startswith(b".."):
                        part = part[1:]
                    size += len(part)
                    if size > 1024 * 1024:
                        self.reply("552 Message too large")
                        return
                    chunks.append(part)
                raw = b"".join(chunks)
                parsed = BytesParser(policy=email.policy.default).parsebytes(raw)
                subject = str(parsed["Subject"])
                with lock:
                    mails.append({"subject": subject, "from": str(parsed["From"]), "to": str(parsed["To"]), "bytes": len(raw)})
                    (output / ("email-%02d.eml" % len(mails))).write_bytes(raw)
                    (output / "emails.json").write_text(json.dumps(mails, indent=2), encoding="utf-8")
                self.reply("250 Message received")
            elif command == "QUIT":
                self.reply("221 Bye")
                return
            else:
                self.reply("502 Unsupported verification SMTP command")

class SMTPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

threading.Thread(target=SMTPServer(("0.0.0.0", 1025), SMTP).serve_forever, daemon=True).start()
ThreadingHTTPServer(("0.0.0.0", 8080), Receiver).serve_forever()
'''


def command(arguments, log, timeout=120):
    result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    with log.open("a", encoding="utf-8") as target:
        target.write("\n$ " + " ".join(arguments) + "\n")
        target.write(result.stdout)
        target.write(result.stderr)
    if result.returncode:
        raise RuntimeError("Alert delivery verification command failed; inspect its local log")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", type=Path, default=ROOT / ".local/trial-20261106/alertmanager-test")
    parser.add_argument("--receiver-image", default="ai-order-assistant-agent:latest", help="An existing image containing /usr/local/bin/python; no image is built")
    parser.add_argument("--alertmanager-image", default=ALERTMANAGER)
    parser.add_argument("--prometheus-image", default=PROMETHEUS)
    parser.add_argument("--subnet", default="10.77.244.0/28", help="Private test subnet; verify it does not overlap the host before running")
    args = parser.parse_args()
    suffix = uuid.uuid4().hex[:10]
    project = "ai-order-alerttest-" + suffix
    directory = args.output_directory.resolve() / suffix
    directory.mkdir(parents=True, exist_ok=False)
    output = directory / "received"
    output.mkdir()
    log = directory / "commands.log"
    receipt = {"schemaVersion": 1, "project": project, "passed": False, "credentialsUsed": False, "externalServicesContacted": False, "cleanupComplete": False}
    compose = None
    try:
        receiver_id = command(["docker", "image", "inspect", args.receiver_image, "--format", "{{.Id}}"], log).strip()
        if not receiver_id.startswith("sha256:"):
            raise ValueError("Existing receiver image ID is invalid")
        alertmanager_id = command(["docker", "image", "inspect", args.alertmanager_image, "--format", "{{.Id}}"], log).strip()
        prometheus_id = command(["docker", "image", "inspect", args.prometheus_image, "--format", "{{.Id}}"], log).strip()
        spec = importlib.util.spec_from_file_location("render_alertmanager", ROOT / "scripts/render-alertmanager.py")
        renderer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(renderer)
        config = renderer.build_config({
            "ALERT_WEBHOOK_URL": "https://receiver.fixture.invalid/events",
            "ALERT_SMTP_HOST": "receiver:1025", "ALERT_SMTP_FROM": "alerts@fixture.invalid",
            "ALERT_SMTP_TO": "operator@fixture.invalid",
        })
        # Only the disposable private receiver uses plaintext. Production rendering keeps TLS.
        config["global"]["smtp_require_tls"] = False
        config["receivers"][0]["webhook_configs"][0]["url"] = "http://receiver:8080/events"
        config["route"].update(group_wait="1s", group_interval="1s", repeat_interval="10m")
        (directory / "alertmanager.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
        (directory / "prometheus.json").write_text(json.dumps({"global": {"scrape_interval": "1s"}, "scrape_configs": []}), encoding="utf-8")
        (directory / "receiver.py").write_text(RECEIVER, encoding="utf-8")
        configuration = {
            "services": {
                "receiver": {
                    "image": receiver_id, "pull_policy": "never", "read_only": True,
                    # The host owns bind-mounted fixture output on Linux CI runners.
                    "user": f"{os.getuid()}:{os.getgid()}" if os.name == "posix" else "10001:10001",
                    "entrypoint": ["/usr/local/bin/python", "-u", "/fixture/receiver.py"],
                    "healthcheck": {"disable": True}, "cap_drop": ["ALL"],
                    "security_opt": ["no-new-privileges:true"],
                    "volumes": [str(directory) + ":/fixture:ro", str(output) + ":/output"],
                    "networks": ["verification"],
                },
                "alertmanager": {
                    "image": alertmanager_id, "pull_policy": "never", "read_only": True,
                    "cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"],
                    "tmpfs": ["/tmp", "/alertmanager"], "networks": ["verification"],
                    "command": ["--config.file=/fixture/alertmanager.json", "--storage.path=/tmp/alertmanager", "--cluster.listen-address="],
                    "volumes": [str(directory) + ":/fixture:ro"],
                },
                "promtool": {
                    "image": prometheus_id, "pull_policy": "never", "profiles": ["validation"],
                    "entrypoint": ["/bin/promtool"], "network_mode": "none",
                    "volumes": [str(ROOT / "deploy") + ":/rules:ro"],
                },
                "prometheus": {
                    "image": prometheus_id, "pull_policy": "never", "read_only": True,
                    "cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"],
                    "tmpfs": ["/tmp", "/prometheus"], "networks": ["verification"],
                    "command": ["--config.file=/fixture/prometheus.json", "--storage.tsdb.path=/tmp/prometheus"],
                    "volumes": [str(directory) + ":/fixture:ro"],
                },
            },
            "networks": {"verification": {"internal": True, "ipam": {"config": [{"subnet": args.subnet}]}}},
        }
        compose_file = directory / "compose.json"
        compose_file.write_text(json.dumps(configuration, indent=2), encoding="utf-8")
        compose = ["docker", "compose", "-p", project, "-f", str(compose_file)]
        promtool = command(compose + ["--profile", "validation", "run", "--rm", "promtool", "check", "rules", "/rules/alerts.yml", "/rules/operations-alerts.yml"], log)
        receipt["promtoolRulesValid"] = "SUCCESS" in promtool
        if not receipt["promtoolRulesValid"]:
            raise RuntimeError("Promtool did not validate both rule files")
        command(compose + ["up", "-d", "--no-build", "receiver", "alertmanager", "prometheus"], log)

        def inside(source):
            return command(compose + ["exec", "-T", "receiver", "/usr/local/bin/python", "-c", source], log, timeout=30)

        deadline = time.monotonic() + 40
        while True:
            try:
                inside("import urllib.request; [urllib.request.urlopen(url,timeout=3).read() for url in ('http://alertmanager:9093/-/ready','http://prometheus:9090/-/ready')]; print('ready')")
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.5)
        inside(r'''
import json, re, urllib.parse, urllib.request
for base, page in (("http://alertmanager:9093", "/"), ("http://prometheus:9090", "/query")):
    html = urllib.request.urlopen(base + page, timeout=5).read().decode()
    assert "<!doctype html>" in html.lower(), "Missing web UI"
    asset = re.search(r'<script[^>]*src=["\']([^"\']+)["\']', html)
    assert asset, "Missing embedded JavaScript"
    url = urllib.parse.urljoin(base + page, asset.group(1))
    assert urllib.parse.urlsplit(url).netloc == urllib.parse.urlsplit(base).netloc
    assert urllib.request.urlopen(url, timeout=5).read(), "Empty UI asset"
result = json.loads(urllib.request.urlopen("http://prometheus:9090/api/v1/query?query=1%2B1", timeout=5).read())
assert result["status"] == "success" and result["data"]["result"][1] == "2"
print("Both web UIs, embedded assets and PromQL passed")
''')
        receipt.update(webUIsValid=True, prometheusQueryValid=True)
        now = datetime.now(timezone.utc)
        labels = {"alertname": "LocalDeliveryVerification", "severity": "warning", "verification": suffix}
        alert = {"labels": labels, "annotations": {"summary": "Synthetic local delivery verification; no credentials or customer data"}, "startsAt": (now - timedelta(seconds=2)).isoformat(), "endsAt": (now + timedelta(minutes=2)).isoformat()}

        def submit():
            encoded = json.dumps([alert])
            inside("import urllib.request; data=" + repr(encoded) + ".encode(); req=urllib.request.Request('http://alertmanager:9093/api/v2/alerts',data=data,headers={'Content-Type':'application/json'},method='POST'); print(urllib.request.urlopen(req,timeout=5).status)")

        def received():
            return json.loads(inside("import urllib.request; print(urllib.request.urlopen('http://receiver:8080/status',timeout=3).read().decode())"))

        def wait_for(status):
            end = time.monotonic() + 45
            while time.monotonic() < end:
                snapshot = received()
                webhook = any(item.get("status") == status.lower() for item in snapshot["webhooks"])
                email = any(status.upper() in item["subject"].upper() for item in snapshot["emails"])
                if webhook and email:
                    return snapshot
                time.sleep(0.5)
            raise RuntimeError("Both receivers did not observe " + status)

        submit()
        wait_for("firing")
        alert["endsAt"] = datetime.now(timezone.utc).isoformat()
        submit()
        snapshot = wait_for("resolved")
        if not all(item.get("commonLabels", {}).get("verification") == suffix for item in snapshot["webhooks"]):
            raise RuntimeError("Receiver observed an unrelated alert")
        receipt.update(
            passed=True, webhookStatuses=[item["status"] for item in snapshot["webhooks"]],
            smtpSubjects=[item["subject"] for item in snapshot["emails"]],
            webhookReceived=len(snapshot["webhooks"]), smtpReceived=len(snapshot["emails"]),
            alertmanagerImageId=alertmanager_id, prometheusImageId=prometheus_id, receiverImageId=receiver_id,
        )
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        receipt["errorType"] = type(error).__name__
        receipt["error"] = "Local delivery verification did not complete; inspect commands.log and service logs"
        if compose:
            try:
                command(compose + ["logs", "--no-color"], log)
            except (RuntimeError, subprocess.TimeoutExpired):
                receipt["serviceLogsAvailable"] = False
    finally:
        if compose:
            try:
                command(compose + ["down", "--timeout", "5"], log)
                receipt["cleanupComplete"] = True
            except (RuntimeError, subprocess.TimeoutExpired):
                receipt["passed"] = False
                receipt["cleanupComplete"] = False
        receipt["completedAt"] = datetime.now(timezone.utc).isoformat()
        (directory / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({**receipt, "reportDirectory": str(directory)}, ensure_ascii=False))
    return int(not receipt["passed"])


if __name__ == "__main__":
    raise SystemExit(main())
