package com.recsys.userserving.grpc;

import com.recsys.user.UserProfileService;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.user.v1.*;
import io.grpc.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实RPC保护读失败与写入未知的状态；失败不得生成兴趣已保存的ACK。 */
class UserGrpcErrorContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureNeverBecomesSuccessAndKeepsExistingStatus(boolean write) throws Exception {
        var owner = mock(UserProfileService.class);
        var server = ServerBuilder.forPort(0).addService(new UserGrpcService(owner)).build().start();
        var channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
            for (RuntimeException failure : new RuntimeException[]{new IllegalArgumentException("private SQL token"),
                    new IllegalStateException("private SQL token"), Status.UNAVAILABLE.withDescription("private upstream token").asRuntimeException()}) {
                doThrow(failure).when(owner).getInterests(1);
                doThrow(failure).when(owner).updateInterests(1, List.of("Drama"));
                var stub = UserProfileServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                var observed = assertThrows(StatusRuntimeException.class, () -> {
                    if (write) stub.updateInterests(UpdateInterestsRequest.newBuilder().setUserId(1).addCategories("Drama").build());
                    else stub.getInterests(UserId.newBuilder().setUserId(1).build());
                });
                assertEquals(Status.Code.UNKNOWN, observed.getStatus().getCode());
                var code = write ? ErrorCode.RPC_USER_WRITE_RESULT_UNKNOWN : ErrorCode.RPC_USER_QUERY_FAILED;
                assertEquals(code.code(), observed.getTrailers().get(GrpcErrors.ERROR_CODE));
                assertEquals(code.displayMessage(), observed.getStatus().getDescription());
                assertFalse(observed.getMessage().contains("private"));
            }
            doReturn(List.of("Drama")).when(owner).getInterests(1);
            doNothing().when(owner).updateInterests(1, List.of("Drama"));
            var stub = UserProfileServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
            assertEquals(List.of("Drama"), stub.getInterests(UserId.newBuilder().setUserId(1).build()).getCategoriesList());
            assertTrue(stub.updateInterests(UpdateInterestsRequest.newBuilder().setUserId(1).addCategories("Drama").build()).getOk());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
