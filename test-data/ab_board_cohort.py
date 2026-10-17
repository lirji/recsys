"""额外四用户样本：固定清单、可重复的画像/曝光 fixture，点击仍走正式接口。"""
import json


def plan(kit):
    """写入前保存身份和每条曝光；中断恢复不另选用户或物品。"""
    target, _ = kit.preflight()
    if (kit.STATE / 'plan.json').exists():
        result = kit.load('plan.json')
        if result['target'] != target:
            raise RuntimeError('数据库身份改变，请核对清单')
        return result
    settings = json.loads((kit.ROOT / 'test-data/scenarios.json').read_text())['moreUsers']
    users = settings['userIds']
    quotas = settings['clicksByInitialExposureRank']
    if (len(users) != 4 or len(set(users)) != 4 or any(type(u) is not int or u <= 0 for u in users)
            or len(quotas) != 4 or any(type(q) is not int or not 0 < q <= 20 for q in quotas)
            or sum(quotas) != 20):
        raise RuntimeError('补充方案必须为4个用户、80曝光和20点击')
    occupied = kit.sql("""
        SELECT (SELECT count(*) FROM app_user WHERE user_id=ANY(:'users'::bigint[]))
             + (SELECT count(*) FROM user_embedding WHERE user_id=ANY(:'users'::bigint[]))
             + (SELECT count(*) FROM user_behavior WHERE user_id=ANY(:'users'::bigint[])
                OR scene=:'marker' OR exposure_id LIKE :'prefix');
    """, {'users': '{' + ','.join(map(str, users)) + '}', 'marker': kit.MARKER,
          'prefix': kit.MARKER + '-%'})
    if occupied:
        raise RuntimeError('预留演示用户/曝光已占用且无清单，拒绝覆盖')
    buckets = kit.sql("""
        SELECT json_agg(t ORDER BY impressions DESC,bucket) FROM (
          SELECT bucket,count(*) FILTER(WHERE action='IMPRESSION') AS impressions,
                 count(*) FILTER(WHERE action='CLICK') AS clicks,count(DISTINCT user_id) AS users
          FROM user_behavior WHERE bucket IS NOT NULL GROUP BY bucket
        ) t;
    """)
    items = kit.sql('SELECT json_agg(item_id) FROM (SELECT item_id FROM item ORDER BY item_id LIMIT 80) t;')
    if len(buckets or []) != 4 or len(items or []) != 80:
        raise RuntimeError('本场景要求4个现有桶和80个现有物品')
    exposures, clicks = [], []
    for i, (bucket, user, quota) in enumerate(zip(buckets, users, quotas)):
        bucket['demoClicks'] = quota
        for j in range(20):
            row = {'user_id': user, 'item_id': items[i * 20 + j], 'bucket': bucket['bucket'],
                   'scene': kit.MARKER, 'exposure_id': f'{kit.MARKER}-{user}-{j + 1:02}', 'position': j + 1}
            exposures.append(row)
            if j < quota:
                clicks.append(row)
    result = {'scenario': kit.MARKER, 'target': target, 'users': users, 'buckets': buckets,
              'profile': {'testDataScenario': kit.MARKER}, 'exposures': exposures, 'targets': clicks,
              'sourceRevision': kit.command(['git', 'rev-parse', 'HEAD'], cwd=kit.ROOT)}
    kit.save('plan.json', result)
    return result


def owned_rows(kit, p):
    """以清单曝光 ID 查所有动作，错误动作或外部占用也必须被发现。"""
    return kit.sql("""
        SELECT coalesce(json_agg(t),'[]'::json) FROM (
          SELECT id,user_id,item_id,action,bucket,scene,exposure_id,position,value,ts::text
          FROM user_behavior WHERE exposure_id IN (SELECT jsonb_array_elements_text(:'ids'::jsonb))
          OR scene=:'marker'
        ) t;
    """, {'ids': json.dumps([r['exposure_id'] for r in p['exposures']]), 'marker': kit.MARKER})


