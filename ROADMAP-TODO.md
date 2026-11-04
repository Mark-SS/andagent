# andagent 推进事项清单（ROADMAP-TODO）

> 单一事实源：每项完成打 ✅ 并附 commit；新需求追加到对应优先级区。
> 状态：TODO / DOING / ✅ / BLOCKED(原因)
> 引擎纪律：全部 Codex 实施，主控独立复测后才能标 ✅。

## 🔴 P0 — 用户已报问题/半成品收尾
| # | 事项 | 状态 | 备注 |
|---|---|---|---|
| 1 | WidgetConversationTools.kt 编译修复（imports） | DOING | strArg/SpecCodec/PermissionBroker import 已补，编译中 |
| 2 | QuickJS console.log 崩溃修复验证（setConsole→logcat，da579b6） | ✅ | 已提交，待真机复测 console.log 输出 |
| 3 | 工具两侧对齐：widget_generate/widget_save_and_pin 已注册，验证 58 个 diff | DOING | |
| 4 | termux_setup 后 pkg/apt 可用性：R5 只做了文本层重定位，ELF 337 处 com.termux 残留 → 评估 dpkg/apt 是否需 patchelf 或标记不可用 | TODO | bash/python 直跑层已可用 |

## 🟠 P1 — 用户明确要求（未实现）
| # | 事项 | 状态 | 技术口径 |
|---|---|---|---|
| 5 | **NFC 读取**：NfcAdapter 前台分发，读 tag（NDEF/TECH_DISCOVERED 全技术），返回类型/UID/原始数据/NDEF 内容 | TODO | Manifest NFC + 依赖 NDEF_PUSH 无需；无 NFC 设备降级报错 |
| 6 | **NFC 写入**：写 NDEF（文本/URI/自定义 payload）到可写 tag（MIFARE/NTAG 等），写前用户确认 | TODO | destructive=true；NDEF WriteProtected 检测 |
| 7 | NFC HCE（可选，设备作卡片模拟）| TODO | 需要 HostApduService + AID 注册，评估后做 |
| 8 | 对话流内嵌 WebView 打开网站：新增 `web_embed(uri)` 工具——WebView 卡片渲染在消息流里（复用主 WebView 的 bridge 注入，卡片内可关/可全屏） | TODO | 前端 ChartCard 模式扩展 WebViewCard |
| 9 | 对话流富数据渲染扩展：a) 相册照片网格缩略图（album_photos 的 base64 已有，补渲染）；b) 表格/键值卡片统一组件；c) JSON tree 视图；d) 地图卡片（location_get 的 geo 坐标渲染静态图或链接） | TODO | 前端为主，零 Kotlin 改动 |
| 10 | MAX_TOOL_ROUNDS=200（已改，随下版发出） | ✅ |
| 10a | **对话流音频/视频播放**：```audio/```video 围栏 + MediaCard 组件 + 工具结果按扩展名内嵌播放器 + WebViewAssetLoader /local/ 媒体映射 | TODO | 已并入 R9 简报 |
| 10b | **SQLite 数据库工具**（建库/建表/CRUD/查询 + andagent.db JS API） | TODO | R9 简报就绪 |
| 10c | **外部数据访问**：ContentProvider（联系人/日历/短信 provider 统一读）、FileProvider（分享文件给其他 app）、SAF DocumentsTree 持久授权、MediaStore 全集合（音/视/文）查询 | TODO | R10 | |

## 🟡 P2 — 增强/补全
| # | 事项 | 状态 | 备注 |
|---|---|---|---|
| 11 | 小组件 SCRIPT 数据源后台调用全工具（当前仅 battery/steps/device_info 三工具白名单） | TODO | R6 遗留边界，需评估后台权限面 |
| 12 | 应用商店：F-Droid API 搜索 + APK 下载 + Shizuku pm install 静默装 | TODO | |
| 13 | 富文本通知（大文本/进度条样式 + 点击跳回 app 深链） | TODO | notify_send 已有基础 |
| 14 | 深度链接回调：andagent:// scheme，供外部/小组件触发对话 | TODO | 与 widget click 字段联动 |
| 15 | 联系人读取（READ_CONTACTS，name/phone 搜索）| TODO | 用户未明确要求，评估隐私面 |
| 16 | 短信读取（READ_SMS） | TODO | 同上，权限敏感 |

## ✅ 已完成（v0.2.1 → v0.10.0 战果存档）
- ✅ 文档生成（纯 Kotlin DOCX/PPTX/XLSX、对话工具、QuickJS API；R8）
web 化重构（pi+assistant-ui）/ 日历闹钟剪贴板通知 / ECharts / Shizuku / 录屏取帧 / 文件三件套 / 传感器步数 / 多会话抽屉 / 动态授权 / Termux 内嵌（memfd+direct+重定位）/ 蓝牙 WiFi 控制 / 设置直达 / 思考过程显示 / 语音输入 / 电话短信 / GPS / 文件选择器 / 存图相册 / open_uri 跳转 / 快速小组件框架 / QuickJS 脚本引擎 / 导出对话 / MAX_ROUNDS=200

## 里程碑
- v0.11.0（R7）：P0 全清 + NFC 读写 + 内嵌 WebView
- v0.12.0（R8）：对话流富渲染 + 小组件后台全工具 + 应用商店

## 🟢 R12+ 战役：Android 系统 API 全覆盖（简报就绪，串行派 Codex）
| 刀 | 内容 | 工具数 |
|---|---|---|
| R12 | 连接与媒体：telephony_info/data_usage/audio_state/audio_set_volume/media_library/ringtone_set/wallpaper_set/vibrate | +8 |
| R13 | 设备管理：torch/screen_state/brightness_set/volume_dnd/installed_app_details/usage_stats/biometric_check | +7 |
| R14 | 自动化效率：alarm_wait/countdown/calendar_reminder_check/hotspot/airplane/tts/screenshot_quick | +6 |
（R9 SQLite+媒体播放 → R10.5 脚本库 ✅ → R10.6 DB GUI → R10.7 QuickJS fs API → R10 外部数据 → R11 Scriptable UI → R12-R14 系统API → R15 图片编辑）
| R10.6 | 数据库 GUI 浏览器（sql.js + Grid.js 开源方案，简报就绪，进行中） | +1 |
| R10.7 | QuickJS 原生 fs API：andagent.fs.read/write/list/exists（沙箱限定 scripts/+data/ 两根）——Scriptable 脚本自有数据存储 | +5 |
| R15 | 图片编辑套件（简报就绪） | +4 |
| R16 | ZIP 套件（zip4j 业界标准库，含加密 zip 支持） | +5 |
| R17 | 文件管理补全（简报就绪） | +7 |
| R18 | LUT 滤镜：.cube 解析+3D 插值+强度混合，内置预置色调，接 R15 管线（简报就绪） | +3 |
| R19 | 视频剪辑：ffmpeg-kit 静态链接（LGPL min 变体），video_clip/concat/filter 三工具（简报就绪） | +3 |
| R20 | 平台桥接大扩展（八域 20+ 方法，简报就绪） | +10~20 |
| R21 | Shader 实时渲染围栏（WebGL2，简报就绪） | 纯前端 |
| R22 | 邮件发送（简报就绪） | +4 |
| R23 | Canvas 围栏（简报就绪） | 纯前端 |
|  | **R19 完成后累计约 90+ 工具** |  |
