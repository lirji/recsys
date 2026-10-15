#!/usr/bin/env python3
"""阻止本次Mapper迁移的实际回归：Java SQL回流、XML原文插值及历史SQL漂移。

轻量词法检查不是完整Java/SQL解析器；数据源/事务正确性仍由真实数据库测试证明。
仅扫描生产源码。测试JDBC独立断言、类型处理及无SQL的连接适配不等于业务SQL残留。
"""
from pathlib import Path
import hashlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
R9_ADAPTER = "recsys-offline/src/main/java/com/recsys/offline/persistence/R9EmbeddingStore.java"
# 先识别字符串再识别注释，防止字符串内URL或SQL注释把后续代码藏起来。
JAVA_TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
SQL = re.compile(r"\b(?:select\s+.+?\s+from|insert\s+into|update\s+\w+\s+set|delete\s+from|create\s+table)\b", re.I | re.S)
DIRECT_JDBC = re.compile(r"\b(?:prepareStatement|prepareCall|createStatement|executeQuery|executeUpdate|executeBatch)\s*\(|\bDriverManager\s*\.\s*getConnection\s*\(")
TEMPLATE = re.compile(r"\b(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcOperations|NamedParameterJdbcOperations)\b")
SQL_ANNOTATION = re.compile(r"@(?:org\.apache\.ibatis\.annotations\.)?(?:Select|Insert|Update|Delete)(?:Provider)?\s*\(")


def java_findings(source: str, relative: str) -> list[str]:
    """剥离注释但保留换行位置；字符串单独审查，连接API检查只看实际代码。"""
    findings = []
    code, previous = [], 0
    for match in JAVA_TOKEN.finditer(source):
        code.append(source[previous:match.start()])
        token = match.group()
        line = source.count("\n", 0, match.start()) + 1
        if token.startswith('"') and SQL.search(token):
            findings.append(f"{relative}:{line}: 业务SQL应移入Mapper XML")
        code.append("\n" * token.count("\n") + " ")
        previous = match.end()
    code.append(source[previous:])
    executable = "".join(code)
    for pattern, reason in [(DIRECT_JDBC, "生产代码不能直接执行JDBC SQL"),
                            (SQL_ANNOTATION, "查询/写入使用可审查的Mapper XML")]:
        for match in pattern.finditer(executable):
            line = executable.count("\n", 0, match.start()) + 1
            findings.append(f"{relative}:{line}: {reason}")
    if relative != R9_ADAPTER and TEMPLATE.search(executable):
        findings.append(f"{relative}: 禁止重新引入业务JdbcTemplate入口")
    return findings


def xml_findings(source: str, relative: str) -> list[str]:
    """XML所有分支都检查；返回行的写入必须显式声明事务及缓存副作用。"""
    findings = []
    root = ET.fromstring(source)
    text = " ".join(root.itertext())
    if "${" in text:
        findings.append(f"{relative}: 禁止原文插值，值用绑定，表列用固定XML分支")
    if root.find("cache") is not None or root.find("cache-ref") is not None:
        findings.append(f"{relative}: 不启用二级缓存")
    fragments = {node.get("id"): node for node in root.findall("sql")}

    def expanded(node, visiting=()):
        parts = [node.text or ""]
        for child in node:
            if child.tag == "include" and child.get("refid") in fragments:
                key = child.get("refid")
                if key not in visiting:
                    parts.append(expanded(fragments[key], (*visiting, key)))
            else:
                parts.append(expanded(child, visiting))
            parts.append(child.tail or "")
        return " ".join(parts)

    for select in root.findall("select"):
        sql = expanded(select)
        if re.search(r"\breturning\b", sql, re.I) and re.search(r"\b(insert|update|delete)\b", sql, re.I):
            if any(select.get(k) != v for k, v in {"affectData": "true", "flushCache": "true", "useCache": "false"}.items()):
                findings.append(f"{relative}#{select.get('id')}: RETURNING写入缺少副作用声明")
    return findings


def check(workspace: Path) -> tuple[list[str], int, int, int]:
    findings = []
    java = sorted(workspace.glob("recsys-*/src/main/java/**/*.java"))
    xml = sorted(workspace.glob("recsys-*/src/main/resources/mapper/**/*.xml"))
    for path in java:
        findings.extend(java_findings(path.read_text(), path.relative_to(workspace).as_posix()))
    for path in xml:
        relative = path.relative_to(workspace).as_posix()
        try:
            source = path.read_text()
            findings.extend(xml_findings(source, relative))
            namespace = ET.fromstring(source).get("namespace", "")
            mapper = workspace / relative.split("/", 1)[0] / "src/main/java" / (namespace.replace(".", "/") + ".java")
            if not namespace or not mapper.is_file():
                findings.append(f"{relative}: namespace未对应本模块Mapper")
        except ET.ParseError as error:
            findings.append(f"{relative}: XML解析失败: {error}")
    inventory = workspace / "docs/design/mybatis-plus-migration/SQL_INVENTORY.md"
    historical = re.findall(r"\| \[([^\]]+\.sql)\]\([^)]*\) \| `([a-f0-9]{64})`", inventory.read_text())
    if len(historical) != 45:
        findings.append("历史SQL基线必须保留初始45项；新迁移不修改该基线")
    for relative, expected in historical:
        path = workspace / relative
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            findings.append(f"{relative}: 历史SQL摘要漂移")
    return findings, len(java), len(xml), len(historical)


def main() -> int:
    findings, java, xml, historical = check(ROOT)
    if findings:
        print("\n".join(findings), file=sys.stderr)
        return 1
    print(f"PASS: {java} production Java, {xml} Mapper XML, {historical} historical SQL; R9 adapter exception has no SQL")
    return 0


if __name__ == "__main__":
    sys.exit(main())
