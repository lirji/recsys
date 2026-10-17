#!/usr/bin/env python3
"""补充已有演示用户的兴趣画像，记录真实推荐前后对比；默认清理仅预览。"""
import argparse
import fcntl
import importlib.util
import json
from pathlib import Path
import sys
import urllib.parse

spec = importlib.util.spec_from_file_location('ab_board_demo', Path(__file__).with_name('ab-board-demo.py'))
kit = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = kit
spec.loader.exec_module(kit)
kit.STATE = kit.ROOT / '.dev/test-data/user-interests-v1'
SCENE = 'demo-user-interests-v1'
COHORT = kit.ROOT / '.dev/test-data/ab-board-v2'


def profiles(users):
    return kit.sql("""
        SELECT coalesce(json_agg(t ORDER BY user_id),'[]'::json) FROM (
          SELECT user_id,profile FROM app_user
          WHERE user_id IN (SELECT jsonb_array_elements_text(:'ids'::jsonb)::bigint)
        ) t;
    """, {'ids': json.dumps(users)})


def plan():
    """只接续已有四个演示用户，保存原画像以便精准恢复。"""
    target, _ = kit.preflight()
    if (kit.STATE / 'plan.json').exists():
        p = kit.load('plan.json')
        if p['target'] != target:
            raise RuntimeError('数据库身份已变，不自动重做')
        return p
    cohort = json.loads((COHORT / 'plan.json').read_text())
    settings = json.loads((kit.ROOT / 'test-data/scenarios.json').read_text())['userInterests']
    if {r['userId'] for r in settings} != set(cohort['users']) or len(settings) != 4:
        raise RuntimeError('兴趣样本必须对应现有四个演示用户')
    before = profiles(cohort['users'])
    if before != sorted(kit.ab_board_cohort.user_profiles(cohort), key=lambda r: r['user_id']):
        raise RuntimeError('用户画像与原清单不一致')
    available = kit.sql('SELECT json_agg(DISTINCT category) FROM item;')
    for s in settings:
        if len(s['categories']) != 2 or any(c not in available for c in s['categories']):
            raise RuntimeError('偏好必须来自当前物品库真实类目')
    if kit.sql("SELECT count(*) FROM user_behavior WHERE scene=:'scene';", {'scene': SCENE}):
        raise RuntimeError('场景已存在但清单缺失，先恢复清单')
    p = {'target': target, 'users': settings, 'before': before,
         'after': [{'user_id': r['user_id'], 'profile': r['profile'] | {'categories': next(
             s['categories'] for s in settings if s['userId'] == r['user_id'])}} for r in before]}
    kit.save('plan.json', p)
    return p


def sync_cohort_profile(row):
    """每个已确认更新同步原清单，防止后续初始化抹掉兴趣或把合法更新当成污染。"""
    path = COHORT / 'plan.json'
    p = json.loads(path.read_text())
    p.setdefault('profiles', {})[str(row['user_id'])] = row['profile']
    tmp = path.with_suffix('.json.tmp')
    tmp.write_text(json.dumps(p, ensure_ascii=False, indent=2) + '\n')
    tmp.chmod(0o600)
    tmp.replace(path)


def recommend(token, uid, phase):
    """推荐GET也会产生曝光；固定次数并记请求状态，响应未知时不能直接重发。"""
    name = f'{phase}-{uid}.json'
    if (kit.STATE / name).exists():
        record = kit.load(name)
        if record['status'] != 'COMPLETE':
            raise RuntimeError(f'{name} 推荐结果未知，先核对数据库和请求日志')
        return record['response']
    query = {'userId': uid, 'size': 5, 'scene': SCENE, 'explain': 'true'}
    kit.save(name, {'status': 'REQUESTED', 'query': query})
    response = kit.api('/api/recommend?' + urllib.parse.urlencode(query), token)
    kit.save(name, {'status': 'COMPLETE', 'query': query, 'response': response})
    return response


def init():
    p, token = plan(), kit.login()
    # 先取所有用户的原推荐，再设置画像，避免部分成功后失去原始对照。
    for s in p['users']:
        recommend(token, s['userId'], 'before')
    writes = 0
    current = {r['user_id']: r for r in profiles([s['userId'] for s in p['users']])}
    for old, new in zip(p['before'], p['after']):
        uid = new['user_id']
        if current.get(uid) not in (old, new):
            raise RuntimeError('画像出现清单外变化，拒绝覆盖')
        if current[uid] != new:
            kit.api(f'/api/user/{uid}/interests', token, new['profile']['categories'])
            if profiles([uid]) != [new]:
                raise RuntimeError('画像写入后读回不一致')
            writes += 1
        sync_cohort_profile(new)
    for s in p['users']:
        recommend(token, s['userId'], 'after')
    kit.save('init-result.json', {'profileWrites': writes})
    verify()


def exposure_rows():
    return kit.sql("""
        SELECT coalesce(json_agg(t ORDER BY id),'[]'::json) FROM (
          SELECT id,user_id,item_id,action,scene,bucket,exposure_id FROM user_behavior WHERE scene=:'scene'
        ) t;
    """, {'scene': SCENE})


