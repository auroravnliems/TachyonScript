"""Summarize real JUnit/Paper evidence and verify the delivered artifact and archived spans."""
from pathlib import Path
import hashlib
import json
import socket
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
artifact = ROOT / 'tachyon-plugin/build/libs/TachyonScript-0.5.1-SNAPSHOT.jar'
results = sorted((ROOT / 'build/security-paper').glob('*/result.json'))
paper_file = results[-1]
paper = json.loads(paper_file.read_text(encoding='utf-8'))
assert paper['success'] and paper['serverStopped']
with socket.socket() as connection:
    connection.settimeout(1)
    assert connection.connect_ex(('127.0.0.1', paper['minecraftPort'])) != 0, 'Disposable Paper port is still open'

counts = dict(tests=0, failures=0, errors=0, skipped=0)
security_suites = []
for xml_file in ROOT.glob('tachyon-*/build/test-results/test/TEST-*.xml'):
    suite = ET.parse(xml_file).getroot()
    values = {key: int(suite.attrib.get(key, 0)) for key in counts}
    for key in counts:
        counts[key] += values[key]
    if 'Security' in suite.attrib['name'] or 'SecureWeb' in suite.attrib['name']:
        security_suites.append(dict(name=suite.attrib['name'], **values))
assert counts['failures'] == counts['errors'] == 0
assert sum(s['tests'] for s in security_suites) >= 48

with zipfile.ZipFile(artifact) as jar:
    entries = jar.namelist()
    for needed in ('dev/tachyonscript/security/SecurityService.class',
                   'dev/tachyonscript/internal/okhttp3/OkHttpClient.class',
                   'dev/tachyonscript/internal/kotlin/jvm/internal/Intrinsics.class'):
        assert needed in entries, needed
    assert not any(e.startswith(('okhttp3/', 'okio/', 'kotlin/')) for e in entries)
    assert not any('SecurityPermissionProbe' in e for e in entries)
    assert not any('OpenRouterSecurityProbe' in e for e in entries)
    assert not any('SecurityConfigurationProbe' in e for e in entries)
artifact_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()
server = Path(paper['server'])
assert hashlib.sha256((server / 'plugins/TachyonScript.jar').read_bytes()).hexdigest() == artifact_hash
security = server / 'plugins/TachyonScript/security'
incident = json.loads((security / (paper['rootIncident'] + '.report.json')).read_text(encoding='utf-8'))
original = (security / (paper['rootIncident'] + '.source.tys')).read_bytes()
assert hashlib.sha256(original).hexdigest() == incident['sha256']
finding = next(f for f in incident['findings'] if f['category'] == 'COMMAND_INJECTION')
location = finding['location']
utf16 = original.decode('utf-8').encode('utf-16-le')
expression = utf16[location['startOffset'] * 2:location['endOffset'] * 2].decode('utf-16-le')
assert expression == 'server.dispatch(value)'
assert (location['file'], location['startLine'], location['startColumn']) == ('quarantine.tys', 5, 5)
assert finding['source'] == 'message' and finding['sink'] == 'server.dispatch'
assert finding['flow'][0]['location']['startLine'] == 4
assert any(step['kind'] == 'CONCAT' for step in finding['flow'])

diff = subprocess.run(['git', '-c', 'core.safecrlf=false', 'diff', '--check'], cwd=ROOT, capture_output=True, text=True)
assert diff.returncode == 0, diff.stdout + diff.stderr
live_file = ROOT / 'build/security-openrouter/result.json'
live_validation = None
if live_file.exists():
    live = json.loads(live_file.read_text(encoding='utf-8'))
    if live.get('success') and live.get('pluginSha256') == artifact_hash:
        assert len(live['checks']) >= 8 and all(live['checks'].values())
        assert live['safeFindings'] == [], 'Live provider flagged the safe control'
        live_finding = next(f for f in live['unsafeFindings'] if f['category'] == 'COMMAND_INJECTION')
        live_span = live_finding['location']
        assert (live_span['file'], live_span['startLine'], live_span['startColumn'],
                live_span['endLine'], live_span['endColumn']) == ('live-unsafe.tys', 5, 5, 5, 25)
        assert live_finding['origin'] == 'AI' and live_finding['concreteEvidence']
        assert live_finding['severity'] == 'CRITICAL' and live_finding['confidence'] >= .95
        assert live_finding['source'] == 'message' and live_finding['sink'] == 'server.dispatch'
        assert live['vulnerableExpression'] == 'server.dispatch(cmd)'
        assert not any(e.startswith('sk-or-v1-') for e in json.dumps(live).split('"'))
        live_validation = dict(report=str(live_file), model=live['model'], checks=len(live['checks']),
                               timestamp=live['timestamp'], sourcePolicy=live['sourcePolicy'],
                               compilerBackedSpanVerified=live_span)
