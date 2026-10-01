#!/usr/bin/env python3
"""在临时目录运行真实 Paper + LP + MV + 外部 API 插件；不修改提供的运行时目录。"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import uuid

REPO = Path(__file__).resolve().parents[4]
OWNER = "00000000-0000-0000-0000-000000000001"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", type=Path, required=True, help="含 Paper JAR、libraries、versions、cache 和 LP/MV JAR 的目录")
    parser.add_argument("--java-home", type=Path, required=True, help="测试 Paper 要求的 JDK（当前 Paper 26.2 为 JDK 25）")
    parser.add_argument("--port", type=int, default=25576)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    work = Path(tempfile.mkdtemp(prefix="mmc-v4-todo-"))
    print(f"Integration directory: {work}", flush=True)
    for name in ("libraries", "versions", "cache"):
        shutil.copytree(runtime / name, work / name)
    paper = next(runtime.glob("paper-*.jar"))
    shutil.copy2(paper, work / "paper.jar")
    plugins = work / "plugins"
    plugins.mkdir()
    for pattern in ("LuckPerms*.jar", "multiverse-core*.jar"):
        shutil.copy2(next((runtime / "plugins").glob(pattern)), plugins)
    shutil.copy2(REPO / "plugin/target/mapmanager-plugin-4.0.0-SNAPSHOT.jar", plugins)
    data = plugins / "MapManager-Core"
    data.mkdir()
    config = (REPO / "plugin/src/main/resources/config.yml").read_text().replace(
        "blocked-world-name-regex: '.*_bak$'", "blocked-world-name-regex: '(?!)'")
    (data / "config.yml").write_text(config)
    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\n")
    classes = work / "probe-classes"
    classes.mkdir()
    dependencies = list((runtime / "libraries").rglob("*.jar")) + [
        REPO / "api/target/mapmanager-api-4.0.0-SNAPSHOT.jar",
        next((runtime / "plugins").glob("multiverse-core*.jar")),
        Path.home() / ".m2/repository/net/luckperms/api/5.4/api-5.4.jar",
        sorted((Path.home() / ".m2/repository/org/jetbrains/annotations").rglob("*.jar"))[-1],
    ]
    subprocess.run([str(args.java_home / "bin/javac"), "-proc:none", "-cp", os.pathsep.join(map(str, dependencies)),
                    "-d", str(classes), str(Path(__file__).with_name("ApiProbe.java"))], check=True)
    (classes / "paper-plugin.yml").write_text("""name: ApiProbe
version: '1'
main: mmc.probe.ApiProbe
api-version: '1.20'
dependencies:
  server:
    MapManager-Core:
      load: BEFORE
      required: true
      join-classpath: true
    LuckPerms:
      load: BEFORE
      required: true
      join-classpath: true
    Multiverse-Core:
      load: BEFORE
      required: true
      join-classpath: true
