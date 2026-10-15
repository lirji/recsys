package com.recsys.offline.persistence;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.recsys.offline.persistence.mapper.manual.R9EmbeddingMapper;
import com.recsys.offline.persistence.mapper.manual.R9EmbeddingMapper.Row;
import com.recsys.persistence.MybatisSettings;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

/** R9发布事务适配：SQL由Mapper执行，原显式事务仍控制同一派生库连接。 */
@Repository
public class R9EmbeddingStore {
    private static final int BATCH_SIZE = 500;
    private final DataSource dataSource;
    private final JdbcTemplate connections;
    private final SqlSessionFactory sessions;

    /** 私有工厂只承接给定连接，不改变普通Spring会话工厂或创建新连接池。 */
    public R9EmbeddingStore(@Qualifier("derivedSqlSessionTemplate") SqlSessionTemplate owner) throws Exception {
        dataSource = owner.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        connections = new JdbcTemplate(dataSource);
        var transactions = new ManagedTransactionFactory();
        var properties = new Properties();
        properties.setProperty("closeConnection", "false");
        transactions.setProperties(properties);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(MybatisSettings.create());
        factory.setTransactionFactory(transactions);
        factory.setMapperLocations(new ClassPathResource("mapper/offline/manual/R9EmbeddingMapper.xml"));
        var global = new GlobalConfig();
        global.setBanner(false);
        factory.setGlobalConfig(global);
        sessions = Objects.requireNonNull(factory.getObject());
    }

    /** 输入全部校验后才bootstrap并发布；任一非空组失败回滚三组版本替换。 */
    public void publish(String version, List<Row> mind, List<Row> graphUsers, List<Row> graphItems) {
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/r9-embeddings.sql")).execute(dataSource);
        connections.execute((ConnectionCallback<Void>) connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // MANAGED不提交、不回滚、不关闭外部连接，session只释放语句和执行器。
                try (var session = sessions.openSession(ExecutorType.BATCH, connection)) {
                    var mapper = session.getMapper(R9EmbeddingMapper.class);
                    replace(session, () -> mapper.deleteMindVersion(version), mapper::insertMind, mind);
                    replace(session, () -> mapper.deleteGraphUserVersion(version), mapper::insertGraphUser, graphUsers);
                    replace(session, () -> mapper.deleteGraphItemVersion(version), mapper::insertGraphItem, graphItems);
                }
                // 保留历史手动边界，包括提交外层先行写入；不能静默换成Spring REQUIRED。
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw sqlFailure(e);
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
            return null;
        });
    }

    private static void replace(SqlSession session, Runnable delete, Consumer<Row> insert, List<Row> rows) {
        if (rows.isEmpty()) return;
        delete.run();
        session.flushStatements();
        int queued = 0;
        for (Row row : rows) {
            insert.accept(row);
            if (++queued % BATCH_SIZE == 0) session.flushStatements();
        }
        if (queued % BATCH_SIZE != 0) session.flushStatements();
    }

    private static SQLException sqlFailure(Exception failure) {
        // 还原BatchUpdateException等驱动错误，交回JdbcTemplate按原SQLSTATE分类。
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
        }
        return new SQLException("R9 向量事务发布失败", failure);
    }
}
