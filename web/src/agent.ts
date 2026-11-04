import type {
  AssistantMessage,
  AssistantMessageEvent,
  Message as PiMessage,
  Model,
  Tool as PiTool,
  ToolCall,
  ToolResultMessage,
  UserMessage,
} from "@mariozechner/pi-ai";
import type { ThreadMessageLike } from "@assistant-ui/react";
import { execTool, type Settings } from "./bridge";
import { PERMISSION_GUIDE } from "./permissionGuide";
import { SessionStore, type SessionMeta } from "./sessions";

type PiModule = typeof import("@mariozechner/pi-ai");

const MAX_TOOL_ROUNDS = 200;

let piPromise: Promise<PiModule> | null = null;

/** Load the esbuild-produced pi bundle from the app's asset root. */
export function loadPi(): Promise<PiModule> {
  if (!piPromise) {
    piPromise = (async () => {
      const url = new URL("pi-bundle.js", document.baseURI).href;
      const mod = (await import(/* @vite-ignore */ url)) as PiModule;
      mod.registerBuiltInApiProviders();
      return mod;
    })();
  }
  return piPromise;
}

const TOOL_DEFS = [
  {
    name: "get_time",
    description:
      "获取当前日期、时间、时区与星期。用户问现在几点、今天几号、今天星期几时使用。",
    parameters: {
      type: "object",
      properties: {
        timezone: {
          type: "string",
          description: "IANA 时区，例如 Asia/Shanghai。省略则使用设备本地时区。",
        },
      },
      required: [],
    },
  },
  {
    name: "device_info",
    description: "获取设备信息：型号、Android 版本、存储空间、内存、电量。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "storage_scan",
    description:
      "分析存储占用：剩余/总空间、下载/影片/音乐/文档目录下最大的文件、应用缓存大小。",
    parameters: {
      type: "object",
      properties: {
        top_n: { type: "integer", description: "每个目录列出最大的 N 个文件，默认 10" },
        path: { type: "string", description: "可选：只分析指定目录" },
      },
      required: [],
    },
  },
  {
    name: "album_stats",
    description: "相册快速统计：截图数量与体积、按年份的照片数量与体积。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "calendar_list",
    description: "列出设备上的日历账户（id、名称、所属账户、是否可写）。写入事件前可先用它选择 calendar_id。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "calendar_events",
    description:
      "查询日历事件：按时间窗 start/end 过滤，返回标题、开始/结束时间、地点、所属日历。最多 100 条。",
    parameters: {
      type: "object",
      properties: {
        start: { type: "string", description: "时间窗开始，例如 2026-11-01 或 2026-11-01 08:00；默认 30 天前" },
        end: { type: "string", description: "时间窗结束，格式同上；默认 30 天后" },
        calendar_id: { type: "integer", description: "可选：只看某个日历 id" },
      },
      required: [],
    },
  },
  {
    name: "calendar_add",
    description:
      "创建日历事件（会修改设备数据）：title/begin/end 必填，location/description/remindMinutes 可选。",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "事件标题" },
        begin: { type: "string", description: "开始时间，例如 2026-11-05 14:00" },
        end: { type: "string", description: "结束时间，格式同上" },
        location: { type: "string", description: "可选：地点" },
        description: { type: "string", description: "可选：备注" },
        remindMinutes: { type: "integer", description: "可选：提前多少分钟提醒" },
        calendar_id: { type: "integer", description: "可选：目标日历 id，默认第一个可写日历" },
      },
      required: ["title", "begin", "end"],
    },
  },
  {
    name: "calendar_delete",
    description: "按事件 id 删除日历事件（会修改设备数据）。先用 calendar_events 查询 id。",
    parameters: {
      type: "object",
      properties: {
        id: { type: "integer", description: "要删除的事件 id" },
      },
      required: ["id"],
    },
  },
  {
    name: "alarm_set",
    description: "唤起系统闹钟应用设置闹钟（小时/分钟/备注），由用户在系统界面确认。",
    parameters: {
      type: "object",
      properties: {
        hour: { type: "integer", description: "小时，0-23" },
        minutes: { type: "integer", description: "分钟，0-59" },
        message: { type: "string", description: "可选：闹钟备注" },
      },
      required: ["hour", "minutes"],
    },
  },
  {
    name: "timer_set",
    description: "唤起系统时钟应用设置倒计时（秒），由用户在系统界面确认。",
    parameters: {
      type: "object",
      properties: {
        seconds: { type: "integer", description: "倒计时秒数" },
        message: { type: "string", description: "可选：计时器备注" },
      },
      required: ["seconds"],
    },
  },
  {
    name: "battery_detail",
    description: "读取电池详情：电量百分比、充电状态、供电方式、健康度、温度、电压。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "wifi_info",
    description:
      "读取 Wi-Fi 信息：SSID、信号强度、IP、链路速度。定位权限被拒时会降级为网络类型。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  { name: "bt_scan", description: "扫描附近蓝牙设备，返回名称、MAC 和 RSSI；需蓝牙扫描与定位权限。", parameters: { type: "object", properties: { duration: { type: "integer", description: "秒数，默认 10，范围 1~30" } }, required: [] } },
  { name: "bt_paired_list", description: "列出已配对蓝牙设备的名称、MAC 与类型。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "bt_connect", description: "对已配对 BLE 设备尝试 GATT 连接；未配对设备发起系统配对。", parameters: { type: "object", properties: { mac: { type: "string", description: "设备 MAC" } }, required: ["mac"] } },
  { name: "wifi_connect", description: "打开系统 Wi-Fi 面板，用户手动选择 SSID。", parameters: { type: "object", properties: { ssid: { type: "string", description: "网络名称" } }, required: ["ssid"] } },
  { name: "wifi_toggle", description: "经 Shizuku 开关 Wi-Fi。", parameters: { type: "object", properties: { on: { type: "boolean", description: "true 开启" } }, required: ["on"] } },
  { name: "bt_toggle", description: "经 Shizuku 开关蓝牙。", parameters: { type: "object", properties: { on: { type: "boolean", description: "true 开启" } }, required: ["on"] } },
  { name: "wifi_connect_strong", description: "经 Shizuku 连接 Wi-Fi 并验证状态。", parameters: { type: "object", properties: { ssid: { type: "string", description: "网络名" }, password: { type: "string", description: "密码" }, security: { type: "string", description: "open|wpa2|wpa3" } }, required: ["ssid", "security"] } },
  { name: "wifi_saved_list", description: "经 Shizuku 列出已保存 Wi-Fi 网络。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "wifi_forget", description: "经 Shizuku 删除指定 SSID 的已保存网络。", parameters: { type: "object", properties: { ssid: { type: "string", description: "网络名" } }, required: ["ssid"] } },
  { name: "bt_connect_direct", description: "经 Shizuku 尝试直接连接蓝牙设备，若设备命令不支持则降级 GATT。", parameters: { type: "object", properties: { mac: { type: "string", description: "设备 MAC" } }, required: ["mac"] } },
  { name: "termux_setup", description: "安装并校验 Termux 用户空间；失败时检查 Termux App 回退。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "shell_exec", description: "在 Termux 用户空间执行 shell 命令。", parameters: { type: "object", properties: { cmd: { type: "string", description: "命令" }, timeout_s: { type: "integer", description: "超时秒数，默认 60" } }, required: ["cmd"] } },
  { name: "pkg_install", description: "安装 Termux 包；白名单外须 confirm=true。", parameters: { type: "object", properties: { pkg: { type: "string", description: "包名" }, confirm: { type: "boolean", description: "确认白名单外安装" } }, required: ["pkg"] } },
  { name: "widget_generate", description: "根据 spec_description 编写 WidgetSpec v2 JSON。schemaVersion=2，name，background=#RRGGBB，padding=0..32，root 为 COLUMN/ROW，children 节点为 COLUMN/ROW/TEXT/SPACER/PROGRESS；TEXT 可用 binding=DATA + source + field。data.sources 为名称到数据源的对象；类型 STATIC(value 对象)、HTTP(https url)、SYSTEM(system=BATTERY/CLOCK/NETWORK/LOCALE/STORAGE/DEVICE_INFO/BATTERY_DETAIL/STEPS_TODAY)、SCRIPT(script 字符串，定义 refresh(ctx) 返回扁平对象；可调用 andagent.tool('steps_today','{}') 并 JSON.parse)。支持 dependsOn、ttlSeconds、fallback、sample。最多 40 节点、6 层、8 数据源。调用后据此输出完整 spec，再调用 widget_save_and_pin。", parameters: { type: "object", properties: { spec_description: { type: "string", description: "用户希望的小组件内容与样式" } }, required: ["spec_description"] } },
  { name: "widget_save_and_pin", description: "验证并保存 WidgetSpec v2 JSON，然后请求添加到桌面。编辑时可在设置页改 JSON。", parameters: { type: "object", properties: { spec_json: { type: "string", description: "完整 WidgetSpec v2 JSON 字符串" } }, required: ["spec_json"] } },
  { name: "docx_create", description: "创建 Word 文档到 Downloads/andagent/。content_spec_json 是 JSON 字符串：{\"title\":\"季度报告\",\"paragraphs\":[{\"type\":\"heading\",\"text\":\"概览\",\"level\":2},{\"type\":\"p\",\"text\":\"正文\",\"bold\":true,\"italic\":false,\"size\":12,\"color\":\"#336699\",\"align\":\"left\"},{\"type\":\"bullet\",\"text\":\"要点\"},{\"type\":\"numbered\",\"text\":\"第一项\"},{\"type\":\"table\",\"headers\":[\"项目\",\"值\"],\"rows\":[[\"A\",\"1\"]]},{\"type\":\"pageBreak\"}]}。", parameters: { type: "object", properties: { filename: { type: "string" }, content_spec_json: { type: "string" } }, required: ["filename", "content_spec_json"] } },
  { name: "pptx_create", description: "创建 16:9 PowerPoint 到 Downloads/andagent/。slides_json 是 JSON 数组字符串：[{\"title\":\"季度报告\",\"bullets\":[\"要点一\",\"要点二\"],\"notes\":\"说明\"}]。也支持 texts:[{x,y,width,height,text,size,bold}]，坐标为英寸。", parameters: { type: "object", properties: { filename: { type: "string" }, slides_json: { type: "string" } }, required: ["filename", "slides_json"] } },
  { name: "xlsx_create", description: "创建 Excel 到 Downloads/andagent/。sheets_json 是 JSON 数组字符串：[{\"name\":\"统计\",\"rows\":[[\"项目\",\"数量\"],[\"A\",12],[\"B\",\"=B2*2\"]],\"columnWidths\":{\"1\":20}}]。数字保留为数值，= 开头为公式。", parameters: { type: "object", properties: { filename: { type: "string" }, sheets_json: { type: "string" } }, required: ["filename", "sheets_json"] } },
  { name: "script_save", description: "保存 JS 脚本到本地脚本库；覆盖已有文件须 confirm=true。保存前自动检查语法。", parameters: { type: "object", properties: { name: { type: "string" }, code: { type: "string" }, description: { type: "string" }, confirm: { type: "boolean" } }, required: ["name", "code"] } },
  { name: "script_list", description: "列出本地脚本库里的脚本、大小、修改时间和描述。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "script_read", description: "读取本地 JS 脚本内容；修改前先读。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "script_run", description: "从本地脚本库加载并执行 JS；args_json 对象注入全局 args。", parameters: { type: "object", properties: { name: { type: "string" }, args_json: { type: "string" }, timeout_s: { type: "integer" } }, required: ["name"] } },
  { name: "script_delete", description: "删除本地 JS 脚本；必须 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, confirm: { type: "boolean" } }, required: ["name"] } },
  { name: "script_check", description: "只检查 JS 语法，不运行源码。", parameters: { type: "object", properties: { code: { type: "string" } }, required: ["code"] } },
  { name: "json_fix", description: "修复并验证常见不规范 JSON：注释、尾逗号、单引号、未引号键、智能引号、截断容器、Python 值。", parameters: { type: "object", properties: { text: { type: "string" } }, required: ["text"] } },
  { name: "js_eval", description: "在 QuickJS 中执行脚本。脚本可用 andagent.tool(name, argsJson) 同步调用设备工具，andagent.log(msg) 记录日志；andagent.docx(specJson, filename)、andagent.pptx(specJson, filename)、andagent.xlsx(specJson, filename) 生成文档；最后表达式作为 JSON 结果。", parameters: { type: "object", properties: { script: { type: "string", description: "JavaScript 脚本" }, timeout_s: { type: "integer", description: "超时秒数，默认 30，最大 30" } }, required: ["script"] } },
  { name: "nfc_status", description: "查询设备是否支持 NFC 及开关状态。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "nfc_read", description: "等待贴卡最多 60 秒，读取 UID、技术和 NDEF 记录。调用前提示用户贴卡。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "nfc_write_text", description: "覆盖 NFC 卡片的 NDEF 内容为文本；调用前说明写入内容并提示贴卡。", parameters: { type: "object", properties: { text: { type: "string", description: "写入文本" } }, required: ["text"] } },
  { name: "nfc_write_uri", description: "覆盖 NFC 卡片的 NDEF 内容为 URI；调用前说明写入 URI 并提示贴卡。", parameters: { type: "object", properties: { uri: { type: "string", description: "写入 URI" } }, required: ["uri"] } },
  { name: "web_embed", description: "在对话中展示内嵌浏览器按钮，支持 HTTPS 网站。", parameters: { type: "object", properties: { uri: { type: "string", description: "HTTPS 网站地址" } }, required: ["uri"] } },
  { name: "screen_capture", description: "经 scrcpy-server 取一帧屏幕并返回 JPEG 缩略图。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "screen_record_h264", description: "用 scrcpy H.264 录屏到 Movies，可设置时长、码率和最大边长。", parameters: { type: "object", properties: { duration_s: { type: "integer", description: "秒数，默认 30" }, bit_rate: { type: "integer", description: "码率 bps" }, max_size: { type: "integer", description: "最大边长" } }, required: [] } },
  { name: "input_tap", description: "经 Shizuku 模拟屏幕点击。", parameters: { type: "object", properties: { x: { type: "integer", description: "横坐标" }, y: { type: "integer", description: "纵坐标" } }, required: ["x", "y"] } },
  { name: "input_swipe", description: "经 Shizuku 模拟屏幕滑动。", parameters: { type: "object", properties: { x1: { type: "integer", description: "起点 X" }, y1: { type: "integer", description: "起点 Y" }, x2: { type: "integer", description: "终点 X" }, y2: { type: "integer", description: "终点 Y" }, ms: { type: "integer", description: "时长毫秒" } }, required: ["x1", "y1", "x2", "y2"] } },
  { name: "input_text", description: "经 Shizuku 向当前焦点输入框输入文本。", parameters: { type: "object", properties: { s: { type: "string", description: "输入内容" } }, required: ["s"] } },
  {
    name: "clipboard_write",
    description: "把文本写入系统剪贴板，方便用户粘贴到其他应用。",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string", description: "要复制的文本" },
        label: { type: "string", description: "可选：剪贴板标签，默认 AndAgent" },
      },
      required: ["text"],
    },
  },
  {
    name: "clipboard_read",
    description: "读取系统剪贴板文本（Android 10+ 需要应用窗口有焦点，失败会给明确原因）。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "notify_send",
    description: "发送一条本地通知（标题 + 内容）。Android 13+ 会申请通知权限。",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "通知标题" },
        text: { type: "string", description: "通知内容" },
      },
      required: ["title", "text"],
    },
  },
  {
    name: "app_list",
    description: "列出已安装应用（包名、应用名、版本、是否系统应用），按安装时间倒序，最多 200 个。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "screen_record",
    description:
      "开始录屏（Shizuku 提权，无系统录屏授权弹窗）。duration 秒，默认 30，最大 300；立即返回保存路径，可调用 screen_record_stop 提前停止。需要先激活 Shizuku。",
    parameters: {
      type: "object",
      properties: {
        duration: { type: "integer", description: "录制秒数，默认 30，最大 300" },
      },
      required: [],
    },
  },
  {
    name: "screen_record_stop",
    description: "停止正在进行的 screen_record 录屏，并让媒体库扫描已保存的视频。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "screen_record_list",
    description:
      "列出已录制的屏幕视频（/sdcard/Movies/andagent_*.mp4），返回文件名、路径、大小、时长与修改时间。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "file_download",
    description:
      "用 HTTP GET 下载文件到 Downloads/andagent/，支持大文件与重定向；返回保存路径与大小。",
    parameters: {
      type: "object",
      properties: {
        url: { type: "string", description: "下载地址（http/https）" },
        filename: { type: "string", description: "可选：保存文件名，默认从 URL 推断" },
      },
      required: ["url"],
    },
  },
  {
    name: "file_search",
    description:
      "按文件名搜索用户文件，支持关键词或 *.pdf 这类通配符；返回路径、大小、类型、修改时间，最多 50 条。",
    parameters: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "文件名关键词或通配符，例如 报告、*.pdf、IMG_2026*",
        },
        dir: {
          type: "string",
          description: "可选：限定目录 downloads|documents|movies|music|pictures",
        },
      },
      required: ["query"],
    },
  },
  {
    name: "file_write",
    description:
      "写入文本文件。dir 为 downloads（Download/andagent/）、documents（Documents/）或 app_private（应用私有目录）；返回完整路径与字节数。",
    parameters: {
      type: "object",
      properties: {
        dir: { type: "string", description: "downloads | documents | app_private，默认 downloads" },
        name: { type: "string", description: "文件名，例如 notes.txt" },
        content: { type: "string", description: "文件内容（UTF-8）" },
      },
      required: ["name"],
    },
  },
  {
    name: "file_read",
    description:
      "读取文本文件。可用 path（绝对路径或 content://），也可用 dir（downloads|documents|app_private）+ name。上限 100KB，二进制文件返回错误。",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "文件绝对路径或 content:// URI" },
        dir: { type: "string", description: "与 name 搭配：downloads | documents | app_private" },
        name: { type: "string", description: "文件名（与 dir 搭配）" },
      },
      required: [],
    },
  },
  {
    name: "file_list",
    description:
      "列出 Download/andagent/（默认）或指定公共目录下的文件，返回名称、大小、修改时间，最多 100 条。",
    parameters: {
      type: "object",
      properties: {
        dir: { type: "string", description: "downloads（默认）|documents|movies|music|pictures" },
        subdir: { type: "string", description: "可选：子目录名；downloads 默认 andagent，其他默认根目录" },
      },
      required: [],
    },
  },
  {
    name: "sensor_list",
    description: "枚举设备全部传感器：名称、类型、厂商、分辨率、最大量程、功耗、最小采样间隔。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "sensor_read",
    description:
      "读取指定传感器当前值（采样 300ms 取平均）。支持 accelerometer|gyroscope|magnetometer|light|pressure|proximity|humidity|ambient_temp；返回数值、单位与物理含义。",
    parameters: {
      type: "object",
      properties: {
        sensor: {
          type: "string",
          description:
            "accelerometer | gyroscope | magnetometer | light | pressure | proximity | humidity | ambient_temp",
        },
      },
      required: ["sensor"],
    },
  },
  {
    name: "steps_today",
    description: "读取今天的步数（Health Connect，需要用户授权步数读取权限）。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "steps_history",
    description: "读取最近 N 天每日步数（Health Connect），默认 7 天，最多 30 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–30" },
      },
      required: [],
    },
  },
  {
    name: "open_settings",
    description:
      "打开 Android 系统设置的具体页面（WiFi/蓝牙/显示/声音/电池/存储/应用/位置/安全/日期时间/开发者选项等），只跳转不修改。用户说「打开XX设置」时使用。",
    parameters: {
      type: "object",
      properties: {
        panel: {
          type: "string",
          description:
            "wifi | bluetooth | display | sound | volume | battery | storage | apps | app_details | location | security | privacy | date_time | nfc | airplane | developer | accessibility | input | data_usage | notification | home | cast | vpn | print | main",
        },
        package: { type: "string", description: "可选：app_details 时指定应用包名" },
      },
      required: ["panel"],
    },
  },
  {
    name: "mic_record",
    description:
      "录制一段麦克风音频并保存为文件（M4A）。用于录音备忘、语音素材；返回文件路径。",
    parameters: {
      type: "object",
      properties: {
        seconds: { type: "integer", description: "录音时长（秒），默认 10，范围 1-300" },
      },
      required: [],
    },
  },
  {
    name: "phone_call",
    description:
      "拨打电话：直接拨打或打开拨号盘预填号码（无权限时由用户按通话键确认）。",
    parameters: {
      type: "object",
      properties: { number: { type: "string", description: "电话号码" } },
      required: ["number"],
    },
  },
  {
    name: "sms_send",
    description: "发送短信到指定号码（需要 SEND_SMS 权限，首次调用会弹授权）。",
    parameters: {
      type: "object",
      properties: {
        number: { type: "string", description: "接收方号码" },
        text: { type: "string", description: "短信内容" },
      },
      required: ["number", "text"],
    },
  },
  {
    name: "location_get",
    description:
      "获取当前 GPS 位置（经纬度/精度/海拔/速度）。首次调用会弹定位权限；室内可能无 GPS，会回退网络定位或说明原因。",
    parameters: {
      type: "object",
      properties: {
        max_age_seconds: { type: "integer", description: "允许的缓存位置最大年龄（秒），默认 60" },
      },
      required: [],
    },
  },
  {
    name: "location_status",
    description: "查询定位服务状态（GPS/网络/被动提供器开关与最后已知位置），不触发定位不弹窗。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "file_pick",
    description:
      "打开系统文件选择器让用户挑选文件，返回所选文件的 Uri 列表；用户取消或超时（120s）会明确返回。",
    parameters: {
      type: "object",
      properties: {
        mime: { type: "string", description: "MIME 过滤，如 image/*、application/pdf；默认全部" },
        multiple: { type: "boolean", description: "是否允许多选，默认 false" },
      },
      required: [],
    },
  },
  {
    name: "image_save",
    description: "把 base64 图片保存到系统相册 Pictures/AndAgent，保存后相册立即可见。",
    parameters: {
      type: "object",
      properties: {
        base64: { type: "string", description: "图片 base64（可含 data:image/...;base64, 前缀）" },
        filename: { type: "string", description: "可选：文件名" },
        display_name: { type: "string", description: "可选：相册显示名" },
      },
      required: ["base64"],
    },
  },
  {
    name: "open_folder",
    description:
      "打开系统文件管理器定位到指定目录（downloads/documents/pictures/movies 或绝对路径），只浏览不修改。",
    parameters: {
      type: "object",
      properties: {
        dir: { type: "string", description: "downloads | documents | pictures | movies | alarms | notifications 或绝对路径" },
      },
      required: ["dir"],
    },
  },
  {
    name: "open_uri",
    description:
      "通过 URI/URL 跳转到其他应用或页面：网页链接、应用深链（taobao:// weixin:// alipays:// bilibili:// 等任意 scheme）、geo: 地图、mailto: 邮件、market:// 应用商店等。用户说「打开淘宝看看X」「跳转到XX应用」时使用。",
    parameters: {
      type: "object",
      properties: {
        uri: { type: "string", description: "要打开的 URI（https:// 或 scheme://）" },
        package: { type: "string", description: "可选：限定目标应用包名（深链歧义时用）" },
        action: { type: "string", description: "可选：自定义 Intent action，默认 VIEW" },
      },
      required: ["uri"],
    },
  },
  {
    name: "album_photos",
    description:
      "查询相册照片（返回缩略图 base64 + content:// uri）。展示照片时将返回的 JSON 放入 ```photos 围栏，点击照片可在图库打开。按文件夹/关键词过滤，默认最新 8 张。",
    parameters: {
      type: "object",
      properties: {
        bucket: { type: "string", description: "相册文件夹名（如 Screenshots/Camera）" },
        keyword: { type: "string", description: "文件名关键词" },
        limit: { type: "integer", description: "返回数量，默认 8，上限 20" },
        thumbnail: { type: "string", description: "是否带缩略图（true/false，默认 true）" },
      },
      required: [],
    },
  },
  {
    name: "album_open",
    description: "在系统图库中打开一张照片（album_photos 返回的 id 或 uri）。",
    parameters: {
      type: "object",
      properties: {
        id: { type: "string", description: "照片 id（album_photos 返回）" },
        uri: { type: "string", description: "或直接给 content:// uri" },
      },
      required: [],
    },
  },
  { name: "image_info", description: "读取图片宽高、格式、大小和 EXIF 方向。source 可用 content:// URI、相册 id、本地路径或 base64。", parameters: { type: "object", properties: { source: { type: "string" } }, required: ["source"] } },
  { name: "image_preview", description: "不保存，仅预览图片和 ops_json 编辑效果。将结果 previewFence 原样贴进回复的 image 围栏。", parameters: { type: "object", properties: { source: { type: "string" }, ops_json: { type: "string", description: "JSON 操作数组" } }, required: ["source"] } },
  { name: "image_edit", description: "按顺序编辑图片并保存。结果必须以返回的 ```image 围栏展示给用户；savedPath 可作为下次 source。ops_json 例如 [{\"op\":\"crop\",\"aspect\":\"16:9\"},{\"op\":\"brightness\",\"value\":20}]。支持 crop/rotate/flip_h/flip_v/resize/brightness/contrast/saturation/sharpness/blur/grayscale/sepia/invert/border/watermark/compose_over。", parameters: { type: "object", properties: { source: { type: "string" }, ops_json: { type: "string" } }, required: ["source", "ops_json"] } },
  { name: "image_collage", description: "拼图并保存；把 previewFence 原样贴进回复。ids_or_uris 是图片源 JSON 数组；free 模式用 slots_json 指定每张图的 x/y/w/h。", parameters: { type: "object", properties: { ids_or_uris: { type: "string" }, layout: { type: "string", enum: ["grid2x2", "hstack", "vstack", "free"] }, output: { type: "string", enum: ["jpeg", "png"] }, slots_json: { type: "string" }, spacing: { type: "integer" }, background: { type: "string" }, corners: { type: "integer" } }, required: ["ids_or_uris", "layout"] } },
  { name: "db_create", description: "创建持久化 SQLite 数据库。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "db_list", description: "列出 SQLite 数据库。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "db_gui", description: "在对话中打开指定数据库的只读图形浏览器。先用 db_list 查库名。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "db_tables", description: "列出数据库的表、列数和行数。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "db_schema", description: "查看表结构。", parameters: { type: "object", properties: { name: { type: "string" }, table: { type: "string" } }, required: ["name", "table"] } },
  { name: "db_exec", description: "执行参数化 SQL。值使用 ? 占位符和 params_json JSON 数组。DROP TABLE、无 WHERE DELETE 需 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, sql: { type: "string" }, params_json: { type: "string" }, confirm: { type: "boolean" } }, required: ["name", "sql"] } },
  { name: "db_query", description: "只读 SELECT，返回最多 limit 行。值使用 ? 和 params_json JSON 数组。", parameters: { type: "object", properties: { name: { type: "string" }, sql: { type: "string" }, params_json: { type: "string" }, limit: { type: "integer" } }, required: ["name", "sql"] } },
  { name: "db_drop", description: "删除整个数据库，必须 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, confirm: { type: "boolean" } }, required: ["name"] } },
] satisfies PiTool[];

/**
 * Appended to whatever system prompt the user saved, so the chart protocol is
 * always in effect (stored prompts predate this feature).
 */
const CHART_GUIDE = [
  "数据可视化约定：",
  "位置结果用 ```geo JSON 块；照片列表用 ```photos JSON 块；设备、电池等键值状态用 ```kv 块。",
  "1. 当需要展示数据对比、趋势或占比时，在回复里插入 ```chart 围栏代码块，围栏内容必须是合法的 ECharts option JSON。",
  "2. 只能使用纯 JSON：禁止函数、注释、变量、单引号或 JS 表达式（例如 formatter 函数一律不要写）。",
  "3. 图表类型仅限 bar、line、pie，用 series[].type 指定；数据标签直接用字符串/数字数组。",
  "4. 工具返回 JSON 时，先读懂数据，再把整理后的 option 放进 chart 块，不要直接粘贴大段原始 JSON。",
  "5. 示例：",
  "```chart",
  '{"tooltip":{},"legend":{},"xAxis":{"type":"category","data":["一月","二月","三月"]},"yAxis":{"type":"value"},"series":[{"name":"照片数","type":"bar","data":[12,20,8]}]}',
  "```",
].join("\n");

const STORAGE_MEDIA_GUIDE = [
  "结构化存储：用户要记账、清单或笔记库时，先用 db_create 建库，再用 db_exec 建表，后续用参数化 INSERT 和 db_query SELECT。",
  "记账表例：CREATE TABLE records(id INTEGER PRIMARY KEY, date TEXT, item TEXT, amount REAL)。",
  "输出呈现：给用户音频或视频文件时，用 ```audio 或 ```video 围栏，内容为 JSON，例如 {\"uri\":\"/本地路径/file.mp4\",\"title\":\"文件名\"}；uri 可为本地文件路径或 https。",
].join("\n");

const ZERO_USAGE = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

function buildModel(settings: Settings): Model<"openai-completions"> {
  return {
    id: settings.model,
    name: settings.model,
    api: "openai-completions",
    provider: "custom",
    baseUrl: settings.baseUrl.trim().replace(/\/+$/, ""),
    input: ["text"],
    // GLM 流式回 reasoning_content，pi 自动转 thinking part → UI 折叠块
    reasoning: true,
    contextWindow: 128_000,
    maxTokens: 8_192,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    // GLM (and most OpenAI-compatible endpoints) wants max_tokens, not
    // max_completion_tokens; the system role, not developer.
    compat: {
      maxTokensField: "max_tokens",
      supportsStore: false,
      supportsDeveloperRole: false,
      supportsUsageInStreaming: true,
    },
  };
}

export interface ToolView {
  id: string;
  name: string;
  args: Record<string, any>;
  result?: string;
  isError?: boolean;
  running?: boolean;
}

type Draft = { text: string; thinking: string; tools: ToolView[] };
type UiPart = Exclude<NonNullable<ThreadMessageLike["content"]>, string>[number];

export interface AgentSnapshot {
  messages: ThreadMessageLike[];
  running: boolean;
  error: string | null;
  sessionId: string;
  sessions: SessionMeta[];
}

function toUiMessages(messages: PiMessage[], draft: Draft | null): ThreadMessageLike[] {
  const results = new Map<string, { text: string; isError: boolean }>();
  for (const message of messages) {
    if (message.role === "toolResult") {
      const text = message.content
        .map((part) => (part.type === "text" ? part.text : "[image]"))
        .join("\n");
      results.set(message.toolCallId, { text, isError: message.isError });
    }
  }

  const ui: ThreadMessageLike[] = [];
  messages.forEach((message, index) => {
    if (message.role === "user") {
      const text =
        typeof message.content === "string"
          ? message.content
          : message.content.map((p) => (p.type === "text" ? p.text : "")).join("");
      ui.push({
        id: `u-${index}-${message.timestamp}`,
        role: "user",
        content: [{ type: "text", text }],
        createdAt: new Date(message.timestamp),
      });
      return;
    }
    if (message.role !== "assistant") return; // toolResult is folded into the call card

    const parts: UiPart[] = [];
    for (const part of message.content) {
      if (part.type === "thinking") {
        if (part.thinking) parts.push({ type: "reasoning", text: part.thinking } as UiPart);
      } else if (part.type === "text") {
        if (part.text) parts.push({ type: "text", text: part.text });
      } else if (part.type === "toolCall") {
        const result = results.get(part.id);
        parts.push({
          type: "tool-call",
          toolCallId: part.id,
          toolName: part.name,
          args: part.arguments ?? {},
          argsText: JSON.stringify(part.arguments ?? {}),
          result: result?.text,
          isError: result?.isError ?? false,
        });
      }
    }
    const incomplete =
      message.stopReason === "aborted" || message.stopReason === "error";
    ui.push({
      id: `a-${index}-${message.timestamp}`,
      role: "assistant",
      content: parts.length ? parts : [{ type: "text", text: "" }],
      status: incomplete
        ? {
            type: "incomplete",
            reason: message.stopReason === "aborted" ? "cancelled" : "error",
          }
        : { type: "complete", reason: "stop" },
      createdAt: new Date(message.timestamp),
    });
  });

  if (draft) {
    const parts: UiPart[] = [];
    if (draft.thinking) {
      // Native reasoning part — assistant-ui renders it via ReasoningUI.
      parts.push({ type: "reasoning", text: draft.thinking } as UiPart);
    }
    if (draft.text) parts.push({ type: "text", text: draft.text });
    for (const tool of draft.tools) {
      parts.push({
        type: "tool-call",
        toolCallId: tool.id,
        toolName: tool.name,
        args: tool.args,
        argsText: JSON.stringify(tool.args ?? {}),
        result: tool.result,
        isError: tool.isError ?? false,
        isPreliminary: tool.running || tool.result === undefined,
      });
    }
    ui.push({
      id: "live",
      role: "assistant",
      content: parts.length ? parts : [{ type: "text", text: "" }],
      status: { type: "running" },
      createdAt: new Date(),
    });
  }
  return ui;
}

function newId(): string {
  return `s_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

function isAbortError(error: unknown): boolean {
  if (!error) return false;
  const name = (error as { name?: string }).name;
  const message = String((error as { message?: string }).message ?? error);
  return name === "AbortError" || /abort|cancel/i.test(message);
}

function friendlyError(error: unknown): string {
  const raw = String((error as { message?: string })?.message ?? error);
  if (/Failed to fetch|NetworkError|Load failed/i.test(raw)) {
    return `网络请求失败：${raw}。请检查 baseUrl 与网络连接。`;
  }
  return raw.length > 600 ? raw.slice(0, 600) + "…" : raw;
}

export class AgentEngine {
  private messages: PiMessage[];
  private draft: Draft | null = null;
  private running = false;
  private error: string | null = null;
  private abort: AbortController | null = null;
  private settings: Settings;
  private snapshot: AgentSnapshot;
  private listeners = new Set<() => void>();
  private sessionId = "";
  private sessions: SessionMeta[] = [];

  constructor(settings: Settings) {
    this.settings = settings;
    this.sessionId = newId();
    this.messages = [];
    this.sessions = SessionStore.list();
    this.snapshot = {
      messages: [],
      running: false,
      error: null,
      sessionId: this.sessionId,
      sessions: this.sessions,
    };
  }

  /* ------------------------- multi-session --------------------------- */

  switchSession(id: string): void {
    if (this.running || id === this.sessionId) return;
    SessionStore.save(this.sessionId, this.messages); // flush current first
    this.sessionId = id;
    this.messages = SessionStore.load(id);
    this.draft = null;
    this.error = null;
    this.sessions = SessionStore.list();
    this.emit();
  }

  newSession(): void {
    if (this.running) return;
    SessionStore.save(this.sessionId, this.messages);
    this.sessionId = newId();
    this.messages = [];
    this.draft = null;
    this.error = null;
    this.sessions = SessionStore.list();
    this.emit();
  }

  deleteSession(id: string): void {
    const wasCurrent = id === this.sessionId;
    const sessions = SessionStore.remove(id);
    if (wasCurrent) {
      this.sessionId = newId();
      this.messages = [];
      this.draft = null;
      this.error = null;
    }
    this.sessions = sessions;
    this.emit();
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): AgentSnapshot => this.snapshot;

  setSettings(settings: Settings): void {
    this.settings = settings;
  }

  clear(): void {
    this.messages = [];
    this.draft = null;
    this.error = null;
    this.persist();
    this.emit();
  }

  stop(): void {
    this.abort?.abort();
  }

  private emit(): void {
    this.snapshot = {
      messages: toUiMessages(this.messages, this.draft),
      running: this.running,
      error: this.error,
      sessionId: this.sessionId,
      sessions: this.sessions,
    };
    for (const listener of this.listeners) listener();
  }

  private persist(): void {
    this.sessions = SessionStore.save(this.sessionId, this.messages);
  }

  async send(text: string): Promise<void> {
    const content = text.trim();
    if (!content || this.running) return;

    this.running = true;
    this.error = null;
    this.draft = null;
    const controller = new AbortController();
    this.abort = controller;
    const userMessage: UserMessage = { role: "user", content, timestamp: Date.now() };
    this.messages = [...this.messages, userMessage];
    this.persist();
    this.emit();

    let pendingText = "";
    try {
      const pi = await loadPi();
      let rounds = 0;
      let finished = false;
      while (rounds < MAX_TOOL_ROUNDS) {
        rounds++;
        const model = buildModel(this.settings);
        const stream = pi.stream(
          model,
          {
            systemPrompt: [this.settings.systemPrompt.trim(), CHART_GUIDE, STORAGE_MEDIA_GUIDE, PERMISSION_GUIDE]
              .filter(Boolean)
              .join("\n\n"),
            messages: this.messages,
            tools: TOOL_DEFS,
          },
          {
            apiKey: this.settings.apiKey,
            baseUrl: model.baseUrl,
            signal: controller.signal,
            maxRetries: 0,
          },
        );

        let liveText = "";
        let liveThinking = "";
        let liveTools: ToolView[] = [];
        for await (const event of stream as AsyncIterable<AssistantMessageEvent>) {
          if (event.type === "text_delta") {
            liveText += event.delta;
            pendingText = liveText;
            this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
            this.emit();
          } else if (event.type === "thinking_delta") {
            liveThinking += event.delta;
            this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
            this.emit();
          } else if (event.type === "toolcall_start") {
            const partial = event.partial.content[event.contentIndex];
            if (partial?.type === "toolCall" && !liveTools.some((t) => t.id === partial.id)) {
              liveTools = [
                ...liveTools,
                { id: partial.id, name: partial.name, args: {}, running: true },
              ];
              this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
              this.emit();
            }
          } else if (event.type === "toolcall_end") {
            const call = event.toolCall;
            liveTools = liveTools.filter((t) => t.id !== call.id);
            liveTools.push({
              id: call.id,
              name: call.name,
              args: call.arguments ?? {},
              running: true,
            });
            this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
            this.emit();
          }
        }

        const final = await stream.result();
        if (controller.signal.aborted || final.stopReason === "aborted") {
          this.finishAborted(pendingText);
          return;
        }
        if (final.stopReason === "error") {
          throw new Error(final.errorMessage || "模型流式返回错误");
        }

        pendingText = "";
        this.draft = null;
        this.messages = [...this.messages, final];
        this.persist();
        this.emit();

        const toolCalls = final.content.filter((part): part is ToolCall => part.type === "toolCall");
        if (final.stopReason !== "toolUse" || toolCalls.length === 0) {
          finished = true;
          break;
        }

        for (const call of toolCalls) {
          const result = await execTool(call.name, call.arguments ?? {});
          if (controller.signal.aborted) return;
          const toolResult: ToolResultMessage = {
            role: "toolResult",
            toolCallId: call.id,
            toolName: call.name,
            content: [{ type: "text", text: result.result }],
            isError: !result.ok,
            timestamp: Date.now(),
          };
          this.messages = [...this.messages, toolResult];
          this.persist();
          this.emit();
        }
      }

      if (!finished) {
        this.error = `工具调用达到 ${MAX_TOOL_ROUNDS} 轮上限，已停止。如需继续可说「继续」。`;
      }
    } catch (e) {
      if (isAbortError(e) || controller.signal.aborted) {
        this.finishAborted(pendingText);
      } else {
        console.error("agent turn failed", e);
        this.error = friendlyError(e);
      }
    } finally {
      this.running = false;
      this.draft = null;
      this.abort = null;
      this.persist();
      this.emit();
    }
  }

  private finishAborted(partialText: string): void {
    this.error = null;
    const text = partialText.trim();
    if (text) {
      const aborted: AssistantMessage = {
        role: "assistant",
        content: [{ type: "text", text }],
        api: "openai-completions",
        provider: "custom",
        model: this.settings.model,
        usage: ZERO_USAGE,
        stopReason: "aborted",
        errorMessage: "用户已停止生成",
        timestamp: Date.now(),
      };
      this.messages = [...this.messages, aborted];
    }
  }
}
