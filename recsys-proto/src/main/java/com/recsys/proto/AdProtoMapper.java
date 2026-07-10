package com.recsys.proto;

import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.query.CategoryScore;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.TermWeight;

import java.util.ArrayList;
import java.util.List;

/**
 * 边界防腐层(ACL):在 gRPC wire 类型(recsys-proto 生成)与领域 record
 * (recsys-common / recsys-ad-common)之间互转。rec-engine 与 recsys-ad-serving 两侧共用,
 * 使内部领域模型不泄漏到网络契约、契约演进不逼着领域模型跟着改。
 *
 * <p>约定:proto 用空值代替 null(protobuf3 无 null);float[] embedding 空 ⇒ 视作 null(不可用)。
 * AdChannel/bidType 以枚举名字符串承载,round-trip 无损。
 */
public final class AdProtoMapper {

    private AdProtoMapper() {
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---------------- StructuredQuery ----------------

    public static com.recsys.proto.ad.v1.StructuredQuery toProto(StructuredQuery sq) {
        com.recsys.proto.ad.v1.StructuredQuery.Builder b =
                com.recsys.proto.ad.v1.StructuredQuery.newBuilder();
        if (sq == null) {
            return b.build();
        }
        b.setRaw(nz(sq.raw())).setNormalized(nz(sq.normalized()));
        for (TermWeight t : sq.terms()) {
            b.addTerms(com.recsys.proto.ad.v1.TermWeight.newBuilder()
                    .setTerm(nz(t.term())).setWeight(t.weight()).build());
        }
        for (CategoryScore c : sq.intents()) {
            b.addIntents(com.recsys.proto.ad.v1.CategoryScore.newBuilder()
                    .setCategory(nz(c.category())).setScore(c.score()).build());
        }
        for (String r : sq.rewrites()) {
            b.addRewrites(nz(r));
        }
        if (sq.embedding() != null) {
            for (float f : sq.embedding()) {
                b.addEmbedding(f);
            }
        }
        return b.build();
    }

    public static StructuredQuery fromProto(com.recsys.proto.ad.v1.StructuredQuery p) {
        if (p == null) {
            return new StructuredQuery("", "", List.of(), List.of(), List.of(), null);
        }
        List<TermWeight> terms = new ArrayList<>(p.getTermsCount());
        for (com.recsys.proto.ad.v1.TermWeight t : p.getTermsList()) {
            terms.add(new TermWeight(t.getTerm(), t.getWeight()));
        }
        List<CategoryScore> intents = new ArrayList<>(p.getIntentsCount());
        for (com.recsys.proto.ad.v1.CategoryScore c : p.getIntentsList()) {
            intents.add(new CategoryScore(c.getCategory(), c.getScore()));
        }
        List<String> rewrites = new ArrayList<>(p.getRewritesList());
        float[] embedding = null;
        if (p.getEmbeddingCount() > 0) {
            embedding = new float[p.getEmbeddingCount()];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = p.getEmbedding(i);
            }
        }
        return new StructuredQuery(p.getRaw(), p.getNormalized(), terms, intents, rewrites, embedding);
    }

    // ---------------- SponsoredAd ----------------

    public static com.recsys.proto.ad.v1.SponsoredAd toProto(SponsoredAd a) {
        return com.recsys.proto.ad.v1.SponsoredAd.newBuilder()
                .setAdId(a.adId())
                .setItemId(a.itemId())
                .setAdvertiserId(a.advertiserId())
                .setBidwordId(a.bidwordId())
                .setTitle(nz(a.title()))
                .setChannel(a.channel() == null ? "" : a.channel().name())
                .setBid(a.bid())
                .setQuality(a.quality())
                .setRelevance(a.relevance())
                .setPctr(a.pctr())
                .setPctrCalibrated(a.pctrCalibrated())
                .setEcpm(a.ecpm())
                .setChargedPrice(a.chargedPrice())
                .setPosition(a.position())
                .setCreativeId(a.creativeId())
                .setBidType(nz(a.bidType()))
                .build();
    }

    public static SponsoredAd fromProto(com.recsys.proto.ad.v1.SponsoredAd p) {
        return new SponsoredAd(
                p.getAdId(),
                p.getItemId(),
                p.getAdvertiserId(),
                p.getBidwordId(),
                p.getTitle(),
                parseChannel(p.getChannel()),
                p.getBid(),
                p.getQuality(),
                p.getRelevance(),
                p.getPctr(),
                p.getPctrCalibrated(),
                p.getEcpm(),
                p.getChargedPrice(),
                p.getPosition(),
                p.getCreativeId(),
                p.getBidType());
    }

    public static List<SponsoredAd> fromProto(List<com.recsys.proto.ad.v1.SponsoredAd> ps) {
        List<SponsoredAd> out = new ArrayList<>(ps.size());
        for (com.recsys.proto.ad.v1.SponsoredAd p : ps) {
            out.add(fromProto(p));
        }
        return out;
    }

    /** channel 名 → AdChannel;空/未知返回 null(仅信息性字段,不影响计费/排序)。 */
    private static AdChannel parseChannel(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return AdChannel.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
