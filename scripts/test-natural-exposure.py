#!/usr/bin/env python3
"""启动独占测试宿主与浏览器，核对真实自然事实/Outbox PG；不连接共享 dev_infra。"""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT / 'recsys-rec-engine' / 'target'


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
        log_path = ARTIFACTS / 'natural-browser-backend.log'
        with log_path.open('w') as log:
            backend = subprocess.Popen(['mvn', '-B', '-pl', 'recsys-behavior', '-am', 'test',
                '-Dtest=NaturalExposureBrowserAcceptanceTest', '-Dsurefire.failIfNoSpecifiedTests=false',
                '-Drecsys.natural.browser.fixture=true', '-Drecsys.natural.browser.fixture-info=' + str(info)],
                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                deadline = time.monotonic() + 180
                while not info.exists():
                    if backend.poll() is not None:
                        raise RuntimeError('后端宿主启动失败，见 ' + str(log_path))
                    if time.monotonic() >= deadline:
                        raise TimeoutError('后端宿主启动超时，见 ' + str(log_path))
                    time.sleep(0.5)
                env = dict(os.environ, NATURAL_FIXTURE_INFO=str(info))
                browser = subprocess.run(['npx', 'playwright', 'test', '--config', 'playwright.natural.config.ts'],
                    cwd=ROOT / 'console', env=env, timeout=240, check=False)
                finish(info)
                code = backend.wait(timeout=30)
                if browser.returncode or code:
                    raise RuntimeError(f'浏览器/自然事实验收失败: browser={browser.returncode}, backend={code}; {log_path}')
                print('PASS: 自然浏览器回执、真实 PG 唯一事实与原子待发 Outbox；未执行 Kafka 投递')
            finally:
                if backend.poll() is None:
                    finish(info)
                    try:
                        backend.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        # 只终止本脚本创建的进程组，不按端口或进程名清理其他环境。
                        os.killpg(backend.pid, signal.SIGTERM)
                        backend.wait(timeout=10)


if __name__ == '__main__':
    main()
