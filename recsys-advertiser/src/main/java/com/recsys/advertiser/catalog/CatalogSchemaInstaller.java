package com.recsys.advertiser.catalog;

import javax.sql.DataSource;
import com.recsys.advertiser.persistence.mapper.migration.CatalogMigrationMapper;
import com.recsys.persistence.MybatisSettings;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/** 只供显式受控安装调用；不是Bean或启动迁移，必须传入目标物理PostgreSQL源。 */
public final class CatalogSchemaInstaller {
    private static final String RESOURCE="/db/ad-catalog/V001__catalog_recovery.sql";
    private CatalogSchemaInstaller() { }

    /** 两库分别安装，不通过SINGLE；已有相同迁移只核验，漂移和错库拒绝而不覆盖。 */
    public static void install(DataSource physical,int shard) throws SQLException {
        CatalogProtocol.requireShard(shard);
        String script=script();String hash=CatalogProtocol.sha256(script.getBytes(StandardCharsets.UTF_8));
        var configuration=MybatisSettings.create();
        configuration.setEnvironment(new Environment("catalog-explicit-physical-migration",new JdbcTransactionFactory(),physical));
        String mapperResource="mapper/advertiser/migration/CatalogMigrationMapper.xml";
        try(var input=CatalogSchemaInstaller.class.getClassLoader().getResourceAsStream(mapperResource)) {
            if(input==null)throw new SQLException("CATALOG_MIGRATION_MAPPER_MISSING");
            new XMLMapperBuilder(input,configuration,mapperResource,configuration.getSqlFragments()).parse();
        }catch(IOException failure){throw new SQLException("CATALOG_MIGRATION_MAPPER_UNREADABLE",failure);}
        try(var session=new SqlSessionFactoryBuilder().build(configuration).openSession(false)) {
            var connection=session.getConnection();
            if(!connection.getMetaData().getURL().startsWith("jdbc:postgresql:"))
                throw new SQLException("CATALOG_PHYSICAL_CONNECTION_REQUIRED");
            var mapper=session.getMapper(CatalogMigrationMapper.class);
            try {
                mapper.setting("lock_timeout","4s");mapper.setting("statement_timeout","5s");
                // 同一个目标库的并发安装串行化，不把“查不到history”当可并发CREATE的充分条件。
                mapper.lockInstallation();
                boolean history=mapper.relationExists("public.advertiser_catalog_schema_history");
                boolean control=mapper.relationExists("public.catalog_shard_control");
                if(history!=control)throw new SQLException("CATALOG_PARTIAL_SCHEMA");
                if(history) {
                    var installed=mapper.installed();
                    if(installed.size()!=1 || installed.getFirst().shardId()!=shard || !hash.equals(installed.getFirst().scriptSha256()))
                        throw new SQLException("CATALOG_SCHEMA_IDENTITY_MISMATCH");
                } else {
                    mapper.setting("recsys.catalog.install_shard",Integer.toString(shard));
                    mapper.setting("recsys.catalog.install_sha256",hash);
                    // MyBatis按完整资源执行；不能按分号切碎PG函数，也不复制另一份DDL到Java/XML。
                    var runner=new ScriptRunner(connection);runner.setAutoCommit(false);runner.setSendFullScript(true);
                    runner.setStopOnError(true);runner.setLogWriter(null);runner.setErrorLogWriter(null);
                    runner.runScript(new StringReader(script));
                }
                session.commit(true);
            } catch(SQLException | RuntimeException failure) {
                try{session.rollback(true);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}
                throw failure;
            }
        }
    }

    /** 启动校验和安装使用相同资源摘要，不另维护手工版本常量。 */
    public static String checksum(){return CatalogProtocol.sha256(script().getBytes(StandardCharsets.UTF_8));}

    private static String script() {
        try(var stream=CatalogSchemaInstaller.class.getResourceAsStream(RESOURCE)) {
            if(stream==null)throw new IllegalStateException("缺少目录治理迁移资源");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException e){throw new IllegalStateException("无法读取目录治理迁移资源",e);}
    }
}
