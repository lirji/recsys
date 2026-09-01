-- A7:随机广告 opportunity 与独立转化事实。控制组不写 IMPRESSION，但仍可由 fact 获得 outcome。
CREATE TABLE IF NOT EXISTS ad_uplift_assignment (
    assignment_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    ad_id BIGINT NOT NULL,
    advertiser_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    treatment BOOLEAN NOT NULL,
    propensity DOUBLE PRECISION NOT NULL CHECK (propensity > 0 AND propensity < 1),
    pctr DOUBLE PRECISION NOT NULL,
    pcvr DOUBLE PRECISION NOT NULL,
    quality DOUBLE PRECISION NOT NULL,
    relevance DOUBLE PRECISION NOT NULL,
    bid DOUBLE PRECISION NOT NULL,
    ad_bucket TEXT,
    model_version TEXT,
    assigned_at TIMESTAMP NOT NULL DEFAULT now(),
    outcome_due_at TIMESTAMP NOT NULL,
    UNIQUE (request_id, ad_id)
);
COMMENT ON TABLE ad_uplift_assignment IS 'A7 随机 show/no-show opportunity；propensity=P(treatment)';
CREATE INDEX IF NOT EXISTS idx_ad_uplift_assignment_outcome
    ON ad_uplift_assignment (advertiser_id, user_id, assigned_at);

CREATE TABLE IF NOT EXISTS ad_conversion_fact (
    event_id TEXT PRIMARY KEY,
    advertiser_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    objective TEXT NOT NULL,
    conversion_value DOUBLE PRECISION NOT NULL DEFAULT 0,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE ad_conversion_fact IS '广告主独立转化事实；允许无 request/ad 归因，供 treatment/control 同口径观测';
CREATE INDEX IF NOT EXISTS idx_ad_conversion_fact_join
    ON ad_conversion_fact (advertiser_id, user_id, occurred_at);
