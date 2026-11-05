# 10d-6 其他 Android 存储技术评估（DataStore / MMKV / Room）

- 评估日期：2026-11-06
- 代码基线：`0.27.0`（versionCode 36），HEAD `4888caa`（R55 secure 命名空间）
- 结论一句话：**三个候选全部「不引入」，KV 底座维持 SharedPreferences（`andagent_kv`）**，本轮不改生产代码。
- 本文所有体积数字均为本机下载真实产物后实测，不是引用二手数据；采集命令见文末附录。

## 0. 结论摘要

| 候选 | 体积（实测） | 能否直接满足现有 `KvStore` 语义 | 结论 |
|---|---|---|---|
| Jetpack DataStore 1.1.1（Preferences） | 5 个 aar/jar 合计 dex **890,972 B（870 KiB）**，占现有 dex 5.8% | 否：suspend/Flow，需在 JSBridge 同步线程 `runBlocking` 桥接 | **不迁移** |
| Jetpack DataStore 1.1.1（Proto） | 上述 + `protobuf-javalite` dex **650,796 B（636 KiB）** + 代码生成 | 否：同 Preferences，另需为每种数据结构写 `.proto` | **不迁移** |
| MMKV 2.4.3 | `libmmkv.so` arm64 **1,147,888 B**（每 ABI），classes.jar 31,450 B | 否：无 TTL/命名空间/配额；加密为裸密钥 AES-CFB，不能复用 Keystore | **不引入** |
| Room 2.6.1 | runtime dex **221,340 B**（216 KiB）+ compiler **5,315,997 B**（仅构建期） | 否：关系型场景已由 `db_*` 覆盖，且需新增 KSP/kapt | **不引入** |

三个候选没有一个是「白拿收益」的：本项目 KV 的真实访问形态是 **WebView JSBridge 同步调用 + 单进程 + 小值（≤64KB）+ 已有的 TTL/配额/加密语义**，而这恰好是 SharedPreferences 唯一不需要额外成本就能满足的组合。

## 1. 评估方法与基线

### 1.1 被测对象

| 项 | 实际值 | 出处 |
|---|---|---|
| applicationId | `com.dc.andagent` | `app/build.gradle.kts:61` |
| compileSdk / minSdk / targetSdk | 34 / 26 / 28 | `app/build.gradle.kts:56,62,63` |
| Kotlin / AGP | 2.0.21 / 8.7.3 | `build.gradle.kts:5` |
| Gradle 堆上限 | `-Xmx1536m` | `gradle.properties:1` |
| 现有 APK（debug） | 49,619,915 B（47.3 MiB），13 个 dex 合计 15,398,452 B | `unzip -l app/build/outputs/apk/debug/app-debug.apk` |
| 现有 ABI | arm64-v8a / armeabi-v7a / x86 / x86_64（`libquickjs-android-wrapper.so` 四份） | APK `lib/` 清单 |

> 体积百分比一律以「新增 dex ÷ 现有 dex 总量 15,398,452 B」和「新增 .so ÷ APK 49,619,915 B」两种口径给出，避免用压缩后大小虚报。

### 1.2 测量手法

1. `curl` 从 `maven.google.com` / `repo1.maven.org` 拉取真实 aar / jar；
2. `unzip -l` 读归档内 `classes.jar`、`jni/<abi>/*.so` 的原始字节数；
3. 用本机 `build-tools/34.0.0/d8 --release --min-api 26 --lib android-34/android.jar` 把 `classes.jar` 转成 dex，读 `classes.dex` 字节数 —— 这是**真实 dex 体积**，不是 `classes.jar` 乘系数；
4. Kotlin 多平台库（datastore-core / datastore-preferences-core / datastore-core-okio）的根 jar 只有 `linkdata/*.knm` 元数据（无 `.class`），Android 侧实际消费的是 `-android` aar 或 `-jvm` 变体，已按 `.module` 变体表取对。

### 1.3 现有实现的基线事实（先读代码，再评估）

