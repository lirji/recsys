package com.recsys.recengine.content;

import com.recsys.content.Item;
import com.recsys.proto.ContentProtoMapper;
import com.recsys.proto.content.v1.BatchGetItemsReply;
import com.recsys.proto.content.v1.BatchGetItemsRequest;
import com.recsys.proto.content.v1.ContentServiceGrpc;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 微服务实现:经 gRPC 调 {@code recsys-content-service}(内容在线服务独立进程)。
 * 边界用 {@link ContentProtoMapper}(ACL)做 record ↔ proto 互转。
 * {@code recsys.content.serving.mode=grpc} 时才装配。目标地址见 {@code grpc.client.content.*}
 * (默认 {@code static://localhost:9096};接 Nacos discovery 后可换 {@code discovery:///recsys-content-service})。
 * gRPC 异常向上抛(展示 hydrate 失败即整条推荐失败,由上层 fail-soft 兜底)。
 */
@Component
@ConditionalOnProperty(name = "recsys.content.serving.mode", havingValue = "grpc")
public class GrpcContentGateway implements ContentGateway {

    private static final Logger log = LoggerFactory.getLogger(GrpcContentGateway.class);

    @GrpcClient("content")
    private ContentServiceGrpc.ContentServiceBlockingStub stub;

    @Override
    @CircuitBreaker(name = "content-grpc", fallbackMethod = "findByIdsFallback")
    public Map<Long, Item> findByIds(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        BatchGetItemsReply reply = stub.batchGetItems(
                BatchGetItemsRequest.newBuilder().addAllItemIds(itemIds).build());
        return ContentProtoMapper.toItemMap(reply.getItemsList());
    }

    /**
     * hydrate 降级(P1):content-service 不可达/超时/熔断时返回空 map,推荐主链路照常返回(展示字段缺失但不 5xx)。
     * 上层编排对缺 Item 的候选 fail-soft(不因单次 hydrate 失败整条推荐失败)。
     */
    Map<Long, Item> findByIdsFallback(List<Long> itemIds, Throwable t) {
        log.warn("content gRPC hydrate 降级为空(候选展示字段缺失): {}", t.toString());
        return Map.of();
    }
}
