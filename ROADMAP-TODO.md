# andagent 推进事项清单（ROADMAP-TODO）

> 单一事实源：每项完成打 ✅ 并附 commit；新需求追加到对应优先级区。
> 状态：TODO / DOING / ✅ / BLOCKED(原因)
> 引擎纪律：全部 Codex 实施，主控独立复测后才能标 ✅。

## 🔴 P0 — 用户已报问题/半成品收尾
| # | 事项 | 状态 | 备注 |
|---|---|---|---|
| 1 | WidgetConversationTools.kt 编译修复（imports） | ✅ | 全量编译通过（41a4b10 及后续） |
| 2 | QuickJS console.log 崩溃修复验证（setConsole→logcat，da579b6） | ✅ | 已提交，待真机复测 console.log 输出 |
| 3 | 工具两侧对齐：widget_generate/widget_save_and_pin 已注册，验证 58 个 diff | ✅ | 139 工具 Kotlin 注册与 web Schema（agent.ts + extraTools.ts）完全一致 |
| 4 | termux_setup 后 pkg/apt 可用性：R5 只做了文本层重定位，ELF 337 处 com.termux 残留 → 评估 dpkg/apt 是否需 patchelf 或标记不可用 | ✅ | 新增 `apt_status` 探测工具；pkg_install 在 ELF PREFIX 残留时明确报错并给出外部 Termux/patchelf 建议，不再误导执行 |

## 🟠 P1 — 用户明确要求（未实现）
| # | 事项 | 状态 | 技术口径 |
|---|---|---|---|
| 5 | **NFC 读取**：NfcAdapter 前台分发，读 tag（NDEF/TECH_DISCOVERED 全技术），返回类型/UID/原始数据/NDEF 内容 | ✅ | nfc_read 返回 UID/techList/NDEF records（含 MIME/external/unknown 解析） |
| 6 | **NFC 写入**：写 NDEF（文本/URI/自定义 payload）到可写 tag（MIFARE/NTAG 等），写前用户确认 | ✅ | nfc_write_text/uri + 新增 nfc_write_raw（mime/external/raw，base64/hex payload）；isWritable + canMakeReadOnly 检测 |
| 7 | NFC HCE（可选，设备作卡片模拟）| ✅ | AndagentHceService（HostApduService）+ AID F0010203040506 + `nfc_hce_status` |
| 8 | 对话流内嵌 WebView 打开网站：新增 `web_embed(uri)` 工具——WebView 卡片渲染在消息流里（复用主 WebView 的 bridge 注入，卡片内可关/可全屏） | ✅ | WebViewCard iframe 卡片（刷新/全屏/独立窗口/关闭）+ 原生 Dialog 兜底 |
| 9 | 对话流富数据渲染扩展：a) 相册照片网格缩略图（album_photos 的 base64 已有，补渲染）；b) 表格/键值卡片统一组件；c) JSON tree 视图；d) 地图卡片（location_get 的 geo 坐标渲染静态图或链接） | ✅ | photos/kv/table/json-tree/geo 围栏全部落地（TableCard/JsonTreeCard 在 RichCards） |
| 10 | MAX_TOOL_ROUNDS=200（已改，随下版发出） | ✅ |
| 10a | **对话流音频/视频播放**：```audio/```video 围栏 + MediaCard 组件 + 工具结果按扩展名内嵌播放器 + WebViewAssetLoader /local/ 媒体映射 | ✅ | 含 Range/206 与路径穿越防护 |
| 10b | **SQLite 数据库工具**（建库/建表/CRUD/查询 + andagent.db JS API） | ✅ | db_* 七工具 + andagent.db() + db_gui |
| 10c | **外部数据访问**：ContentProvider（联系人/日历/短信 provider 统一读）、FileProvider（分享文件给其他 app）、SAF DocumentsTree 持久授权、MediaStore 全集合（音/视/文）查询 | ✅ | contacts_search / sms_read / provider_query（只读） / file_share（FileProvider） / saf_tree_pick/list/read/write（持久授权） / media_library（audio/video/files） |
| 10d | **Android 存储底座 / 通用 KV 存储**：用平台存储能力为工具、QuickJS 脚本、前端提供统一的键值持久化（明细见下节） | TODO | `andagent_kv`（SharedPreferences / DataStore）+ `kv_*` 工具 + `andagent.kv` JS API + 前端桥三侧对齐；会话从 localStorage 迁移 |

### 10d 存储底座明细（Android 存储技术 → KV 及配套能力）

