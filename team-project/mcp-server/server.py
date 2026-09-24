#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
A02 自建 MCP Server（Model Context Protocol）
=============================================

作用：把「岗位 / 课程 / 认证」三类只读数据源以 MCP 工具的形式暴露给智能体，
      使第三方服务对接变成**插件化**能力——新增数据源不需要改智能体。

设计约束（对应 docs/04-架构设计.md §8）：

1. **传输**：默认 stdio（本地演示，零网络风险），也支持 `--http` 以 SSE/HTTP 方式远程部署。
2. **取数**：只调用后端 `/internal/v1/**` 只读内部 API，使用**独立 Service Token**，
   而不是直连数据库——避免两套数据访问逻辑（AD-8），且权限可控可审计。
3. **只读**：三个工具全部只读，不暴露任何写操作，不返回个人隐私数据。
4. **三类异常显式处理**：超时 / 非法入参 / 上游不可用，一律返回结构化错误，**绝不抛栈给模型**。
5. **零第三方依赖**：仅用 Python 标准库（本机 Python 3.9，无外网无法 pip 安装 mcp SDK）。

用法：
    # stdio 模式（供智能体/Host 以子进程方式启动）
    python server.py

    # HTTP 模式（远程部署，供平台侧通过 SSE 调用）
    python server.py --http --port 8770

    # 手工验证
    echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | python server.py

环境变量：
    A02_BACKEND_URL      后端地址，默认 http://127.0.0.1:8080
    A02_SERVICE_TOKEN    内部接口令牌，默认 a02-internal-demo-token
    A02_MCP_TIMEOUT      上游超时秒数，默认 5
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "a02-career-mcp"
SERVER_VERSION = "1.0.0"

# MCP 规范要求 stdio 传输使用 UTF-8。Windows 上 Python 默认按本地代码页（GBK）解码 stdin，
# 会把 MCP Host 发来的 UTF-8 中文参数解成乱码并导致参数校验误判，因此必须显式重设编码。
for _stream in (sys.stdin, sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):  # 极老版本或已被重定向为非文本流
        pass

BACKEND_URL = os.environ.get("A02_BACKEND_URL", "http://127.0.0.1:8080").rstrip("/")
SERVICE_TOKEN = os.environ.get("A02_SERVICE_TOKEN", "a02-internal-demo-token")
TIMEOUT = float(os.environ.get("A02_MCP_TIMEOUT", "5"))

# 入参白名单：中文、字母、数字与常用技术符号（防提示注入经工具外溢）
SAFE_TEXT = re.compile(r"^[\u4e00-\u9fa5A-Za-z0-9 _\-+.+#/]{1,50}$")

TOOLS = [
    {
        "name": "query_job_market",
        "description": "按岗位名或技能关键词查询岗位市场需求与所需技能。只读，不返回任何个人信息。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "keyword": {"type": "string", "minLength": 1, "maxLength": 50,
                            "description": "岗位名或技能关键词，例如「Java 后端」"},
                "region": {"type": "string", "minLength": 1, "maxLength": 20,
                           "description": "地区（可选），当前数据源不区分地区"},
            },
            "required": ["keyword"],
        },
    },
    {
        "name": "search_course",
        "description": "按技能检索学习资源，每条结果均带来源。只读。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "skill": {"type": "string", "minLength": 1, "maxLength": 50},
                "level": {"type": "string", "enum": ["BEGINNER", "INTERMEDIATE", "ADVANCED"]},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20},
            },
            "required": ["skill"],
        },
    },
    {
        "name": "verify_certificate",
        "description": "查询职业认证的颁发机构与有效期。绝不返回任何个人证书编号。只读。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "cert_name": {"type": "string", "minLength": 1, "maxLength": 50},
            },
            "required": ["cert_name"],
        },
    },
]


class ToolError(Exception):
    """带 MCP 错误码的工具异常，不向模型暴露堆栈。"""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


