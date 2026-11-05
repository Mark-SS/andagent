/** 首页空会话的可点击对话模板。
 *  文案与 agent.ts / extraTools.ts 里真实存在的工具对齐
 *  （storage_scan / screen_capture / battery_detail / album_stats /
 *   image_collage / alarm_set / db_create / widget_generate /
 *   docx_create / clipboard_write / web_search / script_save /
 *   mermaid / chart / js_eval+andagent.tool）。 */

export interface ConversationTemplate {
  /** 卡片上的图标（emoji） */
  icon: string;
  /** 卡片标题（两行截断显示） */
  title: string;
  /** 点击后作为用户消息直接发送的完整 prompt */
  prompt: string;
}

export interface TemplateGroup {
  label: string;
  templates: ConversationTemplate[];
}

export const TEMPLATE_GROUPS: TemplateGroup[] = [
  {
    label: "设备与系统",
    templates: [
      {
        icon: "🗂️",
        title: "看看存储都被什么占满了",
        prompt: "看看手机存储都被什么占满了，给我一份占用最大的目录和文件清单，再给几条清理建议。",
      },
      {
        icon: "📸",
        title: "截个屏给我看看",
        prompt: "帮我截一张当前屏幕的图，我想看看现在屏幕上是什么。",
      },
      {
        icon: "🔋",
        title: "现在电量、网络、内存状态如何",
        prompt: "查一下手机现在的电量、Wi-Fi 网络和内存状态，用一小段总结告诉我。",
      },
    ],
  },
  {
    label: "相册与文件",
    templates: [
      {
        icon: "🖼️",
        title: "相册里截图占多少空间？",
        prompt: "统计一下相册里截图有多少张、总共占多少空间，和照片对比一下。",
      },
      {
        icon: "🧩",
        title: "把最近的截图拼成九宫格",
        prompt: "把相册里最近的 9 张截图拼成一张九宫格图片保存。",
      },
      {
        icon: "📄",
        title: "把下载的 PDF 摘要成要点",
        prompt: "在下载目录里找一下最近下载的 PDF，把内容摘要成几条要点。",
      },
    ],
  },
  {
    label: "自动化与效率",
    templates: [
      {
        icon: "⏰",
        title: "明早 8 点提醒我开会",
        prompt: "明天早上 8 点提醒我开会。",
      },
      {
        icon: "📒",
        title: "建个记账数据库记一笔",
        prompt: "建一个记账数据库，先记上今天早饭花了 15 元，以后可以继续用。",
      },
      {
        icon: "📱",
        title: "写个每天早上看天气的小组件",
        prompt: "帮我写一个桌面小组件，每天早上显示今天的天气。",
      },
    ],
  },
  {
    label: "生产力",
    templates: [
      {
        icon: "📝",
        title: "生成一份周报 Word 文档",
        prompt: "帮我生成一份本周工作周报的 Word 文档，结构包含本周完成、数据小结和下周计划，内容你先拟一版再生成。",
      },
      {
        icon: "🌐",
        title: "翻译一句话并存到剪贴板",
        prompt: "把这句话翻译成英文并写入剪贴板：「请把这份文件尽快发给我，谢谢。」",
      },
      {
        icon: "🔍",
        title: "搜一下 Android 16 的新特性",
        prompt: "帮我搜一下 Android 16 的新特性，挑重点总结成几条。",
      },
    ],
  },
  {
    label: "开发与脚本",
    templates: [
      {
        icon: "✏️",
        title: "写个脚本批量重命名笔记文件",
        prompt: "写一个 JS 脚本，批量把我笔记目录里的文件重命名成「日期-标题」格式，先列出预览再执行。",
      },
      {
        icon: "📐",
        title: "用 mermaid 画今天的日程流程",
        prompt: "读一下我今天的日程安排，用 mermaid 流程图画出来。",
      },
      {
        icon: "📊",
        title: "查电池数据画成图表",
        prompt: "用 js_eval 里的 andagent.tool 查一下电池状态，把关键数据画成 chart 图表。",
      },
    ],
  },
];
