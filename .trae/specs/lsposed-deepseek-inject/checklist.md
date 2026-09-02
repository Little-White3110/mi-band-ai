# 验收检查清单

## 模块基础结构
- [x] LSPosed 模块入口类继承 `XposedModule`，`onModuleLoaded()` 被调用并记录日志
- [x] 包名为 `llm.miband.littlewhite`，模块名称为"环上LLM"
- [x] `META-INF/xposed/module.prop`、`java_init.list`、`scope.list` 三件套齐全
- [x] `onPackageLoaded()` 检测目标包名，仅对小米运动健康 App 生效
- [x] `./gradlew :app:assembleDebug` 构建通过

## 逆向分析结论
- [x] 目标 App 包名已确认并写入 `scope.list`
- [x] WebSocket 客户端实现类名/方法签名已确认，记录在 `docs/reverse-notes.md`
- [x] 消息 JSON 结构与 spec.md 定义的 RecognizeResult/Toast 一致

## WebSocket 消息拦截
- [x] 收到 `SpeechRecognizer/RecognizeResult`（is_final=true）时记录 `dialog_id → origin_text`
- [x] 收到 `Template/Toast` 且 dialog_id 命中缓存时，用 LLM 回答替换 `payload.text`
- [x] 非目标消息透传，无副作用
- [x] 配置 `enabled == false` 时全部消息透传
- [x] 无 dialog_id 缓存时放行原始消息

## LLM API 调用（多 API 兼容）
- [x] 默认使用 DeepSeek API（`https://api.deepseek.com`），支持自定义 `base_url` 并自动补全 `/v1/chat/completions`
- [x] 支持 OpenAI chat completion API 与 Anthropic API 两种类型
- [x] 请求体含 model / messages / system_prompt / 识别文本 / temperature / top_p / top_k / max_tokens
- [x] OpenAI 模式成功时提取 `choices[0].message.content`
- [x] Anthropic 模式成功时提取 `content[0].text`
- [x] 思考模式：OpenAI 用 `deepseek-reasoner` 模型；Anthropic 添加 `thinking` 块
- [x] 超时（默认 8000ms）/非 200/异常时捕获并放行原始消息

## 会话管理
- [x] `context_mode == "single"` 时，`context_window_ms` 窗口内连续调用携带历史上下文（最多 `context_length` 条）
- [x] `context_mode == "independent"` 或窗口超时后开启独立会话，仅携带当前查询
- [x] 会话历史按 `dialog_id` 隔离

## 配置管理
- [x] 模块 App 通过 `XposedService.getRemotePreferences("config")` 写入配置
- [x] Hook 进程通过 `XposedModule.getRemotePreferences("config")` 读取配置
- [x] 配置变更实时生效（OnSharedPreferenceChangeListener）
- [x] API Key 本地加密存储，日志/代码中不暴露明文

## 日志搜集导出
- [x] `LogCollector` 搜集模块加载、拦截器注册、RecognizeResult、LLM 调用、错误异常日志
- [x] 日志中 API Key 已脱敏遮蔽
- [ ] 设置页"导出日志"可生成日志文件并通过分享面板导出（需真机验证，实现已就绪）

## Miuix 设置界面
- [x] 使用 `ThemeController(ColorSchemeMode.System)` + `MiuixTheme`，跟随系统深浅色
- [x] TopAppBar 标题为"环上LLM"
- [x] 设置页包含分组 Card：基本设置（启用/API类型/Base URL/API Key/模型）、生成参数（温度/top_p/top_k/思考模式/Prompt/超时/Max Token）、会话设置（模式/窗口/长度）、日志（导出）、关于（测试连接/版本）
- [x] UI 风格与 miuix-main 示例一致（TopAppBar、Card、SwitchPreference、OverlayDropdownPreference 等）
- [x] "测试连接"按钮可校验 API Key 并回显结果

## 端到端验证
- [ ] LSPosed 中启用模块后，手环语音问答实际被 LLM 回答替换（需真机 + LSPosed 环境，当前设备脱机）
- [ ] 关闭开关后消息透传正常（需真机）
- [ ] 超时场景下放行原始回答（需真机）
- [x] `./gradlew :app:assembleRelease` 构建通过
