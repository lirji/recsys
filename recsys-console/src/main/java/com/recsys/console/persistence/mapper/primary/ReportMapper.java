package com.recsys.console.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;

/** 离线报表主库投影读取；JSON解码与CSV回退留在服务。 */
public interface ReportMapper {
    /** 数据库排序保持原created_at倒序及NULL位置。 */
    List<DirectoryEntry> list();
    /** 文件名绑定参数，缺少记录时服务尝试CSV。 */
    Content read(@Param("name") String name);

    /** 原始时间类型保留毫秒截断和NULL语义。 */
    record DirectoryEntry(String category, String name, String ts, long sizeBytes, Timestamp createdAt) { }
    /** 不把持久化JSON结构暴露给HTTP调用方。 */
    record Content(String category, String columnsJson, String rowsJson) { }
}
