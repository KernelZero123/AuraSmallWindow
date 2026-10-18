# 移植顺序与接口契约

## 适配范围

优先同代 Android 16、支持 WM Shell 和 Quickstep 的手机工程。Android 14／15 可人工回移，但窗口装饰、Shell 转场、DesktopModeFlags、per-display SystemUI、输入 WindowInfo 和 AIDL 的差异可能较大，不保证补丁直接应用。平板桌面模式、多显示器和多小窗不是这份实现的当前目标。

`baseline.json` 给出四个仓库的准确提交。Lineage 提交不是某个原生 AOSP tag 的同义词；以方法语义和调用生命周期移植，不只比较文件名。

## 跨仓库分组

| 补丁 | 仓库 | 内容 |
| --- | --- | --- |
| 01 | frameworks/base | Task、WCT、TaskInfo、TransitionInfo、应用多窗行为、Shell 装饰／转场／IME／输入接线及相关资源和测试 |
| 02 | frameworks/native | WindowInfo圆角视觉覆盖字段、Parcel、SF快照和InputDispatcher遮挡处理、native测试 |
| 03 | frameworks/base | Keyguard主Looper和SystemUI display组件生命周期修复 |
| 04 | frameworks/libs/systemui | PerDisplayRepository生命周期CoroutineContext配套接口 |
| 05 | packages/apps/Launcher3 | Quickstep开窗入口、交接遮罩、最近任务与桌面目标正确性 |
| 06 | frameworks/base | 长返回手势侧边栏、选择器、资源和手势拦截接线；可以不接入 |
| 07 | packages/apps/Launcher3 | 可选灵动岛Home目标分支，不属于小窗必需功能 |

03与04在当前基线是一组配套改动。若目标分支没有此per-display结构，迁移它的“UI组件只在主线程创建、StrongAuthTracker明确使用主Looper”的约束，而不是强行复制不存在的类。初始化修复不是新增后台服务。

SystemUIInitializer、SysUIComponent、DozeParameters与记录HEAD无差异，因此未作为补丁导出；不应从旧实验清单恢复历史版本。

## 几何与变换

- `Task.bounds.left/top` 是屏幕平移，宽／高是固定逻辑画布；可见宽高等于逻辑宽高乘实际scale。left/top不乘scale。拖动使用屏幕raw delta，不再除scale。
- Task层承载稳定变换。WMS Task负责持久比例、方向、焦点和正确的转场结束复位，Shell在交互／IME／转场时取得变换所有权，结束时提交几何。
- SF现有parent transform已生成逆输入矩阵，禁止在InputDispatcher或应用端再加一次逆缩放。无障碍坐标另由WindowState屏幕矩阵补偿。
- 圆角按目标物理dp换算到被缩放的本地坐标。圆角外层裁剪与输入覆盖必须一致，不能用View背景替代Task裁剪。
- 迷你窗保留正常应用布局与装饰scale，只裁剪上下chrome并等比缩放；不能去掉Insets又沿用旧宽高导致界面拉伸。
- 开窗逻辑画布按竖向逻辑宽度与16:9高度构造，不能直接用当前横屏可用宽高。应用请求横屏时交换画布并重新限位，保持屏幕本身方向独立。
- dp基于运行时densityDpi/160，不能把Aura的720×1600／320dpi像素尺寸抄到其他设备。

## 必须共同移植的接口

1. `TaskInfo.auraSmallWindowScale / auraSmallWindowMini / auraSmallWindowDecorScale`：填充、复制、比较及Parcel读写保持一致。
2. `WindowContainerTransaction.setAuraSmallWindowGeometry()`、`beginAuraSmallWindowInteraction()`及对应Change位和WOC处理。当前使用1<<15／1<<16；目标分支若已占用，重新分配并同步所有读写端。
3. `TransitionInfo.Change` 的Aura起止比例／迷你信息，Shell转场与Finish事务使用同一契约，不能只移动画类。
4. `ActivityWindowInfo.isAuraSmallWindow()`与Activity／ActivityThread多窗回调分支，支持播放器横屏等场景，不在整个系统伪装所有多窗任务。
5. `InputWindowHandle.touchOcclusionRegion`：Java、JNI、libgui WindowInfo及Parcel、SF计算、InputDispatcher消费共同更新。来自SF的视觉覆盖不是应用可自由提供的信任区域。
6. 最近任务AIDL／代理／状态监听，以及SystemUI显示库新增CoroutineContext接口。AIDL／Parcel改动不是向旧二进制追加一个Java字段即可兼容。

必须重编并配套交付Framework、system_server、SystemUI／Shell、桌面、libgui、输入／SF等相关消费者。不要仅更新SystemUI APK。API检查应按目标分支处理隐藏API／更新规则，不关闭检查来掩盖接口不匹配。

## 设备接入

`ro.aura.small_window=true` 启用行为；`ro.aura.small_window_scale=74` 为默认74%。属性名可统一改名，但要改WMS、Shell、Quickstep／侧边栏及property_contexts所有读者，不加入广泛的ro.aura.*类型映射。

任务范围由WMS判定：默认显示器、STANDARD应用任务、根／叶freeform任务。不要把PiP、桌面任务、其他显示器或嵌套任务套入该缩放路径。

按 `integration/` 示例声明freeform特性、覆盖手机桌面模式开关、声明两条精确属性。目标分支没有某个overlay资源时按自己的DesktopMode入口逻辑适配。不需要Aura_A1的90Hz RRO、SIM热插拔／VoLTE配置，也不需要更改vendor、内核或调度时长。

## 新桌面／其他特权入口

不是Quickstep的桌面可以不接05，但必须自己实现最近任务收尾和开窗交接；否则可能出现错误Home目标、黑闪、旧小窗突然消失。不要直接从一个Activity跳转覆盖已有置顶小窗。

现有入口参考 `TaskShortcutFactory`、`AuraSmallWindowSwipeController` 和 `AuraSmallWindowSidebar`。启动用系统／平台权限下的ActivityOptions freeform＋正确launchBounds，在服务端准备好旧小窗退后台动画后启动新窗。需要旧窗替换时使用现有有权限约束的ordered broadcast `com.android.systemui.AURA_PREPARE_SMALL_WINDOW`；沿用原发送／接收权限、回调和兜底约束，不能把控制任务的接口开放给普通应用。

开窗mask／图标透明buffer、真实装饰就绪通知与动画收尾是一个闭环。新增入口也要接该闭环，不能只写launchBounds就宣称完成开窗动画。

## 重点冲突与回归

- DesktopModeFlags和WindowDecorViewModel的分支、Dagger构造器和Factory参数。
- 新版WCT change mask、TransitionInfo Parcel字段、nativeWindowInfo Parcel顺序。
- Caption输入区域、圆角覆盖和悬浮按钮不能挡住应用内容；横屏触摸不可再次逆缩放。
- 不改标准窗口的输入信任／遮挡策略；回退矩阵与不支持变换条件保留。
- 关闭小窗是应用退后台，不能恢复为removeTask／force-stop。
- 最近任务恢复、第二个小窗替换、过渡取消与task已销毁检查，避免旧Surface闪回。
- IME、拖动、缩放只能有一个变换所有者；切换过程不能不断重排置顶造成全屏桌面动画卡顿。
