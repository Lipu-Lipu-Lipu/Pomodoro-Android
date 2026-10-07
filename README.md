# 番茄钟 · Android 版

`Pomodoro Timer Desk`（C# / .NET 10 WinForms）的 Android 复刻版。
功能对照与逐条实现方式见 [FEATURES.md](FEATURES.md)。

- 语言 / UI：Kotlin + Jetpack Compose（Material 3）
- 最低版本：Android 8.0（API 26），目标版本 API 36
- 依赖：仅 AndroidX + Compose，无第三方库

## 目录结构

```
PomodoroAndroid/
├── LICENSE                           MIT
├── README.md                         本文件
├── FEATURES.md                       桌面版特性清单 + Android 落地方式对照表
├── desktop-selftest-reference.txt    桌面版 --selftest 的真实输出（供交叉校验）
├── gradlew / gradlew.bat             Gradle Wrapper 8.11.1（无需预装 gradle）
├── gradle/wrapper/                   Wrapper jar 与版本配置
├── settings.gradle.kts               rootProject.name = "PomodoroAndroid"
├── build.gradle.kts                  AGP 8.10.1 / Kotlin 2.0.21
├── gradle.properties
├── keystore.properties.example       Release 签名凭据模板（复制成 keystore.properties 再填）
├── release.keystore                  ⚠️ 本机生成、不入库 —— 签名私钥，务必自行备份
├── local.properties                  ⚠️ 本机生成、不入库 —— sdk.dir 指向本机 Android SDK
└── app/
    ├── build.gradle.kts              Compose BOM 2024.02.01，minSdk 26 / targetSdk 36
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/pomodoro/timer/
        │   │   ├── PomodoroEngine.kt      状态机（纯逻辑，逐行移植自 PomodoroEngine.cs）
        │   │   ├── AppSettings.kt         设置数据类 + 钳制/序列化 + SharedPreferences 存储
        │   │   ├── TonePlayer.kt          内存合成 PCM 提示音（对应 BeepPlayer.cs）
        │   │   ├── TimeFormat.kt          MM:SS / +MM:SS / +HhMM 格式化
        │   │   ├── PomodoroController.kt  进程级状态持有者（快照、提醒、退出请求）
        │   │   ├── PomodoroService.kt     前台服务：后台计时、通知、提示音、卡片
        │   │   ├── MainActivity.kt        界面入口：渲染主界面与提醒卡片
        │   │   └── ui/
        │   │       ├── Theme.kt           配色常量与 pt→sp 换算
        │   │       ├── TimerRing.kt       圆环进度 + 文字自适应缩放
        │   │       ├── AccentButton.kt    圆角胶囊强调按钮
        │   │       ├── SettingsPanel.kt   工作/休息 分秒步进器（运行中锁定回弹）
        │   │       ├── AlertCard.kt       置顶提醒卡片
        │   │       └── MainScreen.kt      主界面布局
        │   └── res/                       字符串、主题、通知图标、自适应启动图标
        │                                  （位图前景，取自桌面版 tomato.ico）
        └── test/java/com/pomodoro/timer/
            └── PomodoroEngineTest.kt      对应桌面版 --selftest 的自动化自测
```

## 构建

用自带的 Gradle Wrapper，不依赖本机预装的 gradle：

```bash
cd PomodoroAndroid
./gradlew :app:assembleDebug          # Windows: gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

Release 构建（已实测通过，含 `lintVitalRelease`）：

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk` —— **已签名，可直接安装**。

### Release 签名

签名凭据放在项目根目录的 `keystore.properties`（已 gitignore，不入版本库），
格式见 `keystore.properties.example`：

```properties
storeFile=../release.keystore
storePassword=…
keyAlias=pomodoro
keyPassword=…
```

`storeFile` 的路径相对于 `app/` 模块，所以项目根目录下的 keystore 写 `../release.keystore`。

> **`release.keystore` 是应用的签名身份，务必单独备份。**
> 弄丢它就无法再发布同一应用的更新（Android 会拒绝签名不一致的升级包）。
>
> 若 `keystore.properties` 不存在（例如别人 clone 后没有私钥），
> release 仍能正常构建，只是产物为未签名的 `app-release-unsigned.apk` ——
> 宁可降级也不让构建直接失败。

验证签名：

