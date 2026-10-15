package com.recsys.offline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 真实连接泄漏立即失败；仅服务端收到Terminate后的进程退出允许有界观察延迟。 */
final class TrackedDriverDataSource extends DriverManagerDataSource {
    private final List<Connection> opened = new ArrayList<>();

    TrackedDriverDataSource(String url, String username, String password) {
        super(url, username, password);
    }

    @Override
    protected Connection getConnectionFromDriver(Properties properties) throws SQLException {
        Connection connection = super.getConnectionFromDriver(properties);
        opened.add(connection);
        return connection;
    }

    /** 不重试close、不主动关闭泄漏连接；客户端关闭与服务端消失分别验证。 */
    void assertClosed(JdbcTemplate observer, String applicationName) {
        for (Connection connection : opened) {
            assertTrue(assertDoesNotThrow(connection::isClosed), "作业遗留未关闭的真实JDBC连接");
        }
        // PG驱动close发送Terminate并关socket，不同步等待pg_stat_activity删行。
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(0, observer.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name=?", Integer.class, applicationName)));
    }
}