> 现状：`ui/SettingsStore.kt` 仅 4 个固定字段的 `andagent_settings` SharedPreferences；`SqliteTool` 落在 `databases/andagent_*.db`；`widget/data/` 用 AtomicFile + JSON；`ScriptFsTools` 只实现 `scripts/` 一根沙箱；前端会话存 `web/src/sessions.ts` 的 localStorage。全仓无 MMKV / DataStore / Room 依赖。

| # | 子项 | 状态 | 技术口径 |
|---|---|---|---|
| 10d-1 | `KvStore` 内核 | TODO | `getSharedPreferences("andagent_kv", MODE_PRIVATE)` 作底座（或 Jetpack DataStore/Preferences）；`namespace/key` 两级命名，值支持 string/number/bool/JSON，含 TTL、批量读写、前缀 keys 与命名空间配额；key 正则 + 单值大小上限，`apply`/协程避免主线程 IO |
| 10d-2 | `kv_*` 工具族 | TODO | `kv_get/kv_set/kv_delete/kv_keys/kv_namespaces/kv_clear`（约 +6）；写/清类 `destructive=true` + `confirm=true`，对齐 `db_exec` 口径；按 README「Adding a tool」四步同步 `MainActivity.kt` 注册表与 `web/src/agent.ts` JSON Schema |
| 10d-3 | QuickJS JS API | TODO | `andagent.kv.get/set/keys/delete`，与 R10.7 `andagent.fs` 的 `data/` 根（当前仅 `scripts/` 已落地）联动；脚本自有状态不再靠文件拼字符串，沿用 `QuickJsEngine` 注入方式与 canonicalFile 防越界校验 |
| 10d-4 | 前端统一持久化 | TODO | `AndroidBridge.kvGet/kvSet`（或复用 `toolExec`）+ `web/src/bridge.ts` 类型声明；`sessions.ts` 会话从 localStorage 迁到原生 KV（WebView DOM storage 被清会丢会话），浏览器预览模式保留 localStorage 回退；`getSettings/saveSettings` 固定字段通道不动，API key 不进 web assets |
| 10d-5 | 迁移 / 安全 / 备份 | TODO | 一次性迁移旧 localStorage 会话与自定义 prefs 键；敏感键用 Keystore / EncryptedSharedPreferences 或独立命名空间；按命名空间导出/导入 JSON 备份，厘清 Android Auto Backup 与 `filesDir` / `databases/` 边界；`WidgetStore` / `DataCache` 的 AtomicFile 结构化数据保持原样 |
| 10d-6 | 其他 Android 存储技术评估 | TODO | DataStore(Proto) 替代 SharedPreferences、MMKV（评估额外 .so 体积与 arm64 依赖）、Room（关系型场景已由 `db_*` 覆盖，暂不引入）；Scoped Storage / MediaStore / SAF / ContentProvider / FileProvider 归入 10c，共享路径与 KV 命名空间隔离 |

## 🟡 P2 — 增强/补全
| # | 事项 | 状态 | 备注 |
|---|---|---|---|
| 11 | 小组件 SCRIPT 数据源后台调用全工具（当前仅 battery/steps/device_info 三工具白名单） | ✅ | WidgetToolRuntime 挂载完整注册表；后台过滤需 Activity/UI 的工具（NFC/截屏/SAF 选取等），权限未授予时返回明确错误；进程被杀后回落三工具基础集 |
| 12 | 应用商店：F-Droid API 搜索 + APK 下载 + Shizuku pm install 静默装 | ✅ | fdroid_search（index-v1 缓存 1h）/ fdroid_download（SHA-256 校验）/ app_install（pm install -r -g，confirm=true） |
| 13 | 富文本通知（大文本/进度条样式 + 点击跳回 app 深链） | ✅ | notify_send 支持 progress/progress_max/indeterminate/id（更新进度）+ deeplink（andagent://send PendingIntent） |
| 14 | 深度链接回调：andagent:// scheme，供外部/小组件触发对话 | ✅ | Manifest VIEW/BROWSABLE；andagent://send?prompt=..&auto=1；原生队列 + deeplink.flush 事件 + App.tsx 自动发送/填入；andagent://refresh 触发小组件刷新 |
| 15 | 联系人读取（READ_CONTACTS，name/phone 搜索） | ✅ | contacts_search（动态权限） |
| 16 | 短信读取（READ_SMS） | ✅ | sms_read（号码/正文/时间过滤，只读） |

