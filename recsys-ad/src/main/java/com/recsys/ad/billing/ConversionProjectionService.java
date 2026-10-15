package com.recsys.ad.billing;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 本地事务完成后才允许调用方提交 Kafka 位点，DB 与 broker 不伪装成一个事务。 */
public class ConversionProjectionService {
    private final ConversionProjectionRepository repository;
    private final ConversionEventCodec codec;
    private final TransactionTemplate transactions;
    public ConversionProjectionService(ConversionProjectionRepository repository,ConversionEventCodec codec,TransactionTemplate transactions) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource()!=repository.dataSource()) throw new IllegalArgumentException("Inbox 和投影须同一事务资源");
        this.repository=repository;this.codec=codec;this.transactions=transactions;
    }
    /** 返回 false 表示原事件已应用，仍可安全确认位点。 */
    public boolean apply(String key,String payload) {
        var value=codec.decode(key,payload);
        return Boolean.TRUE.equals(transactions.execute(tx->{
            if (!repository.insert(value)) return false;
            repository.accumulate(value);
            return true;
        }));
    }
}
