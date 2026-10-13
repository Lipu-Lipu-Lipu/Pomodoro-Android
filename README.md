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
        │   │   ├── ReminderScheduler.kt   把提前/到点提醒交给系统精确闹钟排程
        │   │   ├── ReminderReceiver.kt    精确闹钟落点：息屏/Doze 下由系统唤醒后投递
        │   │   ├── ReminderNotifier.kt    提醒投递：提示音 + 高优先级通知 + 全屏 Intent
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

8 个用例覆盖桌面版 `--selftest` 的全部场景：状态机推进、提醒只触发一次、到点后超时正计时、
切换与重置、合成音时长与峰值、设置钳制与存取往返，以及提醒闹钟的时刻推算（含 ≤60 秒不发提前提醒的边界）、
快照的亚秒级剩余毫秒、常驻通知文案不带秒数（秒数交给系统 Chronometer）。

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
- **息屏 / 后台提醒复发修复**：原先提醒只在服务内的 100ms 轮询里产生，息屏或 Doze 后轮询被挂起，
  提醒延迟甚至不响。现改为用系统精确闹钟（`setAlarmClock`）排程 T-60s 与 T-0，由广播接收器在闹钟
  触发时唤起投递；两条路径共用同一次投递，靠引擎标志位保证只提醒一次（详见 FEATURES.md 末尾新增条目）
- **后台倒计时走字修复**：常驻通知原先把「剩余 18:08」写进文案、由应用每 100ms 改写，
  应用退到后台被厂商 ROM 冻结后就不再刷新（实测用 `adb shell am freeze` 复现：15 秒后仍是 `01:26`）。
  现改为把秒数交给系统 Chronometer 渲染（`setUsesChronometer` + `setWhen`），由 SystemUI 每秒刷新，
  不依赖应用进程；应用只在阶段切换、进入超时时重发通知。文案因此变为「工作中 / 休息中 / 已超时」，
  秒数显示在通知右上角的系统计时器位置（实测冻结状态下 `01:19 → 00:53 → 00:15` 持续走字）
- **息屏/后台「亮屏弹出」修复**：原先提醒只发高优先级通知，而 Android 14+ 默认不授予计时类应用
  `USE_FULL_SCREEN_INTENT`（`appops` 里是 `default`，带 `rejectTime`），所以「只有声音」。
  现改为：提醒通知带全屏 Intent，应用侧 `setShowWhenLocked` / `setTurnScreenOn`，并引导用户授权。
  实测退到后台 + 息屏后，T-60s 与 T-0 触发时 `mWakefulness` 变为 `Awake`、`topResumedActivity`
  变为 `com.pomodoro.timer/.MainActivity`（屏幕点亮且应用弹到最前）

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
- **通知倒计时由系统渲染**：常驻通知的秒数是系统 Chronometer 而不是应用逐秒改写的文案。上一条修复过的
  「后台提醒」保证了提醒会响，但通知里那行字仍要有人刷新；应用被后台冻结/挂起时这一步必然停摆，
  所以把秒数交给 SystemUI（系统计时器位置显示，随通知右上角一同刷新）。代价是通知正文只写阶段
  （「工作中 / 休息中」，超时写「已超时」），秒数在右上角。
- **状态栏左上角只能放图标，放不了常驻文字**：Android 的状态栏由系统掌管，普通应用只能贡献一个
  通知小图标（本项目即状态栏里那枚番茄）。Android 16（API 36）新增的
  `Notification.Builder.setShortCriticalText` 是唯一官方途径，本项目已接入（芯片里放 `18:08`），
  但实测 AOSP 16 只在通知弹窗那一瞬显示该芯片、不会常驻，且平台没有提供申请常驻芯片的接口
  （`setRequestPromotedOngoing` 在 compileSdk 36 中不存在）。结论：**状态栏里常驻倒计时数字不是公开能力**；
  按电源键在锁屏上即可看到常驻通知里的实时倒计时（无需解锁、无需打开应用）。
- **提醒会亮屏弹出**：提前 1 分钟与到点两个提醒都带全屏 Intent（`category=alarm` + 高优先级渠道），
  后台运行时把界面带到前台、息屏时点亮屏幕并在锁屏之上显示（对应桌面版的「还原并激活主窗口」）。
  这依赖 `USE_FULL_SCREEN_INTENT`，而 **Android 14+ 默认不授予计时类应用**（这是「只有声音」的原因），
  首次进入应用会引导去系统设置开启（「允许全屏通知」）；未开启时自动退化为 Heads-up 通知，
  声音与震动不受影响。桌面版只有到点提醒才抢焦点，Android 侧两个提醒都会亮屏——见
  [FEATURES.md](FEATURES.md)「有意偏离」第 12 条，改回只需把 `deliverWarn` 的 `fullScreen` 置回 `false`。
- **提醒靠系统精确闹钟**：息屏后 CPU 会挂起，而 `Handler.postDelayed` 基于 `uptimeMillis`（挂起期间不推进）；
  进入 Doze 后系统还会**忽略唤醒锁**，原有的部分唤醒锁不再能阻止挂起。因此「什么时候提醒」交给
  `AlarmManager.setAlarmClock()`（可唤醒设备、不受 Doze 限流），需要 `USE_EXACT_ALARM`（Android 13+
  安装即授予）与 `SCHEDULE_EXACT_ALARM`（Android 12 及以下）。副作用是状态栏会常驻一个闹钟图标——
  这是系统闹钟的固有表现形式，也是它能穿透 Doze 的原因。到点提醒另附全屏 Intent 以便在锁屏上亮屏弹出；
  Android 14+ 该权限默认只给闹钟/来电类应用，未授权时自动降级为 Heads-up 通知。

## 作者与许可

作者：**李普**

本项目以 [MIT 许可](LICENSE) 发布，Copyright (c) 2026 李普。

作为行为基准的桌面版 `Pomodoro Timer Desk`（C# / .NET WinForms）同为作者本人作品，
其源码不在本仓库内；本仓库的 `desktop-selftest-reference.txt` 是从该桌面版二进制的
`--selftest` 输出中导出的，仅用于交叉校验。
