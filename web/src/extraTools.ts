import type { Tool as PiTool } from "@mariozechner/pi-ai";

/**
 * Tool schemas for the R12-R23 backlog. Keep in sync with the registry in
 * MainActivity.kt (SYSTEM_API_TOOLS / AUTOMATION_TOOLS / EXTERNAL_DATA_TOOLS /
 * FILE_OPS_TOOLS / ZIP_TOOLS / EMAIL_TOOLS / STORE_TOOLS plus NFC/Termux).
 */
export const EXTRA_TOOL_DEFS = [
  { name: "web_search", description: "无需 API key 搜索互联网。Bing 网页搜索为主，DuckDuckGo 即时答案补充；返回标题、链接、摘要及来源。时效性问题先搜索。", parameters: { type: "object", properties: { query: { type: "string" }, limit: { type: "integer" }, mkt: { type: "string" } }, required: ["query"] } },
  { name: "web_read", description: "读取 HTTPS 网页正文，去除脚本、样式和标签；最多返回 max_chars 字符。", parameters: { type: "object", properties: { url: { type: "string" }, max_chars: { type: "integer" } }, required: ["url"] } },
  { name: "nfc_write_raw", description: "写入自定义 NDEF 记录：kind=mime/external/raw；payload 支持 base64: 或 hex: 前缀；mime 用 type 指定 MIME，external 用 domain:type。会覆盖卡片原内容，写入前先告知用户。", parameters: { type: "object", properties: { kind: { type: "string" }, payload: { type: "string" }, type: { type: "string" } }, required: ["payload"] } },
  { name: "nfc_hce_status", description: "查询设备是否支持 HCE 卡模拟及 AndAgent 注册的 AID。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "nfc_mf_dump", description: "等待贴 MIFARE Classic 卡（最多 60 秒），按 内置默认 key 集 + 自定义 key 库 + keys 参数 逐扇区尝试 KeyA/KeyB 认证，dump 全卡为 JSON + 十六进制/ASCII 双视图，并保存原始镜像到 Download/andagent/dumps/<uid>.bin。认证失败扇区逐扇区报告。keys 可传 12 位 hex（逗号/空格/换行分隔）或 MCT/Proxmark3 字典文本。合规：仅用于自有卡或获授权安全测试，MIFARE Classic 的 Crypto-1 已被破解，禁止用于未授权卡片。", parameters: { type: "object", properties: { keys: { type: "string", description: "可选：额外尝试的 key" } }, required: [] } },
  { name: "nfc_mf_write_block", description: "等待贴 MIFARE Classic 卡，向指定扇区块写入 16 字节。破坏性：必须 confirm=true；写前读原值、写后 read-back 校验。block 为扇区内块索引（0 起），key_hex 为 12 位 hex，key_type=a|b，data_hex 为 32 位 hex。块 0（厂商块/UID）默认拒绝，仅可变 UID 卡在 allow_block0=true 且 confirm_block0=true 双重确认后允许（写入可能使卡永久变砖，须如实告知用户）。合规：仅自有卡/授权测试。", parameters: { type: "object", properties: { sector: { type: "integer" }, block: { type: "integer" }, key_hex: { type: "string" }, key_type: { type: "string" }, data_hex: { type: "string" }, confirm: { type: "boolean" }, allow_block0: { type: "boolean" }, confirm_block0: { type: "boolean" } }, required: ["sector", "block", "key_hex", "data_hex", "confirm"] } },
  { name: "nfc_ul_read", description: "等待贴 MIFARE Ultralight/NTAG 卡，按页读取（每页 4 字节），返回每页 hex + ASCII 及卡型。pages_start 默认 0，count 默认 16（上限 64）。", parameters: { type: "object", properties: { pages_start: { type: "integer" }, count: { type: "integer" } }, required: [] } },
  { name: "nfc_ul_write_page", description: "等待贴 MIFARE Ultralight/NTAG 卡，向指定页写 4 字节（data_hex 为 8 位 hex）。破坏性：必须 confirm=true，写前读原值、写后 read-back 校验。页 0-3 为 UID/锁定位/OTP/CC 区（OTP 不可逆、UID 通常硬件只读），默认拒绝，确需操作可变 UID 卡加 allow_special=true。合规：仅自有卡/授权测试。", parameters: { type: "object", properties: { page: { type: "integer" }, data_hex: { type: "string" }, confirm: { type: "boolean" }, allow_special: { type: "boolean" } }, required: ["page", "data_hex", "confirm"] } },
  { name: "nfc_keys_manage", description: "管理 MIFARE Classic 自定义 key 库（持久化，dump 时自动并入尝试集）。action=list（默认）列内置默认集与自定义集；add/remove 配 key_hex（12 位 hex）；import 配 keys_text 导入 MIFARE Classic Tool(.keys/.dump) 或 Proxmark3 字典文本。内置默认 key 不可移除。合规：仅自有卡/授权测试。", parameters: { type: "object", properties: { action: { type: "string" }, key_hex: { type: "string" }, keys_text: { type: "string" } }, required: [] } },
  { name: "apt_status", description: "检测内嵌 Termux 的 apt/dpkg 是否可用；ELF 编译期 PREFIX 未重定位时明确标记不可用。", parameters: { type: "object", properties: {}, required: [] } },

  { name: "telephony_info", description: "读取移动网络状态：运营商、SIM 状态、网络类型、漫游、国家码；不读取 IMEI/手机号。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "data_usage", description: "读取设备累计流量：总/移动网络/本应用收发字节数。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "audio_state", description: "读取铃声模式、各音量流当前值与最大值、是否播放音乐。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "audio_set_volume", description: "设置音量流（music/ring/alarm/notification/system/voice）的绝对 level 或百分比 percent。", parameters: { type: "object", properties: { stream: { type: "string" }, level: { type: "integer" }, percent: { type: "integer" } }, required: [] } },
  { name: "media_library", description: "查询 MediaStore 集合 audio/video/files，返回名称、大小、时长与 content URI。", parameters: { type: "object", properties: { kind: { type: "string" }, limit: { type: "integer" } }, required: [] } },
  { name: "ringtone_set", description: "列出系统铃声；传 uri 可设置来电/通知/闹钟铃声（需 WRITE_SETTINGS 或 Shizuku），type=ringtone|notification|alarm。", parameters: { type: "object", properties: { uri: { type: "string" }, type: { type: "string" } }, required: [] } },
  { name: "wallpaper_set", description: "把本地图片路径或 content:// 图片设为桌面壁纸。", parameters: { type: "object", properties: { source: { type: "string" } }, required: ["source"] } },
  { name: "vibrate", description: "让设备振动；duration_ms 指定时长，pattern 为逗号分隔波形毫秒序列，repeat 为重复起始索引。", parameters: { type: "object", properties: { duration_ms: { type: "integer" }, pattern: { type: "string" }, repeat: { type: "integer" } }, required: [] } },

  { name: "torch", description: "开启或关闭手电筒，on=true 开启。", parameters: { type: "object", properties: { on: { type: "boolean" } }, required: ["on"] } },
  { name: "screen_state", description: "读取屏幕亮灭状态、亮度、休眠超时；wake=true 时经 Shizuku 唤醒。", parameters: { type: "object", properties: { wake: { type: "boolean" } }, required: [] } },
  { name: "brightness_set", description: "设置屏幕亮度 0-255，需 WRITE_SETTINGS 或 Shizuku。", parameters: { type: "object", properties: { value: { type: "integer" } }, required: ["value"] } },
  { name: "volume_dnd", description: "设置勿扰模式 mode=all|priority|alarms|silent，需勿扰访问权限。", parameters: { type: "object", properties: { mode: { type: "string" } }, required: ["mode"] } },
  { name: "installed_app_details", description: "查询指定包名应用的版本、目标 SDK、权限、安装时间与 APK 体积。", parameters: { type: "object", properties: { package: { type: "string" } }, required: ["package"] } },
  { name: "usage_stats", description: "查询近 days 天应用前台使用时长，需系统「使用情况访问」权限。", parameters: { type: "object", properties: { days: { type: "integer" }, limit: { type: "integer" } }, required: [] } },
  { name: "biometric_check", description: "检查设备生物识别硬件与录入状态，不弹认证框。", parameters: { type: "object", properties: {}, required: [] } },

  { name: "alarm_wait", description: "读取系统下一个闹钟的时间与剩余毫秒数。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "countdown", description: "进程内倒计时：action=start（需 seconds）|list|cancel（需 id）；到点发本地通知。", parameters: { type: "object", properties: { action: { type: "string" }, seconds: { type: "integer" }, label: { type: "string" }, id: { type: "string" } }, required: [] } },
  { name: "calendar_reminder_check", description: "查询未来 days 天带提醒的日历事件及提前分钟数。", parameters: { type: "object", properties: { days: { type: "integer" }, limit: { type: "integer" } }, required: [] } },
  { name: "hotspot", description: "action=status 查询热点状态，open_settings 打开热点设置，toggle 经 Shizuku 尝试开关（需 on）。", parameters: { type: "object", properties: { action: { type: "string" }, on: { type: "boolean" } }, required: [] } },
  { name: "airplane", description: "action=status 读取飞行模式，on/off 经 Shizuku 切换。", parameters: { type: "object", properties: { action: { type: "string" } }, required: [] } },
  { name: "tts", description: "用系统 TTS 朗读文本；language 为 BCP-47 标签，rate 为 0.5..2.0。", parameters: { type: "object", properties: { text: { type: "string" }, language: { type: "string" }, rate: { type: "string" } }, required: ["text"] } },
  { name: "screenshot_quick", description: "快速截取当前屏幕，返回缩略图与保存路径（等同 screen_capture）。", parameters: { type: "object", properties: {}, required: [] } },

  { name: "contacts_search", description: "按姓名/号码搜索通讯录，返回姓名、电话、邮箱；需 READ_CONTACTS。", parameters: { type: "object", properties: { keyword: { type: "string" }, limit: { type: "integer" } }, required: [] } },
  { name: "sms_read", description: "只读查询短信：address 按号码、keyword 搜正文、days 限定时间；需 READ_SMS。", parameters: { type: "object", properties: { address: { type: "string" }, keyword: { type: "string" }, days: { type: "integer" }, limit: { type: "integer" } }, required: [] } },
  { name: "provider_query", description: "通用只读 ContentProvider 查询：uri 必填，可选 projection（逗号分隔）、selection、selection_args（JSON 数组字符串）、sort、limit。", parameters: { type: "object", properties: { uri: { type: "string" }, projection: { type: "string" }, selection: { type: "string" }, selection_args: { type: "string" }, sort: { type: "string" }, limit: { type: "integer" } }, required: ["uri"] } },
  { name: "file_share", description: "用 FileProvider 把应用私有/公共下载目录里的文件生成 content URI 并打开系统分享面板。", parameters: { type: "object", properties: { path: { type: "string" }, mime: { type: "string" } }, required: ["path"] } },
  { name: "saf_tree_pick", description: "打开系统目录选择器并申请 SAF DocumentsTree 持久读写授权。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "saf_tree_list", description: "列出已授权的 SAF 目录树；传 tree_uri 时列出该目录下的文件与 document URI。", parameters: { type: "object", properties: { tree_uri: { type: "string" } }, required: [] } },
  { name: "saf_tree_read", description: "读取 SAF 文档内容（document_uri），最多 100KB。", parameters: { type: "object", properties: { document_uri: { type: "string" } }, required: ["document_uri"] } },
  { name: "saf_tree_write", description: "在已授权 SAF 目录树中新建文档：filename、content 必填，可指定 tree_uri 与 mime。", parameters: { type: "object", properties: { tree_uri: { type: "string" }, filename: { type: "string" }, content: { type: "string" }, mime: { type: "string" } }, required: ["filename", "content"] } },

  { name: "file_copy", description: "复制文件/目录：source、dest 必填；目标已存在时须 confirm=true。", parameters: { type: "object", properties: { source: { type: "string" }, dest: { type: "string" }, confirm: { type: "boolean" } }, required: ["source", "dest"] } },
  { name: "file_move", description: "移动文件/目录：source、dest 必填；目标已存在时须 confirm=true。", parameters: { type: "object", properties: { source: { type: "string" }, dest: { type: "string" }, confirm: { type: "boolean" } }, required: ["source", "dest"] } },
  { name: "file_delete", description: "删除文件或空目录：path、confirm=true 必填；非空目录需 recursive=true。", parameters: { type: "object", properties: { path: { type: "string" }, recursive: { type: "boolean" }, confirm: { type: "boolean" } }, required: ["path", "confirm"] } },
  { name: "file_rename", description: "重命名：path、name、confirm=true 必填，name 不能含路径分隔符。", parameters: { type: "object", properties: { path: { type: "string" }, name: { type: "string" }, confirm: { type: "boolean" } }, required: ["path", "name", "confirm"] } },
  { name: "file_mkdir", description: "创建目录（自动创建多级父目录），path 必填。", parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] } },
  { name: "file_hash", description: "计算文件哈希：algorithm=md5|sha1|sha256。", parameters: { type: "object", properties: { path: { type: "string" }, algorithm: { type: "string" } }, required: ["path"] } },
  { name: "file_info", description: "查看文件/目录的大小、时间、权限、扩展名、子项数。", parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] } },

  { name: "zip_create", description: "创建标准 ZIP：path 与 sources_json（路径 JSON 数组）必填；覆盖已有文件需 confirm=true。", parameters: { type: "object", properties: { path: { type: "string" }, sources_json: { type: "string" }, confirm: { type: "boolean" } }, required: ["path", "sources_json"] } },
  { name: "zip_list", description: "列出 ZIP 条目名称、原始与压缩大小、时间、CRC。", parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] } },
  { name: "zip_extract", description: "解压 ZIP 到 dest；可用 entries_json 指定条目，覆盖需 confirm=true；带路径穿越防护。", parameters: { type: "object", properties: { path: { type: "string" }, dest: { type: "string" }, entries_json: { type: "string" }, confirm: { type: "boolean" } }, required: ["path", "dest"] } },
  { name: "zip_add", description: "向已有 ZIP 追加文件（sources_json），同名顶层条目会被替换。", parameters: { type: "object", properties: { path: { type: "string" }, sources_json: { type: "string" } }, required: ["path", "sources_json"] } },
  { name: "zip_test", description: "校验 ZIP 每个条目的 CRC，并检测传统加密条目（加密 ZIP 不支持）。", parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] } },

  { name: "email_send", description: "通过 SMTP 直发邮件：host、from、to、subject、body 必填；port/security(ssl|starttls|none)/user/password 可选，html=true 发 HTML，attachments_json 为附件路径 JSON 数组。密码不存储。", parameters: { type: "object", properties: { host: { type: "string" }, port: { type: "integer" }, security: { type: "string" }, user: { type: "string" }, password: { type: "string" }, from: { type: "string" }, to: { type: "string" }, cc: { type: "string" }, subject: { type: "string" }, body: { type: "string" }, html: { type: "boolean" }, attachments_json: { type: "string" } }, required: ["host", "from", "to", "subject", "body"] } },
  { name: "email_verify", description: "验证 SMTP 连接与登录（不发送邮件）：host 必填，port/security/user/password 可选。", parameters: { type: "object", properties: { host: { type: "string" }, port: { type: "integer" }, security: { type: "string" }, user: { type: "string" }, password: { type: "string" } }, required: ["host"] } },
  { name: "email_draft", description: "用系统邮件应用打开草稿（mailto 回退）：to、subject、body。", parameters: { type: "object", properties: { to: { type: "string" }, subject: { type: "string" }, body: { type: "string" } }, required: ["to"] } },

  { name: "fdroid_search", description: "在 F-Droid 官方仓库索引中按关键词搜索开源应用，返回包名、名称、简介、最新版本。", parameters: { type: "object", properties: { keyword: { type: "string" }, limit: { type: "integer" } }, required: ["keyword"] } },
  { name: "fdroid_download", description: "下载指定 F-Droid 包名的最新 APK 到 Downloads/AndAgent，并校验 SHA-256。", parameters: { type: "object", properties: { package: { type: "string" } }, required: ["package"] } },
  { name: "app_install", description: "经 Shizuku 静默安装 APK：path 与 confirm=true 必填。", parameters: { type: "object", properties: { path: { type: "string" }, confirm: { type: "boolean" } }, required: ["path", "confirm"] } },

  { name: "devtools_status", description: "查询 WebView 远程调试开关状态与可调试页面列表（序号/标题/URL）。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "devtools_debugging", description: "运行时开关 WebView 远程调试（release 包同样生效，持久化到设置）。开启后 devtools_* 工具与 USB chrome://inspect 均可用。", parameters: { type: "object", properties: { enable: { type: "boolean" } }, required: ["enable"] } },
  { name: "devtools_eval", description: "在指定页面执行 JavaScript 并返回结果（awaitPromise，可做 DOM 检查/抓取内容）。page 为 devtools_status 的序号或 URL/标题关键词，缺省第一个页面；web_embed 打开的页面可用此工具检查。", parameters: { type: "object", properties: { page: { type: "string" }, js: { type: "string" } }, required: ["js"] } },
  { name: "devtools_console", description: "读取指定页面的 console 输出缓存（log/warn/error 与未捕获异常，自建立监听起）。先用 devtools_eval 让页面产生输出再读取。", parameters: { type: "object", properties: { page: { type: "string" }, lines: { type: "integer" } }, required: [] } },
  { name: "devtools_screenshot", description: "截取指定页面当前画面为 PNG 存入相册 Pictures/AndAgent；缩略图由工具卡片展示。", parameters: { type: "object", properties: { page: { type: "string" } }, required: [] } },
] satisfies PiTool[];
