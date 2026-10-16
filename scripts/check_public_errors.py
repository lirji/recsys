#!/usr/bin/env python3
"""有界防回流：拦截已修复的原异常展示形式，不替代数据流分析和行为测试。"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
RAW_PROPERTY = r"\b(?:error|err|e)(?:\?\.|\.)(?:message|stack)\b"
JSX_RAW = re.compile(r"(?:=|>)\s*\{[^{}]*" + RAW_PROPERTY + r"[^{}]*\}")
TOAST_RAW = re.compile(r"(?:message\.(?:error|warning)|notifyError)\([^;]*" + RAW_PROPERTY)


def violations(path, source):
    """仅检查公开展示出口；内部日志、目录选择器和正常业务reason不受限制。"""
    found = []
    if path.endswith(".tsx"):
        if JSX_RAW.search(source):
            found.append("JSX直接展示原异常message/stack")
        if TOAST_RAW.search(source):
            found.append("通知直接展示原异常message/stack")
    if path.endswith("components/ErrorBoundary.tsx") or path.endswith("components/experiment/AbSignificancePanel.tsx"):
        if re.search(RAW_PROPERTY + r"|String\(error\)", source):
            found.append("公开错误边界透传原异常")
    if path.endswith("pages/CallbackPage.tsx"):
        if re.search(r"return\s+(?:msg\b|e\.message\b|String\(e\))", source):
            found.append("登录回调返回原异常选择器文本")
    if path.endswith("pages/online/ExperimentConsole.tsx"):
        if re.search(r"\b(?:r|data)\.reason\b", source):
            found.append("实验写入展示不可信reason")
    if path.endswith("experiment/ExperimentAdminController.java"):
        if re.search(r"\.get(?:Localized)?Message\(", source):
            found.append("实验控制器将异常详情引入响应边界")
    return found


def check(root=ROOT):
    """扫描现有前端出口和本片遗漏后端，不遍历测试/构建或第三方目录。"""
    paths = [p for p in (root / "console/src").rglob("*.tsx") if "__tests__" not in p.parts]
    paths += list(root.glob("recsys-*/src/main/java/**/experiment/ExperimentAdminController.java"))
    return [f"{p.relative_to(root)}: {finding}" for p in sorted(paths)
            for finding in violations(p.as_posix(), p.read_text())]


if __name__ == "__main__":
    findings = check()
    print("\n".join(findings) if findings else "PASS: 已知公开错误透传形式无回流")
    raise SystemExit(bool(findings))
