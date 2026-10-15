package com.recsys.persistence.spring;

import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.ExecutorType;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import java.util.Objects;
import java.util.function.Consumer;

/** 保留原JDBC批次及Spring连接归属，只提供Mapper回调，不接受SQL字符串。 */
public final class SpringMapperBatches {
    private SpringMapperBatches() { }

    /**
     * 单个原逻辑批次只flush一次；不拆事务、不主动commit，也不替换已绑定的SIMPLE会话。
     * SpringManagedTransaction复用原连接的autoCommit/外层事务；关闭独立会话不关闭事务绑定连接。
     */
    public static <M> void execute(SqlSessionTemplate owner, Class<M> mapperType, Consumer<M> writes) {
        Objects.requireNonNull(owner); Objects.requireNonNull(mapperType); Objects.requireNonNull(writes);
        var factory = owner.getSqlSessionFactory();
        var environment = factory.getConfiguration().getEnvironment();
        if (!(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalArgumentException("Mapper批次必须使用Spring管理的原数据源");
        }
        try (var session = factory.openSession(ExecutorType.BATCH)) {
            writes.accept(session.getMapper(mapperType));
            session.flushStatements();
        } catch (PersistenceException failure) {
            // 独立session没有SqlSessionTemplate拦截器，需在边界保留原Spring数据异常分类。
            var translated = new MyBatisExceptionTranslator(environment.getDataSource(), true)
                    .translateExceptionIfPossible(failure);
            if (translated != null) throw translated;
            throw failure;
        }
    }
}