live_paper_validation = None
live_paper_files = sorted((ROOT / 'build/security-live-paper').glob('*/result.json'))
if live_paper_files:
    live_paper_file = live_paper_files[-1]
    live_paper = json.loads(live_paper_file.read_text(encoding='utf-8'))
    if live_paper.get('success') and live_paper.get('pluginSha256') == artifact_hash:
        assert live_paper['serverStopped'] and len(live_paper['checks']) >= 16 and all(live_paper['checks'].values())
        with socket.socket() as connection:
            connection.settimeout(1)
            assert connection.connect_ex(('127.0.0.1', live_paper['minecraftPort'])) != 0, 'Live test server is still running'
        live_security = Path(live_paper['server']) / 'plugins/TachyonScript/security'
        live_incident = json.loads((live_security / (live_paper['incidentId'] + '.report.json')).read_text(encoding='utf-8'))
        live_source = (live_security / (live_paper['incidentId'] + '.source.tys')).read_bytes()
        assert hashlib.sha256(live_source).hexdigest() == live_paper['sourceSha256'] == live_incident['sha256']
        assert live_source == (ROOT / 'validation/security/fixtures/command-injection.tys').read_bytes()
        queued = {entry['id'] for entry in map(json.loads, (live_security / 'discord-outbox.jsonl').read_text(encoding='utf-8').splitlines())}
        delivered = {entry['id'] for entry in map(json.loads, (live_security / 'discord-delivered.jsonl').read_text(encoding='utf-8').splitlines())}
        assert queued and queued <= delivered
        assert any(item.startswith(live_paper['incidentId'] + '.') for item in delivered)
        assert any(item.startswith(live_paper['webhookTestIncident'] + '.') for item in delivered)
        live_paper_validation = dict(report=str(live_paper_file), model=live_paper['model'],
                                     checks=len(live_paper['checks']), deliveredDiscordParts=len(delivered),
                                     incidentId=live_paper['incidentId'], location=live_paper['location'],
                                     serverStopped=True, sourcePolicy=live_paper['sourcePolicy'])
config_validation_file = ROOT / 'build/security-live-config/config-validation.json'
config_validation = None
if config_validation_file.exists():
    config_validation = json.loads(config_validation_file.read_text(encoding='utf-8'))
    assert config_validation['configurationValidated'] and config_validation['qwenEnabled'] and config_validation['discordEnabled']
    assert config_validation['pluginSha256'] == artifact_hash
    assert hashlib.sha256(Path(config_validation['configPath']).read_bytes()).hexdigest() == config_validation['configSha256']
report = dict(artifact=str(artifact), sha256=artifact_hash, junit=counts,
              executedPassed=counts['tests'] - counts['skipped'], securitySuites=security_suites,
              paperResult=str(paper_file), paperChecks=len(paper['checks']), allPaperChecksPassed=all(paper['checks'].values()),
              compilerBackedSpanVerified=location, vulnerableExpression=expression,
              quarantinedSourceHashVerified=True, relocatedLibrariesVerified=True,
              fixtureExcludedFromArtifact=True, serverStopped=True, diffCheckPassed=True,
              providerValidation=('Live Qwen/OpenRouter and Discord confirmed on synthetic localhost Paper sources'
                                  if live_paper_validation else
                                  'Live Qwen via OpenRouter on synthetic sources; Discord remains locally mocked'
                                  if live_validation else 'Local Qwen/Discord protocol mocks'),
              liveProvider=live_validation, livePaper=live_paper_validation, installedConfig=config_validation)
destination = ROOT / 'build/security-verification.json'
destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(report=str(destination), passed=report['executedPassed'], skipped=counts['skipped'],
                      securityTests=sum(s['tests'] for s in security_suites), paperChecks=report['paperChecks'],
                      liveProviderChecks=live_validation['checks'] if live_validation else 0,
                      livePaperChecks=live_paper_validation['checks'] if live_paper_validation else 0,
                      serverStopped=True, sha256=artifact_hash), indent=2))
