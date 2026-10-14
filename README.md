# Aura Small Window：AOSP 源码移植包

代码基于 GPT-6.1 Sol 编写
已经过 CoolApk@Fish_Neko 的同意并开源

导出日期：2026-10-12。来自 Aura 当前已集成的小窗实现，基于 Android 16 / LineageOS 23.2 trunk_staging。准确仓库提交和远端见 `baseline.json`。这是跨仓库源码移植包，不是 APK 插件、KSU 模块或可以直接刷入的镜像。

## 包含的功能

- 原显示器 Task 图层等比缩放，应用保持逻辑画布，不创建虚拟显示器。
- 全窗圆角、透明三点操作栏和底部横条；内容区域触摸穿透装饰，缩放热区位于下角圆角附近。
- 顶部三点拖动／菜单，双下角等比缩放，底条跟手预览、上滑退后台、下拉全屏。
- 开窗、恢复／全屏、替换旧小窗的转场及过渡遮罩；单小窗策略。
- 上角迷你窗，单击恢复、双击全屏，迷你状态隔离应用内部输入；最近任务等场景协调最小化。
- IME 抬窗／回位，横竖屏画布转换、输入坐标和触摸遮挡修复。
- Quickstep 最近任务菜单入口、全屏应用底部向左／右上角拖动开窗。
- SystemUI 长返回手势侧边栏、应用自行添加、快速返回避免误触。

完整 `source/` 快照有94个文件；`patches/` 有7份按仓库拆分的差异。目录均使用 AOSP 根目录相对路径。源码版权头保留，原仓库可用的 LICENSE/NOTICE 在 `licenses/`。

## 包的边界

没有导出 Aura 的 NFC、短信验证码、后台压制、手电筒、震动、刷新率、设备身份属性、签名私钥、刷机工具或镜像。没有导出 ColorOS/OPPO JAR、APK、反编译文件、录屏或截图；ColorOS 是本项目的行为参考，不是代码依赖。

`LauncherSwipeHandlerV2` 中独立的灵动岛 Home 目标分支已从主快照剥离，放在可选补丁07。它不包含灵动岛应用；不接入灵动岛时不要应用07。其他特权应用可以按照 `docs/PORTING.md` 的开窗接口另接入口。

不要将 `source/` 下的大型现有类直接覆盖目标分支。它们是读代码和人工解决冲突的参考快照；新增 Aura 类／资源可按路径添加，已有类应合并差异。补丁的上下文来自记录的 Lineage 基线，原生 AOSP 和其他 ROM 可能不同。

## 快速开始

1. 在目标 AOSP 源码中创建自己的工作分支并保存现有修改。
2. 阅读 `docs/PORTING.md`，核对 Android、Shell、Quickstep 和 per-display SystemUI 架构。
3. 先只读检查：`python3 tools/check-patches.py /path/to/aosp`。它不会应用补丁或改源码。跨分支失败时人工移植相关方法，不使用强制覆盖或忽略 reject。
4. 按 `patch-series.json` 的顺序合并01–06，仓库相对路径不同，请在相应仓库内执行 `git apply`。例如在 `frameworks/base` 中合并01，在 `frameworks/native` 中合并02。侧边栏06可以不接入；检查时可加 `--without-sidebar`。
5. 将 `integration/aura-small-window.mk`、`overlay-values.xml`、`property_contexts.fragment` 的小窗部分合入自己的设备配置，按目标分区调整，避免覆盖原设备策略。
6. 使用目标工程自己的正常构建流程重建 Framework、WMS、native GUI／输入／SF、WM Shell／SystemUI 和 Quickstep；通过 API、AIDL、SELinux 和构建检查后做配套镜像。
7. 按 `docs/VALIDATION.md` 实机回归。可以先运行 `python3 tools/run-gesture-tests.py` 做不需要 Android 的算法检查。

可选补丁07必须在05已应用后再检查／应用。包不提供自动写入目标树、签名或刷机脚本。

## 验证状态

导出时100个规范清单文件均与实际构建树一致。排除3个设备混合配置和3个对基线无差异的文件后，94个文件进入包。7份补丁在隔离的准确基线依次通过 `git apply --check` 和应用；包括可选07后可重建全部对应的规范源码（已消除CRLF格式噪声）。导出算法在Windows JDK25与Linux JDK21各通过6302条既有断言；见 `EXPORT_VALIDATION.json` 与tests/gesture内日志。

原工程的构建／手机测试不能代替目标分支验证。本次独立导出没有重新编译整套 Android，也没有在其他 AOSP／设备安装；主快照去掉可选灵动岛分支后的 Android 整体编译尚需目标工程完成。硬件比例、不同 Android 版本、桌面实现和 OEM 输入扩展都应实测。
