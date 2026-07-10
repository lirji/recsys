package com.recsys.advertiser;

import com.recsys.proto.ad.v1.AdEventStat;
import com.recsys.proto.ad.v1.AdEventStatsReply;
import com.recsys.proto.ad.v1.AdEventStatsRequest;
import com.recsys.proto.ad.v1.AdServingServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * gRPC 报表来源:经 {@code GetAdEventStats} 调 ad-serving 取 {@code ad_event} 聚合(#3 ad-serving 拆库后用)。
 * {@code recsys.ad.report.source=grpc} 时激活——ad-serving 拥有 ad_event、对外发布报表(Customer-Supplier),
 * advertiser 不再跨库直读。gRPC 异常向上抛(上层 fail-soft)。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.report.source", havingValue = "grpc")
public class GrpcAdReportReader implements AdReportReader {

    @GrpcClient("ad-serving")
    private AdServingServiceGrpc.AdServingServiceBlockingStub stub;

    @Override
    public Map<Long, Stats> statsByAds(Collection<Long> adIds) {
        Map<Long, Stats> out = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return out;
        }
        AdEventStatsReply reply = stub.getAdEventStats(
                AdEventStatsRequest.newBuilder().addAllAdId(adIds).build());
        for (AdEventStat s : reply.getStatsList()) {
            out.put(s.getAdId(), new Stats(
                    s.getImpressions(), s.getClicks(), s.getConversions(), s.getSpend()));
        }
        return out;
    }
}
