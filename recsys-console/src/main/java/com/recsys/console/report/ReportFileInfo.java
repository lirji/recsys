package com.recsys.console.report;

/** 离线报表文件的一条目录项。 */
public record ReportFileInfo(
        String category,
        String fileName,
        String timestamp,
        long sizeBytes,
        long modifiedAt) {
}
