#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""小红书「上海租房」渠道采集脚本。

链路：opencli xiaohongshu search（关键字搜索）→ note（笔记正文/标签）
      → 字段提取（DeepSeek 结构化优先，正则规则兜底）
      → 生成 ingestion 导入 items（provider=xiaohongshu，走候选审核流）。

两种输出模式：
  --output import  默认。登录管理后台并 POST /api/admin/listing-ingestion/import，
                   数据落 listing_candidates（pending，待审核）。
  --output items   仅向 stdout 打印 {"ok":true,"items":[...]} JSON，
                   供后端 XiaohongshuShanghaiPlugin 进程内导入（无需管理员凭据）。

依赖：仅 Python 标准库 + 本机已安装的 opencli（@jackwener/opencli，需 Chrome
      已登录小红书且 OpenCLI 扩展在线，可用 `opencli doctor` 自检）。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

SH_DISTRICTS = [
    "黄浦", "徐汇", "长宁", "静安", "普陀", "虹口", "杨浦", "闵行",
    "宝山", "嘉定", "浦东", "金山", "松江", "青浦", "奉贤", "崇明",
]

LAYOUT_CN = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5}


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


# --------------------------------------------------------------------------- opencli

def resolve_opencli() -> list[str]:
    """返回命令前缀。优先 node + main.js 直调（URL 含 & 时 .cmd shim 会被 cmd.exe 拆断）。"""
    explicit = os.environ.get("OPENCLI_CMD", "").strip()
    if explicit:
        return [explicit]
    patterns = [
        os.path.expanduser(r"~\AppData\Roaming\fnm\node-versions\*\installation"
                           r"\node_modules\@jackwener\opencli\dist\src\main.js"),
        os.path.expanduser(r"~\AppData\Roaming\npm\node_modules\@jackwener\opencli\dist\src\main.js"),
    ]
    for pattern in patterns:
        hits = sorted(glob.glob(pattern))
        if hits:
            main_js = hits[-1]
            # fnm 安装布局：…/installation/node_modules/... → installation/node.exe
            installation = main_js.split(os.sep + "node_modules" + os.sep)[0]
            node = os.path.join(installation, "node.exe")
            if not os.path.isfile(node):
                node = shutil.which("node") or "node"
            return [node, main_js]
    shim = shutil.which("opencli")
    if shim:
        return [shim]
    raise SystemExit("找不到 opencli：请先 npm install -g @jackwener/opencli，或设置环境变量 OPENCLI_CMD")


def run_opencli(prefix: list[str], args: list[str], timeout: int = 240):
    result = subprocess.run(prefix + args + ["-f", "json"], capture_output=True, timeout=timeout)
    out = result.stdout.decode("utf-8", errors="replace")
    if result.returncode != 0:
        err = result.stderr.decode("utf-8", errors="replace")
        raise RuntimeError(f"opencli {' '.join(args[:2])} 失败(exit {result.returncode})：{err[-200:]}")
    starts = [i for i in (out.find("["), out.find("{")) if i >= 0]
    if not starts:
        raise RuntimeError(f"opencli {' '.join(args[:2])} 未返回 JSON：{out[:200]}")
    return json.loads(out[min(starts):])


def search_notes(prefix: list[str], keyword: str, limit: int) -> list[dict]:
    data = run_opencli(prefix, ["xiaohongshu", "search", keyword, "--limit", str(limit)])
    rows = data if isinstance(data, list) else data.get("items") or data.get("results") or []
    return [row for row in rows if isinstance(row, dict) and row.get("url")]


def fetch_note_detail(prefix: list[str], url: str) -> dict:
    """note 命令需要携带 xsec_token 的完整 URL；返回 {field: value} 展平结构。"""
    data = run_opencli(prefix, ["xiaohongshu", "note", url])
    if isinstance(data, list):
        return {str(row.get("field")): row.get("value") for row in data if isinstance(row, dict)}
    return data if isinstance(data, dict) else {}


def note_id_from_url(url: str) -> str:
    match = re.search(r"/(?:search_result|explore|discovery/item)/([0-9a-f]{16,32})", url)
    return match.group(1) if match else re.sub(r"[^0-9a-zA-Z]", "", url)[-24:]


# --------------------------------------------------------------------------- 字段提取