| 事实 | 位置 | 对评估的意义 |
|---|---|---|
| 值格式为 JSON envelope `{"v":…,"exp":…}`，键为 `namespace:key` 单层字符串 | `KvStore.kt:397-400`、`KvStore.kt:377` | 换底座必须整体搬迁 envelope 语义，不存在「字段直映射」 |
| 六工具 + export/import 共 8 个工具全部**同步**返回 String | `KvTools.kt:13-16`、`KvTools.kt:82` | 工具层没有挂起函数出口 |
| JSBridge `kvCall` 在 `@JavascriptInterface` 里 `runBlocking { tool.execute(args) }` | `AgentBridge.kt:210-215` | 已经阻塞 JavaBridge 线程一次；再叠加 DataStore 的挂起写会二次阻塞 |
| QuickJS `kv_get` 走工具通道（同步取字符串回填 JS） | `script/QuickJsEngine.kt:199` | 脚本侧同样是同步语义 |
| 前端 `sessions.ts` 经 `kvCall` 做会话持久化 | `web/src/sessions.ts` | 写频率低（每次会话变更一次），对吞吐无要求 |
| 单进程：`AndroidManifest.xml` 无 `android:process` | manifest 全文 | MMKV / MultiProcessDataStore 的多进程能力用不到 |
| 另有 6 个独立 SP 文件（settings / skills / memory / ui / nfc / external） | `MainActivity.kt:291,598`、`SettingsStore.kt:24`、`SkillTools.kt:49` 等 | 只换 KV 一个文件，收益与成本都不成比例；全量替换则是另一个量级的改造 |

## 2. Jetpack DataStore 1.1.1（Preferences + Proto）

### 2.1 体积实测

版本说明：`androidx.datastore:datastore-preferences` 的根 pom 只是转发，真实代码在三个变体里；Kotlin 多平台模块在 Android 上解析到 `-android`（aar）或 `-jvm`（jar）。

| 模块 | 坐标 | 归档大小 | `classes.jar` | d8 dex（release/min-api 26） |
|---|---|---|---|---|
| datastore-core（Android 变体） | `androidx.datastore:datastore-core-android:1.1.1` | aar 201,990 | 208,700（130 类） | **138,124** |
| datastore-preferences-core（JVM 变体） | `androidx.datastore:datastore-preferences-core:1.1.1` → `-jvm` | jar 769,803 | —（393 类） | **714,048** |
| datastore（Android 变体） | `androidx.datastore:datastore-android:1.1.1` | aar 26,511 | 27,809 | **25,608** |
| datastore-preferences（Android 变体） | `androidx.datastore:datastore-preferences-android:1.1.1` | aar 16,726 | 17,254 | **16,252** |
| datastore-core-okio（JVM 变体） | `androidx.datastore:datastore-core-okio:1.1.1` → `-jvm` | jar 29,754 | — | **24,448** |
| **合计** | | | | 分项相加 918,480 B；**五个 jar 一次性 d8 为 890,972 B ≈ 870 KiB** |

> 口径说明：`d8` 的字符串池/类型池在多个输入之间共享，因此「分项各自 d8 后相加」（918,480 B）比「一次编译全部输入」（890,972 B）大约 3%。**集成后的真实增量按 890,972 B 计**；分项数字只用于定位体积来源（`datastore-preferences-core` 一个模块占 77.6%）。

传递依赖增量：

- `okio`：项目已由 `okhttp:4.12.0` 引入 `okio-jvm:3.6.0`（实测 Gradle 缓存中即有），**增量 0**。
- `kotlinx-coroutines-core`：项目已有 `kotlinx-coroutines-android:1.9.0`，**增量 0**。
- `kotlin-stdlib` / `kotlin-parcelize-runtime`：项目已有 stdlib；parcelize-runtime 是 `datastore-core-android` 声明的 runtime 依赖，量级为几十 KB，未单独 dex（可忽略，但**不是零**）。
- 若走 **Proto DataStore**：额外需要 `com.google.protobuf:protobuf-javalite:3.25.5`（jar 711,462 → dex **650,796 B ≈ 636 KiB**）+ protobuf Gradle 插件与 `.proto` 代码生成。Proto 路线新增 dex 合计 **1,541,768 B ≈ 1.47 MiB**（保守估计，按各模块单测值相加），是本项目已有 dex 的 10.0%。

AAR 元数据检测结果：`datastore-core-android` 与 `datastore-preferences-android` 均声明 `minCompileSdk=34`，与当前 `compileSdk = 34` **正好卡在边界**，没有任何余量。

### 2.2 API 语义 vs 本项目同步桥

