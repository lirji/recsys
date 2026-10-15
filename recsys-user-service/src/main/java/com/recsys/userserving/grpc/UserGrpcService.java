package com.recsys.userserving.grpc;

import com.recsys.proto.user.v1.Ack;
import com.recsys.proto.user.v1.Interests;
import com.recsys.proto.user.v1.UpdateInterestsRequest;
import com.recsys.proto.user.v1.UserId;
import com.recsys.proto.user.v1.UserProfileServiceGrpc;
import com.recsys.user.UserProfileService;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.grpc.GrpcErrors;
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

    /** RPC边界失败与空兴趣分开，保留已有UNKNOWN以免改变客户端重试。 */
    @Override
    public void getInterests(UserId req, StreamObserver<Interests> obs) {
        GrpcErrors.respondWithUnknownOnFailure(obs, ErrorCode.RPC_USER_QUERY_FAILED, () -> {
            List<String> cats = userProfile.getInterests(req.getUserId());
            return Interests.newBuilder().addAllCategories(cats).build();
        });
    }

    /** 写入异常不能生成成功ACK；是否已生效须保留未知，不声称回滚或未落库。 */
    @Override
    public void updateInterests(UpdateInterestsRequest req, StreamObserver<Ack> obs) {
        GrpcErrors.respondWithUnknownOnFailure(obs, ErrorCode.RPC_USER_WRITE_RESULT_UNKNOWN, () -> {
            userProfile.updateInterests(req.getUserId(), req.getCategoriesList());
            return Ack.newBuilder().setOk(true).build();
        });
    }
}
