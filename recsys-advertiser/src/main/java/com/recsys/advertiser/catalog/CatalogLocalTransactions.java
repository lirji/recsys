package com.recsys.advertiser.catalog;

import javax.sql.DataSource;
import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式短事务入口，拒绝从legacy外层事务进入可靠路径或在事务内开始另一物理步骤。 */
public final class CatalogLocalTransactions {
    private final TransactionTemplate transaction;
    private final CatalogLocalMapper mapper;
    public CatalogLocalTransactions(DataSource source, CatalogLocalMapper mapper) {
        this.mapper=mapper;
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(5);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 一个调用只允许一个已知shard；外部Kafka、鉴权、向量及跨库读取必须在返回后进行。 */
    public <T> T on(int shard,Supplier<T> action) {
        CatalogProtocol.requireShard(shard);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("可靠目录不允许嵌套或继承外层事务");
        long remaining=CatalogCallBudget.remainingMillis();
        var bounded=new TransactionTemplate(transaction.getTransactionManager());
        bounded.setTimeout((int)Math.max(1,(remaining+999)/1000));
        bounded.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return bounded.execute(status->{
            // 先在实际目标连接设置预算，再等待control/guard；Spring超时不能替代PG锁等待上限。
            long ms=CatalogCallBudget.remainingMillis();
            if(mapper.prepareTransaction(shard,Math.min(4000,ms)+"ms",ms+"ms")==null || mapper.lockControl(shard)==null)
                throw CatalogProtocol.failure(CatalogProtocol.Failure.NOT_READY);
            return action.get();
        });
    }
}