DataStore 的公开面是挂起/流：读 `Flow<Preferences>`（或 `data.first()`），写 `suspend fun edit { }`，事务是 `updateData` 的 CAS 重试。本项目三个消费侧全部是**同步字符串返回**：

| 消费侧 | 现状 | 换 DataStore 后必须做的事 |
|---|---|---|
| `AgentBridge.kvCall`（WebView `@JavascriptInterface`） | 已是 `runBlocking { tool.execute(args) }`（`AgentBridge.kt:214`） | 在 JavaBridge 线程上再 `runBlocking { dataStore.edit { … } }`；WebView 的 JS 调用方是**同步等待返回字符串**的，无法改成 Promise（现协议就是 String） |
| QuickJS `kv_get` | 注入函数同步返回 JSON | QuickJS 的 native 调用是同步的，只能在宿主线程 `runBlocking` 再取一次 |
| `KvTool.execute`（LLM 工具） | `suspend`，但内部全同步 | 工具本身可挂起，这一侧不是障碍 |

`runBlocking` 的代价不是「多一行代码」，而是把 DataStore 的唯一写入 actor 的排队时间转嫁给 JavaBridge 线程：DataStore 每次 `edit` 都要**整文件重写**并且串行化，一旦叠加并发调用（前端会话写 + 脚本写 + 工具写），桥线程会累积等待。当前 SharedPreferences 的 `apply()` 是「内存立即生效 + 后台线程落盘」，桥线程永不等待落盘 —— 这个差异在同步桥上是**净退化**，不是等价替换。

### 2.3 迁移收益（逐条对照现有能力）

| 现有能力 | 实现位置 | DataStore 是否免费提供 | 实际结论 |
|---|---|---|---|
| TTL（绝对过期时间 + 惰性删除） | `KvStore.kt:67-84,385-395` | 否 | 需在 suspend 层重写，且 `keys()/namespaces()` 的惰性清理要改成「读全量快照 → 批量 edit」 |
| 命名空间（64×512 配额） | `KvStore.kt:352-357,416-422` | 否 | 需在「快照 → 计数 → 校验 → 写」之间做原子化，否则配额可能被并发击穿 |
| 单值 64KB 上限 | `KvStore.kt:355,413` | 否 | Preferences 的 `stringPreferencesKey` 没有大小限制，校验要自己留 |
| secure 命名空间 Keystore 加密 | `KvStore.kt:314-350` | 否 | 加密可保留（值仍是 String），但要在 `edit` 事务里做，加密失败的「整批不落盘」语义需重写 |
| 备份 export/import（含 secure 隔离规则） | `KvStore.kt:205-268` | 否 | 全部要改写成读快照 + 事务写 |
| 前缀 `keys()` / `namespaces()` | `KvStore.kt:136-179` | **半免费**：`snapshot.asMap().keys` 可枚举，但每次都要反序列化整个文件 | 功能可得，复杂度不降 |

真正能白拿的只有两项，且都不解决本项目的问题：**类型安全的 key**（`stringPreferencesKey` 编译期校验）与 **`Flow` 响应式订阅**（前端是 WebView，拿不到 Kotlin Flow）；外加一个隐性收益：不再有 `apply()` 在 `Activity.onStop` 触发的 `QueuedWork.waitToFinish` 抖动 —— 但本项目 KV 写入是会话级小值（`sessions` 命名空间一条索引 + 每会话一条，量级为个位数 KB），该抖动远小于一次 `runBlocking` 的固定成本。

### 2.4 风险

