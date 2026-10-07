"""Run the pinned Paper/Folia probe in a new, disposable localhost-only server.

Build first: gradlew build :tachyon-integration:jar
Use an existing accepted eula.txt with --eula-file, or explicitly --accept-eula.
Only test fixtures are installed; no live server or user config is read or changed.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
import urllib.request

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
USER_AGENT = "TachyonScript-validation/0.7.0 (https://github.com/auroravnliems/TachyonScript)"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download_server(project: str, manifest: dict, cache: Path) -> Path:
    artifact = manifest[project]
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"{project}-{manifest['minecraft']}-{artifact['build']}.jar"
    if not target.exists() or digest(target) != artifact["sha256"]:
        print(f"Downloading pinned {project} {manifest['minecraft']} build {artifact['build']}", flush=True)
        request = urllib.request.Request(artifact["url"], headers={"User-Agent": USER_AGENT})
        partial = target.with_suffix(".jar.part")
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        if digest(partial) != artifact["sha256"]:
            raise RuntimeError(f"SHA-256 mismatch for {partial}")
        partial.replace(target)
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", choices=("paper", "folia"), required=True)
    parser.add_argument("--java", type=Path)
    parser.add_argument("--eula-file", type=Path)
    parser.add_argument("--accept-eula", action="store_true", help="Accept https://aka.ms/MinecraftEULA for this test server")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--backend", choices=("interpreter", "bytecode"), default="interpreter",
                        help="runtime.backend of the test server")
    parser.add_argument("--seed-security", type=Path,
                        help="copy this security folder (for example one damaged by an older version) into the server first")
    args = parser.parse_args()
    accepted = args.accept_eula
    if args.eula_file:
        accepted |= bool(re.search(r"^\s*eula\s*=\s*true\s*$", args.eula_file.read_text(encoding="utf-8"), re.M | re.I))
    if not accepted:
        parser.error("Supply --eula-file pointing to an already accepted EULA, or --accept-eula")
    java = args.java
    if java is None:
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") \
            if "JAVA_HOME" in os.environ else Path(shutil.which("java") or "java")
    manifest = json.loads((HERE / "servers.json").read_text(encoding="utf-8"))
    version = re.search(r"^version=(.+)$", (REPO / "gradle.properties").read_text(), re.M).group(1)
    plugin = REPO / f"tachyon-plugin/build/libs/TachyonScript-{version}.jar"
    probe = REPO / f"tachyon-integration/build/libs/TachyonIntegrationProbe-{version}.jar"
    if not plugin.is_file() or not probe.is_file():
        parser.error("Build :tachyon-plugin:shadowJar :tachyon-integration:jar first")
    server = download_server(args.project, manifest, HERE / "cache")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    run = HERE / "runs" / (f"{stamp}-{args.project}" if args.backend == "interpreter" else f"{stamp}-{args.project}-{args.backend}")
    scripts = run / "plugins/TachyonScript/scripts"
    scripts.mkdir(parents=True)
    shutil.copy2(plugin, run / "plugins" / plugin.name)
    shutil.copy2(probe, run / "plugins" / probe.name)
    shutil.copy2(HERE / "integration.tys", scripts / "integration.tys")
    if args.seed_security:
        shutil.copytree(args.seed_security, run / "plugins/TachyonScript/security")
    (run / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]
    flat = {"layers": [{"block": "minecraft:bedrock", "height": 1},
                       {"block": "minecraft:stone", "height": 63},
                       {"block": "minecraft:grass_block", "height": 1}], "biome": "minecraft:plains"}
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
        "view-distance=2\nsimulation-distance=2\nmax-players=2\nspawn-protection=0\n"
        "level-type=minecraft:flat\nallow-nether=false\npause-when-empty-seconds=-1\n"
        "difficulty=peaceful\n" + "generator-settings=" + json.dumps(flat, separators=(",", ":")) + "\n", encoding="utf-8")
    (run / "bukkit.yml").write_text("settings:\n  allow-end: false\n", encoding="utf-8")
    (run / "config").mkdir()
    (run / "config/paper-global.yml").write_text(
        "_version: 31\nspark:\n  enabled: false\nthreaded-regions:\n  threads: 2\n", encoding="utf-8")
    (run / "plugins/TachyonScript/config.yml").write_text(
        "storage:\n  type: sqlite\n  sqlite-file: integration.db\nsafety:\n  max-execution-time-ms: 50\n"
        "performance:\n  slow-execution-warnings: false\n"
        f"runtime:\n  backend: {args.backend}\n"
        "security:\n  ai:\n    enabled: false\n  discord:\n    enabled: false\n", encoding="utf-8")
    command = [str(java), "-Xms512M", "-Xmx1536M",
               "-Dtachyon.integration.folia=" + str(args.project == "folia").lower(),
               "-jar", str(server), "--nogui"]
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    started = time.monotonic()
    print(f"Starting isolated {args.project}: {run} (localhost:{port})", flush=True)
    process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", creationflags=flags)
    finished = threading.Event()
    tail: list[str] = []

    def drain() -> None:
        with (run / "server.log").open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                tail.append(line.rstrip())
                if len(tail) > 80:
                    del tail[0]
                if "TachyonIntegrationProbe" in line or "TachyonScript failed" in line:
                    print(line.rstrip(), flush=True)
                if "TACHYON_INTEGRATION_FINISHED" in line:
                    finished.set()

    thread = threading.Thread(target=drain, daemon=True)
    thread.start()
    timed_out = False
    forced_stop = False
    try:
        while not finished.wait(1):
            if process.poll() is not None:
                break
            if time.monotonic() - started > args.timeout:
                timed_out = True
                break
    finally:
        if process.poll() is None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=60)
            except (subprocess.TimeoutExpired, BrokenPipeError):
                forced_stop = True
                process.kill()
                process.wait(timeout=10)
        thread.join(timeout=5)
        process.stdin.close()
        process.stdout.close()
    probe_result = run / "probe-result.json"
    result = json.loads(probe_result.read_text(encoding="utf-8")) if probe_result.exists() else {"success": False, "checks": {}}
    server_log = (run / "server.log").read_text(encoding="utf-8")
    # A seeded damaged folder must be repaired: its SEVERE recovery notice is expected, not an error.
    recovery_lines = [line for line in server_log.splitlines()
                      if "Security journal damaged:" in line or "Set aside " in line and "security report" in line]
    server_errors = [line for line in server_log.splitlines()
                     if line not in recovery_lines and re.search(r"\b(?:ERROR|SEVERE)\]", line)
                     or "Cannot unregister script commands" in line or "Cannot release a resource" in line]
    disable = server_log.find("[TachyonScript] Disabling TachyonScript")
    unload_on_stop = disable >= 0 and "INTEGRATION_UNLOAD saved=5" in server_log[disable:]
    result["checks"]["unload-hook-on-server-stop"] = unload_on_stop
    result["checks"]["server-log-without-errors"] = not server_errors
    # The startup line names the backend that really runs the scripts.
    result["checks"][f"runs-{args.backend}-backend"] = f", {args.backend} backend)" in server_log
    if args.seed_security:
        result["checks"]["damaged-security-folder-recovered"] = bool(recovery_lines) and "TachyonScript failed to start" not in server_log
        result["recovery"] = recovery_lines
    database = run / "plugins/TachyonScript/integration.db"
    try:
        with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as connection:
            saved = dict(connection.execute("SELECT name, value FROM tys_data WHERE owner = '' AND scope = 'integration'"))
        result["checks"]["sqlite-flush-on-shutdown"] = saved.get("saved") == "5" and saved.get("unloads") == "2"
        result["persisted"] = saved
    except sqlite3.Error as error:
        result["checks"]["sqlite-flush-on-shutdown"] = False
        result["storage_error"] = str(error)
    clean_stop = process.returncode == 0 and not forced_stop and finished.is_set() and unload_on_stop and not server_errors
    result.update(project=args.project, minecraft=manifest["minecraft"], build=manifest[args.project]["build"],
                  version=version, backend=args.backend, jar_sha256=digest(run / "plugins" / plugin.name), probe_sha256=digest(run / "plugins" / probe.name),
                  server_sha256=digest(server), fixture_sha256=digest(scripts / "integration.tys"),
                  duration_seconds=round(time.monotonic() - started, 3), timeout=timed_out,
                  clean_stop=clean_stop, server_errors=server_errors, exit_code=process.returncode, operating_system=platform.platform(),
                  java=str(java), evidence=str(run))
    result["success"] = bool(result["success"] and clean_stop and not timed_out and all(result["checks"].values()))
    (run / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Evidence:", run / "result.json", flush=True)
    print(f"Result: success={result['success']}, checks={len(result['checks'])}, clean_stop={clean_stop}", flush=True)
    if not result["success"]:
        print("\n".join(tail[-50:]))
    return 0 if result["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
