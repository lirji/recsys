-- 17 · 业务表说明。
--
-- fresh init 时，各建表脚本已就近设置注释；本文件提供统一、可重复执行的补丁，
-- 供已有数据卷或拆分后的独立数据库补齐表注释。当前数据库中不存在的表会自动跳过。
--
-- 主库示例：
--   docker compose -f docker/docker-compose.yml exec -T postgres \
--     psql -U recsys -d recsys < recsys-offline/sql/17_table_comments.sql
-- 其他数据库（如 recsys_ds1、recsys_behavior、recsys_content、recsys_ad、recsys_vec）
-- 只需替换 -d 参数后重复执行。

DO $$
DECLARE
    table_comment RECORD;
BEGIN
    FOR table_comment IN
        SELECT *
        FROM (VALUES
            ('item',                 '物品内容主数据，供内容展示、召回与排序使用'),
            ('item_embedding',       '物品内容向量，用于语义相似度检索与向量召回'),
            ('app_user',             '应用用户及其结构化画像信息'),
            ('user_embedding',       '由用户历史正反馈聚合得到的用户偏好向量'),
            ('user_behavior',        '用户曝光、点击、点赞、播放和评分等行为事件日志'),
            ('advertiser',           '广告主账户、预算与投放状态'),
            ('ad',                   '广告投放单元及其关联物品、审核和出价配置'),
            ('ad_audience_seed',     '广告定向人群包的种子用户'),
            ('ad_contract',          '品牌广告的保量投放合约'),
            ('ad_creative',          '广告动态创意及创意级审核状态'),
            ('bidword',              '广告竞价关键词、匹配方式与出价'),
            ('ad_embedding',         '广告语义向量，用于查询与广告的相似度检索'),
            ('ad_event',             '广告曝光、点击、转化、计费与归因事件日志'),
            ('item_tower_embedding', '双塔召回模型生成的物品侧向量'),
            ('item_semantic_id',     '生成式召回模型生成的物品分层语义编码'),
            ('eval_report',          '离线评估与分析报表的结构化存储'),
            ('ad_servable',          '广告在线服务消费目录事件后生成的可服务广告副本'),
            ('behavior_log',         '供离线数据平台使用的用户行为事件读仓'),
            ('item_local',           '供在线召回与排序热路径使用的物品目录本地读模型'),
            ('ad_event_log',         '供离线分析使用的广告事件读仓')
        ) AS comments(table_name, description)
    LOOP
        IF to_regclass(format('%I.%I', 'public', table_comment.table_name)) IS NOT NULL THEN
            EXECUTE format(
                'COMMENT ON TABLE %I.%I IS %L',
                'public',
                table_comment.table_name,
                table_comment.description
            );
        END IF;
    END LOOP;
END
$$;