def _get(path, params):
    """调用后端只读内部 API；三类异常分别转换为结构化错误。"""
    url = BACKEND_URL + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, method="GET")
    request.add_header("X-Service-Token", SERVICE_TOKEN)
    request.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            body = response.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8")[:200]
        except Exception:  # noqa: BLE001 - 读取错误体失败不应掩盖原始错误
            pass
        if exc.code in (401, 403):
            raise ToolError("UPSTREAM_UNAVAILABLE",
                            "内部接口鉴权失败，请检查 A02_SERVICE_TOKEN 配置")
        raise ToolError("UPSTREAM_UNAVAILABLE", "后端返回 HTTP %s %s" % (exc.code, detail))
    except urllib.error.URLError as exc:
        if "timed out" in str(exc).lower():
            raise ToolError("UPSTREAM_TIMEOUT", "数据源响应超时，请稍后重试")
        raise ToolError("UPSTREAM_UNAVAILABLE", "后端服务暂不可用：%s" % exc.reason)
    except json.JSONDecodeError:
        raise ToolError("UPSTREAM_UNAVAILABLE", "后端返回内容无法解析为 JSON")


def require_text(args, field, min_len=1, max_len=50, required=True):
    value = (args or {}).get(field)
    if value is None or str(value).strip() == "":
        if required:
            raise ToolError("INVALID_ARGUMENT", "缺少必填参数：%s" % field)
        return None
    text = str(value).strip()
    if not (min_len <= len(text) <= max_len):
        raise ToolError("INVALID_ARGUMENT",
                        "参数 %s 长度须在 %d-%d 之间" % (field, min_len, max_len))
    if not SAFE_TEXT.match(text):
        raise ToolError("INVALID_ARGUMENT",
                        "参数 %s 含非法字符（仅允许中文、字母、数字与常用符号）" % field)
    return text


def call_query_job_market(args):
    keyword = require_text(args, "keyword")
    region = require_text(args, "region", max_len=20, required=False)
    payload = _get("/internal/v1/jobs", {"keyword": keyword, "region": region or ""})
    result = payload.get("result", payload)
    if result.get("error"):
        raise ToolError(result["error"], result.get("message", "查询失败"))
    return result


def call_search_course(args):
    skill = require_text(args, "skill")
    level = require_text(args, "level", max_len=20, required=False)
    if level and level.upper() not in ("BEGINNER", "INTERMEDIATE", "ADVANCED"):
        raise ToolError("INVALID_ARGUMENT", "level 取值须为 BEGINNER/INTERMEDIATE/ADVANCED")
    try:
        limit = int((args or {}).get("limit", 5))
    except (TypeError, ValueError):
        raise ToolError("INVALID_ARGUMENT", "limit 必须为整数")
    limit = max(1, min(20, limit))
    payload = _get("/internal/v1/courses", {"skill": skill, "limit": limit})
    result = payload.get("result", payload)
    if result.get("error"):
        raise ToolError(result["error"], result.get("message", "查询失败"))
    return result


def call_verify_certificate(args):
    cert_name = require_text(args, "cert_name")
    payload = _get("/internal/v1/certs", {"cert_name": cert_name})
    result = payload.get("result", payload)
    if result.get("error"):
        raise ToolError(result["error"], result.get("message", "查询失败"))
    # 双保险：即使上游误带个人字段，也在工具层剥离
    for key in ("certNumber", "holderName", "idCard", "personalData"):
        result.pop(key, None)
    return result


HANDLERS = {
    "query_job_market": call_query_job_market,
    "search_course": call_search_course,
    "verify_certificate": call_verify_certificate,
}


