#!/usr/bin/env python3
"""独占 SpiceDB + 真实权限服务/SDK + CPA HTTP 资金验收；不触碰共享授权实例。"""
import datetime
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
import uuid

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
        with (ARTIFACTS / 'actions-real-authz-server.log').open('w') as server_log, (ARTIFACTS / 'actions-real-authz-backend.log').open('w') as backend_log:
            try:
                container = subprocess.check_output(['docker', 'run', '--pull=never', '-d',
                    '--label', 'recsys.fixture=actions-authz', '-p', '127.0.0.1::8443',
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

                def relationship(subject, relation, operation):
                    return {'operation': operation, 'resource': {'type': 'advertiser', 'id': ADV},
                            'relation': relation, 'subject': {'type': 'user', 'id': subject}}
                def relations(updates):
                    return call(authz, '/v1/relationships', 'POST', {'updates': updates}, service_token)
                relations([relationship('browser-admin', 'owner', 'TOUCH'), relationship('browser-editor', 'member', 'TOUCH')])
                backend = subprocess.Popen(['mvn', '-B', '-pl', 'recsys-ad-serving', '-am', 'test',
                    '-Dtest=ClickConversionBrowserFixtureTest', '-Dsurefire.failIfNoSpecifiedTests=false',
                    '-Drecsys.actions.fixture=true', '-Drecsys.actions.mode=grpc',
                    '-Drecsys.actions.authz-url=' + authz, '-Drecsys.browser.fixture-info=' + str(info)],
                    cwd=ROOT, env=dict(os.environ, ACTIONS_AUTHZ_TOKEN=service_token), stdout=backend_log,
                    stderr=subprocess.STDOUT, start_new_session=True)
                wait_until(lambda: info.exists() or (backend.poll() is not None and (_ for _ in ()).throw(RuntimeError('fixture exited before startup'))), START_TIMEOUT_SECONDS)
                fixture = json.loads(info.read_text())
                base, admin, editor, other = (fixture[k] for k in ('url', 'token', 'advertiserToken', 'otherToken'))
                delivery_body = {'userId': '1', 'query': '真实授权验收', 'slots': 1, 'scene': 'search'}
                def deliver(mode):
                    return call(base, '/api/sandbox/' + mode + '-ad-deliveries/' + str(uuid.uuid4()), 'PUT', delivery_body, admin)
                def visible(token):
                    return {'qualificationToken': token, 'visibleDurationMs': 1000, 'minVisibleRatioBps': 5000, 'pageVisible': True}
                clicked = deliver('click')
                click_token = clicked['items'][0]['preparation']['qualificationToken']
                call(base, '/api/sandbox/click-receipts/' + str(uuid.uuid4()), 'PUT',
                     {'kind': 'CLICK', 'qualificationToken': click_token, 'visibleDurationMs': 0, 'minVisibleRatioBps': 0, 'pageVisible': False}, admin)
                delivered = deliver('conversion')
                proof = delivered['items'][0]['preparation']['qualificationToken']
                event = str(uuid.uuid4())
                path = '/api/advertiser/' + ADV + '/sandbox-conversions/' + event
                body = {'qualificationToken': proof, 'occurredAt': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')}
                owner = call(base, path, 'PUT', body, admin)
                assert owner['status'] == 'ACCEPTED_WAITING' and not owner['chargedForThisOutcome']
                call(base, path, token=editor)
                call(base, path, token=other, expected=403)
                call(base, path, 'PUT', body, other, expected=403)
                relations([relationship('browser-admin', 'owner', 'DELETE')])
                call(base, path, token=admin, expected=403)
                call(base, path, 'PUT', body, admin, expected=403)
                # editor 的独立业务提交仍可成功，额外事件只关联已有资格，不收费。
                duplicate_path = '/api/advertiser/' + ADV + '/sandbox-conversions/' + str(uuid.uuid4())
                duplicate = call(base, duplicate_path, 'PUT', body, editor)
                assert duplicate['status'] == 'DUPLICATE_QUALIFICATION' and not duplicate['chargedForThisOutcome']
                time.sleep(1.1)
                call(base, '/api/sandbox/click-receipts/' + str(uuid.uuid4()), 'PUT', dict(visible(click_token), kind='VISIBLE'), admin)
                call(base, '/api/sandbox/conversion-impressions/' + str(uuid.uuid4()), 'PUT', visible(proof), admin)
                captured = call(base, path, token=editor)
                assert captured['chargedForThisOutcome'] and captured['chargedAmount'] == '0.234567'
                relations([relationship('browser-editor', 'member', 'DELETE')])
                call(base, path, token=editor, expected=403)
                call(base, path, 'PUT', body, editor, expected=403)
                relations([relationship('browser-admin', 'owner', 'TOUCH')])
                # 只停止本脚本拥有的判权服务，真实 SDK 连接失败必须是 503。
                stop(server)
                call(base, path, token=admin, expected=503)
                call(base, path, 'PUT', body, admin, expected=503)
                call(base, '/fixture/finish', 'POST')
                if backend.wait(timeout=STOP_TIMEOUT_SECONDS):
                    raise RuntimeError('fixture final PostgreSQL reconciliation failed')
                print('PASS: real SDK → auth-platform-server → SpiceDB; owner/editor, unrelated ADMIN, revoked PUT/GET, dependency 503, real money reconciliation')
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
