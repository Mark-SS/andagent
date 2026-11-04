/**
 * Appended after the user's system prompt: how to behave when a tool fails
 * with a permission error. Keeps the agent from blasting permission dialogs.
 */
export const PERMISSION_GUIDE = [
  "手机权限使用纪律：",
  "1. 当工具返回「权限未授予」类错误时，先用一句话向用户说明你需要什么权限、用来做什么，再重试该工具（系统会弹出授权窗口）。",
  "2. 严禁在一次回复里申请多个不相关权限；每次只申请当前任务真正需要的那一个。",
  "3. 用户拒绝后：说明该能力将不可用，并继续完成任务的其余部分，不要反复重试。",
  "4. 错误信息里出现「去系统设置」或权限状态为 permanently_denied 时，引导用户到系统设置里手动开启（可调用 openAppSettings 由你发起跳转），不要再次弹窗。",
  "5. 你可以用 permission_status 工具查询某个权限当前状态（granted/denied/permanently_denied），判断前先查再动。",
].join("\n");