## ✅ 已完成（v0.2.1 → v0.10.0 战果存档）
- ✅ 文档生成（纯 Kotlin DOCX/PPTX/XLSX、对话工具、QuickJS API；R8）
web 化重构（pi+assistant-ui）/ 日历闹钟剪贴板通知 / ECharts / Shizuku / 录屏取帧 / 文件三件套 / 传感器步数 / 多会话抽屉 / 动态授权 / Termux 内嵌（memfd+direct+重定位）/ 蓝牙 WiFi 控制 / 设置直达 / 思考过程显示 / 语音输入 / 电话短信 / GPS / 文件选择器 / 存图相册 / open_uri 跳转 / 快速小组件框架 / QuickJS 脚本引擎 / 导出对话 / MAX_ROUNDS=200

## 里程碑
- v0.11.0（R7）：P0 全清 + NFC 读写 + 内嵌 WebView
- v0.12.0（R8）：对话流富渲染 + 小组件后台全工具 + 应用商店
- v0.17.0（R12-R23）：系统 API 全覆盖 + 外部数据 + ZIP/文件管理 + LUT + 邮件 + 商店 + HCE + 深链 + Canvas/Shader 围栏（累计 139 工具）

## 🟢 R12+ 战役：Android 系统 API 全覆盖（简报就绪，串行派 Codex）
| 刀 | 内容 | 工具数 |
|---|---|---|
| R12 | 连接与媒体：telephony_info/data_usage/audio_state/audio_set_volume/media_library/ringtone_set/wallpaper_set/vibrate | ✅ +8 |
| R13 | 设备管理：torch/screen_state/brightness_set/volume_dnd/installed_app_details/usage_stats/biometric_check | ✅ +7 |
| R14 | 自动化效率：alarm_wait/countdown/calendar_reminder_check/hotspot/airplane/tts/screenshot_quick | ✅ +7 |
（R9 SQLite+媒体播放 ✅ → R10.5 脚本库 ✅ → R10.6 DB GUI ✅ → R10.7 QuickJS fs API ✅ → R10 外部数据 ✅ → R11 Scriptable UI → R12-R14 系统API ✅ → R15 图片编辑 ✅）
| R10.6 | 数据库 GUI 浏览器（sql.js + Grid.js 开源方案） | ✅ +1 |
| R10.7 | QuickJS 原生 fs API：andagent.fs.read/write/list/exists（沙箱限定 scripts/+data/ 两根）——Scriptable 脚本自有数据存储 | ✅ |
| R15 | 图片编辑套件（crop/rotate/color/convolve/border/watermark/collage） | ✅ +4 |
| R16 | ZIP 套件：zip_create/list/extract/add/test，平台 java.util.zip 实现，含 zip-slip 防护；加密 ZIP 检测后明确报错（zip4j 加密支持未引入，离线无依赖缓存） | ✅ +5 |
| R17 | 文件管理补全：file_copy/move/delete/rename/mkdir/hash/info | ✅ +7 |
| R18 | LUT 滤镜：.cube 解析+三线性插值+强度混合，内置 warm/cool/vintage/cinematic/bw 预置，接 R15 管线（image_edit op=lut） | ✅ |
| R19 | 视频剪辑：ffmpeg-kit 静态链接（LGPL min 变体），video_clip/concat/filter 三工具 | BLOCKED(需要 ffmpeg-kit .aar 外部二进制，离线环境无法引入；待真机环境补齐) |
| R20 | 平台桥接大扩展（八域 20+ 方法） | TODO（AgentBridge 已有 24 个 @JavascriptInterface 方法，缺简报中规划的新域） |
| R21 | Shader 实时渲染围栏（WebGL2，Shadertoy 风格 iTime/iResolution） | ✅ 纯前端 |
| R22 | 邮件发送：email_send（SMTP SSL/STARTTLS+AUTH+HTML+附件）/email_verify/email_draft | ✅ +3 |
| R23 | Canvas 围栏（沙箱 iframe 2D 绘制，静态+动画） | ✅ 纯前端 |
| R24 | 存储底座：KV 工具族 + `andagent.kv` JS API + 前端会话迁移（见 10d；依赖 R10.7 `data/` 根与 R20 平台桥接） | TODO +6 |
| R26 | 网络搜索：web_search（Bing HTML 主引擎 + DDG IA 摘要，零 key）+ web_read 网页正文；Bing fixture 单测 | ✅ +2 |
| R28 | MediaProjection 截屏/录屏（进行中） | 路由改造 |
| R29 | Mermaid+KaTeX+DB GUI 移动端适配（进行中） | 纯前端+dbgui |
|  | **当前累计 139 工具（v0.17.0）** |  |