1. **单实例约束**：同一文件在同一进程内创建两个 `DataStore` 会抛 `IllegalStateException`。当前 `KvTool` 是**每个工具各 new 一个 `KvStore`**（`KvTools.kt:19`，8 个工具实例 = 8 个 `KvStore`），SharedPreferences 允许这样，DataStore 只有走 `by preferencesDataStore` 顶层委托才安全 —— 这是一次容易踩的语义陷阱。
2. **损坏处理都不理想，但 DataStore 更差一档**：DataStore 读失败抛 `CorruptionException`，不挂 handler 直接崩；挂 `ReplaceFileCorruptionHandler { emptyPreferences() }` 则等于**静默清空全部 KV**。SharedPreferences 在 XML 解析失败时同样是「打条日志 → 返回空 map」（也是全丢），差别是它不崩；而现有实现能把「单键无法解密/解析」降级为「该键视为不存在」（`KvStore.kt:74-84,334-350`），**粒度是键级而非文件级** —— 这是换底座会丢掉的语义。
3. **回滚不可能**：迁移 API `SharedPreferencesMigration` 只做单向复制并写 `_migration` 标记，旧 XML 保留但之后不再同步。用户装回旧版 APK 会读到迁移前的陈旧数据，且新旧分叉无合并策略。
4. **磁盘占用翻倍**：迁移后 `andagent_kv.xml` 与 `andagent_kv.preferences_pb` 同时存在。
5. **可观测性下降**：现状可以用 `adb` 直接看 XML（debug 包 `run-as` 即可），DataStore 是二进制 protobuf，排障要额外工具。
6. **构建面耦合**：`minCompileSdk=34` 无余量；且这是 AGP 8.7.3 + Kotlin 2.0.21 下新增 5 个 artifact 的解析与 R8 规则面。

### 2.5 结论：不迁移

理由排序（重要性从高到低）：

1. **同步桥是硬约束**，`runBlocking` 桥接让每次读写都多一次线程切换 + actor 排队，而现有 `apply()` 路径的桥线程开销接近零；
2. **收益为零**：TTL / 配额 / 命名空间 / secure 加密 / 备份隔离五件事全部要在上层重造，DataStore 只提供了「一个线程安全的 map 文件」；
3. **+870 KiB dex（Preferences）/ +1.47 MiB（Proto）**，换来的能力本项目一个都用不上（无 Flow 消费者、无多进程、无跨平台）；
4. **迁移是单向且不可回滚**，一旦线上出现 `CorruptionException`，默认 handler 的语义是清库，与「会话不能丢」的产品口径冲突；
5. 同类收益（避免 `apply()` 抖动）可以用「写路径改 `commit()` on IO 线程」或「批量合并写」以 ~0 依赖成本拿到，不需要引 870 KiB。

## 3. MMKV（com.tencent:mmkv）

### 3.1 版本与体积实测

Maven Central 上 `com.tencent:mmkv` 的 `maven-metadata.xml`（`lastUpdated=2026-10-16`）显示最新稳定版为 **2.4.3**，1.3 线最后一个版本是 **1.3.17**。两个版本的关键差异不是体积，而是 **ABI 覆盖**：

| 版本 | aar 大小 | classes.jar | arm64-v8a | armeabi-v7a | x86 | x86_64 | .so 合计 |
|---|---|---|---|---|---|---|---|
| **2.4.3** | 1,575,132 | 31,450（16 类） | 1,147,888 | **缺失** | **缺失** | 1,129,576 | 2,277,464（仅 2 ABI） |
| 1.3.17 | 865,013 | 25,849 | 589,272 | 368,788 | 550,720 | 585,488 | 2,094,268（4 ABI） |

两个可直接落地的结论（第 3 条是统计口径提醒）：

1. **2.4.3 砍掉了 armeabi-v7a / x86**。本项目 APK 实际打包了四个 ABI（`lib/armeabi-v7a/libquickjs-android-wrapper.so` 等均在包内），升到 2.x 会让 32 位设备在首次 `MMKV.initialize` 时抛 `UnsatisfiedLinkError` —— 这是**运行时崩溃**，不是体积问题。
2. 若要 32 位/64 位全支持必须退回 1.3.17；它的 `.so` 合计 2,094,268 B（2.00 MiB），arm64 单 ABI 增量 589,272 B（占 47.3 MiB APK 的 1.2%）。2.4.3 的 arm64 单 ABI 增量 1,147,888 B（+2.3%），是 1.3.17 的 **1.95 倍**。
3. aar 内 `prefab/modules/mmkv/libs/android.<abi>/libmmkv.so` 是同一份 .so 的副本，供 CMake 消费者使用，不进 APK —— 统计 APK 增量时**不能**把 jni 与 prefab 相加（否则会虚报一倍）。

兼容性：2.4.3 的 `AndroidManifest.xml` 声明 `minSdkVersion=23`，aar-metadata `minCompileSdk=23`，对 `minSdk 26 / compileSdk 34` 无阻塞。

### 3.2 API 语义对照

MMKV 的 API 是**同步**的（`mmkv.encode(key, value)` / `mmkv.decodeString(key)`），这一点比 DataStore 更贴合现有桥 —— 它是三个候选里唯一「不需要 `runBlocking`」的。但语义面差距更大：