""")
    subprocess.run([str(args.java_home / "bin/jar"), "--create", "--file", str(plugins / "ApiProbe.jar"),
                    "-C", str(classes), "."], check=True)
    process = None
    log_stream = None
    phase = 0
    log = None

    def wait(condition, description, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if condition():
                print(f"PASS {description}", flush=True)
                return
            if log is not None and "PROBE FAIL" in log.read_text():
                raise AssertionError(f"External API probe failed during {description}; see {log}")
            if process is not None and process.poll() is not None:
                raise AssertionError(f"Server exited during {description}; see {log}")
            time.sleep(0.2)
        raise AssertionError(f"Timed out: {description}; see {log}")

    def start():
        nonlocal process, log, log_stream, phase
        phase += 1
        log = work / f"phase-{phase}.log"
        log_stream = log.open("w")
        process = subprocess.Popen([str(args.java_home / "bin/java"), "-Xms256M", "-Xmx1200M", "-jar", "paper.jar", "--nogui"],
                                   cwd=work, stdin=subprocess.PIPE, stdout=log_stream, stderr=subprocess.STDOUT, text=True)
        wait(lambda: "PROBE REGISTER_PASS" in log.read_text(), f"phase {phase} API service registered")

    def command(text, expected=None):
        offset = len(log.read_text())
        process.stdin.write(text + "\n")
        process.stdin.flush()
        if expected:
            wait(lambda: expected in log.read_text()[offset:], text)

    def stop():
        nonlocal process, log_stream
        if process is not None and process.poll() is None:
            command("stop")
            process.wait(timeout=60)
        process = None
        if log_stream:
            log_stream.close()
            log_stream = None

    def state():
        return json.loads((data / "maps.json").read_text())

    def drain():
        wait(lambda: json.loads((data / "permission-sync.json").read_text()) == [], "durable LP queue drained", 30)

    def plan(map_record, operation, identity=None):
        result = {"operationId": str(uuid.uuid4()), "operation": operation, "map": map_record.copy(), "members": [OWNER]}
        if operation == "CREATE":
            result["create"] = {"worldName": map_record["worldName"], "displayName": map_record["displayName"],
                                "groupId": map_record["groupId"], "ownerId": OWNER, "worldType": "FLAT",
                                "environment": "NETHER" if map_record["worldName"] == "probe-nether" else "NORMAL",
                                "parameters": {"flat.height": "64"}}
        else:
            folder = work / "world/dimensions/minecraft" / map_record["worldName"]
            result.update(environment="NORMAL", directoryIdentity=identity,
                          worldIdentity=base64.b64encode((folder / "uid.dat").read_bytes()).decode() if (folder / "uid.dat").exists() else None)
        return result

    try:
        start()
        command("mmcprobe suite", "PROBE SUITE_PASS")
        command("mmcprobe load", "PROBE LOAD_PASS")
        drain()
        command("mmcprobe projection", "PROBE PROJECTION_PASS")
        for name in ("resume_bak", "guard_bak", "gone_bak", "left_bak"):
            command(f"mapadmin create {name} flat normal flat.height=8 admin={OWNER}", "操作 ID")
            wait(lambda: any(m["worldName"] == name and m["status"] == "ACTIVE" for m in state()["maps"]), f"fixture {name}")
        command("mmcprobe identity")
        wait(lambda: "PROBE DIR left_bak" in log.read_text(), "directory identities captured")
        identities = dict(re.findall(r"PROBE DIR (\S+) (\(.*?\))", log.read_text()))
        command("mmcprobe remove-left", "PROBE REMOVE_LEFT_PASS")
        command("mapadmin storage export pre-restart", "存储快照已导出")
        drain()
        stop()
        snapshot = state()
        journal = data / "world-recovery"
        journal.mkdir(exist_ok=True)
        for record in snapshot["maps"]:
            name = record["worldName"]
            if name not in {"probe-nether", "resume_bak", "guard_bak", "gone_bak", "left_bak"}:
                continue
            operation = "CREATE" if name == "probe-nether" else "DELETE"
            prepared = plan(record, operation, identities.get(name))
            record["status"] = "PENDING" if operation == "CREATE" else "DELETING"
            (journal / f"{name}.json").write_text(json.dumps(prepared))
            snapshot["operations"].append({"id": prepared["operationId"], "name": operation, "worldName": name, "stage": "STARTED"})
        # 角色写入前断点，检查原 owner 能从持久请求恢复。
        snapshot["grants"] = [g for g in snapshot["grants"] if not (g["scopeId"] == "team" and g["role"] == "ADMIN")]
        fresh = {"id": str(uuid.uuid4()), "worldName": "resume-none", "groupId": "resumeg", "displayName": "Resume",
                 "status": "PENDING", "settings": {"physical": True, "explosion": False, "isPublic": False}}
        (journal / "resume-none.json").write_text(json.dumps(plan(fresh, "CREATE")))
        (data / "maps.json").write_text(json.dumps(snapshot))
        dimensions = work / "world/dimensions/minecraft"
        shutil.rmtree(dimensions / "gone_bak")  # 仅隔离测试服的故障注入。
        shutil.move(dimensions / "guard_bak", dimensions / "guard-original")
        shutil.copytree(dimensions / "guard-original", dimensions / "guard_bak")
        start()
        command("mmcprobe recovery", "PROBE RECOVERY_PASS")
        assert (dimensions / "guard_bak").exists()
        assert not (dimensions / "resume_bak").exists()
        assert not (dimensions / "gone_bak").exists()
        assert not (dimensions / "left_bak").exists()
        drain()
        stop()
        # v3 预检及正式导入；无需旧插件运行。
        (data / "worlds.json").write_text(json.dumps({"legacy": {"group": "legacyteam", "visitors": ["Guest"]}}))
        (data / "groups.json").write_text(json.dumps({"legacyteam": {"worlds": ["legacy"], "admins": ["AdminA"], "builders": ["BuildB"]}}))
        (data / "config.yml").write_text(config.replace("dry-run: false", "dry-run: true"))
        start()
        assert not any(m["worldName"] == "legacy" for m in state()["maps"])
        assert (data / "v3-migration-report.json").exists()
        assert not (data / "v3-migration.done").exists()
        command("mapadmin migration apply", "v3 迁移完成")
        assert any(m["worldName"] == "legacy" for m in state()["maps"])
        assert (data / "v3-target-backup.json").exists() and (data / "v3-migration.done").exists()
        drain()
        command("mmcprobe disable", "PROBE UNREGISTER_PASS")
        stop()
        logs = "\n".join(file.read_text() for file in work.glob("phase-*.log"))
        assert "PROBE FAIL" not in logs
        report = {"passed": True, "runtime": paper.name, "workdir": str(work),
                  "scenarios": ["service registration/revocation and shared classpath", "FLAT environments and layers", "MMC roles and synthetic plugin teleport/respawn events",
                                "real LP projection/revocation", "public load", "API deletion protections", "pending creation recovery with original owner",
                                "deletion recovery across three interruptions", "directory replacement refusal", "v3 dry-run/apply and target backup", "snapshot export"]}
        (work / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(f"ALL INTEGRATION CHECKS PASSED: {work / 'report.json'}", flush=True)
    finally:
        stop()


if __name__ == "__main__":
    main()
