"""Run real inventory packets against a new, isolated local Paper server.

Uses cached Paper/Java/Mineflayer; never edits or starts the template server.
Results and logs remain under validation/gui/runs/.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import re
import shutil
import socket
import subprocess
import threading
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--book', type=Path, default=REPO.with_name('TachyonScript-book'))
parser.add_argument('--java', type=Path, default=Path.home() / '.jdks/ms-21.0.12.1/bin')
args = parser.parse_args()
template = args.book / 'paper-server'
version = re.search(r'^version=(.+)$', (REPO / 'gradle.properties').read_text(), re.M).group(1)
jar = REPO / f'tachyon-plugin/build/libs/TachyonScript-{version}.jar'
run = HERE / 'runs' / datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
run.mkdir(parents=True)
server_args = (template / 'server.args').read_text(encoding='utf-8').splitlines()
classpath = server_args[server_args.index('-cp') + 1].strip('"')
plugins = run / 'plugins'
scripts = plugins / 'TachyonScript/scripts'
scripts.mkdir(parents=True)
shutil.copy2(jar, plugins / jar.name)
shutil.copy2(HERE / 'probe.tys', scripts / 'probe.tys')
shutil.copy2(template / 'eula.txt', run / 'eula.txt')
classes = run / 'probe-classes'
classes.mkdir()
subprocess.run([str(args.java / 'javac.exe'), '-encoding', 'UTF-8', '-cp', classpath + ';' + str(jar),
                '-d', str(classes), str(HERE / 'GuiProbe.java')], check=True)
(classes / 'plugin.yml').write_text("name: GuiProbe\nversion: 1\nmain: dev.tachyonscript.validation.GuiProbe\n"
    "api-version: '1.21'\ndepend: [TachyonScript]\ncommands:\n  guiprobe:\n    description: Local test probe\n", encoding='utf-8')
subprocess.run([str(args.java / 'jar.exe'), 'cf', str(plugins / 'GuiProbe.jar'), '-C', str(classes), '.'], check=True)
with socket.socket() as free:
    free.bind(('127.0.0.1', 0))
    port = free.getsockname()[1]
(run / 'server.properties').write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
    "view-distance=2\nsimulation-distance=2\nmax-players=3\nspawn-protection=0\n"
    "level-type=minecraft:flat\ngenerator-settings={\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
    "{\"block\":\"minecraft:stone\",\"height\":63},{\"block\":\"minecraft:grass_block\",\"height\":1}],"
    "\"biome\":\"minecraft:plains\"}\n", encoding='utf-8')
(plugins / 'TachyonScript/config.yml').write_text("storage:\n  type: memory\nperformance:\n  slow-execution-warnings: false\n"
    "security:\n  ai:\n    enabled: false\n  discord:\n    enabled: false\n", encoding='utf-8')
(run / 'config').mkdir()
(run / 'config/paper-global.yml').write_text("_version: 31\nspark:\n  enabled: false\n", encoding='utf-8')
ready = threading.Event()
lines = []
def drain(process):
    with (run / 'server.log').open('w', encoding='utf-8') as log:
        for line in process.stdout:
            log.write(line)
            log.flush()
            lines.append(line)
            if 'Done (' in line:
                ready.set()
            if 'ERROR' in line or 'Exception' in line:
                print(line.rstrip(), flush=True)

process = subprocess.Popen([str(args.java / 'java.exe'), '-Xms512M', '-Xmx1536M', '-cp', classpath,
    'org.bukkit.craftbukkit.Main', '--nogui'], cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', creationflags=subprocess.CREATE_NO_WINDOW)
thread = threading.Thread(target=drain, args=(process,), daemon=True)
thread.start()
print('Isolated Paper server:', run, 'port', port, flush=True)
try:
    deadline = time.monotonic() + 180
    while not ready.wait(1):
        if process.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError('Paper startup failed.\n' + ''.join(lines[-40:]))
    process.stdin.write('op GuiVerifier\n')
    process.stdin.flush()
    time.sleep(1)
    client = subprocess.run(['node', str(HERE / 'client.cjs'), str(args.book / 'validation/paper/node_modules'),
        str(port), str(run / 'result.json')], cwd=HERE, timeout=180, text=True, encoding='utf-8', capture_output=True)
    (run / 'client.log').write_text(client.stdout + client.stderr, encoding='utf-8')
    print(client.stdout, end='')
    print(client.stderr, end='')
    result = json.loads((run / 'result.json').read_text(encoding='utf-8')) if (run / 'result.json').exists() else {}
    result.update(jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), version=version,
                  paper='1.21.11', exit_code=client.returncode)
    (run / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    if client.returncode:
        raise SystemExit(client.returncode)
finally:
    if process.poll() is None:
        process.stdin.write('stop\n')
        process.stdin.flush()
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    thread.join(timeout=3)
    print('Evidence:', run / 'result.json', flush=True)
