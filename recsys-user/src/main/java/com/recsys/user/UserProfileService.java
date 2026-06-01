package com.recsys.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 设置(覆盖)用户偏好类目;upsert app_user,仅合并 categories 键。 */
    public void updateInterests(long userId, List<String> categories) {
        String json;
        try {
            json = mapper.writeValueAsString(categories == null ? List.of() : categories);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法的类目列表", e);
        }
        jdbc.update(
                "INSERT INTO app_user(user_id, profile) " +
                "VALUES (?, jsonb_build_object('categories', ?::jsonb)) " +
                "ON CONFLICT (user_id) DO UPDATE " +
                "SET profile = COALESCE(app_user.profile, '{}'::jsonb) " +
                "             || jsonb_build_object('categories', ?::jsonb), " +
                "    updated_at = now()",
                userId, json, json);
        log.info("更新用户 {} 偏好类目: {}", userId, categories);
    }

    /** 读取用户偏好类目;无画像返回空列表。 */
    public List<String> getInterests(long userId) {
        try {
            return jdbc.queryForList(
                    "SELECT jsonb_array_elements_text(profile->'categories') FROM app_user WHERE user_id=?",
                    String.class, userId);
        } catch (Exception e) {
            log.debug("读取用户 {} 偏好类目失败: {}", userId, e.getMessage());
            return List.of();
        }
    }
}
