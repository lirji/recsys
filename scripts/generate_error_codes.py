#!/usr/bin/env python3
"""从公开错误契约生成Java枚举和前端字典；--check防止双端代码/安全文案漂移。"""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = "recsys-common/src/main/java/com/recsys/common/error/ErrorCode.java"
TYPESCRIPT = "console/src/api/generated/errorCodes.ts"
# HTTP错误状态的协议范围；0只表示CLI/客户端不存在HTTP响应。
NO_HTTP_STATUS = 0
HTTP_ERROR_STATUS_MIN = 400
HTTP_ERROR_STATUS_MAX = 599


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
    return catalog["errors"]


def outputs(catalog):
    entries = validate(catalog)
    quoted = lambda value: json.dumps(value, ensure_ascii=False)
    constants = ",\n".join(f"    {e['name']}({quoted(e['code'])}, {quoted(e['message'])}, {e['httpStatus']})" for e in entries)
    defaults = "\n".join(f"            case {e['httpStatus']} -> {e['name']};" for e in entries if e["httpDefault"])
    java = '''package com.recsys.common.error;

/** 由contracts/errors.json生成；纯契约，不依赖Web框架。请修改目录后运行generate_error_codes.py。 */
public enum ErrorCode {
''' + constants + ''';

    /** CLI/客户端故障没有HTTP状态，不能将此值写入HTTP响应。 */
    public static final int NO_HTTP_STATUS = 0;
    private final String code;
    private final String message;
    private final int httpStatus;

    ErrorCode(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /** 使用显式稳定字符串，不用枚举ordinal或依赖重命名后的name。 */
    public String code() { return code; }

    /** 可直接向用户展示的固定文案，禁止拼接内部异常详情。 */
    public String message() { return message; }

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
        ts += f"  {entry['code']}: {{\n    message: {ts_string(entry['message'])},\n    httpStatus: {entry['httpStatus']},\n  }},\n"
    ts += "} as const;\n\n"
    ts += "export type KnownErrorCode = keyof typeof errorCodes;\n\n"
    ts += "export const defaultHttpErrorCodes: Readonly<Record<number, KnownErrorCode>> = {\n"
    ts += "".join(f"  {e['httpStatus']}: {ts_string(e['code'])},\n" for e in entries if e["httpDefault"])
    ts += "};\n"
    return {JAVA: java, TYPESCRIPT: ts}


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
