package com.recsys.contentserving.grpc;

import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.proto.ContentProtoMapper;
import com.recsys.proto.content.v1.BatchGetItemsReply;
import com.recsys.proto.content.v1.BatchGetItemsRequest;
import com.recsys.proto.content.v1.ContentServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.Map;

/**
 * 内容服务 gRPC 服务端(P2):把内容领域库 {@link ContentService} 暴露为 {@code BatchGetItems}。
 * 边界经 {@link ContentProtoMapper}(ACL)做 {@link Item} ↔ proto 互转,领域模型不泄漏到网络契约。
 */
@GrpcService
public class ContentGrpcService extends ContentServiceGrpc.ContentServiceImplBase {

    private final ContentService content;

    public ContentGrpcService(ContentService content) {
        this.content = content;
    }

    @Override
    public void batchGetItems(BatchGetItemsRequest req, StreamObserver<BatchGetItemsReply> obs) {
        Map<Long, Item> items = content.findByIds(req.getItemIdsList());
        BatchGetItemsReply.Builder rb = BatchGetItemsReply.newBuilder();
        for (Item it : items.values()) {
            rb.addItems(ContentProtoMapper.toProto(it));
        }
        obs.onNext(rb.build());
        obs.onCompleted();
    }
}
