package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.CatalogEventCodec;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static com.recsys.common.ad.VersionedAdCatalogEvent.*;
import static org.assertj.core.api.Assertions.*;

/** 共享wire的有意义反例：精确长ID、版本/摘要、删除与未知向量、创意审核和非法输入。 */
class CatalogSnapshotCodecTest {
    final CatalogSnapshotCodec codec=new CatalogSnapshotCodec(new ObjectMapper());
    final UUID source=UUID.fromString("00000000-0000-0000-0000-000000000016");
    final UUID command=UUID.fromString("00000000-0000-0000-0000-000000000017");
    final long adId=Long.MAX_VALUE-1;
    final Instant time=Instant.parse("2026-10-17T00:00:00Z");

    @Test void originalBytesAreDeterministicAndLongIdsAndUnknownEnrichmentRemainExact() {
        var event=snapshot("active","approved",unknown(10L),List.of(new Creative(7,"title","/creative","active","pending_review")));
        byte[] bytes=codec.encode(event);
        assertThat(codec.encode(event)).isEqualTo(bytes);
        var decoded=codec.decode(CatalogEventCodec.key(adId),bytes);
        assertThat(decoded).isEqualTo(event);assertThat(decoded.adId()).isEqualTo(adId);
        assertThat(decoded.creatives().getFirst().reviewStatus()).isEqualTo("pending_review");
        assertThat(decoded.embedding().state()).isEqualTo(EmbeddingState.UNKNOWN);
        assertThat(decoded.servable()).isTrue();
    }

    @Test void pausedAndRejectedArePresentWhileExplicitDeletionHasNoSourceBody() {
        for(var event:List.of(snapshot("paused","approved",unknown(10L),List.of()),snapshot("active","rejected",unknown(10L),List.of()))) {
            assertThat(codec.decode(CatalogEventCodec.key(adId),codec.encode(event)).lifecycle()).isEqualTo(Lifecycle.PRESENT);
            assertThat(event.servable()).isFalse();
        }
        var deleted=deleted();var decoded=codec.decode(CatalogEventCodec.key(adId),codec.encode(deleted));
        assertThat(decoded.eventType()).isEqualTo(CatalogEventType.AD_DELETED);assertThat(decoded.ad()).isNull();
        assertThat(decoded.embedding().state()).isEqualTo(EmbeddingState.NOT_APPLICABLE);
    }

    @Test void changedItemCannotCarryOldEmbeddingAndInvalidDimensionsAndNanAreRejected() {
        assertThatThrownBy(()->codec.encode(snapshot("active","approved",unknown(11L),List.of()))).isInstanceOf(IllegalArgumentException.class);
        var shortVector=new Embedding(EmbeddingState.PRESENT,10L,"model",List.of(1.0,0.0,0.0),time,EmbeddingReason.NONE);
        assertThatThrownBy(()->codec.encode(snapshot("active","approved",shortVector,List.of()))).isInstanceOf(IllegalArgumentException.class);
        var vector=new java.util.ArrayList<>(Collections.nCopies(CatalogEventCodec.VECTOR_DIMENSION,0.0));vector.set(0,1.0);
        var valid=new Embedding(EmbeddingState.PRESENT,10L,"model",vector,time,EmbeddingReason.NONE);
        assertThat(codec.decode(CatalogEventCodec.key(adId),codec.encode(snapshot("active","approved",valid,List.of()))).embedding()).isEqualTo(valid);
        vector.set(0,Double.NaN);
        assertThatThrownBy(()->codec.encode(snapshot("active","approved",new Embedding(EmbeddingState.PRESENT,10L,"model",vector,time,EmbeddingReason.NONE),List.of()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void wrongKeyUnknownVersionDuplicateJsonAndFractionalLongAreRejected() {
        String original=new String(codec.encode(snapshot("active","approved",unknown(10L),List.of())),java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->codec.decode(CatalogEventCodec.key(adId-1),original.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        for(String bad:List.of(original.replace("\"schemaVersion\":1","\"schemaVersion\":2"),
                original.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                original.replace("\"adId\":"+adId,"\"adId\":1.5"),original+" {}")) {
            assertThatThrownBy(()->codec.decode(CatalogEventCodec.key(adId),bad.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void sameVersionDifferentBytesRemainDetectablyDifferentAndByteLimitIsEnforced() {
        byte[] first=codec.encode(snapshot("active","approved",unknown(10L),List.of()));
        byte[] second=(new String(first,java.nio.charset.StandardCharsets.UTF_8)+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(codec.decode(CatalogEventCodec.key(adId),second)).isEqualTo(codec.decode(CatalogEventCodec.key(adId),first));
        assertThat(CatalogEventCodec.digest(first)).isNotEqualTo(CatalogEventCodec.digest(second));
        assertThatThrownBy(()->codec.decode(CatalogEventCodec.key(adId),new byte[CatalogEventCodec.MAX_BYTES+1])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateOrUnsortedChildrenAreNotSilentlySortedOrDeduplicated() {
        var one=new Creative(2,"title","/landing","active",null);
        var two=new Creative(1,"title","/landing","active","unrecognized-review");
        for(var children:List.of(List.of(one,one),List.of(one,two)))
            assertThatThrownBy(()->codec.encode(snapshot("active","approved",unknown(10L),children))).isInstanceOf(IllegalArgumentException.class);
        // 未知创意审核原样传递，不能因其未知而把整个广告误做删除。
        var valid=snapshot("active","approved",unknown(10L),List.of(two));
        assertThat(codec.decode(CatalogEventCodec.key(adId),codec.encode(valid)).creatives()).isEqualTo(List.of(two));
    }

    VersionedAdCatalogEvent snapshot(String status,String review,Embedding embedding,List<Creative> creatives) {
        return new VersionedAdCatalogEvent(1,CatalogEventType.AD_SNAPSHOT,source,1,TOPOLOGY,CatalogEventCodec.eventId(source,adId,2),
                adId,1,2,1,command,time,time,Lifecycle.PRESENT,"active".equals(status)&&"approved".equals(review),
                new Ad(10,"title","/landing",1,status,review,null,"CPC",null,null),"active",List.of(new Bidword(3,"word","EXACT",1,"CPC")),creatives,embedding,true,null);
    }
    Embedding unknown(Long item){return new Embedding(EmbeddingState.UNKNOWN,item,null,null,time,EmbeddingReason.UNAVAILABLE);}
    VersionedAdCatalogEvent deleted(){return new VersionedAdCatalogEvent(1,CatalogEventType.AD_DELETED,source,1,TOPOLOGY,
            CatalogEventCodec.eventId(source,adId,3),adId,1,3,1,command,time,time,Lifecycle.DELETED,false,null,null,List.of(),List.of(),
            new Embedding(EmbeddingState.NOT_APPLICABLE,null,null,null,time,EmbeddingReason.DELETED),true,"EXPLICIT_DELETE");}
}