| 现有语义 | MMKV 原生能力 | 差距 |
|---|---|---|
| `namespace:key` 两级 + 64 命名空间 × 512 键配额 | 单文件扁平 key（可分文件模拟命名空间，但 64 个文件/配额统计要自建） | 需重造 |
| TTL（`exp` + 惰性删除 + `ttlRemainingSeconds`） | 无 | 需重造（值仍是自封装 envelope） |
| 单值 64KB 上限 | 无（mmap 追加页，大值会导致文件膨胀与写放大） | 需重造 |
| `secure` 命名空间 Keystore AES-256-GCM | 整文件 `cryptKey`（AES-CFB-128），密钥必须是**可直接读到的明文 String/ByteArray** | **不能复用**：AndroidKeyStore 的密钥不可导出，MMKV 的加密模型与之互斥 |
| `kv_export/kv_import` JSON 备份 | 无（只能拷整个 mmap 文件） | 需重造，且跨版本/跨设备不可移植 |
| 多进程并发 | 支持（`MULTI_PROCESS_MODE`） | **本项目用不到**：manifest 无 `android:process`，全部组件同进程 |

关于加密，这是最容易被误判的一条：MMKV 的 `cryptKey` 只能拿到裸密钥，密钥必须常驻进程内存；而 R55 的设计前提是「密钥在 Keystore 里、进程内拿不到明文」（`KeystoreCipher`）。用 MMKV 加密等于把 `secure` 命名空间的安全等级**降级**；保留 Keystore 加密则 MMKV 的加密功能完全用不上，只是换了个「更快的 mmap 文件」。

### 3.3 风险

1. **ABI 断裂**（上述）：2.x 只有 arm64/x86_64，1.3.x 才有 4 ABI；两种选择的体积/覆盖都不理想，且这是一次「为了不用改任何东西而引入 1~2 MiB .so」的交易。
2. **崩溃一致性**：MMKV 依赖 mmap 页回写 + CRC 校验，进程被 kill 时最后一次写可能丢失；SharedPreferences 用「写 `.bak` 再原子替换」，对「会话不能丢」更保守。MMKV 的 `mmap` 优势是零拷贝读，但我们的读频率是「打开会话时一次」，不是热路径。
3. **两套存储并存**：`andagent_kv` 之外，项目还有 6 个 SharedPreferences 文件（settings/skills/memory/ui/nfc/external）。只把 KV 换成 MMKV 会形成「同一个 App 两套持久化心智模型」，而 KV 的写入量在全部持久化里占比极小。
4. **JNI 面**：新增一个 native 库意味着 4 个 ABI 的 `.so` 都要过 16KB page alignment 校验（Android 15+ 要求）。现有 `libquickjs-android-wrapper.so` 已经踩过一次这个坑；MMKV 1.3.17 的 `.so` 是否 16KB 对齐**未验证**，需要在引入前实测（本次未做，标记为待验证项）。
5. **构建内存**：`.so` 打包本身不吃内存，但 4 ABI 的 strip/对齐处理会增加 `mergeDebugNativeLibs` 时间。

### 3.4 结论：不引入

MMKV 的卖点是「比 SharedPreferences 快一个量级的同步读写 + 多进程」，而本项目的瓶颈不在 KV 读写：一次会话切换只读写 1~2 个 ≤ 数 KB 的值，SharedPreferences 的 `apply()` 已经在后台线程落盘。用 2.0~2.2 MiB 的 `.so`（还要接受 ABI 断裂或退回 1.3.17）换一个「用不到的更快」，同时把 R55 的 Keystore 加密模型推倒重来，投入产出比不成立。

## 4. Room

### 4.1 体积与构建成本实测

| 产物 | 坐标 | 大小 | 是否进 APK |
|---|---|---|---|
| room-runtime aar | `androidx.room:room-runtime:2.6.1` | 242,741 | 是 |
| └ `classes.jar` | | 257,757 | — |
| └ d8 dex | | **221,340 B（216 KiB）** | 是（占现有 dex 1.4%） |
| room-compiler jar | `androidx.room:room-compiler:2.6.1` | **5,315,997 B（5.07 MiB）** | 否，**仅构建期** |

