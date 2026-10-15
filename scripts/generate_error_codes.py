#!/usr/bin/env python3
"""从公开错误契约生成Java枚举和前端字典；--check防止双端代码/安全文案漂移。"""
import argparse
import json
from pathlib import Path
import re
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
JAVA = "recsys-common/src/main/java/com/recsys/common/error/ErrorCode.java"
JAVA_FAMILY = "recsys-common/src/main/java/com/recsys/common/error/ErrorFamily.java"
TYPESCRIPT = "console/src/api/generated/errorCodes.ts"
# HTTP错误状态的协议范围；0表示RPC专用码、CLI或客户端没有直接HTTP响应。
NO_HTTP_STATUS = 0
HTTP_ERROR_STATUS_MIN = 400
HTTP_ERROR_STATUS_MAX = 599
# 沿用console现有Prettier 100列规范；中文全角占两列。
TS_PRINT_WIDTH = 100


def validate(catalog):
    """禁止重复码、含糊状态默认项和成功状态混入错误目录。"""
    if not isinstance(catalog, dict) or type(catalog.get("version")) is not int or catalog["version"] != 1 or not isinstance(catalog.get("errors"), list) or not catalog["errors"]:
        raise ValueError("错误目录须为version=1且包含非空errors数组")
    names, codes, defaults = set(), set(), set()
    for entry in catalog["errors"]:
        if not isinstance(entry, dict):
            raise ValueError("错误条目须为对象")
        for key, seen in [("name", names), ("code", codes)]:
            value = entry.get(key)
            if not isinstance(value, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", value) or value in seen:
                raise ValueError(f"重复或非法{key}: {value}")
            seen.add(value)
        status = entry.get("httpStatus")
        if type(status) is not int or (status != NO_HTTP_STATUS and not HTTP_ERROR_STATUS_MIN <= status <= HTTP_ERROR_STATUS_MAX):
            raise ValueError(f"非法错误HTTP状态: {status}")
        if not isinstance(entry.get("message"), str) or not entry["message"].strip() or len(entry["message"]) > 300:
            raise ValueError("错误文案须为1～300字符的固定安全提示")
        if any(ord(char) < 32 or ord(char) == 127 for char in entry["message"]):
            raise ValueError("安全文案不能含控制字符")
        if type(entry.get("httpDefault")) is not bool:
            raise ValueError("httpDefault须显式为boolean")
        if entry["httpDefault"]:
            if status == NO_HTTP_STATUS or status in defaults:
                raise ValueError(f"HTTP默认错误码重复或没有状态: {status}")
            defaults.add(status)
    by_code = {e["code"]: e for e in catalog["errors"]}
    for entry in catalog["errors"]:
        if "wireCode" not in entry:
            continue
        wire = entry["wireCode"]
        target = by_code.get(wire) if isinstance(wire, str) else None
        if (target is None or target is entry or "wireCode" in target or entry["httpDefault"]
                or entry["httpStatus"] == NO_HTTP_STATUS or entry["httpStatus"] != target["httpStatus"]):
            raise ValueError("wireCode须引用同HTTP状态的非别名条目；禁止默认项/无HTTP/循环别名")
    families = catalog.get("families", [])
    if not isinstance(families, list):
        raise ValueError("families须为数组")
    family_names, prefixes = set(), set()
    by_name = {e["name"]: e for e in catalog["errors"]}
    for family in families:
        if not isinstance(family, dict):
            raise ValueError("码族须为对象")
        for key, seen in [("name", family_names), ("prefix", prefixes)]:
            value = family.get(key)
            # 为稳定代码中的下划线和三位HTTP状态预留长度。
            if not isinstance(value, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,59}", value) or value in seen:
                raise ValueError(f"重复或非法码族{key}: {value}")
            seen.add(value)
        fallback_name = family.get("fallback")
        fallback = by_name.get(fallback_name) if isinstance(fallback_name, str) else None
        prefix = family["prefix"] + "_"
        if fallback is None or fallback["httpStatus"] == NO_HTTP_STATUS or fallback["code"] != prefix + str(fallback["httpStatus"]):
            raise ValueError("码族fallback须引用本族已登记HTTP错误")
        for entry in catalog["errors"]:
            suffix = entry["code"].removeprefix(prefix)
            if entry["code"].startswith(prefix) and suffix.isdigit():
                if entry["httpStatus"] == NO_HTTP_STATUS or suffix != str(entry["httpStatus"]):
                    raise ValueError("码族状态后缀须与条目HTTP状态一致")
    return catalog["errors"]


def outputs(catalog):
    entries = validate(catalog)
    quoted = lambda value: json.dumps(value, ensure_ascii=False)
    constants = ",\n".join(f"    {e['name']}({quoted(e['code'])}, {quoted(e['message'])}, {e['httpStatus']}"
                             + (f", {quoted(e['wireCode'])}" if "wireCode" in e else "") + ")" for e in entries)
    defaults = "\n".join(f"            case {e['httpStatus']} -> {e['name']};" for e in entries if e["httpDefault"])
    java = '''package com.recsys.common.error;

/** 由contracts/errors.json生成；纯契约，不依赖Web框架。请修改目录后运行generate_error_codes.py。 */
public enum ErrorCode {
''' + constants + ''';

    /** RPC专用码、CLI或客户端故障没有HTTP状态，不能将此值写入HTTP响应。 */
    public static final int NO_HTTP_STATUS = 0;
    private final String code;
    private final String message;
    private final int httpStatus;
    private final String wireCode;

    ErrorCode(String code, String message, int httpStatus) {
        this(code, message, httpStatus, code);
    }

    ErrorCode(String code, String message, int httpStatus, String wireCode) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.wireCode = wireCode;
    }

    /** 使用显式稳定字符串，不用枚举ordinal或依赖重命名后的name。 */
    public String code() { return code; }

    /** HTTP兼容码；有上下文安全文案时仍保留已发布的响应code。 */
    public String wireCode() { return wireCode; }

    /** 可直接向用户展示的固定文案，禁止拼接内部异常详情。 */
    public String message() { return message; }

    /** RPC/CLI等直接展示场景统一包含目录码；HTTP的code与message字段仍分别序列化。 */
    public String displayMessage() { return "[" + code + "] " + message; }

    /** 默认协议状态；业务适配层仍须保留其既有状态分类。 */
    public int httpStatus() { return httpStatus; }

    /** 未登记代码返回null，让调用边界显式选择兼容回退。 */
    public static ErrorCode fromCode(String code) {
        for (ErrorCode value : values()) {
            if (value.code.equals(code)) return value;
        }
        return null;
    }

    /** 未登记HTTP状态只使用安全兜底文案，调用方决定原HTTP状态是否保留。 */
    public static ErrorCode forHttpStatus(int status) {
        return switch (status) {
''' + defaults + '''
            default -> INTERNAL_ERROR;
        };
    }
}
'''
    def ts_string(value):
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"

    ts = "// 由contracts/errors.json生成；请运行python3 scripts/generate_error_codes.py，勿手工修改。\n"
    ts += "export const errorCodes = {\n"
    for entry in entries:
        message = ts_string(entry['message'])
        inline = f"    message: {message},"
        width = sum(0 if unicodedata.combining(char) else 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1 for char in inline)
        message_line = inline if width <= TS_PRINT_WIDTH else f"    message:\n      {message},"
        ts += f"  {entry['code']}: {{\n{message_line}\n    httpStatus: {entry['httpStatus']},\n  }},\n"
    ts += "} as const;\n\n"
    ts += "export type KnownErrorCode = keyof typeof errorCodes;\n\n"
    ts += "export const defaultHttpErrorCodes: Readonly<Record<number, KnownErrorCode>> = {\n"
    ts += "".join(f"  {e['httpStatus']}: {ts_string(e['code'])},\n" for e in entries if e["httpDefault"])
    ts += "};\n"
    variants = {}
    for entry in entries:
        if "wireCode" in entry:
            variants.setdefault(entry["wireCode"], []).append(entry["code"])
    ts += "\nexport const httpErrorVariants: Readonly<\n  Partial<Record<KnownErrorCode, readonly KnownErrorCode[]>>\n> = {\n"
    for wire, members in variants.items():
        ts += f"  {wire}: [" + ", ".join(ts_string(member) for member in members) + "],\n"
    ts += "};\n"
    families = ",\n".join(f"    {f['name']}({quoted(f['prefix'])}, ErrorCode.{f['fallback']})" for f in catalog.get("families", []))
    family_java = '''package com.recsys.common.error;

/** 由contracts/errors.json生成；保留既有码族，状态分类由所属业务边界决定。 */
public enum ErrorFamily {
''' + families + ''';

    private static final int MIN_HTTP_ERROR_STATUS = 400;
    private static final int MAX_HTTP_ERROR_STATUS = 599;
    private final String prefix;
    private final ErrorCode fallback;

    ErrorFamily(String prefix, ErrorCode fallback) {
        this.prefix = prefix;
        this.fallback = fallback;
    }

    /** 未单独登记的错误状态也保留原码族，不能静默改成通用500。 */
    public String code(int status) {
        if (status < MIN_HTTP_ERROR_STATUS || status > MAX_HTTP_ERROR_STATUS) {
            throw new IllegalArgumentException("错误码族仅支持HTTP错误状态");
        }
        return prefix + "_" + status;
    }

    /** 只取目录固定文案；503原ID恢复提示与其他状态的安全回退均不依赖异常详情。 */
    public String message(int status) {
        ErrorCode registered = ErrorCode.fromCode(code(status));
        return (registered == null ? fallback : registered).message();
    }
}
'''
    return {JAVA: java, JAVA_FAMILY: family_java, TYPESCRIPT: ts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        generated = outputs(json.loads((ROOT / "contracts/errors.json").read_text()))
    except (ValueError, KeyError, TypeError) as error:
        print(f"错误目录校验失败: {error}", file=sys.stderr)
        return 1
    stale = []
    for relative, content in generated.items():
        path = ROOT / relative
        if args.check:
            if not path.is_file() or path.read_text() != content:
                stale.append(relative)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    if stale:
        print("错误目录生成物过期，请运行python3 scripts/generate_error_codes.py:\n" + "\n".join(stale), file=sys.stderr)
        return 1
    print("PASS: 公开错误目录与Java/TypeScript生成物一致" if args.check else "已生成Java/TypeScript错误目录")
    return 0


if __name__ == "__main__":
    sys.exit(main())
