-- 原启动时防御性DDL；采样关闭时不执行，不改历史迁移。
CREATE TABLE IF NOT EXISTS ad_uplift_assignment(
    assignment_id TEXT PRIMARY KEY,request_id TEXT NOT NULL,user_id BIGINT NOT NULL,
    ad_id BIGINT NOT NULL,advertiser_id BIGINT NOT NULL,item_id BIGINT NOT NULL,
    treatment BOOLEAN NOT NULL,propensity DOUBLE PRECISION NOT NULL CHECK(propensity>0 AND propensity<1),
    pctr DOUBLE PRECISION NOT NULL,pcvr DOUBLE PRECISION NOT NULL,quality DOUBLE PRECISION NOT NULL,
    relevance DOUBLE PRECISION NOT NULL,bid DOUBLE PRECISION NOT NULL,ad_bucket TEXT,model_version TEXT,
    assigned_at TIMESTAMP NOT NULL,outcome_due_at TIMESTAMP NOT NULL,UNIQUE(request_id,ad_id));
COMMENT ON TABLE ad_uplift_assignment IS '广告末位展示与不展示的随机采样分配';
COMMENT ON COLUMN ad_uplift_assignment.assignment_id IS '采样分配标识，requestId和adId组合';
COMMENT ON COLUMN ad_uplift_assignment.request_id IS '推荐请求标识';
COMMENT ON COLUMN ad_uplift_assignment.user_id IS '用户标识';
COMMENT ON COLUMN ad_uplift_assignment.ad_id IS '广告标识';
COMMENT ON COLUMN ad_uplift_assignment.advertiser_id IS '广告主标识';
COMMENT ON COLUMN ad_uplift_assignment.item_id IS '内容标识';
COMMENT ON COLUMN ad_uplift_assignment.treatment IS '是否为展示实验组';
COMMENT ON COLUMN ad_uplift_assignment.propensity IS '展示实验组分配概率';
COMMENT ON COLUMN ad_uplift_assignment.pctr IS '采样时点击率估计';
COMMENT ON COLUMN ad_uplift_assignment.pcvr IS '采样时转化率估计';
COMMENT ON COLUMN ad_uplift_assignment.quality IS '采样时质量分';
COMMENT ON COLUMN ad_uplift_assignment.relevance IS '采样时相关性';
COMMENT ON COLUMN ad_uplift_assignment.bid IS '采样时出价';
COMMENT ON COLUMN ad_uplift_assignment.ad_bucket IS '广告实验分桶';
COMMENT ON COLUMN ad_uplift_assignment.model_version IS '增益估计模型版本';
COMMENT ON COLUMN ad_uplift_assignment.assigned_at IS '分配时间';
COMMENT ON COLUMN ad_uplift_assignment.outcome_due_at IS '结果观测窗口截止时间';