`room-compiler` 不进 APK，但它要作为注解处理器加载进编译进程：本项目当前 **零 kapt / 零 KSP**（`app/build.gradle.kts` 只有 `android`、`kotlin.android`、`serialization` 三个插件），引入 Room 需要新增 KSP 插件 + 处理器，并在 `gradle.properties` 已限死 `-Xmx1536m` 的机器上跑一轮注解处理。这不是「加一行依赖」，而是给构建链加一个环节。

版本矩阵另需核对（本轮未实测，标记为待验证）：Room 2.6.1 的处理器在 Kotlin 2.0.21 上通常配 KSP1（`2.0.21-1.0.28`），而 KSP2 支持从 Room 2.7 起；2.7 的 `minCompileSdk` 是否仍是 34 需要单独验证，若抬到 35 则要连带升 AGP。

### 4.2 关系型场景已被覆盖，且覆盖方式更契合本项目

现状是 `db_*` 八个工具（`MainActivity.kt:428-435`）：`db_create / db_list / db_tables / db_schema / db_exec / db_query / db_drop / db_gui`，底层是原生 `SQLiteDatabase`，另有 `db_gui` 打开的 Web 版库浏览器（`MainActivity.kt:733`）。

关键点在于**谁写 SQL**：这些工具是给 LLM 用的，SQL 在**运行时**由模型生成（`db_query` 直接接收 SQL 字符串）。Room 的核心价值是「编译期校验 SQL + 类型安全 DAO」，而运行时生成的 SQL 在编译期根本不存在 —— 只能用 `@RawQuery`，此时 Room 退化成一个「带注解的 SQLite 打开器」，编译期校验这一最大收益直接归零。

| Room 的卖点 | 在本项目是否成立 |
|---|---|
| 编译期 SQL 校验 | **不成立**：SQL 来自模型，运行时才出现 |
| 类型安全 DAO | 部分成立，但 `db_*` 的契约是 JSON 字符串，DAO 还要再包一层映射 |
| 迁移脚手架（Migration/auto-migrations） | 成立，但 `db_*` 面向用户自建库，schema 由用户决定，Room 的 schema 版本管理反而是负担 |
| Flow/LiveData 响应式查询 | 不成立：消费方是 WebView，`db_gui` 是独立页面轮询/重查 |
| 减少样板代码 | 不成立：现有 `SqliteTools.kt` 是通用 SQL 执行器，样板量本来就很小 |

### 4.3 风险

1. **构建链风险最大**：新增 KSP 后所有 Kotlin 编译单元都要过一轮符号处理，`-Xmx1536m` 下 OOM/变慢的概率不可忽略；若 KSP 版本与 Kotlin 2.0.21 对不齐，是「构建直接红」而不是「功能降级」。
2. **两套 SQLite 打开方式并存**：`db_*` 直接开库（WAL/超时/加密策略由自己控制），Room 走 `RoomDatabase` 的 `SupportSQLiteOpenHelper`，两者对同一文件的并发打开、journal 模式、`PRAGMA` 设置需要统一，否则容易出现 `database is locked`。
3. **对用户已有数据库是破坏性变更**：`db_*` 已经让用户/模型建过库，Room 接管需要 schema 对齐，历史库无法自动迁移。
4. **收益不可度量**：换完之后 `db_query` 的行为不变（仍是 SQL 进、JSON 出），唯一的可观测差异是 APK +216 KiB 与构建 +5 MiB 处理器。

### 4.4 结论：不引入

关系型需求已经由 `db_*` + `db_gui` 覆盖，且「SQL 在运行时生成」这一事实让 Room 的编译期校验失去意义。引入 Room 只能换来 216 KiB 的 dex、一个 5 MiB 的注解处理器、一条 KSP 构建链和一套需要与原生 `SQLiteDatabase` 共存的打开策略 —— 全部是成本，没有一项对应的收益。

## 5. 能力对照总表

✅ = 原生提供；△ = 需在上层重写；✗ = 不提供。

