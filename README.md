# andagent

Android 端设备助手。WebView 前端由 `web/` 构建，Android APK 由 Gradle 构建。

## 更新内嵌 Termux bootstrap

`app/src/main/assets/bootstrap/bootstrap-aarch64.zip` 随 APK 发布，运行时的
`termux_setup` 仅从该 asset 解压，不访问网络。当前归档 SHA-256：
`5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae`。

更新归档时，先确认上游 release 和 SHA-256，然后同步修改
`app/build.gradle.kts` 中 `downloadBootstrap` 的 URL 和期望哈希，运行
`./gradlew downloadBootstrap`。该任务通过 ghfast.top 获取文件、校验哈希后才替换
提交的 asset。最后运行 `./gradlew assembleDebug`，在真机执行 `termux_setup`
验证 `dash` 和 `pkg`，并检查 APK 体积。

官方 Termux bootstrap 为 `com.termux` 固定安装路径编译。当前实现保留
`dash`/`pkg` 实测门槛；如果该归档不能在 `io.nous.andagent` 的私有目录运行，
`termux_setup` 会返回明确错误，而不会把环境标记为可用。
