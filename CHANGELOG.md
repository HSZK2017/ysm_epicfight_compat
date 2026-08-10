# 更新日志 / Changelog

## v1.5.0 — 2026-08

### 中文

#### 新增

- **ModernYSM 风格 GPU 蒙皮渲染路径**：骨骼 SSBO + 皮肤着色器，整个模型一次 `glDrawArrays` 绘制；每帧 CPU 仅合成关节矩阵（`poses×toOrigin`），顶点蒙皮完全在 GPU 上；与 Epic Fight 计算着色器路径数值等价（端到端模拟逐位验证）
- **Android（OpenGL ES 3.1）支持**：自动检测 GLES 上下文（FCL / Zalith 等启动器）并选用 `#version 310 es` 着色器变体，GPU 蒙皮在 Android 上可用
- **YSM 分支自动检测**：ModernYSM / OpenYSM / 官方 2.6.5 / 完全混淆构建四种形态自动识别
- **GPU 渲染开关联动**：ModernYSM 加载时链接其 `UseGpuRenderer` / `UseCompatibilityRenderer`（反射实时读取，含其运行时自动禁用）；其余分支使用本模组配置
- **配置界面复选框**：OpenYSM / 官方 2.6.5 的 YSM 模型选择界面配置中新增 "YSM-EF Compat: GPU 渲染" 勾选项（ModernYSM 自带勾选项，不重复添加）
- **ModernYSM 兼容**：新签名渲染抑制 mixin（玩家 / 第一人称手臂 / 背景手），以及官方 2.6.5 / OpenYSM 此前缺失的未混淆抑制 mixin（手臂 / 背景手 / 投射物 / 鱼钩 / 载具 / 载具预览）
- **客户端配置**：`enableGpuRender`、`lazyModelCacheSize`（LRU 上限）、`scriptAsyncEval`（异步脚本求值）

#### 性能与内存优化

- **懒加载与 LRU 模型缓存**：模型按需转换、验证缓存恢复；超过上限（默认 64）淘汰最久未用模型并整体释放（GPU 缓冲、纹理、编译脚本、逐玩家动画器）
- **运行时模型后台预编译**：脚本编译移出渲染线程（大模型 ~100ms 不再卡首帧），渲染线程遇在途编译先回退显示
- **异步 Molang 求值**：非本地玩家的脚本求值在后台线程（双缓冲发布），渲染线程仅做网格推送
- **Molang 求值器优化**：查询/变量编译期内联为整数 ID（`double[]` 槽位替代 HashMap）、函数调用零分配（ThreadLocal 复用）、变量引用编译期预分类、纯数字表达式常量折叠
- **纹理管线**：图片解码移入后台池、GL 上传按每帧 10ms 预算分时排空、淘汰纹理延迟 5 tick 释放（防同帧引用闪烁）
- **并发转换信号量**：同时最多 2 个模型转换（大模型转换峰值内存数百 MB，控制内存尖峰）
- **逐玩家动画器清扫**：每 15s 清除 60s 未使用的动画器（大模型每个 ~300-400KB，玩家离开后不再残留）
- **战斗模式 GPU 上传优化**：部件段（bind 增量 + 隐藏标志）静态缓存只上传一次，每帧上传从 ~114KB 降至 ~3KB
- 修复模型选择读取日志每秒刷屏（改为每玩家一次）

#### 修复

- 修复 GPU 路径顶点缓冲步长错位（28B vs 32B 属性步长）导致的放射状条纹铺满屏幕
- 修复 GPU 路径矩阵乘积约定错误（delta 未在 GPU 侧相乘）导致的模型巨大（w=0 透视除零）
- 修复 ModernYSM 下 `OpenYsmPlayerRenderMixin` 注入崩溃（旧签名方法不存在，改为 `require=0` 软注入）
- 修复配置界面复选框注入崩溃（旧版目标方法不存在）
- 修复首次绘制大模型 / 上传大纹理时的帧卡顿

#### 兼容性

- **ModernYSM**：完整支持——渲染抑制（新签名）、配置界面（OptionScreen 自动跳过）、GPU 开关联动
- **OpenYSM / 官方 2.6.5**：补齐此前缺失的渲染抑制；配置界面复选框可用
- **Android**：OpenGL ES 3.1 设备启用 GPU 蒙皮；低于 ES 3.1 自动回退
- **macOS**：GPU 路径自动排除（GL 4.1 无 SSBO），回退链不变
- **Iris / Oculus 光影包**：激活时 GPU 路径让位 EF 计算路径（内建 Iris 支持）

