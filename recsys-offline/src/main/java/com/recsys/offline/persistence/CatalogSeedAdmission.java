package com.recsys.offline.persistence;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.offline.persistence.mapper.catalogprobe.CatalogModeProbeMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** 旧seed在任何清理/向量/目录/Redis效果前直接探测两物理库；未知、半登记和ACTIVE都拒绝。 */
@Component
public final class CatalogSeedAdmission {
    /** PG原生只读标志与治理模式的固定wire值，不能把未知字符串归一成LEGACY。 */
    private static final String LEGACY_MODE="LEGACY", READ_ONLY_ON="on";
    private static final int GOVERNANCE_TABLE_COUNT=9;
    private final DataSource[] sources;
    @Autowired public CatalogSeedAdmission(org.springframework.core.env.Environment environment) {
        sources=new DataSource[2];
        for(int shard=0;shard<2;shard++) {
            String url=environment.getProperty(shard==0?"PG_JDBC_URL":"PG_DS1_JDBC_URL",shard==0?"jdbc:postgresql://127.0.0.1:5432/recsys":"jdbc:postgresql://127.0.0.1:5432/recsys_ds1");
            if(!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("seed准入必须直连两个实际PG");
            var ds=new DriverManagerDataSource(url,environment.getProperty("PG_USER","recsys"),environment.getProperty("PG_PASSWORD","recsys"));
            var props=new java.util.Properties();props.setProperty("connectTimeout","2");props.setProperty("socketTimeout","2");ds.setConnectionProperties(props);sources[shard]=ds;
        }
    }
    /** 独占验证可注入实际物理源；仍执行同一只读协议和身份检查。 */
    public CatalogSeedAdmission(DataSource zero,DataSource one){sources=new DataSource[]{zero,one};}

    /** 这是前置检查，不替代切换owner排除已运行旧seed及手工SQL的维护窗口。 */
    public void requireLegacy() {
        // 两连接必须同时保持：先关闭第一条会使本地标识消失，从而漏掉地址别名指向同库。
        try(var zero=openProbe(0);var one=openProbe(1)) {
            verifyLegacy(zero.mapper(),0);verifyLegacy(one.mapper(),1);
            verifyDistinct(zero,one);verifyDistinct(one,zero);
        } catch(Exception unknown) {
            // 未安装必须由元数据明确证明；网络、权限和SQL异常都不能降级为旧模式。
            throw new PublicErrorException(ErrorCode.CATALOG_NOT_READY);
        }
    }

    private Probe openProbe(int shard) throws Exception {
        var factory=SpringSqlSessions.create(sources[shard],"classpath*:mapper/offline/catalogprobe/CatalogModeProbeMapper.xml");
        var configuration=factory.getConfiguration();
        configuration.setEnvironment(new Environment("physical-catalog-seed-probe",new JdbcTransactionFactory(),sources[shard]));
        configuration.setDefaultStatementTimeout(2);
        var session=factory.openSession(false);
        try {
            var connection=session.getConnection();
            if(!connection.getMetaData().getURL().startsWith("jdbc:postgresql:"))throw new IllegalStateException("不是物理PG连接");
            connection.setReadOnly(true);
            var mapper=session.getMapper(CatalogModeProbeMapper.class);
            String tag="catalog-seed-"+java.util.UUID.randomUUID();
            // 只设置事务局部的会话标识，不写治理或业务表，回滚自动恢复原连接属性。
            if(!tag.equals(mapper.markProbe(tag)))throw new IllegalStateException("目录探针标识未知");
            return new Probe(session,mapper,tag);
        } catch(Exception failure) {session.close();throw failure;}
    }

    private void verifyLegacy(CatalogModeProbeMapper mapper,int shard) {
        var installation=mapper.installation();
        if(installation.tables()==0)return; // 正式C16批准的无迁移旧库例外，不等于缺control行。
        if(installation.tables()!=GOVERNANCE_TABLE_COUNT || installation.regularTables()!=GOVERNANCE_TABLE_COUNT)
            throw new IllegalStateException("目录治理安装不完整");
        var rows=mapper.modes();
        if(rows.size()!=1)throw new IllegalStateException("目录控制身份不唯一");
        var state=rows.getFirst();
        if(state.shardId()!=shard || !LEGACY_MODE.equals(state.mode()) || !state.unbound() || state.historyRows()!=1
                || state.scriptSha256()==null || !state.scriptSha256().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("目录不允许旧seed写入");
    }

    private void verifyDistinct(Probe self,Probe peer) {
        // PG统计视图在事务内缓存快照；双方标识写好后显式清除，不能拿旧快照证明不同库。
        self.mapper().clearSnapshot();
        var state=self.mapper().peers(self.tag(),peer.tag());
        if(state.ownCount()!=1 || state.peerCount()!=0 || !READ_ONLY_ON.equals(state.readOnly()))
            throw new IllegalStateException("目录物理连接身份或只读状态不可信");
    }

    private record Probe(org.apache.ibatis.session.SqlSession session,CatalogModeProbeMapper mapper,String tag) implements AutoCloseable {
        @Override public void close(){try{session.rollback();}finally{session.close();}}
    }
}
