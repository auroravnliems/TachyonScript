"""Opt-in live Qwen/Discord test on disposable localhost Paper, using synthetic sources only.

Requires OPENROUTER_API_KEY and TACHYON_SECURITY_DISCORD_WEBHOOK in the process environment.
Posts new test incidents to the configured webhook; never replays historical incidents.
The supplied Paper installation stays stopped and is used only for its cached classpath.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time

from paper_security_smoke import ROOT, discord_message_text, free_port, validate_discord_payload, wait_for


SAFE = ('on load { log("LIVE_SECURITY_CONTROL_ACTIVE") }\n'
        'on unload { log("LIVE_SECURITY_UNLOAD") }\n'
        'command liveprobe { log("LIVE_SECURITY_CONTROL_COMMAND") }\n'
        'every 1 second { log("LIVE_SECURITY_CONTROL_TICK") }\n'
        'function exported(): int { return 7 }\n')
DEPENDENT = ('import vulnerable_test\n'
             'on load { log("LIVE_SECURITY_DEPENDENT_ACTIVE {vulnerable_test.exported()}") }\n')


def records(file):
    try:
        return [json.loads(line) for line in file.read_text(encoding='utf-8').splitlines() if line.strip()]
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--paper-home', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'tachyon-plugin/build/libs/TachyonScript-0.5.1-SNAPSHOT.jar')
    parser.add_argument('--mineflayer', type=Path)
    args = parser.parse_args()
    secrets = [os.environ.get(name, '') for name in ('OPENROUTER_API_KEY', 'TACHYON_SECURITY_DISCORD_WEBHOOK')]
    if not all(secrets):
        raise ValueError('Live test requires both credential environment variables; values withheld')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    folder = ROOT / 'build/security-live-paper' / stamp
    plugin_folder = folder / 'plugins/TachyonScript'
    scripts = plugin_folder / 'scripts'
    scripts.mkdir(parents=True)
    security = plugin_folder / 'security'
    paper = args.paper_home.resolve()
    shutil.copy2(args.plugin, folder / 'plugins/TachyonScript.jar')
    shutil.copy2(paper / 'server.args', folder / 'server.args')
    shutil.copy2(paper / 'eula.txt', folder / 'eula.txt')
    port = free_port()
    (folder / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
        'level-type=minecraft:flat\nview-distance=2\nsimulation-distance=2\n'
        'spawn-protection=0\nmax-players=4\nenable-query=false\nenable-rcon=false\n'
        'pause-when-empty-seconds=-1\n', encoding='utf-8')
    profile = (Path(__file__).with_name('openrouter-security.yml')).read_text(encoding='utf-8')
    (plugin_folder / 'config.yml').write_text('reload:\n  mode: lenient\nstorage:\n  type: memory\n'
                                           '  flush-interval-seconds: 0\n' + profile, encoding='utf-8')
    (scripts / 'vulnerable_test.tys').write_text(SAFE, encoding='utf-8')
    (scripts / 'dependent_test.tys').write_text(DEPENDENT, encoding='utf-8')
    bad = (Path(__file__).parent / 'fixtures/command-injection.tys').read_bytes()
    if args.mineflayer:
        classes = folder / 'permission-probe'
        classes.mkdir()
        classpath = re.search(r'-(?:cp|classpath)\s+"([^"]+)"', (folder / 'server.args').read_text()).group(1)
        javac = args.java.with_name('javac.exe' if os.name == 'nt' else 'javac')
        jar = args.java.with_name('jar.exe' if os.name == 'nt' else 'jar')
        subprocess.run([str(javac), '-cp', classpath, '-d', str(classes),
                        str(Path(__file__).with_name('SecurityPermissionProbe.java'))], check=True, capture_output=True)
        (classes / 'plugin.yml').write_text('name: SecurityPermissionProbe\nversion: 1\napi-version: "1.21"\n'
                                          'main: dev.tachyonscript.validation.SecurityPermissionProbe\ndepend: [TachyonScript]\n')
        subprocess.run([str(jar), 'cf', str(folder / 'plugins/SecurityPermissionProbe.jar'), '-C', str(classes), '.'],
                       check=True, capture_output=True)
    summary = dict(server=str(folder), minecraftPort=port, model='qwen/qwen3-coder',
                   pluginSha256=hashlib.sha256(args.plugin.read_bytes()).hexdigest(),
                   sourcePolicy='Synthetic sources only; original server scripts are never uploaded or modified',
                   checks={}, success=False)
    lines = []
    process = clients = reader = None
    client_output = folder / 'admin-messages.json'

    def output():
        return ''.join(lines)

    def incidents():
        result = []
        for file in security.glob('*.report.json'):
            try:
                result.append(json.loads(file.read_text(encoding='utf-8')))
            except (OSError, json.JSONDecodeError):
                pass
        return result

    def client_results():
        try:
            return json.loads(client_output.read_text(encoding='utf-8'))
        except (OSError, json.JSONDecodeError):
            return {}

    def command(value):
        assert process.poll() is None, 'Disposable Paper server stopped unexpectedly'
        process.stdin.write(value + '\n')
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

    def deliveries_complete():
        queued = {item['id'] for item in records(security / 'discord-outbox.jsonl')}
        delivered = {item['id'] for item in records(security / 'discord-delivered.jsonl')}
        return queued and queued <= delivered

    try:
        process = subprocess.Popen([str(args.java), '@server.args', '--nogui'], cwd=folder,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace', bufsize=1,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        reader = threading.Thread(target=read_console, daemon=True)
        reader.start()
        wait_for(lambda: 'LIVE_SECURITY_CONTROL_ACTIVE' in output()
                 and 'LIVE_SECURITY_DEPENDENT_ACTIVE' in output(), 'live Qwen approves safe initial scripts', 150)
        check('required_live_qwen_review_precedes_activation', not any(i['action'] == 'API_FAILURE' for i in incidents()))
        command('liveprobe')
        wait_for(lambda: 'LIVE_SECURITY_CONTROL_COMMAND' in output(), 'approved command executes')
        check('safe_control_command_runs', True)
        wait_for(lambda: 'LIVE_SECURITY_CONTROL_TICK' in output(), 'approved scheduled task runs')
        if args.mineflayer:
            observer_env = os.environ.copy()
            for name in ('OPENROUTER_API_KEY', 'TACHYON_SECURITY_DISCORD_WEBHOOK'):
                observer_env.pop(name, None)
            clients = subprocess.Popen(['node', str(Path(__file__).with_name('admin_notification_probe.js')),
                                        str(args.mineflayer.resolve()), str(port), str(client_output)],
                                       env=observer_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            wait_for(lambda: len(client_results().get('ready', [])) == 2, 'local administrator/ordinary clients join', 30)
            check('admin_permission_is_explicit', 'SECURITY_SMOKE_ROLE TSAlert op=false alerts=true' in output()
                  and 'SECURITY_SMOKE_ROLE TSObserver op=false alerts=false' in output())
        before = len(output())
        (scripts / 'vulnerable_test.tys').write_bytes(bad)
        command('tys reload vulnerable_test.tys')
        incident = wait_for(lambda: next((i for i in incidents() if i['file'] == 'vulnerable_test.tys'
                                         and i['action'] == 'AUTO_QUARANTINE'), None), 'deliberate fixture is quarantined', 90)
        dependency = wait_for(lambda: next((i for i in incidents() if i['file'] == 'dependent_test.tys'
                                           and i['decision'] == 'DISABLE'), None), 'importing script is disabled')
        finding = next(f for f in incident['findings'] if f['category'] == 'COMMAND_INJECTION')
        span = finding['location']
        check('precise_compiler_backed_location', (span['file'], span['startLine'], span['startColumn'],
                                                 span['endLine'], span['endColumn']) == ('vulnerable_test.tys', 5, 5, 5, 25))
        check('exact_vulnerable_expression', bad.decode('utf-8')[span['startOffset']:span['endOffset']] == 'server.dispatch(cmd)')
        check('complete_taint_flow', finding['source'] == 'message' and finding['sink'] == 'server.dispatch'
              and any(step['kind'] == 'SOURCE' and step['location']['startLine'] == 4 for step in finding['flow'])
              and any(step['kind'] == 'CONCAT' for step in finding['flow']) and finding['flow'][-1]['kind'] == 'SINK')
        check('dependent_import_has_exact_location', dependency['findings'][0]['location']['startLine'] == 1)
        snapshot = security / (incident['incidentId'] + '.source.tys')
        check('source_and_quarantine_snapshot_preserved', (scripts / 'vulnerable_test.tys').read_bytes() == bad
              and snapshot.read_bytes() == bad and hashlib.sha256(bad).hexdigest() == incident['sha256'])
        time.sleep(2)
        check('unsafe_initializer_never_executes', 'LIVE_SECURITY_UNSAFE_INITIALIZER' not in output())
        check('retirement_skips_unload_and_stops_tasks', 'LIVE_SECURITY_UNLOAD' not in output()[before:]
              and 'LIVE_SECURITY_CONTROL_TICK' not in output().split('AUTO_QUARANTINE')[-1])
        count = output().count('LIVE_SECURITY_CONTROL_COMMAND')
        command('liveprobe')
        time.sleep(1)
        check('quarantined_command_is_unregistered', output().count('LIVE_SECURITY_CONTROL_COMMAND') == count)
        command('tys security inspect ' + incident['incidentId'])
        wait_for(lambda: output().count('vulnerable_test.tys:5:5') >= 2, 'console incident inspect')
        check('console_inspect_contains_location_and_flow', 'Data flow:' in output()[before:]
              and 'server.dispatch(cmd)' in output()[before:])
        if args.mineflayer:
            wait_for(lambda: incident['incidentId'] in '\n'.join(client_results().get('messages', {}).get('TSAlert', [])),
                     'non-OP administrator receives the incident')
            admin = '\n'.join(client_results()['messages']['TSAlert'])
            check('non_op_admin_receives_detailed_alert', 'vulnerable_test.tys:5:5' in admin and 'server.dispatch(cmd)' in admin)
            check('ordinary_player_receives_no_alert', not any(incident['incidentId'] in message
                  for message in client_results()['messages']['TSObserver']))
        command('tys security test-webhook')
        webhook_test = wait_for(lambda: next((i for i in incidents() if i['action'] == 'WEBHOOK_TEST'), None),
                                'webhook test command creates a new audit incident')
        wait_for(deliveries_complete, 'real Discord confirms all newly queued test messages', 90)
        queued = records(security / 'discord-outbox.jsonl')
        contents = '\n'.join(discord_message_text(json.loads(item['payload'])) for item in queued
                             if item['id'].startswith(incident['incidentId'] + '.'))
        check('live_discord_receives_precise_quarantine_alert', 'vulnerable_test.tys:5:5' in contents
              and 'server.dispatch(cmd)' in contents and 'Luồng dữ liệu' in contents
              and 'AUTO_QUARANTINE' in contents and incident['sha256'] in contents)
        check('live_discord_webhook_test_is_confirmed', any(item['id'].startswith(webhook_test['incidentId'] + '.') for item in queued))
        check('discord_mentions_disabled', all(json.loads(item['payload'])['allowed_mentions']['parse'] == [] for item in queued))
        for item in queued:
            validate_discord_payload(json.loads(item['payload']))
        check('discord_uses_bounded_colored_cards', all(json.loads(item['payload']).get('embeds') for item in queued))
        check('no_fake_api_findings_or_failures', not any(i['action'] in ('API_FAILURE', 'SCANNER_FAILURE', 'WEBHOOK_FAILURE') for i in incidents()))
        recorded_text = output() + json.dumps(incidents()) + json.dumps(queued)
        check('secrets_absent_from_console_reports_and_alerts', all(secret not in recorded_text for secret in secrets))
        summary.update(success=True, incidentId=incident['incidentId'], dependencyIncident=dependency['incidentId'],
                       webhookTestIncident=webhook_test['incidentId'], location=span, sourceSha256=incident['sha256'],
                       deliveredDiscordParts=len(records(security / 'discord-delivered.jsonl')))
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
        if reader is not None:
            reader.join(timeout=5)
        summary['serverStopped'] = process is None or process.poll() is not None
        (folder / 'result.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Result: ' + str(folder / 'result.json'), flush=True)


if __name__ == '__main__':
    main()
