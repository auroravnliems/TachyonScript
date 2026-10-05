"""Exercise the packaged plugin on an isolated, loopback-only Paper server.

Requires a prepared Paper installation's server.args/libraries and Java 21.
Uses local protocol mocks, dummy credentials and a fresh build/security-paper folder.
Never modifies the supplied Paper installation; always stops its own server.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


ROOT = Path(__file__).resolve().parents[2]
SAFE = ('on load { log("SECURITY_SMOKE_ACTIVE") }\n'
        'on unload { log("SECURITY_SMOKE_UNLOAD") }\n'
        'command securityprobe { log("SECURITY_SMOKE_COMMAND") }\n'
        'every 1 second { log("SECURITY_SMOKE_TICK") }\n'
        'function exported(): int { return 7 }\n')
BAD = ('\n// Compiler positions must include the blank line and comment.\n'
       'event player.chat {\n'
       '    let value = "say " + message\n'
       '    server.dispatch(value)\n'
       '}\nfunction exported(): int { return 7 }\n')
REPAIRED = SAFE.replace('SECURITY_SMOKE_ACTIVE', 'SECURITY_SMOKE_REPAIRED')


def wait_for(condition, description, timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = condition()
        if result:
            return result
        time.sleep(0.1)
    raise AssertionError('Timed out: ' + description)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def discord_message_text(payload):
    parts = [payload.get('content', '')]
    for embed in payload.get('embeds', []):
        parts.extend((embed.get('title', ''), embed.get('description', ''), embed.get('footer', {}).get('text', '')))
        for field in embed.get('fields', []):
            parts.extend((field.get('name', ''), field.get('value', '')))
    return '\n'.join(parts)


def validate_discord_payload(payload):
    assert payload['allowed_mentions']['parse'] == []
    assert len(payload.get('content', '')) <= 2000
    embeds = payload.get('embeds', [])
    assert 1 <= len(embeds) <= 10
    total = 0
    for embed in embeds:
        title, description, footer = embed.get('title', ''), embed.get('description', ''), embed.get('footer', {}).get('text', '')
        assert len(title) <= 256 and len(description) <= 4096 and len(footer) <= 2048
        assert len(embed.get('fields', [])) <= 25
        total += len(title) + len(description) + len(footer)
        for field in embed.get('fields', []):
            assert 0 < len(field['name']) <= 256 and 0 < len(field['value']) <= 1024
            total += len(field['name']) + len(field['value'])
    assert total <= 6000


class ProtocolMock(BaseHTTPRequestHandler):
    manifests = []
    webhook_messages = []
    lock = threading.Lock()
    retry_seen = False

    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path != '/echo':
            self.send_error(404)
            return
        body = b'LOCAL_TRANSPORT_OK'
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if size > 4_194_304:
            self.send_error(413)
            return
        request = json.loads(self.rfile.read(size))
        if self.path == '/qwen':
            assert self.headers['Authorization'] == 'Bearer local-smoke-dummy'
            manifest = json.loads(request['messages'][1]['content'])
            with self.lock:
                self.manifests.append(manifest)
            findings = []
            decision, severity, summary = 'ALLOW', 'INFO', 'Local protocol review'
            if manifest['file'] == 'ai_guard.tys':
                sink = next(n for n in manifest['nodes'] if n['capability'] == 'CONSOLE_COMMAND')
                assert sink['concreteDanger'] and sink['span']['startLine'] == 3
                findings = [{'id': 'AI-GROUNDED-001', 'nodeId': sink['nodeId'],
                             'category': 'COMMAND_INJECTION', 'severity': 'CRITICAL', 'confidence': .99,
                             'explanation': 'Command argument value reaches privileged server.dispatch.',
                             'evidence': 'The supplied compiler native node executes the tainted command argument.',
                             'line': 900, 'codeSnippet': 'untrusted model snippet'}]
                decision, severity = 'QUARANTINE', 'CRITICAL'
            elif manifest['file'] == 'vague.tys':
                decision, severity, summary = 'QUARANTINE', 'CRITICAL', 'This script is dangerous (no evidence)'
            answer = dict(scriptId=manifest['scriptId'], sha256=manifest['sha256'], decision=decision,
                          severity=severity, confidence=.99, summary=summary, findings=findings)
            response = {'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(answer)}}]}
            status = 200
        elif self.path.startswith('/discord?'):
            assert 'wait=true' in self.path
            validate_discord_payload(request)
            with self.lock:
                if not self.retry_seen:
                    type(self).retry_seen = True
                    response, status = {'retry_after': .05}, 429
                else:
                    self.webhook_messages.append(discord_message_text(request))
                    response, status = {'id': str(len(self.webhook_messages))}, 200
        else:
            self.send_error(404)
            return
        body = json.dumps(response).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        if status == 429:
            self.send_header('Retry-After', '0.05')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--paper-home', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'tachyon-plugin/build/libs/TachyonScript-0.5.1-SNAPSHOT.jar')
    parser.add_argument('--mineflayer', type=Path, help='Optional installed mineflayer module for non-OP administrator delivery tests')
    args = parser.parse_args()
    paper = args.paper_home.resolve()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    folder = ROOT / 'build/security-paper' / stamp
    folder.mkdir(parents=True)
    plugin_folder = folder / 'plugins/TachyonScript'
    plugin_folder.mkdir(parents=True)
    scripts = plugin_folder / 'scripts'
    shutil.copytree(paper / 'plugins/TachyonScript/scripts', scripts)
    for addon in (paper / 'plugins').glob('*.jar'):
        if not addon.name.lower().startswith('tachyonscript'):
            shutil.copy2(addon, folder / 'plugins' / addon.name)
    shutil.copy2(args.plugin, folder / 'plugins/TachyonScript.jar')
    (folder / 'eula.txt').write_bytes((paper / 'eula.txt').read_bytes())
    # Keep cached classpath entries read-only. Worlds, configs, logs and plugins live only in folder.
    (folder / 'server.args').write_text((paper / 'server.args').read_text(encoding='utf-8'), encoding='utf-8')
    if args.mineflayer:
        classes = folder / 'permission-probe'
        classes.mkdir()
        classpath = re.search(r'-(?:cp|classpath)\s+"([^"]+)"', (folder / 'server.args').read_text()).group(1)
        subprocess.run([str(args.java.with_name('javac.exe' if os.name == 'nt' else 'javac')), '-cp', classpath, '-d', str(classes),
                        str(Path(__file__).with_name('SecurityPermissionProbe.java'))], check=True, capture_output=True)
        (classes / 'plugin.yml').write_text('name: SecurityPermissionProbe\nversion: 1\napi-version: "1.21"\n'
                                           'main: dev.tachyonscript.validation.SecurityPermissionProbe\ndepend: [TachyonScript]\n')
        subprocess.run([str(args.java.with_name('jar.exe' if os.name == 'nt' else 'jar')), 'cf', str(folder / 'plugins/SecurityPermissionProbe.jar'),
                        '-C', str(classes), '.'], check=True, capture_output=True)
    port = free_port()
    (folder / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
        'level-type=minecraft:flat\nview-distance=2\nsimulation-distance=2\n'
        'spawn-protection=0\nmax-players=4\nenable-query=false\nenable-rcon=false\n'
        'pause-when-empty-seconds=-1\n', encoding='utf-8')
    (scripts / 'quarantine.tys').write_text(SAFE, encoding='utf-8')
    (scripts / 'market.tys').write_text('import quarantine\nfunction value(): int { return quarantine.exported() }\n', encoding='utf-8')
    (scripts / 'ai_guard.tys').write_text('@permission("admin.run")\ncommand aireview(value: string) {\n    server.dispatch(value)\n}\n', encoding='utf-8')
    (scripts / 'vague.tys').write_text('on load { log("SECURITY_SMOKE_VAGUE_ALLOWED") }\n', encoding='utf-8')
    mock = ThreadingHTTPServer(('127.0.0.1', 0), ProtocolMock)
    mock_thread = threading.Thread(target=mock.serve_forever, daemon=True)
    mock_thread.start()
    mock_port = mock.server_address[1]
    (scripts / 'web_smoke.tys').write_text(f'on load {{ web.get("http://127.0.0.1:{mock_port}/echo", (status, body) => '
                                          '{ log("SECURITY_SMOKE_HTTP {status} {body}") }) }\n', encoding='utf-8')
    (plugin_folder / 'config.yml').write_text(f'''reload:
  mode: lenient
storage:
  type: memory
  flush-interval-seconds: 0
security:
  enabled: true
  allowed-network-hosts: ["127.0.0.1"] # Explicit exception for this local transport fixture.
  ai:
    enabled: true
    required: true
    endpoint: "http://127.0.0.1:{mock_port}/qwen"
    model: "local-protocol-test"
    api-key: "local-smoke-dummy"
    connect-timeout-ms: 1000
    read-timeout-ms: 3000
    max-retries: 1
  discord:
    enabled: true
    webhook: "http://127.0.0.1:{mock_port}/discord"
    timeout-ms: 2000
    max-retries: 2
''', encoding='utf-8')
    lines = []
    summary = dict(server=str(folder), minecraftPort=port, checks={}, success=False)
    process = None
    clients = None
    client_output = folder / 'admin-messages.json'

    def client_results():
        try:
            return json.loads(client_output.read_text(encoding='utf-8'))
        except (OSError, json.JSONDecodeError):
            return {}

    def output():
        return ''.join(lines)

    def incidents():
        result = []
        for file in (plugin_folder / 'security').glob('*.report.json'):
            try:
                result.append(json.loads(file.read_text(encoding='utf-8')))
            except (json.JSONDecodeError, OSError):
                pass  # A report may be in the middle of its atomic write-ahead operation.
        return result

    def command(text):
        assert process.poll() is None, 'Paper stopped unexpectedly'
        process.stdin.write(text + '\n')
        process.stdin.flush()

    def check(name, condition):
        assert condition, name
        summary['checks'][name] = True
        print('PASS ' + name, flush=True)

    def read_console():
        with (folder / 'console.log').open('w', encoding='utf-8') as log:
            for line in process.stdout:
                lines.append(line)
                log.write(line)
                log.flush()

    try:
        process = subprocess.Popen([str(args.java), '@server.args', '--nogui'], cwd=folder,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace', bufsize=1,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        reader = threading.Thread(target=read_console, daemon=True)
        reader.start()
        wait_for(lambda: 'SECURITY_SMOKE_ACTIVE' in output(), 'initial security-approved activation', 90)
        check('packaged_plugin_started', '[TachyonScript]' in output() and process.poll() is None)
        wait_for(lambda: 'SECURITY_SMOKE_VAGUE_ALLOWED' in output(), 'vague AI finding remains advisory')
        check('vague_ai_cannot_disable', not any(i['file'] == 'vague.tys' and i['decision'] in ('DISABLE', 'QUARANTINE') for i in incidents()))
        ai_incident = wait_for(lambda: next((i for i in incidents() if i['file'] == 'ai_guard.tys' and i['decision'] == 'QUARANTINE'), None), 'grounded AI quarantine')
        ai_finding = next(f for f in ai_incident['findings'] if f['origin'] == 'AI')
        check('ai_location_comes_from_compiler', ai_finding['location']['startLine'] == 3 and ai_finding['location']['startColumn'] == 5)
        check('ai_snippet_is_generated_locally', 'server.dispatch(value)' in ai_finding['codeSnippet'] and 'untrusted model snippet' not in ai_finding['codeSnippet'])
        command('securityprobe')
        wait_for(lambda: 'SECURITY_SMOKE_COMMAND' in output(), 'approved script command runs')
        check('normal_scripts_active', True)
        wait_for(lambda: 'SECURITY_SMOKE_HTTP 200 LOCAL_TRANSPORT_OK' in output(), 'relocated HTTP transport and owned callback')
        check('shaded_script_http_transport_works', True)
        if args.mineflayer:
            clients = subprocess.Popen(['node', str(Path(__file__).with_name('admin_notification_probe.js')), str(args.mineflayer.resolve()),
                                        str(port), str(client_output)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            wait_for(lambda: len(client_results().get('ready', [])) == 2, 'two non-OP clients joined', 30)
            check('admin_permission_is_explicit', 'SECURITY_SMOKE_ROLE TSAlert op=false alerts=true' in output()
                  and 'SECURITY_SMOKE_ROLE TSObserver op=false alerts=false' in output())
        before = len(output())
        (scripts / 'quarantine.tys').write_bytes(BAD.encode())
        command('tys reload quarantine.tys')
        quarantine = wait_for(lambda: next((i for i in incidents() if i['file'] == 'quarantine.tys' and i['decision'] == 'QUARANTINE'), None), 'deterministic quarantine on reload')
        dependency = wait_for(lambda: next((i for i in incidents() if i['file'] == 'market.tys' and i['decision'] == 'DISABLE'), None), 'dependent retirement')
        finding = next(f for f in quarantine['findings'] if f['category'] == 'COMMAND_INJECTION')
        check('static_exact_source_location', finding['location']['startLine'] == 5 and finding['location']['startColumn'] == 5)
        check('static_source_sink_and_complete_flow', finding['source'] == 'message' and finding['sink'] == 'server.dispatch'
              and any(step['kind'] == 'CONCAT' for step in finding['flow']) and len(finding['flow']) >= 3)
        check('dependency_import_exact_location', dependency['findings'][0]['location']['startLine'] == 1)
        check('original_source_not_deleted', (scripts / 'quarantine.tys').read_bytes() == BAD.encode())
        snapshot = plugin_folder / 'security' / (quarantine['incidentId'] + '.source.tys')
        check('immutable_quarantine_snapshot_hash', hashlib.sha256(snapshot.read_bytes()).hexdigest() == quarantine['sha256'])
        time.sleep(2)
        check('quarantine_skips_unload_hooks', 'SECURITY_SMOKE_UNLOAD' not in output()[before:])
        check('quarantined_tasks_stop', 'SECURITY_SMOKE_TICK' not in output()[before:].split('AUTO_QUARANTINE')[-1])
        command('tys security inspect ' + quarantine['incidentId'])
        wait_for(lambda: output().count('quarantine.tys:5:5') >= 2, 'console inspect source locations')
        check('console_and_inspect_detailed_location', 'Data flow' in output()[before:] and 'quarantine.tys:5:5' in output()[before:])
        wait_for(lambda: any(quarantine['incidentId'] in m and 'quarantine.tys:5:5' in m for m in ProtocolMock.webhook_messages), 'confirmed Discord source-location alert')
        check('discord_detailed_alert_with_retry', ProtocolMock.retry_seen)
        if args.mineflayer:
            wait_for(lambda: quarantine['incidentId'] in '\n'.join(client_results().get('messages', {}).get('TSAlert', [])), 'administrator incident delivery')
            messages = client_results()['messages']
            admin = '\n'.join(messages['TSAlert'])
            check('non_op_admin_receives_precise_alert', 'quarantine.tys:5:5' in admin and 'server.dispatch(value)' in admin)
            check('ordinary_player_receives_no_security_alert', not any('TachyonSecurity' in m or quarantine['incidentId'] in m for m in messages['TSObserver']))
        (scripts / 'quarantine.tys').write_bytes(REPAIRED.encode())
        command('tys security approve quarantine.tys')
        wait_for(lambda: any(i['file'] == 'quarantine.tys' and i['action'] == 'APPROVE' and i['sha256'] == hashlib.sha256(REPAIRED.encode()).hexdigest() for i in incidents()), 'edited source explicit hash approval')
        command('tys security restore ' + quarantine['incidentId'])
        wait_for(lambda: 'SECURITY_SMOKE_REPAIRED' in output(), 'restored source passes compiler gate')
        check('edited_source_restore_works', snapshot.read_bytes() == BAD.encode())
        command('tys security approve market.tys')
        wait_for(lambda: any(i['file'] == 'market.tys' and i['action'] == 'APPROVE' for i in incidents()), 'dependent approval')
        command('tys security restore ' + dependency['incidentId'])
        wait_for(lambda: any(i['file'] == 'market.tys' and i['action'] == 'RESTORE' for i in incidents()), 'dependent restore')
        command('tys security test-webhook')
        webhook_test = wait_for(lambda: next((i for i in incidents() if i['action'] == 'WEBHOOK_TEST'), None), 'webhook command audit')
        wait_for(lambda: any(webhook_test['incidentId'] in m for m in ProtocolMock.webhook_messages), 'webhook command confirmed delivery')
        check('webhook_test_command', True)
        check('secrets_not_in_console_or_reports', 'local-smoke-dummy' not in output()
              and all('local-smoke-dummy' not in json.dumps(i) for i in incidents()))
        check('existing_book_scripts_compile_and_run', not any('TYS0' in line or 'SCANNER_FAILURE' in line for line in lines))
        summary.update(success=True, incidentCount=len(incidents()), qwenRequests=len(ProtocolMock.manifests),
                       discordMessages=len(ProtocolMock.webhook_messages), rootIncident=quarantine['incidentId'])
    finally:
        if clients is not None and clients.poll() is None:
            clients.terminate()
            clients.wait(timeout=10)
        if process is not None and process.poll() is None:
            process.stdin.write('stop\n')
            process.stdin.flush()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
        summary['serverStopped'] = process is None or process.poll() is not None
        summary['aiManifestParts'] = [{ 'file': m['file'], 'part': m.get('reviewPart', 1), 'parts': m.get('reviewParts', 1),
                                      'nodes': len(m['nodes']) } for m in ProtocolMock.manifests]
        (folder / 'result.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
        mock.shutdown()
        mock.server_close()
        print('Result: ' + str(folder / 'result.json'), flush=True)


if __name__ == '__main__':
    main()
