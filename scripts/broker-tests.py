#!/usr/bin/env python3
"""Test the packaged KSM against real, disposable Apache Kafka processes (Python 3.12+).

No existing cluster is contacted. Broker metadata configuration lives only in this test fixture.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tarfile
import time

ROOT = Path(__file__).resolve().parents[1]
MATRIX = json.loads((ROOT / "scripts/broker-matrix.json").read_text())
USERS = ["root", "manager", "app", "outsider"]
PASSWORD = "local-probe"  # disposable localhost fixtures only
PROFILES = ["sasl-plain", "plaintext", "mtls", "sasl-tls", "scram-tls"]

def execute(command, env, log, timeout=120):
    with log.open("w") as output:
        subprocess.run([str(x) for x in command], cwd=ROOT, env=env, stdout=output,
                       stderr=subprocess.STDOUT, timeout=timeout, check=True)

def download(cell, cache):
    name = cell["url"].rsplit("/", 1)[1]
    archive = cache / name
    cache.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        print("Downloading " + name, flush=True)
        temporary = archive.with_suffix(".part")
        subprocess.run(["curl", "-fsSL", "--retry", "2", "--max-time", "300", "-o", str(temporary), cell["url"]], check=True)
        temporary.replace(archive)
    if hashlib.sha512(archive.read_bytes()).hexdigest() != cell["sha512"]:
        raise RuntimeError("Apache archive SHA-512 mismatch: " + name)
    destination = cache / name.removesuffix(".tgz")
    if not destination.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(cache, filter="data")
    return destination

def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

def wait_port(number, process, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Fixture exited before readiness; inspect broker/ZooKeeper log")
        try:
            with socket.create_connection(("127.0.0.1", number), timeout=0.3):
                return
        except OSError:
            time.sleep(0.2)
    raise TimeoutError("Fixture listener did not start")

def properties(path, values):
    # Paths in these test files are absolute. Escape backslashes for java.util.Properties.
    path.write_text("".join(str(k) + "=" + str(v).replace("\\", "\\\\") + "\n" for k, v in values.items()))

def certificates(directory, java):
    directory.mkdir(parents=True, exist_ok=True)
    if (directory / "complete").exists():
        return
    keytool = java / "bin/keytool"
    def key(*args):
        with (directory / "keytool.log").open("a") as log:
            subprocess.run([str(keytool), *map(str, args)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
    for user in ["broker"] + USERS:
        key("-genkeypair", "-alias", user, "-keystore", directory / (user + ".p12"), "-storetype", "PKCS12",
            "-storepass", PASSWORD, "-keypass", PASSWORD, "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=" + user, "-validity", "3", "-ext", "SAN=ip:127.0.0.1")
        key("-exportcert", "-alias", user, "-keystore", directory / (user + ".p12"), "-storepass", PASSWORD,
            "-file", directory / (user + ".crt"))
        key("-importcert", "-noprompt", "-alias", user, "-file", directory / (user + ".crt"),
            "-keystore", directory / "trust.p12", "-storetype", "PKCS12", "-storepass", PASSWORD)
    key("-importcert", "-noprompt", "-alias", "untrusted", "-file", directory / "outsider.crt",
        "-keystore", directory / "bad-trust.p12", "-storetype", "PKCS12", "-storepass", PASSWORD)
    (directory / "complete").touch()

def jaas(user, scram=False, server=False):
    module = "scram.ScramLoginModule" if scram else "plain.PlainLoginModule"
    values = f'username="{user}" password="{PASSWORD}"'
    if server and not scram:
        values += "".join(f' user_{name}="{PASSWORD}"' for name in USERS)
    return f"org.apache.kafka.common.security.{module} required {values};"

def run_case(cell, profile, args, output):
    name = cell["version"] + "-" + cell["fixture"] + "-" + profile
    directory = output / name
    directory.mkdir()
    state = directory / "state"
    java = args.java17 if cell["version"].startswith(("2.", "3.")) else args.java25
    env = dict(os.environ, JAVA_HOME=str(java), KAFKA_HEAP_OPTS="-Xmx384m -Xms128m",
               KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseG1GC", LOG_DIR=str(directory / "logs"))
    env.pop("KAFKA_OPTS", None)
    processes, handles = [], []
    result = dict(version=cell["version"], fixture=cell["fixture"], profile=profile,
                  broker_java=str(java), ksm_java=str(args.java25), evidence=str(directory.relative_to(ROOT)))
    def start(script, config, label):
        log = (directory / (label + ".log")).open("w")
        handles.append(log)
        proc = subprocess.Popen([str(distro / "bin" / script), str(config)], env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(proc)
        return proc
    try:
        distro = download(cell, args.cache)
        client_port, internal_port, control_port, zk_port = [port() for _ in range(4)]
        protocol = {"sasl-plain": "SASL_PLAINTEXT", "plaintext": "PLAINTEXT", "mtls": "SSL",
                    "sasl-tls": "SASL_SSL", "scram-tls": "SASL_SSL"}[profile]
        scram = profile == "scram-tls"
        config = {
            "listeners": f"CLIENT://127.0.0.1:{client_port},INTERNAL://127.0.0.1:{internal_port}",
            "advertised.listeners": f"CLIENT://127.0.0.1:{client_port},INTERNAL://127.0.0.1:{internal_port}",
            "listener.security.protocol.map": f"CLIENT:{protocol},INTERNAL:SASL_PLAINTEXT",
            "inter.broker.listener.name": "INTERNAL",
            "sasl.enabled.mechanisms": "PLAIN,SCRAM-SHA-512" if scram else "PLAIN",
            "sasl.mechanism.inter.broker.protocol": "PLAIN",
            "listener.name.internal.plain.sasl.jaas.config": jaas("root", server=True),
            "listener.name.internal.sasl.enabled.mechanisms": "PLAIN",
            "super.users": "User:root",
            "allow.everyone.if.no.acl.found": "false",
            "log.dirs": state / "data",
            "offsets.topic.replication.factor": "1",
            "transaction.state.log.replication.factor": "1",
            "transaction.state.log.min.isr": "1",
            "group.initial.rebalance.delay.ms": "0",
            "auto.create.topics.enable": "false",
            "num.partitions": "1",
        }
        if protocol.startswith("SASL"):
            mechanism = "scram-sha-512" if scram else "plain"
            config[f"listener.name.client.{mechanism}.sasl.jaas.config"] = jaas("root", scram, server=True)
            config["listener.name.client.sasl.enabled.mechanisms"] = "SCRAM-SHA-512" if scram else "PLAIN"
        if "SSL" in protocol:
            tls = output / "tls"
            certificates(tls, args.java25)
            config.update({"ssl.keystore.location": tls / "broker.p12", "ssl.keystore.password": PASSWORD,
                           "ssl.key.password": PASSWORD, "ssl.keystore.type": "PKCS12",
                           "ssl.truststore.location": tls / "trust.p12", "ssl.truststore.password": PASSWORD,
                           "ssl.truststore.type": "PKCS12", "ssl.client.auth": "required" if profile == "mtls" else "none",
                           "ssl.principal.mapping.rules": "RULE:^CN=(.*)$/$1/,DEFAULT"})
            shutil.copy2(tls / "bad-trust.p12", directory / "bad-trust.p12")
        for user in USERS:
            internal = user == "root" or (profile == "plaintext" and user != "manager")
            p = {"bootstrap.servers": f"127.0.0.1:{internal_port if internal else client_port}",
                 "security.protocol": "SASL_PLAINTEXT" if internal else protocol,
                 "default.api.timeout.ms": "8000", "request.timeout.ms": "5000"}
            if p["security.protocol"].startswith("SASL"):
                p.update({"sasl.mechanism": "SCRAM-SHA-512" if scram and not internal else "PLAIN",
                          "sasl.jaas.config": jaas(user, scram and not internal)})
            if "SSL" in p["security.protocol"]:
                p.update({"ssl.truststore.location": str(tls / "trust.p12"), "ssl.truststore.password": PASSWORD,
                          "ssl.truststore.type": "PKCS12"})
                if profile == "mtls":
                    p.update({"ssl.keystore.location": str(tls / (user + ".p12")), "ssl.keystore.password": PASSWORD,
                              "ssl.key.password": PASSWORD, "ssl.keystore.type": "PKCS12"})
            properties(directory / (user + ".properties"), p)
        if cell["fixture"] == "zk":
            zk_config = directory / "zookeeper.properties"
            properties(zk_config, {"dataDir": state / "zookeeper", "clientPort": zk_port,
                                   "clientPortAddress": "127.0.0.1", "admin.enableServer": "false"})
            zk = start("zookeeper-server-start.sh", zk_config, "zookeeper")
            wait_port(zk_port, zk)
            config.update({"broker.id": "1", "zookeeper.connect": f"127.0.0.1:{zk_port}",
                           "authorizer.class.name": "kafka.security.authorizer.AclAuthorizer"})
            if scram:
                for user in USERS:
                    execute([distro / "bin/kafka-configs.sh", "--zookeeper", f"127.0.0.1:{zk_port}", "--alter",
                             "--add-config", f"SCRAM-SHA-512=[password={PASSWORD}]", "--entity-type", "users", "--entity-name", user],
                            env, directory / ("scram-" + user + ".log"))
        else:
            config.update({"node.id": "1", "process.roles": "broker,controller", "controller.listener.names": "CONTROLLER",
                           "controller.quorum.voters": f"1@127.0.0.1:{control_port}",
                           "sasl.mechanism.controller.protocol": "PLAIN",
                           "listener.name.controller.plain.sasl.jaas.config": jaas("root", server=True),
                           "listener.name.controller.sasl.enabled.mechanisms": "PLAIN",
                           "authorizer.class.name": "org.apache.kafka.metadata.authorizer.StandardAuthorizer"})
            config["listeners"] += f",CONTROLLER://127.0.0.1:{control_port}"
            config["listener.security.protocol.map"] += ",CONTROLLER:SASL_PLAINTEXT"
        broker_config = directory / "server.properties"
        properties(broker_config, config)
        if cell["fixture"] == "kraft":
            command = [distro / "bin/kafka-storage.sh", "format", "-t", "MkU3OEVBNTcwNTJENDM2Qk", "-c", broker_config]
            if scram:
                for user in USERS:
                    command += ["--add-scram", f"SCRAM-SHA-512=[name={user},password={PASSWORD}]"]
            execute(command, env, directory / "format.log")
        broker = start("kafka-server-start.sh", broker_config, "broker")
        wait_port(client_port, broker)
        maven_env = dict(os.environ, JAVA_HOME=str(args.java25))
        execute([ROOT / "mvnw", "-B", "-ntp", "-q", "-Pbroker-it", "-Dit.test=BrokerIT", "-Dksm.it.directory=" + str(directory), "-Dksm.it.jar=" + str(output / "ksm.jar"), "-Dksm.it.kafka.version=" + cell["version"],
                 "failsafe:integration-test", "failsafe:verify"], maven_env, directory / "test.log", timeout=300)
        shutil.copytree(ROOT / "target/failsafe-reports", directory / "junit")
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", error=str(error))
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
        for handle in handles:
            handle.close()
        (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result), flush=True)
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java25", type=Path, default=Path(os.environ.get("JAVA_HOME", Path.home() / ".sdkman/candidates/java/25.0.3-tem")))
    parser.add_argument("--java17", type=Path, default=Path(os.environ.get("JAVA17_HOME", Path.home() / ".sdkman/candidates/java/17.0.18-tem")))
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("KSM_KAFKA_CACHE", Path.home() / ".cache/ksm-kafka")))
    parser.add_argument("--cases", nargs="+", help="e.g. 4.3.1-kraft; default all seven matrix cells")
    parser.add_argument("--profiles", nargs="+", choices=PROFILES, default=["sasl-plain"])
    args = parser.parse_args()
    args.java25, args.java17, args.cache = args.java25.resolve(), args.java17.resolve(), args.cache.resolve()
    names = {c["version"] + "-" + c["fixture"] for c in MATRIX}
    if args.cases and set(args.cases) - names:
        parser.error("Unknown matrix cell")
    cells = [c for c in MATRIX if not args.cases or c["version"] + "-" + c["fixture"] in args.cases]
    output = ROOT / "target/broker-it" / time.strftime("%Y%m%d-%H%M%S")
    output.mkdir(parents=True)
    jar = ROOT / "target/kafka-security-manager-2.0.0-SNAPSHOT.jar"
    if not jar.is_file() or not (ROOT / "target/test-classes/io/conduktor/ksm/BrokerIT.class").is_file():
        parser.error("Run ./mvnw verify first to build the artifact and integration tests")
    shutil.copy2(jar, output / "ksm.jar")
    results = []
    for cell in cells:
        for profile in args.profiles:
            results.append(run_case(cell, profile, args, output))
    report = {"artifact_sha256": hashlib.sha256((output / "ksm.jar").read_bytes()).hexdigest(), "results": results}
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Report: " + str(output / "results.json"), flush=True)
    raise SystemExit(0 if all(r["status"] == "PASS" for r in results) else 1)

if __name__ == "__main__":
    main()
