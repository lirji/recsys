package com.recsys.content.persistence.model;

/** 来源由服务端配置决定；XML 选择固定表，不接受外部 SQL 标识符。 */
public enum CatalogTable {
    OWNED,
    REPLICA
}
