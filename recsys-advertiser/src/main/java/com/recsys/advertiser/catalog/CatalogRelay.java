package com.recsys.advertiser.catalog;

import java.util.UUID;

/** 两个物理库由worker公平轮转调用；一次最多10条，未启动开关不会自动构造本对象。 */
public final class CatalogRelay {
    private final CatalogOutboxRepository outbox;
    private final CatalogKafkaPublisher publisher;
    private final UUID source;
    public CatalogRelay(CatalogOutboxRepository outbox,CatalogKafkaPublisher publisher,UUID source) {
        this.outbox=outbox;this.publisher=publisher;this.source=source;
    }
    /** 一轮发送有界；只有有效确认落库的条数才返回成功。 */
    public int advance(int shard) {
        int published=0;
        for(int candidate=0;candidate<10;candidate++) {
            var rows=outbox.claim(shard,source);if(rows.isEmpty())break;var row=rows.getFirst();
            try {
                var metadata=publisher.send(row);
                if(outbox.published(row,metadata.partition(),metadata.offset()))published++;
            } catch(Exception failure) {
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                var reason=failure instanceof CatalogProtocol.CatalogException c?c.failure():CatalogProtocol.Failure.RESULT_UNKNOWN;
                outbox.failed(row,reason);
                if(reason==CatalogProtocol.Failure.INCONSISTENT){outbox.stop(shard,reason);break;}
                if(Thread.currentThread().isInterrupted())break;
            }
        }
        return published;
    }
}
