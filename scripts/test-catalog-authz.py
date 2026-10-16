#!/usr/bin/env python3
"""执行已编译目录验收宿主，使用显式固定的权限平台制品与独占SpiceDB；不改共享授权。"""
import argparse
import os
from pathlib import Path
import subprocess
import signal
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--server-jar', required=True, type=Path)
    parser.add_argument('--schema', required=True, type=Path)
    parser.add_argument('--spicedb-image', required=True)
    parser.add_argument('--scenario', choices=('full', 'root-recovery'), default='full')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    module = root / 'recsys-advertiser'
    classpath_file = module / 'target/catalog-test-classpath.txt'
    main_class = module / 'target/test-classes/com/recsys/advertiser/catalog/CatalogRealAuthzAcceptance.class'
    for path in (args.server_jar, args.schema, classpath_file, main_class):
        if not path.is_file():
            raise FileNotFoundError(f'缺少实际制品/编译证据：{path.name}')
    # 复用仓库已验证的Docker API兼容配置，不回退到跳过Testcontainers。
    pom = ET.parse(root / 'pom.xml')
    version = pom.findtext('{*}properties/{*}docker.api.version')
    if not version:
        raise ValueError('缺少仓库Docker API配置')
    environment = dict(os.environ, ACTIONS_AUTHZ_SERVER_JAR=str(args.server_jar.resolve()),
                       ACTIONS_AUTHZ_SCHEMA=str(args.schema.resolve()), ACTIONS_SPICEDB_IMAGE=args.spicedb_image,
                       CATALOG_AUTHZ_SCENARIO=args.scenario)
    log = module / f'target/catalog-real-authz-{time.time_ns()}.log'
    with log.open('w') as output:
        process = subprocess.Popen(['java', '-Dapi.version=' + version, '-cp', classpath_file.read_text().strip(),
                                    'com.recsys.advertiser.catalog.CatalogRealAuthzAcceptance'], cwd=module,
                                   env=environment, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            result = process.wait(timeout=300)
        except subprocess.TimeoutExpired:
            # 仅终止本脚本新建的验收进程组，不能留下独占权限服务成为未知运行任务。
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
            raise SystemExit(f'目录真实权限验收超时；日志保留：{log}')
    if result:
        raise SystemExit(f'目录真实权限验收FAIL；脱敏诊断日志：{log}')
    print(f'PASS: 目录真实权限与原身份恢复；证据：{log}')


if __name__ == '__main__':
    main()
