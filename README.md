# NeXtepHomeShim — 格窗（NeXtep / Panes）的 HyperOS 适配垫片

一个 LSPosed 模块，让 [格窗 NeXtep（Panes）](https://github.com/yishisanren/NeXtep) 能在小米 HyperOS 上正常进入和使用工作台。

## 为什么需要它

格窗进入工作台前，会向桌面（Home）发一个广播询问“工作台是否就绪”，等桌面按协议回复后才继续。ColorOS 桌面有这个应答方，而 MIUI 桌面没有，于是在 HyperOS 上点开格窗只会得到“launcher: no answer”，工作台根本进不去。

本垫片注入 MIUI 桌面进程，冒充这个应答方，并把格窗在 HyperOS 上缺失的几块适配补齐。**它不是格窗本体**，必须先安装格窗模块才能工作。

## 功能（v2.10）

- **工作台握手应答**：同时支持两套协议命名空间——yishisanren 版 Panes（协议 52）与 lujinxin 上游版 NeXtep（协议 39），装哪个都能进工作台。
- **桌面变换与触摸矫正**：工作台激活时，桌面图标页按格窗官方公式缩放/平移让出侧栏，并对触摸坐标做逆映射，解决图标点位偏移。
- **输入法键盘适配**（系统框架作用域）：键盘弹出时把输入法表面适配到主格窗区域内，不再遮挡侧栏小窗。
- **手势唤醒**：提供一个无界面入口「格窗开关」，可绑定到 MyGesture 等手势工具（如左边缘长按上滑），一下开/关工作台，不必再去点快捷设置磁贴。
- **干净退出**：退出工作台时桌面精确恢复原位，无闪屏残留。

## 安装

1. 先安装格窗本体（[yishisanren/NeXtep](https://github.com/yishisanren/NeXtep) 或上游 lujinxin/NeXtep），并在 LSPosed 中启用、按其说明配置。
2. 安装本模块 APK（见 [Releases](https://github.com/zealler9560/NeXtepHomeShim/releases)）。
3. 在 LSPosed 中启用 NeXtepHomeShim，作用域务必勾选两项：**系统桌面（com.miui.home）** 和 **系统框架（android）**——少勾系统框架，键盘适配不生效。
4. 重启手机。
5. （可选）打开 MyGesture，把喜欢的手势动作设为打开应用「格窗开关」，即可手势唤醒工作台。

## 已验证环境

- Redmi Note 12 Turbo（marble），HyperOS 2.0（Android 15）
- 格窗 Panes 2.6.5

其他 HyperOS 机型理论可用，但未逐一验证。ColorOS 设备不需要本垫片。

## 构建

无 Gradle，使用 JDK 17 + Android SDK（platform android-35、build-tools 35.0.0）直接构建：

```bash
export KS_PASS=你的签名口令 KEY_PASS=你的签名口令   # 可选，默认用 KEYSTORE 环境变量指向的密钥库
./build.sh   # 产物：NeXtepHomeShim.apk
```

签名相关环境变量：`KEYSTORE`（密钥库路径）、`KS_PASS`、`KEY_PASS`。`stubs/` 是 Xposed API 的编译期存根，仅参与编译、不会打进 APK（打进去 LSPosed 会拒绝加载）。

## 实现要点（踩过的坑）

- 格窗的广播接收器有 `STATUS_BAR_SERVICE` 权限门槛，普通应用进程发给它的广播会被系统静默丢弃——所以手势入口必须借道桌面进程转发，直达是无效的。
- 格窗会把“工作台状态通知”在每次状态变更后重新广播一遍；垫片如果把通知再当请求转发回去，就会形成自激回声（曾实测约 100Hz 的开关振荡）。v2.10 起通知只记录、不回转，状态回复统一报“已生效值”而非滞后的系统设置值。
- MIUI 在工作台激活时，输入法的 layering target 是一个无任务的系统窗口，单目标的键盘适配方案会静默放弃；需对 layering / input / control 三个 target 分别解析到任务再判断。

上游同步情况：本垫片曾以 `hyperos-shim/` 目录形式提交到 yishisanren/NeXtep 并被合并（PR #1），后续修复在 PR #2 中。此仓库为独立维护版本。

## 许可证

Apache License 2.0，与格窗本体一致。见 [LICENSE](LICENSE)。
