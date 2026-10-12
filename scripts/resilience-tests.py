#!/usr/bin/env python3
"""Exercise the packaged KSM on three separate controllers and two brokers.

Inject a lost CreateAcls response, controller election, total broker outage,
source errors and notification failure. All processes and data are disposable.
"""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
import os
import re
import signal
import socket
import struct
import subprocess
import threading
import time
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "brokers", Path(__file__).with_name("broker-tests.py")
)
B = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(B)


class FaultProxy:
    """Frame-aware TCP proxy. Drop responses after forwarding a CreateAcls request."""

    def __init__(self, listen, destination, armed, triggered):
        self.destination, self.armed, self.triggered = destination, armed, triggered
        self.server = socket.socket()
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("127.0.0.1", listen))
        self.server.listen()
        self.closed = False
        self.sockets = []
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while not self.closed:
            try:
                client, _ = self.server.accept()
                upstream = socket.create_connection(("127.0.0.1", self.destination), timeout=5)
                upstream.settimeout(None)
                self.sockets.extend([client, upstream])
                threading.Thread(
                    target=self.forward, args=(client, upstream, True), daemon=True
                ).start()
                threading.Thread(
                    target=self.forward, args=(upstream, client, False), daemon=True
                ).start()
            except OSError:
                if self.closed:
                    return
                try:
                    client.close()
                except (UnboundLocalError, OSError):
                    pass

    def forward(self, source, destination, requests):
        pending = b""
        try:
            while data := source.recv(65536):
                if self.triggered.is_set() and self.armed.is_set():
                    return
                if not requests:
                    destination.sendall(data)
                    continue
                pending += data
                while len(pending) >= 4:
                    size = struct.unpack(">i", pending[:4])[0]
                    if size < 0 or size > 64 * 1024 * 1024:
                        return
                    if len(pending) < size + 4:
                        break
                    frame, pending = pending[: size + 4], pending[size + 4 :]
                    create_acls = size >= 2 and struct.unpack(">h", frame[4:6])[0] == 30
                    if create_acls and self.armed.is_set():
                        # Mark before sending so the reverse thread cannot deliver a successful response.
                        self.triggered.set()
                        destination.sendall(frame)
                        return
                    destination.sendall(frame)
        except OSError:
            pass
        finally:
            for sock in (source, destination):
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()

    def close(self):
        self.closed = True
        self.server.close()
        for sock in self.sockets:
            try:
                sock.close()
            except OSError:
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--java25",
        type=Path,
        default=Path(
            os.environ.get("JAVA_HOME", Path.home() / ".sdkman/candidates/java/25.0.3-tem")
        ),
    )
    parser.add_argument(
        "--cache",
        type=Path,
        default=Path(os.environ.get("KSM_KAFKA_CACHE", Path.home() / ".cache/ksm-kafka")),
    )
    args = parser.parse_args()
    java = args.java25.resolve()
    directory = B.ROOT / "target/resilience-it" / time.strftime("%Y%m%d-%H%M%S")
    directory.mkdir(parents=True)
    jar = directory / "ksm.jar"
    import shutil

    shutil.copy2(B.ROOT / "target/kafka-security-manager-2.0.0.jar", jar)
    distro = B.download(next(c for c in B.MATRIX if c["version"] == "4.3.1"), args.cache.resolve())
    env = dict(
        os.environ,
        JAVA_HOME=str(java),
        KAFKA_HEAP_OPTS="-Xms128m -Xmx384m",
        KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseG1GC",
        LOG_DIR=str(directory / "logs"),
    )
    for key in list(env):
        if key.startswith(
            ("KSM_", "SOURCE_", "NOTIFICATION_", "ADMIN_CLIENT_", "AUTHORIZER_")
        ) or key in ("KAFKA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):
            env.pop(key)
    handles, processes, proxies, events = [], {}, [], []
    controls = {i: B.port() for i in (1, 2, 3)}
    clients = {i: B.port() for i in (4, 5)}
    internals = {i: B.port() for i in (4, 5)}
    exposed = {i: B.port() for i in (4, 5)}
    voters = ",".join(f"{i}@127.0.0.1:{p}" for i, p in controls.items())
    armed, triggered = threading.Event(), threading.Event()
    source = directory / "policy.csv"
    header = "KafkaPrincipal,ResourceType,PatternType,ResourceName,Operation,PermissionType,Host\n"
    management = {
        "User:manager,Cluster,LITERAL,kafka-cluster,Describe,Allow,*",
        "User:manager,Cluster,LITERAL,kafka-cluster,Alter,Allow,*",
    }
    expected = set(management)
    probe = 0

    def record(name, **details):
        event = dict(scenario=name, **details)
        events.append(event)
        print(json.dumps(event), flush=True)

    def log(label):
        handle = (directory / (label + ".log")).open("a")
        handles.append(handle)
        return handle

    def start_node(node):
        processes[node] = subprocess.Popen(
            [str(distro / "bin/kafka-server-start.sh"), str(directory / f"node-{node}.properties")],
            env=env,
            stdout=log(f"node-{node}"),
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        B.wait_port(controls[node] if node < 4 else clients[node], processes[node], 90)

    def stop(key, hard=False):
        process = processes.get(key)
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL if hard else signal.SIGTERM)
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()

    def ksm_config(path, user, ports, extra=""):
        values = {
            "bootstrap.servers": ",".join(f"127.0.0.1:{p}" for p in ports),
            "security.protocol": "SASL_PLAINTEXT",
            "sasl.mechanism": "PLAIN",
            "sasl.jaas.config": B.jaas(user),
            "default.api.timeout.ms": "4000",
            "request.timeout.ms": "2000",
        }
        path.write_text("authorizer.admin-client-config=" + json.dumps(values) + "\n" + extra)
        return path

    def read_acls(node=4):
        nonlocal probe
        probe += 1
        config = ksm_config(
            directory / f"extract-{node}.conf",
            "root",
            [internals[node]],
            "ksm.extract.enable=true\n",
        )
        result = subprocess.run(
            [str(java / "bin/java"), "-Dconfig.file=" + str(config), "-jar", str(jar)],
            env=env,
            text=True,
            capture_output=True,
            timeout=12,
        )
        (directory / f"observed-{probe}.csv").write_text(result.stdout)
        (directory / f"extract-{probe}.log").write_text(result.stderr)
        if result.returncode:
            raise RuntimeError("Admin API extract failed")
        return {",".join(row) for row in list(csv.reader(io.StringIO(result.stdout)))[1:]}

    def eventually(description, predicate, seconds=45):
        deadline, last = time.monotonic() + seconds, None
        while time.monotonic() < deadline:
            try:
                if predicate():
                    return
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                last = error
            if "ksm" in processes and processes["ksm"].poll() is not None:
                raise AssertionError("Continuous KSM exited; inspect ksm.log")
            time.sleep(0.1)
        raise AssertionError(description + " timed out: " + str(last))

    def write_policy(rows):
        temporary = source.with_suffix(".tmp")
        temporary.write_text(header + "\n".join(sorted(rows)) + "\n")
        temporary.replace(source)

    def convergence():
        eventually(
            "ACL convergence on both brokers",
            lambda: read_acls(4) == expected and read_acls(5) == expected,
        )

    def start_ksm():
        processes["ksm"] = subprocess.Popen(
            [
                str(java / "bin/java"),
                "-Dconfig.file=" + str(directory / "ksm.conf"),
                "-jar",
                str(jar),
            ],
            env=env,
            stdout=log("ksm"),
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    def failures():
        path = directory / "ksm.log"
        return path.read_text().count("Cycle failed:") if path.exists() else 0

    def leader():
        result = subprocess.run(
            [
                str(distro / "bin/kafka-metadata-quorum.sh"),
                "--bootstrap-server",
                f"127.0.0.1:{internals[4]}",
                "--command-config",
                str(directory / "root.properties"),
                "describe",
                "--status",
            ],
            env=env,
            capture_output=True,
            text=True,
            timeout=15,
        )
        (directory / "quorum.log").open("a").write(result.stdout + result.stderr)
        match = re.search(r"LeaderId:\s+(\d+)", result.stdout)
        if result.returncode or not match:
            raise RuntimeError("No metadata leader")
        return int(match.group(1))

    status = "FAIL"
    try:
        for node in range(1, 6):
            controller = node < 4
            config = {
                "node.id": node,
                "process.roles": "controller" if controller else "broker",
                "controller.quorum.voters": voters,
                "controller.listener.names": "CONTROLLER",
                "listener.security.protocol.map": "CLIENT:SASL_PLAINTEXT,INTERNAL:SASL_PLAINTEXT,CONTROLLER:SASL_PLAINTEXT",
                "listeners": f"CONTROLLER://127.0.0.1:{controls[node]}"
                if controller
                else f"CLIENT://127.0.0.1:{clients[node]},INTERNAL://127.0.0.1:{internals[node]}",
                "inter.broker.listener.name": "INTERNAL",
                "sasl.enabled.mechanisms": "PLAIN",
                "sasl.mechanism.inter.broker.protocol": "PLAIN",
                "sasl.mechanism.controller.protocol": "PLAIN",
                "authorizer.class.name": "org.apache.kafka.metadata.authorizer.StandardAuthorizer",
                "super.users": "User:root",
                "allow.everyone.if.no.acl.found": "false",
                "log.dirs": directory / f"data-{node}",
                "offsets.topic.replication.factor": "2",
                "transaction.state.log.replication.factor": "2",
                "transaction.state.log.min.isr": "1",
            }
            for listener in ("client", "internal", "controller"):
                config[f"listener.name.{listener}.plain.sasl.jaas.config"] = B.jaas(
                    "root", server=True
                )
            if not controller:
                config["advertised.listeners"] = (
                    f"CLIENT://127.0.0.1:{exposed[node]},INTERNAL://127.0.0.1:{internals[node]}"
                )
                proxies.append(FaultProxy(exposed[node], clients[node], armed, triggered))
            config_path = directory / f"node-{node}.properties"
            B.properties(config_path, config)
            B.execute(
                [
                    distro / "bin/kafka-storage.sh",
                    "format",
                    "-t",
                    "MkU3OEVBNTcwNTJENDM2Qk",
                    "-c",
                    config_path,
                ],
                env,
                directory / f"format-{node}.log",
            )
        for node in range(1, 6):
            start_node(node)
        B.properties(
            directory / "root.properties",
            {
                "security.protocol": "SASL_PLAINTEXT",
                "sasl.mechanism": "PLAIN",
                "sasl.jaas.config": B.jaas("root"),
            },
        )
        eventually("Initial authorizer readiness", lambda: read_acls() == set())
        B.execute(
            [
                distro / "bin/kafka-acls.sh",
                "--bootstrap-server",
                f"127.0.0.1:{internals[4]}",
                "--command-config",
                directory / "root.properties",
                "--add",
                "--allow-principal",
                "User:manager",
                "--operation",
                "Describe",
                "--operation",
                "Alter",
                "--cluster",
            ],
            env,
            directory / "bootstrap-acls.log",
        )
        write_policy(expected)
        ksm_config(
            directory / "ksm.conf",
            "manager",
            exposed.values(),
            """
ksm { readonly=false, debug=true, refresh.frequency.ms=200, cycle.timeout.ms=30000 }
source.class="io.conduktor.ksm.source.FileSourceAcl"
source.file.filename="""
            + json.dumps(str(source))
            + """
notification.class="io.conduktor.ksm.notification.SlackNotification"
notification.slack.webhook="http://127.0.0.1:1/unavailable"
notification.slack.request.timeout.ms=300
""",
        )
        start_ksm()
        expected.add("User:app,Topic,LITERAL,initial,Read,Allow,*")
        write_policy(expected)
        convergence()
        eventually(
            "Slack failure stays separate",
            lambda: "after successful ACL application" in (directory / "ksm.log").read_text(),
        )
        record(
            "separate-controllers-forwarding-and-propagation",
            status="PASS",
            acl_count=len(expected),
        )

        initial = set(expected)
        previous = failures()
        armed.set()
        expected.update(f"User:app,Topic,LITERAL,bulk-{i},Read,Allow,*" for i in range(250))
        write_policy(expected)
        assert triggered.wait(15), "CreateAcls request never traversed the fault proxy"
        eventually("Lost connection causes a failed cycle", lambda: failures() > previous, 20)
        during_fault = read_acls()
        assert initial <= during_fault <= expected
        (directory / "fault-state.json").write_text(
            json.dumps(
                {"before": len(initial), "observed": len(during_fault), "desired": len(expected)}
            )
        )
        armed.clear()
        convergence()
        record(
            "create-request-forwarded-response-lost-recovery",
            status="PASS",
            observed_during_fault=len(during_fault),
        )

        old_leader = leader()
        expected.add("User:app,Topic,LITERAL,after-election,Read,Allow,*")
        write_policy(expected)
        stop(old_leader, hard=True)
        eventually("New controller leader", lambda: leader() != old_leader)
        convergence()
        record(
            "controller-leader-killed-during-continuous-reconciliation",
            status="PASS",
            old_leader=old_leader,
            new_leader=leader(),
        )
        start_node(old_leader)

        previous = failures()
        stop(4, hard=True)
        stop(5, hard=True)
        expected.remove("User:app,Topic,LITERAL,initial,Read,Allow,*")
        expected.add("User:app,Topic,LITERAL,after-outage,Read,Allow,*")
        write_policy(expected)
        eventually("Total broker outage is reported", lambda: failures() > previous, 20)
        for node in (4, 5):
            start_node(node)
        convergence()
        record("total-broker-outage-and-persistent-restart", status="PASS")

        previous = failures()
        source.unlink()
        eventually("Missing source is reported", lambda: failures() > previous)
        assert read_acls() == expected
        source.write_text("KafkaPrincipal,ResourceType\nnot-a-policy\n")
        previous = failures()
        eventually("Malformed source is reported", lambda: failures() > previous)
        assert read_acls() == expected
        write_policy(expected)
        convergence()
        record("missing-and-malformed-source-preserve-permissions", status="PASS")

        started = time.monotonic()
        stop("ksm")
        assert time.monotonic() - started < 10, "KSM shutdown exceeded 10 seconds"
        # Restart with no in-memory source cache and the existing metadata.
        start_ksm()
        expected.add("User:app,Topic,LITERAL,after-ksm-restart,Read,Allow,*")
        write_policy(expected)
        convergence()
        record("ksm-sigterm-and-fresh-process-reconstruction", status="PASS")
        status = "PASS"
    finally:
        for key in ["ksm", 5, 4, 3, 2, 1]:
            stop(key)
        for proxy in proxies:
            proxy.close()
        for handle in handles:
            handle.close()
        report = {
            "status": status,
            "version": "4.3.1",
            "controllers": 3,
            "brokers": 2,
            "artifact_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "scenarios": events,
        }
        (directory / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        print("Report: " + str(directory / "results.json"), flush=True)


if __name__ == "__main__":
    main()
