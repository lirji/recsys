package com.recsys.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.type.JdbcType;

/** 所有数据源保持原 JDBC 的每次查询语义，避免资金锁定或数据库时间被会话缓存替代。 */
public final class MybatisSettings {

    private MybatisSettings() {
    }

    /** 每个 factory 独占配置实例；共享可变 Configuration 会混淆数据源与 Mapper 注册。 */
    public static MybatisConfiguration create() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setJdbcTypeForNull(JdbcType.NULL);
        configuration.setCallSettersOnNulls(true);
        return configuration;
    }
}
