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

export const PERMISSION_GUIDE_EN = [
  "Phone permission rules:",
  "1. If a tool reports missing permission, explain in one sentence which permission is needed and why, then retry the tool so Android can show the permission prompt.",
  "2. Do not request multiple unrelated permissions in one response. Request only the one needed for the current task.",
  "3. If the user denies access, explain that the feature is unavailable and continue with the rest of the task. Do not keep retrying.",
  "4. If the error says to open system settings or the status is permanently_denied, guide the user to enable access there. You may call openAppSettings; do not show another permission prompt.",
  "5. Use permission_status to check granted/denied/permanently_denied before deciding what to do.",
].join("\n");
