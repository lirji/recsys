package com.recsys.recengine.user;

import com.recsys.user.UserProfileService;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认(单体回退)实现:进程内直调 {@link UserProfileService}(recsys-user lib)。行为与拆分前等价、零网络跳。
 * {@code recsys.user.serving.mode} 缺省或 = {@code in-process} 时生效(绞杀者迁移的安全默认与回滚落点)。
 */
@Component
@ConditionalOnProperty(name = "recsys.user.serving.mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessUserGateway implements UserGateway {

    private final UserProfileService userProfile;

    public InProcessUserGateway(UserProfileService userProfile) {
        this.userProfile = userProfile;
    }

    /** 进程内查询同样区分真实空与不可读取，不能依赖RPC模式才暴露故障。 */
    @Override
    public List<String> getInterests(long userId) {
        try {
            return userProfile.getInterests(userId);
        } catch (RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.USER_INTEREST_QUERY_FAILED, failure);
        }
    }

    @Override
    public void updateInterests(long userId, List<String> categories) {
        userProfile.updateInterests(userId, categories);
    }
}
