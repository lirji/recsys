package com.recsys.advertiser.persistence.model;

/** 只接收数据库驱动生成的主键，避免MyBatis-Plus在分片路由之前自行分配ID。 */
public final class GeneratedId {
    private Long value;
    /** JDBC没有返回主键时沿用原来的0值。 */
    public long valueOrZero() { return value == null ? 0L : value; }
    /** MyBatis读取回填字段。 */
    public Long getValue() { return value; }
    /** MyBatis通过getGeneratedKeys回填，不参与ID计算。 */
    public void setValue(Long value) { this.value = value; }
}
