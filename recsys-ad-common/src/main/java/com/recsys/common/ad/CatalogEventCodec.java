package com.recsys.common.ad;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

import static com.recsys.common.ad.VersionedAdCatalogEvent.*;

/** 共享字段/键/原文字节校验；JSON绑定复用进程现有ObjectMapper，不给共享契约引入新依赖。 */
public final class CatalogEventCodec {
    public static final int MAX_BYTES=512*1024;
    public static final int MAX_BIDWORDS=1024;
    public static final int MAX_CREATIVES=256;
    /** 现有item/ad embedding物理列均为vector(768)，更换维度须另行治理。 */
    public static final int VECTOR_DIMENSION=768;
    private static final String STATUS_ACTIVE="active", REVIEW_APPROVED="approved";
    private static final Set<String> AD_STATUS=Set.of("active","paused");
    private static final Set<String> ROOT_STATUS=Set.of("active","paused","over_budget");
    private static final Set<String> REVIEW=Set.of("approved","pending_review","rejected");
    private static final Set<String> OPTIMIZATION=Set.of("CPC","OCPC","CPM","OCPM","CPA");
    private static final Set<String> MATCH=Set.of("EXACT","PHRASE","BROAD");
    private static final Set<String> BID_MODE=Set.of("CPC","oCPC","oCPM");
    private CatalogEventCodec() { }

    /** 事件身份与版本绑定，内容摘要只校验原文，不用于生成业务身份。 */
    public static String eventId(java.util.UUID source,long ad,long version) {
        require(source!=null && ad>0 && version>0);return source+":ad:"+ad+":"+version;
    }
    /** Kafka键保持十进制long精度，不经过浮点或浏览器数字。 */
    public static byte[] key(long adId){require(adId>0);return Long.toString(adId).getBytes(StandardCharsets.UTF_8);}

    /** JSON解码后验证字段，再与原Kafka键匹配；摘要始终基于收到的原字节。 */
    public static String verify(VersionedAdCatalogEvent event,byte[] key,byte[] originalPayload) {
        validate(event);require(Arrays.equals(key(event.adId()),key));return digest(originalPayload);
    }

    /** 不修正、排序或截断非法快照；源异常必须由producer保留为待核验状态。 */
    public static void validate(VersionedAdCatalogEvent e) {
        require(e!=null && e.schemaVersion()==SCHEMA_VERSION && e.sourceId()!=null && e.commandId()!=null);
        require(e.adId()>0 && e.advertiserId()>0 && e.aggregateVersion()>0 && e.advertiserVersion()>0);
        require(e.ownerShard()==e.advertiserId()%2 && TOPOLOGY.equals(e.topologyVersion()));
        require(eventId(e.sourceId(),e.adId(),e.aggregateVersion()).equals(e.eventId()));
        require(e.occurredAt()!=null && e.capturedAt()!=null && e.catalogComplete());
        require(e.bidwords()!=null && e.bidwords().size()<=MAX_BIDWORDS && e.creatives()!=null && e.creatives().size()<=MAX_CREATIVES);
        require(e.embedding()!=null && e.embedding().state()!=null && e.embedding().reason()!=null);
        if(e.lifecycle()==Lifecycle.DELETED) {
            require(e.eventType()==CatalogEventType.AD_DELETED && !e.servable() && e.ad()==null && e.advertiserStatus()==null);
            require(e.bidwords().isEmpty() && e.creatives().isEmpty() && text(e.deletionReason(),100,false));
            require(e.embedding().state()==EmbeddingState.NOT_APPLICABLE && e.embedding().reason()==EmbeddingReason.DELETED
                    && e.embedding().itemId()==null && e.embedding().vector()==null && e.embedding().model()==null);
            return;
        }
        require(e.lifecycle()==Lifecycle.PRESENT && e.eventType()==CatalogEventType.AD_SNAPSHOT && e.deletionReason()==null);
        Ad ad=e.ad();require(ad!=null && ad.itemId()>0 && text(ad.title(),300,true) && text(ad.landingUrl(),1000,true));
        require(Double.isFinite(ad.qualityScore()) && ad.qualityScore()>=0 && contains(AD_STATUS,ad.status())
                && contains(REVIEW,ad.reviewStatus()) && contains(ROOT_STATUS,e.advertiserStatus()) && contains(OPTIMIZATION,ad.optimizationType()));
        require(ad.targetCpa()==null || Double.isFinite(ad.targetCpa()) && ad.targetCpa()>=0);
        if(Set.of("OCPC","OCPM","CPA").contains(ad.optimizationType()))require(ad.targetCpa()!=null && ad.targetCpa()>0);
        require(ad.audienceId()==null || ad.audienceId()>0);
        require(e.servable()==servable(e.advertiserStatus(),ad.status(),ad.reviewStatus()));
        long previous=0;
        for(Bidword word:e.bidwords()) {
            require(word!=null && word.id()>previous && text(word.keyword(),200,false) && contains(MATCH,word.matchType())
                    && Double.isFinite(word.bid()) && word.bid()>0 && contains(BID_MODE,word.bidMode()));previous=word.id();
        }
        previous=0;
        for(Creative creative:e.creatives()) {
            require(creative!=null && creative.creativeId()>previous && text(creative.title(),300,true)
                    && text(creative.landingUrl(),1000,true) && contains(AD_STATUS,creative.status()) && text(creative.reviewStatus(),100,true));
            previous=creative.creativeId();
        }
        Embedding embedding=e.embedding();
        require(embedding.itemId()!=null && embedding.itemId()==ad.itemId() && embedding.observedAt()!=null);
        if(embedding.state()==EmbeddingState.PRESENT) {
            require(embedding.reason()==EmbeddingReason.NONE && text(embedding.model(),200,false)
                    && embedding.vector()!=null && embedding.vector().size()==VECTOR_DIMENSION);
            double norm=0;
            for(Double coordinate:embedding.vector()){require(coordinate!=null && Double.isFinite(coordinate));norm+=coordinate*coordinate;}
            require(Double.isFinite(norm) && norm>0);
        } else {
            require(embedding.state()==EmbeddingState.UNKNOWN && embedding.vector()==null && embedding.model()==null
                    && Set.of(EmbeddingReason.UNAVAILABLE,EmbeddingReason.MISSING,EmbeddingReason.INVALID).contains(embedding.reason()));
        }
    }

    /** 发布与解码共用同一可投放规则，保留原目录的精确大小写语义。 */
    public static boolean servable(String advertiserStatus,String adStatus,String reviewStatus) {
        return STATUS_ACTIVE.equals(advertiserStatus) && STATUS_ACTIVE.equals(adStatus) && REVIEW_APPROVED.equals(reviewStatus);
    }

    /** 消费者同版本比较本摘要；重放不得重序列化或重查源表后计算一个新摘要。 */
    public static String digest(byte[] original) {
        require(original!=null && original.length>0 && original.length<=MAX_BYTES);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("JVM缺少SHA-256",e);}
    }
    private static boolean text(String value,int max,boolean nullable){return value==null?nullable:value.length()<=max && (nullable || !value.isBlank());}
    private static boolean contains(Set<String> allowed,String value){return value!=null && allowed.contains(value);}
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException("非法版本化目录事件");}
}