---

### English

#### Added

- **ModernYSM-style GPU skinning path**: bone SSBO + skinning shader, the whole model drawn in a single `glDrawArrays`; only the joint matrices (`poses×toOrigin`) are composed on the CPU per frame, vertex skinning runs fully on the GPU; numerically identical to Epic Fight's compute-shader path (verified bit-for-bit with an end-to-end simulation)
- **Android (OpenGL ES 3.1) support**: GLES contexts (Fold Craft Launcher / Zalith launchers) are auto-detected and get a `#version 310 es` shader variant, making GPU skinning work on Android
- **Automatic YSM fork detection**: ModernYSM / OpenYSM / official 2.6.5 / fully-obfuscated builds are recognized automatically
- **Linked GPU render toggle**: with ModernYSM installed the toggle follows its `UseGpuRenderer` / `UseCompatibilityRenderer` (read live via reflection, including its runtime auto-disable); all other forks use this mod's own config
- **Config screen checkbox**: a "YSM-EF Compat: GPU Rendering" checkbox is added to the YSM model-selection config screen for OpenYSM / official 2.6.5 (ModernYSM already ships its own, so nothing is duplicated)
- **ModernYSM compatibility**: suppression mixins for its new hook signatures (player render / first-person arm / background hand), plus un-obfuscated suppression mixins for official 2.6.5 / OpenYSM that were previously missing (arm / background hand / projectile / fishing hook / vehicle / vehicle preview)
- **Client config options**: `enableGpuRender`, `lazyModelCacheSize` (LRU cap), `scriptAsyncEval` (async script evaluation)

#### Performance & Memory

- **Lazy loading + LRU model cache**: models convert on demand with verified on-disk cache restore; least-recently-used models beyond the cap (default 64) are evicted and fully released (GPU buffers, textures, compiled scripts, per-player animators)
- **Background runtime-model precompilation**: script compilation moved off the render thread (no more ~100 ms first-draw hitch for large models); the render thread falls back briefly while a precompile is in flight
- **Async Molang evaluation**: script evaluation for non-local players runs on a background thread (double-buffered results), the render thread only pushes to the mesh
- **Molang evaluator optimizations**: query/variable paths interned to integer IDs at compile time (`double[]` slots instead of HashMaps), zero-allocation function calls (ThreadLocal reuse), compile-time variable classification, constant folding for pure-numeric expressions
- **Texture pipeline**: image decoding moved to the background pool, GL uploads drained with a 10 ms per-frame budget, evicted textures released after a 5-tick delay (prevents mid-frame reference flicker)
- **Conversion semaphore**: at most 2 concurrent model conversions (a conversion holds hundreds of MB for large models - caps memory spikes)
- **Per-player animator sweep**: animators unused for 60 s are pruned every 15 s (each ~300-400 KB for large models - no accumulation after players leave)
- **Battle-mode GPU upload optimization**: the part section (bind deltas + hidden flags) is uploaded once and cached, dropping the per-frame upload from ~114 KB to ~3 KB
- Fixed the per-second log spam from model-selection reads (now once per player)

#### Fixed

- Fixed the GPU path vertex-buffer stride mismatch (28 B written vs 32 B attribute stride) that caused radial stripe artifacts covering the screen
- Fixed the GPU path matrix-product convention error (delta not multiplied on the GPU side) that caused the model to render gigantic (w = 0 perspective divide)
- Fixed a mixin injection crash under ModernYSM (`OpenYsmPlayerRenderMixin` - the old method signature no longer exists; now `require=0` soft injection)
- Fixed a config-screen checkbox injection crash (obsolete target method)
- Fixed frame hitches when first drawing large models / uploading large textures

#### Compatibility

- **ModernYSM**: fully supported - render suppression (new signatures), config screen (OptionScreen auto-skipped), linked GPU toggle
- **OpenYSM / official 2.6.5**: previously missing render suppression restored; config screen checkbox available
- **Android**: OpenGL ES 3.1 devices get GPU skinning; older ES versions fall back automatically
- **macOS**: GPU path auto-excluded (GL 4.1 lacks SSBO); fallback chain unchanged
- **Iris / Oculus shader packs**: when active, the GPU path yields to Epic Fight's compute path (built-in Iris support)
