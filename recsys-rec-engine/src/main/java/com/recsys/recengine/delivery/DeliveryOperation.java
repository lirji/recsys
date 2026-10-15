package com.recsys.recengine.delivery;

import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.Candidate;
import com.recsys.common.ad.delivery.SandboxDelivery.Prepared;
import java.util.List;
import java.util.UUID;

/** engine 整次交付聚合，展示顺序与子操作一旦冻结便不可替换。 */
public record DeliveryOperation(Request request, String bucket, double reserve, State state,
                                long fence, List<Entry> plan) {
    public enum State { PREPARING, COMPLETE }

    /** 客户端只选择实验请求，不得提交报价、资格、候选或来源。 */
    public record Request(UUID operationId, String subject, long userId, String query, int slots, String scene) {
        public Request {
            SandboxDelivery.requireIdentity(operationId, subject, userId);
            SandboxDelivery.requireSlot(slots);
            if (query == null || query.isBlank() || query.length() > 500 || scene == null || scene.isBlank() || scene.length() > 100) {
                throw new IllegalArgumentException("query 须为 1～500 字符，scene 须为 1～100 字符");
            }
        }
    }
    /** result 为空表示尚未确认，不能展示成已准备好。 */
    public record Entry(int slot, Candidate candidate, Prepared result) {
        public Entry {
            SandboxDelivery.requireSlot(slot);
            if (candidate == null || (result != null && !candidate.childId().equals(result.childId()))) {
                throw new IllegalArgumentException("子操作与结果不匹配");
            }
        }
    }
}
