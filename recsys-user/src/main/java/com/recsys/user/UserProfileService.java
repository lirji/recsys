package com.recsys.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.user.persistence.mapper.UserProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户画像服务:读写 {@code app_user.profile}(JSONB)。
 *
 * <p>当前聚焦冷启动 onboarding 的"偏好类目":用户首次进入时主动勾选感兴趣的类目,
 * 写入 {@code profile.categories} 后 TagRecaller 立即生效,把新用户快速带出冷启动区。
 * upsert 用 jsonb 合并,只更新 categories 键,保留 profile 内其他字段。
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserProfileMapper profiles;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserProfileService(UserProfileMapper profiles) {
        this.profiles = profiles;
    }

    /** 设置(覆盖)用户偏好类目;upsert app_user,仅合并 categories 键。 */
    public void updateInterests(long userId, List<String> categories) {
        String json;
        try {
            json = mapper.writeValueAsString(categories == null ? List.of() : categories);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法的类目列表", e);
        }
        profiles.updateInterests(userId, json);
        log.info("更新用户 {} 偏好类目: {}", userId, categories);
    }

    /** 无画像才返回空；查询异常交给调用边界，避免用户把故障当成偏好已清空。 */
    public List<String> getInterests(long userId) {
        return profiles.findInterests(userId);
    }
}