def verify():
    """画像DB、兴趣API、用户360和真实TAG候选均须匹配，不靠返回200断言成功。"""
    p, token = plan(), kit.login()
    if profiles([s['userId'] for s in p['users']]) != p['after']:
        raise RuntimeError('画像落库不一致')
    summary, exposures = [], {}
    for s in p['users']:
        uid = s['userId']
        interests = kit.api(f'/api/user/{uid}/interests', token)
        view = kit.api(f'/api/console/user/{uid}', token)
        if (interests['categories'] != s['categories'] or view.get('errors', {}).get('profile')
                or json.loads(view['profileJson'])['categories'] != s['categories']):
            raise RuntimeError('兴趣API/用户360未显示目标偏好')
        counts = {}
        for phase in ('before', 'after'):
            # verify只读取已保存的真实响应，不隐式再发会增加曝光的推荐请求。
            rec = kit.load(f'{phase}-{uid}.json')['response']
            counts[phase] = next((c['rawCount'] for c in (rec.get('explain') or {}).get('channelRecall', [])
                                  if c['channel'] == 'TAG'), 0)
            if len(rec['items']) != 5:
                raise RuntimeError('推荐未返回预期的5条结果')
            for item in rec['items']:
                eid = item.get('exposureId')
                if not eid or eid in exposures:
                    raise RuntimeError('曝光标识缺失或重复')
                exposures[eid] = (uid, item['itemId'])
        if counts['after'] <= counts['before']:
            raise RuntimeError(f'用户{uid}设置兴趣后TAG候选未增加：{counts}')
        tagged = [item for item in rec['items'] if 'TAG' in item['recallFrom']]
        matching = kit.sql("""SELECT count(*) FROM item
            WHERE item_id IN (SELECT jsonb_array_elements_text(:'ids'::jsonb)::bigint)
            AND category IN (SELECT jsonb_array_elements_text(:'categories'::jsonb));""",
            {'ids': json.dumps([i['itemId'] for i in tagged]), 'categories': json.dumps(s['categories'])})
        if not tagged or matching != len(tagged):
            raise RuntimeError('最终推荐中的TAG物品未匹配该用户兴趣')
        summary.append(s | {'tagBefore': counts['before'], 'tagAfter': counts['after'],
                            'tagInTop5': len(tagged)})
    rows = exposure_rows()
    if len(rows) != 40 or any(r['action'] != 'IMPRESSION' or exposures.get(r['exposure_id']) !=
                              (r['user_id'], r['item_id']) for r in rows):
        raise RuntimeError('异步曝光尚未完整落库或与推荐结果不一致；稍后仅重跑verify')
    kit.save('owned-exposures.json', rows)
    result = {'DATA_VERIFY': 'PASS', 'APP_VERIFY': 'PASS', 'users': summary, 'newExposures': len(rows)}
    kit.save('verification.json', result)
    print(json.dumps(result, ensure_ascii=False))


def cleanup(apply):
    """恢复原画像并仅清理本场景曝光；默认预览，已有后续点击时停止删除。"""
    p = plan()
    rows = exposure_rows()
    expected = kit.load('owned-exposures.json') if (kit.STATE / 'owned-exposures.json').exists() else []
    if rows != expected or profiles([s['userId'] for s in p['users']]) != p['after']:
        raise RuntimeError('当前画像/曝光与保存结果不一致，不能清理')
    if not apply:
        print(json.dumps({'mode': 'PREVIEW_ONLY', 'restoreProfiles': 4, 'ownedExposures': len(rows)}))
        return
    dependent = kit.sql("""SELECT count(*) FROM user_behavior WHERE action<>'IMPRESSION'
        AND exposure_id IN (SELECT jsonb_array_elements_text(:'ids'::jsonb));""",
        {'ids': json.dumps([r['exposure_id'] for r in rows])})
    if dependent:
        raise RuntimeError('曝光已有后续行为，保留画像与曝光')
    result = kit.sql("""
        WITH restored AS (
          UPDATE app_user u SET profile=b.profile,updated_at=CURRENT_TIMESTAMP
          FROM jsonb_to_recordset(:'before'::jsonb) AS b(user_id bigint,profile jsonb),
               jsonb_to_recordset(:'after'::jsonb) AS a(user_id bigint,profile jsonb)
          WHERE u.user_id=b.user_id AND a.user_id=u.user_id AND u.profile=a.profile RETURNING u.user_id
        ), removed AS (
          DELETE FROM user_behavior WHERE scene=:'scene' AND action='IMPRESSION'
          AND id IN (SELECT jsonb_array_elements_text(:'ids'::jsonb)::bigint) RETURNING id
        ) SELECT json_build_object('restoredProfiles',(SELECT count(*) FROM restored),
                                   'removedExposures',(SELECT count(*) FROM removed));
    """, {'before': json.dumps(p['before']), 'after': json.dumps(p['after']), 'scene': SCENE,
          'ids': json.dumps([r['id'] for r in rows])}, write=True)
    for row in p['before']:
        sync_cohort_profile(row)
    kit.save('cleanup-result.json', result)
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'verify', 'cleanup'])
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    kit.STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    # 与原样本共用锁，避免原init/cleanup和画像扩充交错覆盖清单。
    with (COHORT / 'operation.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == 'cleanup':
            cleanup(args.apply)
        else:
            {'init': init, 'verify': verify}[args.action]()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'{type(error).__name__}: {error}', file=sys.stderr)
        sys.exit(1)