| 能力 | SharedPreferences（现状） | DataStore Preferences | MMKV 2.4.3 | Room 2.6.1 |
|---|---|---|---|---|
| 同步读写（JSBridge 直连） | ✅ | ✗（suspend/Flow） | ✅ | ✗（suspend/DAO 阻塞需另起线程） |
| TTL / 惰性过期 | △ 已实现（`KvStore.kt`） | △ | △ | △（需自建列 + 定时清理） |
| 命名空间 + 配额（64×512） | △ 已实现 | △ | △ | ✅（表/行天然隔离，但配额仍需自建） |
| 单值 64KB 上限 | △ 已实现 | △ | △ | △ |
| Keystore 非导出密钥加密 | ✅（R55 `enc1:`） | ✅ 可保留（值为 String） | ✗（裸密钥 AES-CFB） | ✗（需 SQLCipher 等额外方案） |
| JSON 备份导出/导入 + secure 隔离 | △ 已实现 | △ | ✗（只能拷 mmap 文件） | △（需写序列化层） |
| 多进程 | ✗（不可靠） | △（`MultiProcessDataStoreFactory`） | ✅（用不到） | ✅（SQLite 文件锁） |
| 关系查询 / 任意 SQL | ✗ | ✗ | ✗ | ✅（已有 `db_*` 覆盖） |
| 新增 APK 体积 | **0** | +870 KiB dex（Proto +1.47 MiB） | +589 KiB（1.3.17/arm64）~ +1.15 MiB（2.4.3/arm64）/ABI | +216 KiB dex |
| 新增构建期成本 | **0** | 0（仅依赖解析） | 0（但 4 ABI native 打包） | +5.07 MiB 处理器 + KSP 链 |
| 迁移风险 | 0 | 高（单向、不可回滚、损坏即清库） | 中（需重造 envelope 语义） | 高（历史库 schema 冲突） |
| 32 位 ABI 可用性 | ✅ | ✅ | **2.x 缺失**（仅 1.3.x 可用） | ✅ |

## 6. 何时需要重新评估

以下条件**任一**成立时，本结论需要重做（不是「顺便升级」，而是「条件触发后重新走一遍 10d-6」）：

1. **引入多进程**（例如把采集/小组件放进 `android:process=":xxx"`，或新增 `:remote` 服务）。届时 SharedPreferences 的跨进程一致性彻底不可用，应评估 MMKV 2.x（注意只支持 arm64/x86_64，需要同时决定是否放弃 32 位）或 `MultiProcessDataStoreFactory`（需验证其 alpha 稳定性）。
2. **需要响应式数据**（原生 Compose/View 直接订阅 KV 变化，而不是 WebView 拉取）。DataStore 的 `Flow<Preferences>` 是唯一现成方案，此时 +870 KiB 才买到东西。
3. **单值需求超过 64KB，或单次导出逼近 512KB**（base64 图片、录音、超长对话快照）。注意：**这不是换 KV 底座的信号**，正确做法是把大对象落到 `db_*` 的 BLOB 或 `filesDir` + `AtomicFile`，KV 只存引用（path/id）。换成 DataStore/MMKV 只会让「整文件重写」的代价从 XML 平移到 pb/mmap，问题不会消失。
4. **上架 Play Store / targetSdk 升到 34+**。届时 `apply()` 在 lifecycle 的 `QueuedWork.waitToFinish` 抖动、以及 DataStore 的官方推荐地位都会改变权衡；同时 Scoped Storage 的影响面远大于 KV 本身，应作为一次完整的存储架构评估，而不是单点替换。
5. **KV 规模逼近上限**（64 命名空间 × 512 键 = 32,768 键，或 `kv_keys`/`kv_namespaces` 明显变慢）。现状 `keys()` / `namespaces()` 每次都遍历 `prefs.all`（`KvStore.kt:142,165`），复杂度 O(全部键)；一旦命名空间内键数上千，索引化的 SQLite 才有意义。
6. **需要跨语言/跨进程同时写同一份 KV**（QuickJS 与 native 或前台与后台服务并发写）。SharedPreferences 与 DataStore（单进程）都不保证，需换 SQLite（文件锁 + 事务）。
7. **`secure` 命名空间要做密钥轮换或多用户隔离**。这属于 crypto 层演进，`enc1:` → `enc2:` 的前缀已预留（`KvStore.kt:365-366`），**不需要**换存储引擎。

## 7. 附录

### 7.1 复现命令

