package com.recsys.recall.channel;

import com.recsys.common.user.UserProfileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认用户画像类目读:直读 {@code app_user.profile->categories}(与 P3/#3 之前等价,回滚落点)。
 * {@code recsys.user.serving.mode} 缺省或 = {@code in-process} 时生效(离线 eval 复用召回也走它)。
 */
@Component
@ConditionalOnProperty(name = "recsys.user.serving.mode", havingValue = "in-process", matchIfMissing = true)
public class DbUserProfileReader implements UserProfileReader {

    private static final Logger log = LoggerFactory.getLogger(DbUserProfileReader.class);

    private final JdbcTemplate jdbc;

    public DbUserProfileReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> categories(long userId) {
        try {
            return jdbc.queryForList(
                    "SELECT jsonb_array_elements_text(profile->'categories') FROM app_user WHERE user_id=?",
                    String.class, userId);
        } catch (Exception e) {
            log.debug("读取用户偏好类目失败(app_user): {}", e.getMessage());
            return List.of();
        }
    }
}
