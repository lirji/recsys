package com.recsys.recengine.exposure;

import com.recsys.common.exposure.NaturalQualification;
import com.recsys.common.exposure.NaturalReceipt;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Instant;
import java.util.*;

/** engine 自有自然交付模型；内部 long 与外部十进制字符串明确分开。 */
public final class NaturalDelivery {
    private NaturalDelivery() {}
    public enum State { PREPARING, COMPLETE }
    public enum ModelLineage { UNVERIFIED }
    /** 原请求不可变，不接受终端指定 bucket/slot/policy 或资格身份。 */
    public record Request(UUID operationId, String subject, long userId, NaturalQualification.Kind kind, int size, String scene, String query) {
        public Request {
            Objects.requireNonNull(operationId); NaturalReceipt.validateSubject(subject); Objects.requireNonNull(kind);
            if (userId <= 0 || size < 1 || size > NaturalQualification.MAX_SLOTS || scene == null || scene.isBlank() || scene.length() > 100
                    || (kind == NaturalQualification.Kind.SEARCH ? query == null || query.isBlank() || query.length() > 500 : query != null)) {
                throw new IllegalArgumentException("自然交付请求非法");
            }
        }
        @Override public String toString() { return "NaturalDelivery.Request[operationId=" + operationId + "]"; }
    }
    /** 完整响应在提交前整体冻结；查询只返回该快照。 */
    public record Operation(Request request, NaturalQualification.Policy policy, State state, long fence, View result) {}
    public record View(String operationId, NaturalQualification.Traffic trafficClass, State state, String userId,
                       NaturalQualification.Kind kind, int size, String scene, String query, NaturalQualification.Policy policy,
                       String bucket, String traceId, ModelLineage modelLineage, List<Entry> items) {
        public View { items = List.copyOf(items); }
        @Override public String toString() { return "NaturalDelivery.View[operationId=" + operationId + ",state=" + state + "]"; }
    }
    /** 文本快照实际用于渲染；不依赖稍后变化的内容详情接口证明当时交付。 */
    public record Content(String title, String category, String description) {
        public Content {
            if (title == null || title.isBlank() || title.length() > 2000) throw new IllegalArgumentException("自然内容标题缺失或过长");
            category = category == null ? "" : category; description = description == null ? "" : description;
            if (category.length() > 200 || description.length() > 16000) throw new IllegalArgumentException("自然内容快照过长");
        }
        /** 固定字段序与编码，避免展示内容改变却继续使用旧摘要。 */
        public String hash() {
            try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
                out.writeUTF(title); out.writeUTF(category); out.writeUTF(description); out.flush();
                return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("自然内容摘要不可用", ex); }
        }
    }
    public record Entry(int slot, String itemId, String title, String category, String description, double score,
                        List<String> recallFrom, String reason, String qualificationId, String qualificationToken,
                        Instant issuedAt, Instant expiresAt, String contentHash) {
        public Entry {
            if (!Double.isFinite(score) || recallFrom == null || recallFrom.size() > 50 || reason != null && reason.length() > 4000) {
                throw new IllegalArgumentException("自然排序解释非法");
            }
            recallFrom = List.copyOf(recallFrom);
            if (recallFrom.stream().anyMatch(s -> s.length() > 100)) throw new IllegalArgumentException("自然召回标签过长");
        }
        @Override public String toString() { return "NaturalDelivery.Entry[qualificationId=" + qualificationId + ",slot=" + slot + "]"; }
    }
    /** 未完成响应仍回显原请求，前端不能把当前表单误当原操作。 */
    public static View view(Operation operation) {
        if (operation.result() != null) return operation.result();
        var r=operation.request();
        return new View(r.operationId().toString(),NaturalQualification.Traffic.SANDBOX,State.PREPARING,Long.toString(r.userId()),
                r.kind(),r.size(),r.scene(),r.query(),operation.policy(),"","",ModelLineage.UNVERIFIED,List.of());
    }
}
