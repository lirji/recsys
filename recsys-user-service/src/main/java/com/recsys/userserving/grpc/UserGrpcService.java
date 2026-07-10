package com.recsys.userserving.grpc;

import com.recsys.proto.user.v1.Ack;
import com.recsys.proto.user.v1.Interests;
import com.recsys.proto.user.v1.UpdateInterestsRequest;
import com.recsys.proto.user.v1.UserId;
import com.recsys.proto.user.v1.UserProfileServiceGrpc;
import com.recsys.user.UserProfileService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;

/**
 * 用户画像 gRPC 服务端(P3):把用户领域库 {@link UserProfileService} 暴露为 GetInterests/UpdateInterests。
 * 边界只有 long + repeated string,无领域 record,直接转换。
 */
@GrpcService
public class UserGrpcService extends UserProfileServiceGrpc.UserProfileServiceImplBase {

    private final UserProfileService userProfile;

    public UserGrpcService(UserProfileService userProfile) {
        this.userProfile = userProfile;
    }

    @Override
    public void getInterests(UserId req, StreamObserver<Interests> obs) {
        List<String> cats = userProfile.getInterests(req.getUserId());
        obs.onNext(Interests.newBuilder().addAllCategories(cats).build());
        obs.onCompleted();
    }

    @Override
    public void updateInterests(UpdateInterestsRequest req, StreamObserver<Ack> obs) {
        userProfile.updateInterests(req.getUserId(), req.getCategoriesList());
        obs.onNext(Ack.newBuilder().setOk(true).build());
        obs.onCompleted();
    }
}
