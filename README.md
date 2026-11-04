# andagent

Android 端设备助手。WebView 前端由 `web/` 构建，Android APK 由 Gradle 构建。

## 内嵌 Termux 用户空间

`termux_setup` 从 `app/src/main/assets/bootstrap/bootstrap-aarch64.zip` 安装到
app 私有 `files/usr`。归档由官方 bootstrap 的文本文件路径转换而来；只将
`/data/data/com.termux` 改为 `/data/user/0/io.nous.andagent`，ELF 保持原样。
当前归档 SHA-256：`62a92f2f6c9433f721232b24f11be2d09b76149608122694364a6957f2d69809`。
原始官方归档 SHA-256：`5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae`。

安装时会校验 dash、bash 和 `pkg list-installed`，三者成功才写就绪标记。
ELF 中仍有官方编译期路径；包管理及其他命令是否能在 app 私有路径运行待真机验证。
`./gradlew downloadBootstrap` 会校验官方归档哈希并运行 `scripts/rewrite_termux_bootstrap.py`。更新上游版本后须更新官方与派生归档哈希，并验证残留清单。
完整移植记录与残留路径清单见 `/tmp/andagent-r5-run.md`。
