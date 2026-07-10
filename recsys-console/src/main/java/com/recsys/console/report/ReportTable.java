package com.recsys.console.report;

import java.util.List;

/** 解析后的 CSV:表头 + 数据行(全部以字符串返回,由前端按列语义渲染)。 */
public record ReportTable(
        String fileName,
        String category,
        List<String> columns,
        List<List<String>> rows) {
}
