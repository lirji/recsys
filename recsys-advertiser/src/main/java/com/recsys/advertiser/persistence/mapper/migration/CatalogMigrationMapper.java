package com.recsys.advertiser.persistence.mapper.migration;

import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 显式物理安装会话专用，不加入应用primary Mapper扫描，不建立启动自动迁移。 */
public interface CatalogMigrationMapper {
    /** 本物理安装事务预算和迁移身份上下文，名称和值都来自安装器固定允许列表。 */
    String setting(@Param("name") String name,@Param("value") String value);
    /** 同库安装串行，不能由两个安装者同时猜测schema不存在。 */
    String lockInstallation();
    /** 明确区分没有迁移与只有半份治理schema。 */
    boolean relationExists(@Param("name") String name);
    /** 已有安装只核验原身份和checksum，不覆盖历史。 */
    List<Installed> installed();
    record Installed(String scriptSha256,int shardId) { }
}
