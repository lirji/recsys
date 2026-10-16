#!/usr/bin/env python3
"""独占 SpiceDB + 真实权限服务/SDK + 只读view账户日HTTP验收；不触碰共享授权实例。"""
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT / 'recsys-ad-serving' / 'target'
ADV = '9223372036854775807'
START_TIMEOUT_SECONDS = 240
REQUEST_TIMEOUT_SECONDS = 15
STOP_TIMEOUT_SECONDS = 30


def call(base, path, method='GET', body=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + path, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status != expected:
        # 禁止在异常或 CI 日志回显证明、服务凭据或响应全文。
        raise AssertionError(f'{method} {path}: expected {expected}, received {status}')
    return json.loads(raw) if raw else None


def wait_until(probe, seconds):
    deadline = time.monotonic() + seconds
    last_failure = 'not ready'
    while time.monotonic() < deadline:
        try:
            value = probe()
            if value:
                return value
        except (OSError, AssertionError) as error:
            # 启动重试有截止时间，保留错误类别但不传播可能含凭据的响应内容。
            last_failure = type(error).__name__
        time.sleep(0.3)
    raise TimeoutError('isolated service startup timed out: ' + last_failure)


def stop(process):
    if process and process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=STOP_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=STOP_TIMEOUT_SECONDS)


def main():
    jar = Path(os.environ['ACTIONS_AUTHZ_SERVER_JAR']).resolve()
    schema = Path(os.environ['ACTIONS_AUTHZ_SCHEMA']).read_text()
    image = os.environ['ACTIONS_SPICEDB_IMAGE']
    if not jar.is_file():
        raise FileNotFoundError('build the pinned auth-platform-server artifact first')
    ARTIFACTS.mkdir(exist_ok=True)
    container = None
    server = backend = None
    with tempfile.TemporaryDirectory(prefix='recsys-real-authz-') as directory:
        info = Path(directory) / 'fixture.json'
        key, service_token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        with (ARTIFACTS / 'reconciliation-real-authz-server.log').open('w') as server_log, (ARTIFACTS / 'reconciliation-real-authz-backend.log').open('w') as backend_log:
            try:
                container = subprocess.check_output(['docker', 'run', '--pull=never', '-d',
                    '--label', 'recsys.fixture=reconciliation-authz', '-p', '127.0.0.1::8443',
                    '-e', 'SPICEDB_GRPC_PRESHARED_KEY=' + key, image,
                    'serve', '--http-enabled', '--http-addr=:8443', '--dispatch-cluster-enabled=false'], text=True).strip()
                port = json.loads(subprocess.check_output(['docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', container]))['8443/tcp'][0]['HostPort']
                spice = 'http://127.0.0.1:' + port
                wait_until(lambda: call(spice, '/v1/schema/write', 'POST', {'schema': schema}, key), STOP_TIMEOUT_SECONDS)
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0))
                    server_port = listener.getsockname()[1]
                authz = f'http://127.0.0.1:{server_port}'
                env = dict(os.environ, SPICEDB_HTTP=spice, SPICEDB_KEY=key,
                           AUTHZ_SERVER_SECURITY_ENABLED='true', AUTHZ_SERVER_TOKEN=service_token)
                server = subprocess.Popen(['java', '-jar', str(jar), '--server.address=127.0.0.1',
                    '--server.port=' + str(server_port)], env=env, stdout=server_log, stderr=subprocess.STDOUT, start_new_session=True)
                wait_until(lambda: call(authz, '/actuator/health'), STOP_TIMEOUT_SECONDS)

                def relation(resource_type, resource_id, name, subject_type, subject_id, operation='TOUCH'):
                    return {'operation': operation, 'resource': {'type': resource_type, 'id': resource_id},
                            'relation': name, 'subject': {'type': subject_type, 'id': subject_id}}
                def relations(updates):
                    return call(authz, '/v1/relationships', 'POST', {'updates': updates}, service_token)
                link = relation('advertiser', ADV, 'platform', 'platform', 'reconciliation')
                viewer = relation('platform', 'reconciliation', 'operator', 'user', 'report-viewer')
                relations([link, viewer])
                # 实际schema中operator经view_reports获得view，既不是owner也不是member。
                def check(permission):
                    return call(authz, '/v1/check', 'POST', {'subject': {'type': 'user', 'id': 'report-viewer'},
                        'resource': {'type': 'advertiser', 'id': ADV}, 'permission': permission,
                        'consistency': {'mode': 'full'}}, service_token)['allowed']
                assert check('view') and not check('edit'), '必须是真实view-only主体'
                backend = subprocess.Popen(['mvn', '-B', '-pl', 'recsys-ad-serving', '-am', 'test',
                    '-Dtest=ReconciliationBrowserFixtureTest', '-Dsurefire.failIfNoSpecifiedTests=false',
                    '-Drecsys.reconciliation.fixture=true', '-Drecsys.ad-serving.mode=grpc',
                    '-Drecsys.reconciliation.authz-url=' + authz, '-Drecsys.browser.fixture-info=' + str(info)],
                    cwd=ROOT, env=dict(os.environ, ACTIONS_AUTHZ_TOKEN=service_token), stdout=backend_log,
                    stderr=subprocess.STDOUT, start_new_session=True)
                wait_until(lambda: info.exists() or (backend.poll() is not None and (_ for _ in ()).throw(RuntimeError('fixture exited before startup'))), START_TIMEOUT_SECONDS)
                fixture = json.loads(info.read_text())
                base, viewer_token, other = (fixture[k] for k in ('url', 'token', 'otherToken'))
                before = call(base, '/fixture/money')
                path = '/api/advertiser/' + ADV + '/sandbox-reconciliation/' + fixture['day']
                report = call(base, path, token=viewer_token)
                assert report['account']['chargedAmount'] == '0.600006' and report['fundsStatus'] == 'BALANCED'
                assert [f['projectionStatus'] for f in report['families']] == ['LAGGING'] * 3
                call(base, path, token=other, expected=403)
                call(base, path, expected=401)
                relations([dict(viewer, operation='DELETE')])
                call(base, path, token=viewer_token, expected=403)
                relations([viewer])
                call(base, path, token=viewer_token)
                # 只停止本脚本拥有的判权服务，真实SDK连接失败必须为503，不能误报无权限。
                stop(server)
                unavailable = call(base, path, token=viewer_token, expected=503)
                assert unavailable['code'] == 'RECONCILIATION_AUTHZ_UNAVAILABLE'
                assert call(base, '/fixture/money') == before
                call(base, '/fixture/finish', 'POST')
                if backend.wait(timeout=STOP_TIMEOUT_SECONDS):
                    raise RuntimeError('fixture final PostgreSQL read-only verification failed')
                print('PASS: real SDK → auth-platform-server → SpiceDB; view-only (edit denied), unrelated ADMIN, revoked GET, dependency 503, unchanged money tables')
            finally:
                if backend and backend.poll() is None and info.exists():
                    try:
                        call(json.loads(info.read_text())['url'], '/fixture/finish', 'POST')
                        backend.wait(timeout=STOP_TIMEOUT_SECONDS)
                    except (OSError, AssertionError, subprocess.TimeoutExpired) as error:
                        print('fixture graceful cleanup failed; stopping owned process:', type(error).__name__)
                stop(backend)
                stop(server)
                if container:
                    # 唯一 ID 由本脚本 docker run 返回；不按名称清理任何共享容器。
                    subprocess.run(['docker', 'rm', '-f', container], check=True, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