```bash
# 1) 拉产物（maven.google.com 会 301 到 dl.google.com，需 -L）
curl -sSL -o datastore-core-android-1.1.1.aar \
  https://maven.google.com/androidx/datastore/datastore-core-android/1.1.1/datastore-core-android-1.1.1.aar
curl -sSL -o datastore-preferences-core-jvm-1.1.1.jar \
  https://maven.google.com/androidx/datastore/datastore-preferences-core-jvm/1.1.1/datastore-preferences-core-jvm-1.1.1.jar
curl -sSL -o mmkv-2.4.3.aar \
  https://repo1.maven.org/maven2/com/tencent/mmkv/2.4.3/mmkv-2.4.3.aar

# 2) 看归档内尺寸（只看 jni/，prefab/ 是同一份 .so 的副本）
unzip -l mmkv-2.4.3.aar | awk '{print $1, $4}' | grep -E "jni/.*\.so|classes.jar"

# 3) classes.jar → 真实 dex 体积
unzip -o mmkv-2.4.3.aar classes.jar -d x && \
  /opt/android-sdk/build-tools/34.0.0/d8 --release --min-api 26 \
    --lib /opt/android-sdk/platforms/android-34/android.jar \
    --output dex-out x/classes.jar && stat -c%s dex-out/classes.dex
```

Kotlin 多平台模块（`datastore-core` / `datastore-preferences-core` / `datastore-core-okio`）的根 jar 只有 `linkdata/*.knm` 元数据、`grep -c '\.class'` 为 0；Android 侧要取 `-android`（aar）或 `-jvm`（jar）变体，变体表见各自的 `.module` 文件。

### 7.2 原始数据汇总

| 产物 | 归档 | classes.jar | dex |
|---|---|---|---|
| datastore-core-android-1.1.1 | 201,990 | 208,700 | 138,124 |
| datastore-preferences-core-jvm-1.1.1 | 769,803 | —（393 类） | 714,048 |
| datastore-android-1.1.1 | 26,511 | 27,809 | 25,608 |
| datastore-preferences-android-1.1.1 | 16,726 | 17,254 | 16,252 |
| datastore-core-okio-jvm-1.1.1 | 29,754 | — | 24,448 |
| **datastore 合计** | | | **890,972**（一次性 d8；分项相加 918,480，见 2.1 口径说明） |
| protobuf-javalite-3.25.5 | 711,462 | — | 650,796 |
| room-runtime-2.6.1 | 242,741 | 257,757 | 221,340 |
| room-compiler-2.6.1（构建期） | 5,315,997 | — | — |
| mmkv-2.4.3 | 1,575,132 | 31,450 | d8 失败（见 7.3） |
| mmkv-1.3.17 | 865,013 | 25,849 | 未测 |

参照基线：debug APK 49,619,915 B；13 个 dex 合计 15,398,452 B；4 个 ABI。

### 7.3 未验证 / 已知局限

1. **MMKV `classes.jar` 无法用 d8 转 dex**：`com/tencent/mmkv/MMKVLogLevel.class` 触发 R8 内部 `NullPointerException`（尝试过 `--no-desugaring`，仍失败）。以 16 个类 / 69,146 B 未压缩 class 字节估算，其 dex 量级为 **25~31 KB**，相对 `.so` 可忽略；文档中 MMKV 的体积结论只依赖 `.so`，不受此项影响。
2. **MMKV `.so` 的 16 KB page alignment 未验证**（Android 15+ 要求）；引入前必须 `zipalign -c -P 16 -v 4` 实测。
3. **Room 2.7.x 的 `minCompileSdk` 未实测**；2.6.1 与 Kotlin 2.0.21 的 KSP 版本组合为公开资料推断，未在本机构建验证。
4. **DataStore 的 dex 未做 R8 全程序压缩（`--release` 只做 d8 级别的去符号，不做 tree shaking）**：真实集成后若开 minify，体积会小于 870 KiB；但本项目 `release` 明确 `isMinifyEnabled = false`（`app/build.gradle.kts:87`），所以 870 KiB 是本项目的准确口径。
5. 未评估 Scoped Storage / MediaStore / SAF / ContentProvider / FileProvider —— 按 10d-6 的分工，这些归 10c。

### 7.4 本轮变更

- 新增本文档；`ROADMAP-TODO.md` 的 10d-6 置 ✅、10d 置 ✅。
- **未改动任何生产代码、未改动 web、未新增依赖**（`app/build.gradle.kts` 的 dependencies 一行未动）。
- 验证方式：`./gradlew :app:assembleDebug --no-daemon` 通过（文档轮无代码改动，确认树未破坏）。