def extract_by_rules(title: str, content: str) -> dict:
    text = f"{title} {content}"
    fields: dict = {}

    for pattern in (
        r"(?:月租|租金|价格|房租)[^\d]{0,4}(\d{3,5})",
        r"(\d{3,5})\s*(?:元|块)\s*(?:/|每)?\s*月",
        r"(\d{3,5})\s*(?:元|块)",
        r"[居室房间]\s*(\d{4})(?:\D|$)",
    ):
        match = re.search(pattern, text)
        if match:
            price = int(match.group(1))
            if 300 <= price <= 50000:
                fields["rent_price"] = price
                break

    match = re.search(r"(\d)\s*室\s*(\d)\s*厅", text)
    if match:
        fields["layout"] = f"{match.group(1)}室{match.group(2)}厅"
    else:
        match = re.search(r"([一二两三四五\d])\s*居", text)
        if match:
            count = LAYOUT_CN.get(match.group(1)) or (int(match.group(1)) if match.group(1).isdigit() else None)
            if count:
                fields["layout"] = f"{count}室1厅"
        elif re.search(r"单间|主卧|次卧|床位", text):
            fields["layout"] = "1室0厅"

    if re.search(r"合租|单间|主卧|次卧|床位|室友", text):
        fields["rent_type"] = "合租"
    elif re.search(r"整租|一居|1室|独门独户", text):
        fields["rent_type"] = "整租"

    match = re.search(r"(\d{1,3})\s*(?:平米|平方|㎡|平(?!台))", text)
    if match:
        area = int(match.group(1))
        if 5 <= area <= 500:
            fields["area_sqm"] = area

    for district in SH_DISTRICTS:
        if district in text:
            fields["district"] = district
            break

    return fields


def load_deepseek_config() -> dict:
    """key 优先取环境变量，其次 agent-service/.env.local（与 Python agent 共用凭据）。"""
    config = {
        "key": os.environ.get("DEEPSEEK_API_KEY", "").strip(),
        "base": os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").strip(),
        "model": os.environ.get("DEEPSEEK_CHAT_MODEL", "deepseek-chat").strip(),
    }
    if config["key"]:
        return config
    env_local = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "agent-service", ".env.local"))
    if os.path.isfile(env_local):
        for line in open(env_local, encoding="utf-8"):
            line = line.strip()
            if "=" not in line or line.startswith("#"):
                continue
            name, _, value = line.partition("=")
            value = value.strip().strip('"').strip("'")
            if name == "DEEPSEEK_API_KEY":
                config["key"] = value
            elif name == "DEEPSEEK_BASE_URL" and value:
                config["base"] = value
            elif name == "DEEPSEEK_CHAT_MODEL" and value:
                config["model"] = value
    return config


LLM_SYSTEM_PROMPT = (
    "你是房源信息抽取助手。输入是若干条小红书租房笔记（id/标题/正文）。"
    "请对每条笔记提取字段并输出 JSON 对象：{\"items\":[{\"id\":...,\"rentPrice\":月租金整数,"
    "\"layout\":\"N室N厅\",\"rentType\":\"整租|合租\",\"district\":\"上海行政区名(不带区字,如 浦东/静安)\","
    "\"businessArea\":\"商圈/板块\",\"community\":\"小区或公寓名\",\"areaSqm\":面积整数}]}。"
    "无法确定的字段填 null；不要编造；rentPrice 是月租金（元），多个价格取最低的整租/单间价。"
)