```bash
$ANDROID_HOME/build-tools/36.1.0/apksigner.bat verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

> 在受限环境下若遇到 Gradle 守护进程/构建缓存的写权限问题，追加 `--no-daemon --no-build-cache`。
> 若默认 Gradle 家目录不可写，追加 `GRADLE_USER_HOME=<项目内目录>`。
> Wrapper 固定在 Gradle 8.11.1（首次运行会自动下载）。

## 运行自测

```bash
./gradlew :app:testDebugUnitTest
```

5 个用例覆盖桌面版 `--selftest` 的全部场景：状态机推进、提醒只触发一次、到点后超时正计时、
切换与重置、合成音时长与峰值、设置钳制与存取往返。

其中 `toneDurationsMatchDesktopSelfTestOutput` 会读取 `desktop-selftest-reference.txt`
（桌面版真实二进制的自测输出）做**交叉校验** —— 让两边各算一遍再比，而不是只比人抄过来的期望值。
参考文件缺失时该用例自动跳过。

重新生成参考文件：

```bash
cd "../Pomodoro Timer Desk/publish"
dotnet PomodoroTimer.dll --selftest > "../../PomodoroAndroid/desktop-selftest-reference.txt"
```

## 验证状态

已在 Android 16 / API 36 模拟器（1080×2400 @420dpi）上完整走通，逐项结果见
[FEATURES.md](FEATURES.md) 末尾的「实测验证记录」。摘要：

- 构建一次通过；`assembleDebug` 与 `assembleRelease` 均 BUILD SUCCESSFUL；5 个单元测试全绿；`logcat` 无崩溃
- **与桌面版交叉校验通过**：桌面版真实二进制 `--selftest` 报 24 条 PASS / `ALL PASS`；
  Android 端合成音时长与其打印值逐值相等（Start 280ms / Warn 490ms / End 3310ms）
- 前台流程：空闲 → 开始 → 提前提醒卡片 → 到时卡片 → 超时正计时 → 阶段切换 → 返回设置
- **后台流程**：按 HOME 退到后台后计时不中断，提前提醒与到时提醒都在后台正确触发；
  首次退到后台会弹一次性提示「已转入后台运行，计时继续」，第二次不再出现
- **划掉最近任务后继续计时**：从最近任务移除应用后，前台服务与计时继续运行，
  重新打开显示正确的进行中状态 —— 对应桌面版「关窗隐藏到托盘继续计时」
- 通知操作：`打开` / `结束当前阶段` / `退出` 三个动作均验证可用
- 设置上限与钳制：工作 120 分 / 59 秒、休息 60 分 / 59 秒，直接输入越界值会被钳制
- 环内文字自适应：`25:59` → `120:59` 字号自动缩小，始终不溢出圆环
- 验证中发现并修复了 4 个缺陷（卡片残留通知、按钮与链接误触、过期卡片未失效、退出后状态未复位）

## 安装与调试

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pomodoro.timer/.MainActivity
```

## 界面与桌面版的对应关系

| 桌面版 | Android 版 |
|---|---|
| 主窗口（400×620，深色） | `MainScreen`（全屏深色，配色逐值一致） |
| 圆环 + 中央倒计时 | `TimerRing`（同一套进度/脉冲/文字自适应公式） |
| NumericUpDown 分秒微调 | `NumberStepper`（− 数值 +，可直接输入，范围一致） |
| 开始 / 结束，进入休息 / 结束，进入工作 | `AccentButton`（颜色与文案随阶段切换） |
| 返回设置链接（仅运行中可见） | 同 |
| 关闭窗口 → 隐藏到托盘继续计时 | 退到后台 → 前台服务继续计时 |
| 托盘图标 + 右键菜单（2 项） | 常驻通知 + 「打开 / 结束当前阶段 / 退出」操作（3 项，多一个见 [FEATURES.md](FEATURES.md) 的「有意偏离」第 4 条） |
| 置顶提醒卡片（`AlertForm`） | `AlertCard`（Compose Dialog，10 秒自动关 / 手动关） |
| 气泡通知 + 任务栏闪烁 | 高优先级通知（Heads-up）+ 渠道震动 |
| 合成 WAV 提示音 | `TonePlayer` 合成 PCM，结构一致；**波形参数经调整以消除刺耳感**（见 [FEATURES.md](FEATURES.md)「有意偏离」第 7 条） |

## 已知平台差异

- **后台启动界面**：到时提醒会尝试把界面带到前台（对应桌面版「还原并激活主窗口」），
  但 Android 10+ 对后台启动 Activity 有限制，可能被系统拒绝；此时高优先级通知仍然可见。
- **通知多一个「结束当前阶段」操作**：桌面版托盘菜单只有「打开番茄钟」「退出」两项。
  多出来的这个操作是对上面那条限制的补偿 —— 否则到时提醒后，用户不打开应用就没有入口切阶段。
  完整的十处有意偏离清单见 [FEATURES.md](FEATURES.md)。
- **提示音兜底**：桌面版用 `Console.Beep`，Android 无对应 API，改为系统默认通知音。
- **提示音走媒体音量**：合成音以 `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` 播放，可用媒体音量键统一控制；桌面版 `Console.Beep` 不涉及音量通道。媒体音量为 0 时提示音不响，这是预期行为。
- **深色主题**：应用固定使用桌面版那套深色配色，不跟随系统浅色模式。

## 作者与许可

作者：**李普**

本项目以 [MIT 许可](LICENSE) 发布，Copyright (c) 2026 李普。

作为行为基准的桌面版 `Pomodoro Timer Desk`（C# / .NET WinForms）同为作者本人作品，
其源码不在本仓库内；本仓库的 `desktop-selftest-reference.txt` 是从该桌面版二进制的
`--selftest` 输出中导出的，仅用于交叉校验。
