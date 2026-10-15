package com.recsys.offline.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;

/** 报表发布复用原主库，保留文件名幂等和单文件失败继续的作业策略。 */
public interface ReportPublicationMapper {
    /** JSON文本仍通过PG jsonb转换写入，避免将文件内容拼入SQL。 */
    record Report(String category, String name, String ts, String columnsJson, String rowsJson, long sizeBytes) { }
    /** 保留原离线自举行为，目录不存在时也先确保报表结构存在。 */
    void createTable();
    /** 表注释描述权威用途。 */
    void commentTable();
    /** 补齐自举表字段注释，旧迁移文件保持不变。 */
    void commentColumns();
    /** 与原分类/时间查询索引一致，不新增其他约束或索引。 */
    void createIndex();
    /** 返回实际插入行数；同名文件保持旧内容、身份和创建时间。 */
    int publishIfAbsent(@Param("report") Report report);
}
