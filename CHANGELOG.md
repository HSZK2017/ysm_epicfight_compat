# 更新日志 / Changelog

只列每个版本的主要变化。完整排查与测试记录见[开发过程归档](docs/development-history.md)。

Release highlights only. See the [development history](docs/development-history.md) for detailed investigation and test notes.

## 未发布 / Unreleased

- 修复可选真实模型测试的 Gradle 系统属性转发，提供安装目录参数时不再误跳过。
- 构建改用环境中的 JDK 17；新增每次推送与拉取请求的构建、测试及发行 JAR 校验。
- 物理模拟状态按实体隔离，并在实体或世界失效时清理，避免多个实例共享运动状态。
- 头部接触判定与物理部件构建解耦；移除未接入渲染的旧布料求解器及无效配置。
- Fixed Gradle system-property forwarding for optional real-model tests so a supplied install path enables them.
- Builds now use the configured JDK 17; CI checks the build, tests, and release JAR on every push and pull request.
- Physics state is isolated per entity and cleaned up when the entity or world becomes invalid.
- Head-contact classification is decoupled from physics-part construction; the unused cloth solver and inactive settings were removed.

## v1.10.0 — 2026-10-09

- 阎魔刀等闪避残影保留创建瞬间的部件可见状态，不再显示所有条件形态。
- 修复尾尖与尾根分离；改善裙片联动与头顶发片的几何接触约束。
- 加载本模组时拦截 YSM 针对 Epic Fight 的旧版兼容警告。
- 已知限制：EF 大幅前倾和迈步时仍可能出现局部裙摆穿模；可通过模型专属物理覆盖配置调整。
- Dodge afterimages retain the part visibility captured at creation.
- Tail binding, skirt coupling, and geometric head-hair contact constraints were improved.
- The obsolete YSM warning about Epic Fight is suppressed when this bridge is loaded.
- Known limit: extreme Epic Fight running poses can still cause local skirt clipping; per-model physics overrides remain available.

## v1.9.0 — 2026-08

- 收紧 Molang 函数参数检查，修复部分表达式求值问题。
- 加固模型路径与资源读取限制，改进切换世界时的异步任务及资源清理。
- 修复渲染状态恢复问题，并完善模型转换、纹理与动作轮映射处理。
- Tightened Molang argument validation and fixed expression evaluation cases.
- Added model path and resource limits, and improved asynchronous cleanup across world changes.
- Fixed render-state restoration and refined model conversion, texture handling, and animation-wheel mappings.

## v1.8.1 — 2026-08

- 修复联机时生成的动作轮模板触发 EF 动画注册校验，导致断线的问题。
- 修正内嵌依赖的重映射打包流程。
- Fixed multiplayer disconnects caused by Epic Fight validating generated animation-wheel templates.
- Corrected reobfuscation of bundled dependencies.

## v1.8.0 — 2026-08

- 拆分模型资源总管，理清纹理、清单与关节数据职责。
- 修复动画关键帧读取、异步求值及资源生命周期问题。
- 加固模型路径检查和渲染状态恢复。
- Split the model resource manager into texture, manifest, and joint-data components.
- Fixed animation keyframe loading, asynchronous evaluation, and resource lifetime issues.
- Strengthened model path checks and render-state restoration.

## v1.5.1 — 2026-08

- 为不支持计算着色器的设备提供 CPU 蒙皮回退。
- 修复部分模型缺面及战斗模式下的性能问题。
- Added CPU skinning fallback for devices without compute shaders.
- Fixed missing faces in some models and performance issues in battle mode.

## v1.5.0 — 2026-08

- 引入基于 ModernYSM 的 GPU 蒙皮，并支持 Android GLES 3.1。
- 加入模型资源按需加载和异步脚本、纹理处理。
- Introduced ModernYSM-based GPU skinning with Android GLES 3.1 support.
- Added lazy model-resource loading and asynchronous script and texture processing.
