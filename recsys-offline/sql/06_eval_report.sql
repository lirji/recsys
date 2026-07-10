-- Phase 1(前后端分离配套):离线报表落库,解耦控制台读取。
-- 背景:控制台后端 recsys-console 原先直接读 offline 的本地 eval/*.csv 文件(跨进程共享文件系统路径,
-- 只在同机/同挂载卷下成立)。改为:offline 作业 `publish-reports` 把 eval/*.csv 解析后写入本表,
-- recsys-console 从本表读取(表缺失/无数据时回退到 CSV 目录,保持优雅降级)。
CREATE TABLE IF NOT EXISTS eval_report (
    id           BIGSERIAL    PRIMARY KEY,
    category     VARCHAR(64)  NOT NULL,          -- eval / ab-report / ad-report / data-quality / ad-quality / other
    name         VARCHAR(256) NOT NULL UNIQUE,   -- 文件名(幂等键)
    ts           VARCHAR(32),                    -- 文件名里的时间戳段(yyyyMMdd-HHmmss)
    columns_json JSONB        NOT NULL,          -- 表头列名 JSON 数组
    rows_json    JSONB        NOT NULL,          -- 数据行 JSON(二维字符串数组)
    size_bytes   BIGINT       DEFAULT 0,
    created_at   TIMESTAMPTZ  DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_eval_report_cat_ts ON eval_report (category, ts DESC);