def verify_fixtures(kit, p, partial=False):
    """核对所有已存在行的归属；初始化/清理可接受缺行，但从不接受重复或改写。"""
    profiles = kit.sql("""
        SELECT coalesce(json_agg(t),'[]'::json) FROM (
          SELECT user_id,profile FROM app_user
          WHERE user_id IN (SELECT jsonb_array_elements_text(:'users'::jsonb)::bigint)
        ) t;
    """, {'users': json.dumps(p['users'])})
    if any(r['profile'] != p['profile'] for r in profiles) or (not partial and len(profiles) != 4):
        raise RuntimeError('演示用户画像缺失、已变更或存在身份冲突')
    expected = {('IMPRESSION', r['exposure_id']): r for r in p['exposures']}
    expected.update({('CLICK', r['exposure_id']): r for r in p['targets']})
    rows, seen = owned_rows(kit, p), set()
    for row in rows:
        key = (row['action'], row['exposure_id'])
        source = expected.get(key)
        if (not source or key in seen or row['value'] != 0
                or any(row[k] != source[k] for k in ('user_id', 'item_id', 'bucket', 'scene'))
                or (row['action'] == 'IMPRESSION' and row['position'] != source['position'])):
            raise RuntimeError('演示曝光/点击存在重复或与保存的归属不一致')
        seen.add(key)
    if not partial and sum(r['action'] == 'IMPRESSION' for r in rows) != 80:
        raise RuntimeError('演示曝光数量不足80')
    return rows, profiles


def seed(kit, p):
    """曝光没有唯一键，因此 fixture 用事务锁和固定 ID 清单去重；不改表结构。"""
    verify_fixtures(kit, p, partial=True)
    kit.sql("""
        DO $$ BEGIN PERFORM pg_advisory_xact_lock(20261017, 2); END $$;
        INSERT INTO app_user(user_id,profile)
          SELECT jsonb_array_elements_text(:'users'::jsonb)::bigint,:'profile'::jsonb
          ON CONFLICT(user_id) DO NOTHING;
        INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,position,exposure_id,ts)
          SELECT e.user_id,e.item_id,'IMPRESSION',0,e.scene,e.bucket,e.position,e.exposure_id,CURRENT_TIMESTAMP
          FROM jsonb_to_recordset(:'exposures'::jsonb)
            AS e(user_id bigint,item_id bigint,scene text,bucket text,position int,exposure_id text)
          JOIN app_user u ON u.user_id=e.user_id AND u.profile=:'profile'::jsonb
          JOIN item i ON i.item_id=e.item_id
          WHERE NOT EXISTS(SELECT 1 FROM user_behavior b WHERE b.exposure_id=e.exposure_id);
    """, {'users': json.dumps(p['users']), 'profile': json.dumps(p['profile']),
          'exposures': json.dumps(p['exposures'])}, write=True)
    rows, _ = verify_fixtures(kit, p)
    kit.save('owned-impressions.json', [r for r in rows if r['action'] == 'IMPRESSION'])


def cleanup(kit, apply):
    """精确删除本批次；用户有后续行为/向量或画像改变时保留，不碰上一批。"""
    p = plan(kit)
    rows, profiles = verify_fixtures(kit, p, partial=True)
    info = next((kit.load(f) for f in ('report.json', 'report-pending.json') if (kit.STATE / f).exists()), None)
    if not apply:
        print(json.dumps({'mode': 'PREVIEW_ONLY', 'ownedImpressions': sum(r['action'] == 'IMPRESSION' for r in rows),
                          'ownedClicks': sum(r['action'] == 'CLICK' for r in rows), 'ownedUsers': len(profiles),
                          'report': info}, ensure_ascii=False))
        return
    result = kit.sql("""
        DO $$ BEGIN PERFORM pg_advisory_xact_lock(20261017, 2); END $$;
        WITH deleted_behavior AS (
          DELETE FROM user_behavior WHERE scene=:'marker' AND action IN ('IMPRESSION','CLICK')
          AND id IN (SELECT jsonb_array_elements_text(:'ids'::jsonb)::bigint) RETURNING id
        ), deleted_reports AS (
          DELETE FROM eval_report WHERE category='ab-report' AND name=:'name'
          AND name LIKE 'ab-report-DEMO-%' RETURNING id
        ), deleted_users AS (
          DELETE FROM app_user u WHERE profile=:'profile'::jsonb
          AND user_id IN (SELECT jsonb_array_elements_text(:'users'::jsonb)::bigint)
          AND NOT EXISTS(SELECT 1 FROM user_embedding v WHERE v.user_id=u.user_id)
          AND NOT EXISTS(SELECT 1 FROM user_behavior b WHERE b.user_id=u.user_id
            AND b.id NOT IN (SELECT jsonb_array_elements_text(:'ids'::jsonb)::bigint)) RETURNING user_id
        ) SELECT json_build_object('deletedBehavior',(SELECT count(*) FROM deleted_behavior),
          'deletedUsers',(SELECT count(*) FROM deleted_users),'deletedReports',(SELECT count(*) FROM deleted_reports));
    """, {'marker': kit.MARKER, 'ids': json.dumps([r['id'] for r in rows]),
          'users': json.dumps(p['users']), 'profile': json.dumps(p['profile']),
          'name': info['fileName'] if info else ''}, write=True)
    kit.save('cleanup-result.json', result)
    print(json.dumps(result))
