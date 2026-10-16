#!/usr/bin/env python3
"""启动独占测试宿主与浏览器，核对真实 PG/资金事务与双调用模式；不连接共享 dev_infra。"""
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
BROWSER_TIMEOUT_SECONDS = 240
FINISH_TIMEOUT_SECONDS = 30
SHUTDOWN_TIMEOUT_SECONDS = 15
TERMINATE_TIMEOUT_SECONDS = 10
ARTIFACTS = ROOT / 'recsys-ad-serving' / 'target'


def redact(text):
    """失败快照也可能携带表单值；发布日志/文本证据前清除真实证明与 JWT。"""
    text = re.sub(r'v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_PROOF]', text)
    return re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]', text)


def sanitize_artifacts(mode, log_path):
    evidence = ARTIFACTS / ('actions-evidence-' + mode)
    for path in [log_path, *evidence.rglob('*')]:
        if path.is_file() and path.suffix in ('.md', '.txt', '.log', '.json'):
            path.write_text(redact(path.read_text()))


def finish(info):
    if info.exists():
        url = json.loads(info.read_text())['url']
        try:
            urllib.request.urlopen(urllib.request.Request(url + '/fixture/finish', method='POST'), timeout=5).close()
        except OSError as error:
            print('测试宿主 finish 未送达；仍须检查退出码:', type(error).__name__)


def main():
    ARTIFACTS.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='recsys-browser-') as directory:
        info = Path(directory) / 'fixture.json'
        log_path = ARTIFACTS / ('actions-browser-' + os.environ.get('ACTIONS_MODE', 'grpc') + '.log')
        with log_path.open('w') as log:
            backend = subprocess.Popen(['mvn', '-B', '-pl', 'recsys-ad-serving', '-am', 'test',
                '-Dtest=ClickConversionBrowserFixtureTest', '-Dsurefire.failIfNoSpecifiedTests=false',
                '-Drecsys.actions.fixture=true', '-Drecsys.actions.mode=' + os.environ.get('ACTIONS_MODE', 'grpc'), '-Drecsys.browser.fixture-info=' + str(info)],
                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                deadline = time.monotonic() + 240
                while not info.exists():
                    if backend.poll() is not None:
                        raise RuntimeError('后端宿主启动失败，见 ' + str(log_path))
                    if time.monotonic() >= deadline:
                        raise TimeoutError('后端宿主启动超时，见 ' + str(log_path))
                    time.sleep(0.5)
                env = dict(os.environ, ACTIONS_FIXTURE_INFO=str(info))
                browser = subprocess.run(['npx', 'playwright', 'test', '--config', 'playwright.click-conversion.config.ts'],
                    cwd=ROOT / 'console', env=env, timeout=BROWSER_TIMEOUT_SECONDS, check=False,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                print(redact(browser.stdout))
                finish(info)
                code = backend.wait(timeout=FINISH_TIMEOUT_SECONDS)
                if browser.returncode or code:
                    raise RuntimeError(f'浏览器/资金验收失败: browser={browser.returncode}, backend={code}; {log_path}')
                print('PASS: 浏览器双条件、真实账本/Outbox 及宿主最终资金对账')
            finally:
                if backend.poll() is None:
                    finish(info)
                    try:
                        backend.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
                    except subprocess.TimeoutExpired:
                        # 只终止本脚本创建的进程组，不按端口或进程名清理其他环境。
                        os.killpg(backend.pid, signal.SIGTERM)
                        backend.wait(timeout=TERMINATE_TIMEOUT_SECONDS)
                log.flush()
                sanitize_artifacts(os.environ['ACTIONS_MODE'], log_path)


if __name__ == '__main__':
    modes = [os.environ['ACTIONS_MODE']] if 'ACTIONS_MODE' in os.environ else ['in-process', 'grpc']
    for mode in modes:
        if mode not in ('in-process', 'grpc'):
            raise ValueError('ACTIONS_MODE must be in-process or grpc')
        os.environ['ACTIONS_MODE'] = mode
        main()
