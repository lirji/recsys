#!/usr/bin/env python3
"""本机桶对比演示：复用真实曝光写点击，调用真实离线作业生成并发布报表。"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import ab_board_cohort

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.dev/test-data/ab-board-v1'
MARKER = 'demo-ab-board-v1'
COHORT = False
PG = os.environ.get('DEMO_PG_CONTAINER', 'dev-infra-recsys-postgres-1')
CONSOLE = os.environ.get('DEMO_CONSOLE_CONTAINER', 'recsys-console-api')
BASE = os.environ.get('DEMO_BASE_URL', 'http://localhost:9095').rstrip('/')
DB = os.environ.get('DEMO_PG_DB', 'recsys')
USER = os.environ.get('DEMO_PG_USER', 'recsys')
QUOTAS = json.loads((ROOT / 'test-data/scenarios.json').read_text())['clicksByInitialExposureRank']


def command(args, **kwargs):
    """命令通过 argv 传递；不使用 shell 拼接，也不打印含认证信息的环境。"""
    return subprocess.check_output(args, text=True, **kwargs).strip()


def save(name, data):
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = STATE / name
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    tmp.chmod(0o600)
    tmp.replace(path)


def load(name):
    return json.loads((STATE / name).read_text())


def preflight():
    """仅允许本机 Docker 和回环 HTTP，并确认 BFF 与目标数据库属于同一连接。"""
    if urllib.parse.urlsplit(BASE).hostname not in ('localhost', '127.0.0.1', '::1'):
        raise RuntimeError('只允许本机回环地址')
    expected = os.environ.get('DEMO_DOCKER_CONTEXT', 'desktop-linux')
    if command(['docker', 'context', 'show']) != expected:
        raise RuntimeError('Docker context 与显式配置不符')
    pg = json.loads(command(['docker', 'inspect', PG]))[0]
    console = json.loads(command(['docker', 'inspect', CONSOLE]))[0]
    env = dict(v.split('=', 1) for v in console['Config']['Env'])
    aliases = [a for n in pg['NetworkSettings']['Networks'].values() for a in n.get('Aliases', [])]
    if not pg['State']['Running'] or env.get('PG_HOST') not in aliases:
        raise RuntimeError('目标数据库不是当前控制台的数据库')
    if env.get('PG_DB', 'recsys') != DB or env.get('PG_USER', 'recsys') != USER:
        raise RuntimeError('数据库或用户与当前运行配置不符')
    return {'container': PG, 'containerId': pg['Id'], 'database': DB, 'user': USER}, env


def sql(statement, values=None, write=False):
    """SQL 值通过 psql 变量的 quoted-literal 语法传入；事务限定时间与读写范围。"""
    _, runtime = preflight()
    env = os.environ.copy()
    args = ['docker', 'exec', '-i']
    if runtime.get('PG_PASSWORD'):
        env['PGPASSWORD'] = runtime['PG_PASSWORD']
        args += ['-e', 'PGPASSWORD']
    args += [PG, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', USER, '-d', DB]
    for key, value in (values or {}).items():
        args += ['-v', key + '=' + str(value)]
    txn = 'BEGIN;' if write else 'BEGIN READ ONLY;'
    script = txn + "\nSET LOCAL statement_timeout='30s'; SET LOCAL lock_timeout='3s';\n" + statement + '\nCOMMIT;\n'
    result = command(args, input=script, env=env)
    return json.loads(result) if result else None


def api(path, token=None, data=None):
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(BASE + path, headers=headers,
        data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'HTTP {error.code}: {path}') from None


def login():
    user = os.environ.get('RECSYS_DEMO_USERNAME')
    password = os.environ.get('RECSYS_DEMO_PASSWORD')
    if not user or not password:
        raise RuntimeError('请通过环境变量提供现有本机账号 RECSYS_DEMO_USERNAME / RECSYS_DEMO_PASSWORD')
    return api('/api/auth/login', data={'username': user, 'password': password})['token']


def plan():
    if COHORT:
        return ab_board_cohort.plan(sys.modules[__name__])
    target, _ = preflight()
    if len(QUOTAS) != 4 or any(type(x) is not int or x <= 0 for x in QUOTAS) or sum(QUOTAS) != 100:
        raise RuntimeError('数量方案必须是4桶合计100条点击')
    if (STATE / 'plan.json').exists():
        result = load('plan.json')
        if result['target'] != target:
            raise RuntimeError('数据库身份已变，需核对旧清单；不自动重新灌数')
        return result
    existing = sql("SELECT count(*) FROM user_behavior WHERE scene=:'marker';", {'marker': MARKER})
    if existing:
        raise RuntimeError('存在本场景点击但缺少清单，需恢复清单后续办')
    buckets = sql("""
        SELECT coalesce(json_agg(t ORDER BY impressions DESC,bucket),'[]'::json) FROM (
          SELECT bucket,count(*) AS impressions FROM user_behavior
          WHERE action='IMPRESSION' AND bucket IS NOT NULL GROUP BY bucket
        ) t;
    """)
    if len(buckets) != 4:
        raise RuntimeError('当前场景要求4个已有桶；数据分布改变时需重新确认数量方案')
    targets = []
    for bucket, quota in zip(buckets, QUOTAS):
        selected = sql("""
            SELECT coalesce(json_agg(t),'[]'::json) FROM (
              SELECT e.id,e.user_id,e.item_id,e.bucket,e.exposure_id,e.scene,e.ts::text AS ts
              FROM user_behavior e WHERE e.action='IMPRESSION' AND e.bucket=:'bucket'
              AND e.exposure_id IS NOT NULL AND e.ts<=CURRENT_TIMESTAMP
              AND NOT EXISTS(SELECT 1 FROM user_behavior c WHERE c.action='CLICK' AND c.exposure_id=e.exposure_id)
              ORDER BY e.id DESC LIMIT :quota
            ) t;
        """, {'bucket': bucket['bucket'], 'quota': quota})
        if len(selected) != quota or len({x['exposure_id'] for x in selected}) != quota:
            raise RuntimeError('可归因且未点击的曝光数量不足')
        targets.extend(selected)
        bucket['demoClicks'] = quota
    result = {'scenario': MARKER, 'target': target, 'buckets': buckets, 'targets': targets,
              'sourceRevision': command(['git', 'rev-parse', 'HEAD'], cwd=ROOT)}
    # 先持久化曝光清单，再发送任何写入；超时恢复仍复用同一组幂等键。
    save('plan.json', result)
    return result


def clicks(p):
    return sql("""
        SELECT coalesce(json_agg(c ORDER BY c.id),'[]'::json) FROM (
          SELECT id,user_id,item_id,bucket,scene,exposure_id FROM user_behavior
          WHERE action='CLICK' AND exposure_id IN (SELECT jsonb_array_elements_text(:'ids'::jsonb))
        ) c;
    """, {'ids': json.dumps([x['exposure_id'] for x in p['targets']])})


def data_verify(p):
    rows = clicks(p)
    expected = {x['exposure_id']: x for x in p['targets']}
    if len(rows) != len(expected):
        raise RuntimeError(f'点击落库数量不符：{len(rows)}/{len(expected)}')
    for row in rows:
        source = expected[row['exposure_id']]
        if row['scene'] != MARKER or any(row[k] != source[k] for k in ('user_id', 'item_id', 'bucket')):
            raise RuntimeError('点击归属/归因与演示清单不一致')
    if COHORT:
        ab_board_cohort.verify_fixtures(sys.modules[__name__], p)
        save('owned-clicks.json', rows)
        return rows
    sources = sql("""
        SELECT count(*) FROM user_behavior e JOIN jsonb_to_recordset(:'sources'::jsonb)
          AS s(id bigint,user_id bigint,item_id bigint,bucket text,exposure_id text,scene text,ts text)
          ON e.id=s.id AND e.action='IMPRESSION' AND e.user_id=s.user_id AND e.item_id=s.item_id
          AND e.bucket=s.bucket AND e.exposure_id=s.exposure_id AND e.scene IS NOT DISTINCT FROM s.scene
          AND e.ts=s.ts::timestamp;
    """, {'sources': json.dumps(p['targets'])})
    if sources != len(expected):
        raise RuntimeError('原曝光记录发生变化')
    save('owned-clicks.json', rows)
    return rows


def init():
    p = plan()
    token = login()
    if COHORT:
        ab_board_cohort.seed(sys.modules[__name__], p)
    before = {x['exposure_id']: x for x in clicks(p)}
    if any(x['scene'] != MARKER for x in before.values()):
        raise RuntimeError('清单中某曝光已被其他点击占用；不覆盖、不另选后重试')
    sent = 0
    for source in p['targets']:
        if source['exposure_id'] in before:
            continue
        api('/api/behavior', token, {'userId': source['user_id'], 'itemId': source['item_id'],
            'action': 'CLICK', 'value': 0, 'scene': MARKER, 'exposureId': source['exposure_id'], 'ts': 0})
        sent += 1
    rows = data_verify(p)
    print(json.dumps({'DATA_VERIFY': 'PASS', 'sent': sent, 'ownedClicks': len(rows)}, ensure_ascii=False))


def report():
    p = plan()
    data_verify(p)
    if (STATE / 'report.json').exists():
        published = sql("SELECT count(*) FROM eval_report WHERE name=:'name';",
                        {'name': load('report.json')['fileName']})
        if published:
            print('已有报表记录；执行 verify 核对，不重复生成。')
            verify()
            return
    jar = Path(os.environ.get('DEMO_OFFLINE_JAR', ROOT / 'recsys-offline/target/recsys-offline-1.0.0-SNAPSHOT.jar')).resolve()
    if not jar.is_file():
        raise RuntimeError('离线可执行 JAR 不存在；先运行 run-ab-report.sh 构建')
    directory = STATE / 'reports'
    directory.mkdir(exist_ok=True)
    _, runtime = preflight()
    source = json.loads(command(['docker', 'inspect', CONSOLE]))[0]
    postgres = json.loads(command(['docker', 'inspect', PG]))[0]
    network = next(n for n in source['NetworkSettings']['Networks'] if n in
                   postgres['NetworkSettings']['Networks'])
    image = json.loads(command(['docker', 'inspect', 'recsys-gateway']))[0]['Image']
    env = os.environ.copy()
    env.update({k: v for k, v in runtime.items() if k.startswith(('PG_', 'REDIS_'))})
    pg_env = dict(v.split('=', 1) for v in postgres['Config']['Env'])
    env.update({'PG_PORT': runtime.get('PG_PORT', '5432'), 'PG_DB': DB, 'PG_USER': USER})
    env['PG_PASSWORD'] = runtime.get('PG_PASSWORD') or pg_env.get('POSTGRES_PASSWORD', '')
    if not env['PG_PASSWORD']:
        raise RuntimeError('未取得当前数据库凭据，不写入默认密码')
    # 复用已构建JAR的类与依赖，窄启动器不扫描Web安全链，不替代正式报表实现。
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    classpath = STATE / ('offline-classpath-' + digest[:12])
    classpath.mkdir(exist_ok=True)
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                continue
            relative = Path(entry.filename).relative_to('BOOT-INF')
            if '..' in relative.parts:
                raise RuntimeError('JAR路径不合法')
            destination = classpath / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists() or destination.stat().st_size != entry.file_size:
                destination.write_bytes(archive.read(entry))
    runner = classpath / 'runner'
    runner.mkdir(exist_ok=True)
    subprocess.run(['javac', '--release', '21', '-proc:none', '-cp', str(classpath / 'classes') + os.pathsep + str(classpath / 'lib/*'),
                    '-d', str(runner), str(ROOT / 'test-data/AbBoardReportRunner.java')], check=True)
    args = ['docker', 'run', '--rm', '--network', network, '--name', 'recsys-ab-board-demo-job',
            '--mount', f'type=bind,src={classpath.resolve()},dst=/opt/job,readonly',
            '--mount', f'type=bind,src={directory.resolve()},dst=/work', '--workdir', '/work']
    for key in ('PG_HOST', 'PG_PORT', 'PG_DB', 'PG_USER', 'PG_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD'):
        if key in env:
            args += ['-e', key]
    args += ['--entrypoint', 'java', image, '-cp', '/opt/job/runner:/opt/job/classes:/opt/job/lib/*', 'AbBoardReportRunner']
    # 中断后先检查输出；生成文件已存在时直接续发布，不重复统计生成第二份。
    generated = list((directory / 'eval').glob('ab-report-*.csv'))
    if not generated:
        with (STATE / 'ab-report.log').open('w') as log:
            subprocess.run(args + ['ab-report'], env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        generated = list((directory / 'eval').glob('ab-report-*.csv'))
    if len(generated) != 1:
        raise RuntimeError('输出文件数量不符，先核对已有运行结果')
    path = generated[0]
    if not path.name.startswith('ab-report-DEMO-'):
        renamed = path.with_name(path.name.replace('ab-report-', 'ab-report-DEMO-', 1))
        path.rename(renamed)
        path = renamed
    info = {'fileName': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'jarSha256': digest, 'syntheticClicks': len(p['targets']), 'launcher': 'AbBoardReportRunner (real production jobs/mappers)'}
    save('report-pending.json', info)
    with (STATE / 'publish-reports.log').open('w') as log:
        subprocess.run(args + ['publish-reports'], env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    save('report.json', info)
    verify()


def verify():
    p = plan()
    data_verify(p)
    result = {'DATA_VERIFY': 'PASS', 'ownedClicks': len(p['targets']), 'APP_VERIFY': 'SKIPPED_NO_REPORT'}
    if (STATE / 'report.json').exists():
        info = load('report.json')
        token = login()
        index = api('/api/console/report/index', token)
        if not any(x['fileName'] == info['fileName'] for x in index):
            raise RuntimeError('BFF 报表索引未出现本次报表')
        table = api('/api/console/report/file?' + urllib.parse.urlencode({'name': info['fileName']}), token)
        rows = [dict(zip(table['columns'], row)) for row in table['rows']]
        expected = {b['bucket']: b['demoClicks'] for b in p['buckets']}
        actual = {r['bucket']: int(r['clicks']) for r in rows}
        if not all(actual.get(bucket, 0) >= number for bucket, number in expected.items()):
            raise RuntimeError('报表点击汇总与已写入数据不符')
        if COHORT:
            for bucket in p['buckets']:
                row = next(r for r in rows if r['bucket'] == bucket['bucket'])
                if (int(row['impressions']) < bucket['impressions'] + 20
                        or int(row['clicks']) < bucket['clicks'] + bucket['demoClicks']
                        or int(row['users']) < bucket['users'] + 1):
                    raise RuntimeError('新增用户/曝光/点击未完整进入报表')
        result.update({'APP_VERIFY': 'PASS', 'fileName': info['fileName'], 'reportRows': rows})
    save('verification.json', result)
    print(json.dumps(result, ensure_ascii=False))


def cleanup(apply):
    """默认只预览；删除时严格匹配清单与演示 scene，不处理其他点击或旧报表。"""
    if COHORT:
        return ab_board_cohort.cleanup(sys.modules[__name__], apply)
    p = plan()
    rows = clicks(p)
    own = [r for r in rows if r['scene'] == MARKER]
    info = (load('report.json') if (STATE / 'report.json').exists() else
            load('report-pending.json') if (STATE / 'report-pending.json').exists() else None)
    if not apply:
        print(json.dumps({'mode': 'PREVIEW_ONLY', 'ownedClicks': len(own), 'report': info}, ensure_ascii=False))
        return
    expected = {x['exposure_id']: x for x in p['targets']}
    for row in own:
        if any(row[k] != expected[row['exposure_id']][k] for k in ('user_id', 'item_id', 'bucket')):
            raise RuntimeError('待清理点击与保存的原归属不符')
    name = info['fileName'] if info else ''
    result = sql("""
        WITH deleted_clicks AS (
          DELETE FROM user_behavior WHERE action='CLICK' AND scene=:'marker'
          AND id IN(SELECT jsonb_array_elements_text(:'ids'::jsonb)::bigint) RETURNING id
        ), deleted_reports AS (
          DELETE FROM eval_report WHERE category='ab-report' AND name=:'name'
          AND name LIKE 'ab-report-DEMO-%' RETURNING id
        ) SELECT json_build_object('deletedClicks',(SELECT count(*) FROM deleted_clicks),
                                   'deletedReports',(SELECT count(*) FROM deleted_reports));
    """, {'marker': MARKER, 'ids': json.dumps([r['id'] for r in own]), 'name': name}, write=True)
    save('cleanup-result.json', result)
    print(json.dumps(result))


def main():
    global STATE, MARKER, COHORT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'verify', 'report', 'cleanup'])
    parser.add_argument('--scenario', choices=['existing-clicks', 'more-users'], default='existing-clicks')
    parser.add_argument('--apply', action='store_true', help='仅 cleanup：执行清单限定的删除')
    args = parser.parse_args()
    if args.scenario == 'more-users':
        COHORT = True
        STATE = ROOT / '.dev/test-data/ab-board-v2'
        MARKER = 'demo-ab-board-v2'
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    STATE.chmod(0o700)
    # 本机清单、HTTP写入和报表发布共用互斥锁，避免两个工具进程交错操作。
    lock = (STATE / 'operation.lock').open('a+')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    if args.action == 'cleanup':
        cleanup(args.apply)
    else:
        {'init': init, 'verify': verify, 'report': report}[args.action]()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'{type(error).__name__}: {error}', file=sys.stderr)
        sys.exit(1)