def extract_by_llm(notes: list[dict], config: dict) -> dict[str, dict]:
    if not config.get("key") or not notes:
        return {}
    lines = [
        {"id": note["external_id"], "title": note["title"][:120], "content": (note.get("content") or "")[:400]}
        for note in notes
    ]
    body = {
        "model": config["model"],
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": LLM_SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps(lines, ensure_ascii=False)},
        ],
    }
    request = urllib.request.Request(
        config["base"].rstrip("/") + "/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + config["key"]},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            payload = json.loads(response.read().decode("utf-8"))
        content = payload["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        items = parsed.get("items") if isinstance(parsed, dict) else parsed
        result = {}
        for item in items or []:
            if isinstance(item, dict) and item.get("id"):
                result[str(item["id"])] = item
        log(f"[llm] DeepSeek 结构化抽取成功：{len(result)}/{len(notes)} 条")
        return result
    except Exception as exc:  # LLM 失败自动降级规则抽取
        log(f"[llm] DeepSeek 抽取失败，使用规则兜底：{exc}")
        return {}


# --------------------------------------------------------------------------- 组装与导入

def build_item(note: dict, rules: dict, llm: dict) -> dict:
    def pick(*keys, default=None):
        """llm 优先，规则兜底；llm 的 null/空串视为缺失。"""
        for source, key in ((llm, keys[0]), (rules, keys[1] if len(keys) > 1 else keys[0])):
            value = source.get(key)
            if value not in (None, "", 0):
                return value
        return default

    item = {
        "external_id": note["external_id"],
        "title": note["title"][:160],
        "source_url": note["url"][:500],
        "city": "上海",
        "rent_type": pick("rentType", "rent_type", default="整租"),
        "tags": note.get("tags", [])[:6],
    }
    for item_key, llm_key, rule_key in (
        ("rent_price", "rentPrice", "rent_price"),
        ("layout", "layout", "layout"),
        ("district", "district", "district"),
        ("business_area", "businessArea", "business_area"),
        ("community", "community", "community"),
        ("area_sqm", "areaSqm", "area_sqm"),
    ):
        value = pick(llm_key, rule_key)
        if value not in (None, "", 0):
            item[item_key] = value
    if note.get("published_at"):
        item["updated_at"] = note["published_at"]
    return item


def post_json(url: str, body: dict, cookie: str | None = None):
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    if cookie:
        request.add_header("Cookie", cookie)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode("utf-8")), response.headers.get("Set-Cookie", "")


def import_to_backend(items: list[dict], args) -> dict:
    login, set_cookie = post_json(
        args.backend.rstrip("/") + "/api/admin/login",
        {"username": args.admin_user, "password": args.admin_pass},
    )
    if not login.get("ok"):
        raise SystemExit(f"管理员登录失败：{login}")
    cookie = set_cookie.split(";")[0]
    payload = {
        "items": items,
        "sourceName": "小红书",
        "provider": "xiaohongshu",
        "sourceType": "public_listing_page",
        "jobType": "crawler",
        "city": "上海",
        "baseUrl": "https://www.xiaohongshu.com",
        "cleanupMissing": False,
    }
    result, _ = post_json(args.backend.rstrip("/") + "/api/admin/listing-ingestion/import", payload, cookie)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="小红书上海租房渠道采集")
    parser.add_argument("--keyword", default="上海租房", help="搜索关键字，支持逗号/空格分隔多个（最多 5 个）")
    parser.add_argument("--limit", type=int, default=12)
    parser.add_argument("--output", choices=["import", "items"], default="import")
    parser.add_argument("--skip-detail", action="store_true", help="不抓笔记正文，仅用标题提取（更快）")
    parser.add_argument("--backend", default=os.environ.get("RENTI_BACKEND_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--admin-user", default=os.environ.get("RENTI_ADMIN_USER", "admin"))
    parser.add_argument("--admin-pass", default=os.environ.get("RENTI_ADMIN_PASS", "admin123"))
    args = parser.parse_args()

    prefix = resolve_opencli()
    log(f"[opencli] {' '.join(prefix)}")
    keywords = [kw for kw in re.split(r"[,，;；\s]+", args.keyword.strip()) if kw][:5] or ["上海租房"]
    rows, seen_urls = [], set()
    for keyword in keywords:
        log(f"[search] 关键字「{keyword}」limit={args.limit}")
        for row in search_notes(prefix, keyword, args.limit):
            note_id = note_id_from_url(str(row.get("url") or ""))
            if note_id in seen_urls:
                continue
            seen_urls.add(note_id)
            rows.append(row)
    log(f"[search] {len(keywords)} 个关键字合计命中 {len(rows)} 条笔记（去重后）")

    notes = []
    for row in rows:
        url = str(row.get("url") or "")
        note = {
            "external_id": "xhs-" + note_id_from_url(url),
            "title": str(row.get("title") or "").strip() or "小红书租房笔记",
            "url": url,
            "published_at": str(row.get("published_at") or ""),
            "content": "",
            "tags": [],
        }
        if not args.skip_detail:
            try:
                detail = fetch_note_detail(prefix, url)
                note["content"] = str(detail.get("content") or "")
                raw_tags = str(detail.get("tags") or "")
                note["tags"] = [tag.strip().lstrip("#") for tag in raw_tags.split(",") if tag.strip()][:6]
                time.sleep(1.0)
            except Exception as exc:
                log(f"[note] 正文抓取失败（跳过，仅用标题）：{note['external_id']}：{exc}")
        notes.append(note)

    llm_result = extract_by_llm(notes, load_deepseek_config())
    items = []
    for note in notes:
        rules = extract_by_rules(note["title"], note["content"])
        items.append(build_item(note, rules, llm_result.get(note["external_id"], {})))
    log(f"[extract] 组装 {len(items)} 条导入 items（含租金字段 {sum(1 for i in items if i.get('rent_price'))} 条）")

    if args.output == "items":
        print(json.dumps({"ok": True, "keyword": args.keyword, "items": items}, ensure_ascii=False))
        return

    result = import_to_backend(items, args)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
