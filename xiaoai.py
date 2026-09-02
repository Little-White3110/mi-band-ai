# -*- coding: utf-8 -*-
"""
xiaoai_hijack.py (v5 - async websocket_message，正确方案)

关键发现：websocket_message 这个钩子本身可以定义成 async def。
mitmproxy 会等这个协程执行完，才把消息转发出去——也就是说
在协程里 await 调用DeepSeek API，等结果回来后再修改 message.content，
函数返回时mitmproxy就会用"改过的内容"去转发，完全不需要
drop()+inject()这套之前反复失败的机制。

这是目前找到的最贴近官方推荐、最可靠的实现方式：
  - 不用inject（之前验证不可靠，手环会认为是异常消息）
  - 直接让mitmproxy"暂停"这条消息的转发，等API返回后带着新内容一起发出去
  - 代价：手环这段等待时间里，用户体验上是"卡在等待语音助手响应"，
    等同于DeepSeek的网络延迟被直接加到了整个响应时间上。
    如果觉得等待太久，可以设置一个超时兜底（超时后不改内容，
    让原始回答正常转发出去，保证起码有响应而不是彻底卡死）。

用法：
    export DEEPSEEK_API_KEY="你的key"
    mitmdump -s xiaoai_hijack.py
"""

import asyncio
import json
import os

import httpx
from mitmproxy import http, ctx

TARGET_HOST = "speech.ai.xiaomi.com"

DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
DEEPSEEK_API_URL = "https://api.deepseek.com/chat/completions"
DEEPSEEK_MODEL = "deepseek-chat"

SYSTEM_PROMPT = (
    "你是一个由深度求索开发的DeepSeek语音助手，通过小米手环回答用户问题，由于用户是语音输入发送请求，所以可能会有些许错别字"
    "回答要简洁，尽量控制在80字以内，不要使用markdown格式。"
)

# Toast消息最多等DeepSeek多久，超时就放行原始回答，避免手环彻底卡死无响应
MAX_WAIT_SECONDS = 8.0

# dialog_id -> 用户识别到的原话
pending_queries: dict[str, str] = {}


async def call_deepseek(query_text: str) -> str:
    if not DEEPSEEK_API_KEY:
        return "没有配置DeepSeek API密钥"

    headers = {
        "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
        "Content-Type": "application/json",
    }
    body = {
        "model": DEEPSEEK_MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": query_text},
        ],
        "max_tokens": 200,
        "temperature": 0.7,
    }

    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(DEEPSEEK_API_URL, headers=headers, json=body)
        resp.raise_for_status()
        data = resp.json()
        return data["choices"][0]["message"]["content"].strip()


async def websocket_message(flow: http.HTTPFlow):
    if TARGET_HOST not in flow.request.pretty_host:
        return

    assert flow.websocket is not None
    message = flow.websocket.messages[-1]

    if message.from_client:
        return
    if not message.is_text:
        return

    try:
        data = json.loads(message.text)
    except Exception:
        return

    header = data.get("header", {})
    namespace = header.get("namespace")
    name = header.get("name")
    dialog_id = header.get("dialog_id", "")

    # 记录识别到的用户原话
    if namespace == "SpeechRecognizer" and name == "RecognizeResult":
        payload = data.get("payload", {})
        if payload.get("is_final") is True:
            results = payload.get("results", [])
            query_text = results[0].get("origin_text", "") if results else ""
            if query_text:
                pending_queries[dialog_id] = query_text
                ctx.log.info(f"[xiaoai_hijack] 记录识别文本 dialog={dialog_id}: {query_text!r}")
        return

    # 命中Toast：直接在这个协程里await调用DeepSeek，函数返回前修改message.content，
    # mitmproxy会自动用修改后的内容转发，不需要drop/inject
    if namespace == "Template" and name == "Toast":
        query_text = pending_queries.pop(dialog_id, "")
        if not query_text:
            ctx.log.info(f"[xiaoai_hijack] dialog={dialog_id} 无对应识别文本，放行原始回答")
            return

        ctx.log.info(f"[xiaoai_hijack] dialog={dialog_id} 开始调用DeepSeek: {query_text!r}")
        try:
            reply_text = await asyncio.wait_for(call_deepseek(query_text), timeout=MAX_WAIT_SECONDS)
            ctx.log.info(f"[xiaoai_hijack] dialog={dialog_id} DeepSeek回复: {reply_text!r}")
        except asyncio.TimeoutError:
            ctx.log.info(f"[xiaoai_hijack] dialog={dialog_id} DeepSeek超时，放行原始回答")
            return
        except Exception as e:
            ctx.log.error(f"[xiaoai_hijack] dialog={dialog_id} DeepSeek出错: {e}，放行原始回答")
            return

        data["payload"]["text"] = reply_text
        message.text = json.dumps(data, ensure_ascii=False)
        ctx.log.info(f"[xiaoai_hijack] dialog={dialog_id} 已原地替换为: {reply_text!r}")