def handle(request):
    """处理一条 JSON-RPC 请求，返回响应字典（通知类请求返回 None）。"""
    method = request.get("method")
    request_id = request.get("id")
    params = request.get("params") or {}

    if method == "initialize":
        return _result(request_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "capabilities": {"tools": {"listChanged": False}},
        })

    if method in ("notifications/initialized", "initialized"):
        return None

    if method == "tools/list":
        return _result(request_id, {"tools": TOOLS})

    if method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        handler = HANDLERS.get(name)
        if handler is None:
            return _error(request_id, -32602, "未知工具：%s" % name)
        started = time.time()
        try:
            payload = handler(args)
            elapsed_ms = int((time.time() - started) * 1000)
            _log(name, args, "OK", elapsed_ms)
            return _result(request_id, {
                "content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}],
                "isError": False,
            })
        except ToolError as exc:
            elapsed_ms = int((time.time() - started) * 1000)
            _log(name, args, exc.code, elapsed_ms)
            return _result(request_id, {
                "content": [{"type": "text",
                             "text": json.dumps({"error": exc.code, "message": exc.message},
                                                ensure_ascii=False)}],
                "isError": True,
            })
        except Exception as exc:  # noqa: BLE001 - 兜底：绝不让异常穿透到模型
            _log(name, args, "INTERNAL_ERROR", int((time.time() - started) * 1000))
            return _result(request_id, {
                "content": [{"type": "text",
                             "text": json.dumps({"error": "INTERNAL_ERROR",
                                                 "message": "工具内部错误：%s" % exc},
                                                ensure_ascii=False)}],
                "isError": True,
            })

    if method in ("ping",):
        return _result(request_id, {})

    return _error(request_id, -32601, "不支持的方法：%s" % method)


def _result(request_id, result):
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _error(request_id, code, message):
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def _log(tool, args, status, elapsed_ms):
    """stderr 输出调用日志（stdout 是协议通道，不能污染）。"""
    digest = json.dumps(args, ensure_ascii=False)
    if len(digest) > 160:
        digest = digest[:160] + "…"
    sys.stderr.write("[mcp] tool=%s status=%s elapsed=%dms args=%s\n"
                     % (tool, status, elapsed_ms, digest))
    sys.stderr.flush()


def serve_stdio():
    """stdio 传输：逐行读取 JSON-RPC（Content-Length 帧与行分隔均兼容）。"""
    sys.stderr.write("[mcp] %s v%s 已启动，后端 %s（只读工具 %d 个）\n"
                     % (SERVER_NAME, SERVER_VERSION, BACKEND_URL, len(TOOLS)))
    sys.stderr.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line.lower().startswith("content-length:"):
            # LSP 风格帧头：读取后续长度指定的正文
            length = int(line.split(":")[1].strip())
            sys.stdin.readline()  # 空行
            line = sys.stdin.read(length)
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            sys.stdout.write(json.dumps(_error(None, -32700, "JSON 解析失败")) + "\n")
            sys.stdout.flush()
            continue

        response = handle(request)
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            sys.stdout.flush()


def serve_http(port):
    """HTTP 传输：POST /mcp 收 JSON-RPC，GET /health 探活。"""
    from http.server import BaseHTTPRequestHandler, HTTPServer

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler 约定
            if self.path.startswith("/health"):
                body = json.dumps({"status": "UP", "server": SERVER_NAME,
                                   "tools": [t["name"] for t in TOOLS]}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_error(404)

        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length).decode("utf-8") if length else "{}"
            try:
                request = json.loads(raw)
            except json.JSONDecodeError:
                response = _error(None, -32700, "JSON 解析失败")
            else:
                response = handle(request) or {}
            body = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):  # 静音默认访问日志
            pass

    sys.stderr.write("[mcp] HTTP 模式已启动：http://127.0.0.1:%d/mcp（后端 %s）\n"
                     % (port, BACKEND_URL))
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    if "--http" in sys.argv:
        port = 8770
        if "--port" in sys.argv:
            port = int(sys.argv[sys.argv.index("--port") + 1])
        serve_http(port)
    else:
        serve_stdio()
