#!/usr/bin/env python3
"""Build and exercise ZIP, tar.gz and Docker distributions against the local demo.

Requires Java 25, Docker Compose and native execution or Docker emulation for
the requested platforms. Never publishes images or contacts an existing cluster.
"""

import argparse
import csv
import hashlib
import io
import json
import os
import platform
import socket
import subprocess
import tarfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--platforms",
        nargs="+",
        choices=["linux/amd64", "linux/arm64"],
        default=["linux/amd64", "linux/arm64"],
    )
    args = parser.parse_args()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    directory = ROOT / "target/distribution-it" / stamp
    directory.mkdir(parents=True)
    project = "ksm-distribution-" + stamp.lower()
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    env = dict(os.environ, KSM_DEMO_PORT=str(port), KSM_IMAGE=project + "-ksm:local")
    for key in list(env):
        if (
            key.startswith(("SOURCE_", "ADMIN_CLIENT_", "AUTHORIZER_", "NOTIFICATION_"))
            or (key.startswith("KSM_") and key not in ("KSM_DEMO_PORT", "KSM_IMAGE"))
            or key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")
        ):
            env.pop(key)
    command_number = 0
    results = []
    images = [env["KSM_IMAGE"]]

    def run(command, label, *, timeout=120, input=None, check=True):
        nonlocal command_number
        command_number += 1
        result = subprocess.run(
            [str(x) for x in command],
            cwd=ROOT,
            env=env,
            input=input,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        (directory / f"{command_number:03}-{label}.stdout").write_text(result.stdout)
        (directory / f"{command_number:03}-{label}.stderr").write_text(result.stderr)
        if check and result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}; inspect {directory}")
        return result

    def compose(*arguments, **kwargs):
        return run(
            ["docker", "compose", "-p", project, *arguments], "compose-" + arguments[0], **kwargs
        )

    def acls(text):
        rows = list(csv.reader(io.StringIO(text)))
        assert rows and rows[0][0] == "KafkaPrincipal", "Export stdout is not clean CSV"
        return {tuple(row) for row in rows[1:]}

    desired = acls((ROOT / "example/demo-acls.csv").read_text())
    policy = directory / "policy.csv"
    policy.write_text(
        (ROOT / "example/demo-acls.csv").read_text()
        + "User:app,Topic,LITERAL,distribution-proof,Describe,Allow,*\n"
    )
    modified = acls(policy.read_text())
    credentials = 'org.apache.kafka.common.security.plain.PlainLoginModule required username="manager" password="local-demo";'
    auth = {
        "ADMIN_CLIENT_BOOTSTRAP_SERVERS": "kafka:9092",
        "ADMIN_CLIENT_SECURITY_PROTOCOL": "SASL_PLAINTEXT",
        "ADMIN_CLIENT_SASL_MECHANISM": "PLAIN",
        "ADMIN_CLIENT_SASL_JAAS_CONFIG": credentials,
    }

    def record(name, **details):
        result = dict(scenario=name, status="PASS", **details)
        results.append(result)
        print(json.dumps(result), flush=True)

    status = "FAIL"
    try:
        # Execute the README quickstart with its own isolated Compose project.
        compose("up", "--build", "-d", timeout=300)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if "Reconciliation succeeded" in compose("logs", "--no-color", "ksm").stdout:
                break
            time.sleep(0.2)
        else:
            raise AssertionError("Quickstart KSM did not reconcile")
        compose(
            "exec",
            "-T",
            "kafka",
            "/opt/kafka/bin/kafka-console-producer.sh",
            "--bootstrap-server",
            "kafka:9092",
            "--topic",
            "orders",
            "--producer.config",
            "/demo/demo-app.properties",
            input="hello from KSM\n",
        )
        consumed = compose(
            "exec",
            "-T",
            "kafka",
            "/opt/kafka/bin/kafka-console-consumer.sh",
            "--bootstrap-server",
            "kafka:9092",
            "--topic",
            "orders",
            "--group",
            "orders-reader",
            "--consumer.config",
            "/demo/demo-app.properties",
            "--from-beginning",
            "--max-messages",
            "1",
            "--timeout-ms",
            "15000",
        )
        assert "hello from KSM" in consumed.stdout
        compose("stop", "ksm")
        record("readme-quickstart-producer-consumer")

        client = {
            "bootstrap.servers": "localhost:" + str(port),
            "security.protocol": "SASL_PLAINTEXT",
            "sasl.mechanism": "PLAIN",
            "sasl.jaas.config": credentials,
        }
        config = directory / "config with spaces.conf"
        config.write_text(
            "authorizer.admin-client-config="
            + json.dumps(client)
            + """
ksm { readonly=false, refresh.frequency.ms=0 }
source.class="io.conduktor.ksm.source.FileSourceAcl"
source.file.filename="""
            + json.dumps(str(policy))
            + "\n"
        )
        for extension in ("zip", "tar.gz"):
            archive = ROOT / f"target/kafka-security-manager-2.0.0-bin.{extension}"
            unpacked = directory / extension
            unpacked.mkdir()
            if extension == "zip":
                with zipfile.ZipFile(archive) as zipped:
                    zipped.extractall(unpacked)
                    for entry in zipped.infolist():
                        if entry.filename.endswith("/bin/kafka-security-manager"):
                            mode = entry.external_attr >> 16
                            assert mode & 0o111, "ZIP launcher must be executable"
                            (unpacked / entry.filename).chmod(mode)
            else:
                with tarfile.open(archive) as tar:
                    tar.extractall(unpacked, filter="data")
            launcher = next(unpacked.glob("*/bin/kafka-security-manager"))
            command = [launcher, "-Dconfig.file=" + str(config)]
            assert "Usage:" in run([launcher, "--help"], extension + "-help").stdout
            run([*command, "--dry-run"], extension + "-preview")
            run(command, extension + "-apply")
            observed = run([*command, "-Dksm.extract.enable=true"], extension + "-extract")
            assert acls(observed.stdout) == modified
            run(
                [*command, "-Dsource.file.filename=" + str(ROOT / "example/demo-acls.csv")],
                extension + "-restore",
            )
            observed = run([*command, "-Dksm.extract.enable=true"], extension + "-restored")
            assert acls(observed.stdout) == desired
            record(
                extension + "-launcher-real-apply-extract",
                sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
            )

        engine_arch = run(
            ["docker", "info", "--format", "{{.Architecture}}"], "engine-arch"
        ).stdout.strip()
        for target in args.platforms:
            arch = target.split("/")[1]
            image = f"ksm-distribution-test:{stamp}-{arch}"
            images.append(image)
            run(
                ["docker", "build", "--platform", target, "-t", image, "."],
                arch + "-build",
                timeout=300,
            )
            inspected = json.loads(
                run(["docker", "image", "inspect", image], arch + "-inspect").stdout
            )[0]
            assert inspected["Architecture"] == arch
            assert inspected["Config"]["User"] == "10001:10001"
            runtime = run(
                [
                    "docker",
                    "run",
                    "--rm",
                    "--platform",
                    target,
                    "--entrypoint",
                    "java",
                    image,
                    "-XshowSettings:properties",
                    "-version",
                ],
                arch + "-runtime",
            )
            assert "java.version = 25." in runtime.stderr
            assert "os.arch = " + ("aarch64" if arch == "arm64" else "amd64") in runtime.stderr
            base = [
                "docker",
                "run",
                "--rm",
                "--platform",
                target,
                "--network",
                project + "_default",
                "-v",
                str(directory) + ":/test:ro",
                "-v",
                str(ROOT / "example") + ":/example:ro",
            ]
            for key, value in auth.items():
                base += ["-e", key + "=" + value]
            for key, value in {
                "KSM_READONLY": "false",
                "KSM_REFRESH_FREQUENCY_MS": "0",
                "SOURCE_CLASS": "io.conduktor.ksm.source.FileSourceAcl",
                "SOURCE_FILE_FILENAME": "/test/policy.csv",
            }.items():
                base += ["-e", key + "=" + value]
            command = [*base, image]
            run([*command, "--dry-run"], arch + "-preview")
            run(command, arch + "-apply")
            observed = run([*command, "-Dksm.extract.enable=true"], arch + "-extract")
            assert acls(observed.stdout) == modified
            run([*command, "-Dsource.file.filename=/example/demo-acls.csv"], arch + "-restore")
            observed = run([*command, "-Dksm.extract.enable=true"], arch + "-restored")
            assert acls(observed.stdout) == desired
            record(
                "docker-real-apply-extract",
                platform=target,
                engine_arch=engine_arch,
                execution="native"
                if engine_arch in (arch, "aarch64" if arch == "arm64" else "x86_64")
                else "emulated",
                image_id=inspected["Id"],
            )
        status = "PASS"
    finally:
        compose("logs", "--no-color", check=False)
        compose("down", "-v", "--remove-orphans", check=False)
        for image in images:
            run(["docker", "image", "rm", image], "remove-test-image", check=False)
        report = {"status": status, "host_arch": platform.machine(), "results": results}
        (directory / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        print("Report: " + str(directory / "results.json"), flush=True)


if __name__ == "__main__":
    main()
