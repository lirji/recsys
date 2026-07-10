package com.recsys.recengine.user;

import com.recsys.proto.user.v1.Ack;
import com.recsys.proto.user.v1.Interests;
import com.recsys.proto.user.v1.UpdateInterestsRequest;
import com.recsys.proto.user.v1.UserId;
import com.recsys.proto.user.v1.UserProfileServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 微服务实现:经 gRPC 调 {@code recsys-user-service}(用户画像在线服务独立进程)。
 * {@code recsys.user.serving.mode=grpc} 时才装配。目标地址见 {@code grpc.client.user.*}
 * (默认 {@code static://localhost:9097};接 Nacos discovery 后可换 {@code discovery:///recsys-user-service})。
 */
@Component
@ConditionalOnProperty(name = "recsys.user.serving.mode", havingValue = "grpc")
public class GrpcUserGateway implements UserGateway {

    @GrpcClient("user")
    private UserProfileServiceGrpc.UserProfileServiceBlockingStub stub;

    @Override
    public List<String> getInterests(long userId) {
        Interests reply = stub.getInterests(UserId.newBuilder().setUserId(userId).build());
        return new ArrayList<>(reply.getCategoriesList());
    }

    @Override
    public void updateInterests(long userId, List<String> categories) {
        Ack ignored = stub.updateInterests(UpdateInterestsRequest.newBuilder()
                .setUserId(userId)
                .addAllCategories(categories == null ? List.of() : categories)
                .build());
    }
}
