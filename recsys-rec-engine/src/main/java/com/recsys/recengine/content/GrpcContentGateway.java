package com.recsys.recengine.content;

import com.recsys.content.Item;
import com.recsys.proto.ContentProtoMapper;
import com.recsys.proto.content.v1.BatchGetItemsReply;
import com.recsys.proto.content.v1.BatchGetItemsRequest;
import com.recsys.proto.content.v1.ContentServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
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

    @GrpcClient("content")
    private ContentServiceGrpc.ContentServiceBlockingStub stub;

    @Override
    public Map<Long, Item> findByIds(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        BatchGetItemsReply reply = stub.batchGetItems(
                BatchGetItemsRequest.newBuilder().addAllItemIds(itemIds).build());
        return ContentProtoMapper.toItemMap(reply.getItemsList());
    }
}
