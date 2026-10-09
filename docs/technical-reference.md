# 技术参考

[返回 README](../README.md)

本文保留模型解析、类职责、性能机制、调试参数及已知限制，供开发与排障查阅。安装、构建准备和主流程请先阅读 README。

本文的性能数字与游戏内验证记录来自原 README，不代表对当前环境重新测量的结果。类数量和测试数量也可能随源码更新变化。

## 阅读导航

- [模块说明](#模块说明)
- [性能优化](#性能优化)
- [配置与调试参数](#配置)
- [测试分类](#测试)
- [完整已知限制与验证边界](#已知限制)

## 阅读约定

- GPU、CPU 和 Iris 是按条件选择的路径，不能把它们当作一条固定执行链。
- `require=0` 允许未匹配的可选注入跳过，不等于所有异常均安全，也不提供方法匹配成功的日志证明。
- 二次运动目前使用 `YsmMeshSecondaryMotion` 摆锤求解器；独立布料求解器尚未接入绘制。重力参数应区分 `secondaryMotionGravityAcceleration` 与 `secondaryMotionGravity`。
- 安装依赖的版本范围见 README；当前构建本地依赖还包括两个女仆联动 jar，运行时则可选。

## 模块说明

### 模型解析 (`com.ysmef.compat.ysm`)

| 类 | 职责 |
|---|---|
| `YsmModelPackage` | 统一入口：按 modelId 加载目录包或二进制包，返回几何 + 贴图 + 属性 + 脚本动画 (`ScriptAnim`)；modelId 与包内路径均有穿越/符号链接防护，源文件大小受上限保护 |
| `YsmBinaryReader` | 二进制格式反序列化：`format` 版本链 (legacy V1/V15、modern 16+)，几何段、贴图表、动画；字节序 LE，VarInt LEB128 |
| `YsmFileCrypto` | `.ysm` 解密管线：XChaCha20 解密 → MT19937 白化 → 魔改 zstd 块头洗牌 → 标准 zstd 解压 |
| `ScriptJson` / `ScriptAnim` / `Molang` | 脚本动画解析与编译；Molang 求值器（查询/变量整数 ID 内联、零分配函数调用、常量折叠，见性能节） |

### 几何转换 (`com.ysmef.compat.model`)

| 类 | 职责 |
|---|---|
| `YSMGeoModel` | Bedrock 几何解析：cube 8 顶点、6 面（box UV / per-face UV）、镜像、膨胀、cube 枢轴旋转；骨骼层级、绑定变换链 |
| `EFMeshJsonWriter` | 生成 EF animmodels JSON + 运行时 JSON。骨骼级部件 (`y/<boneName>`)、预三角化（每四边形 6 角点）匹配 EF 的三角绘制约定；顶点焊接、关节映射、宽高缩放 |
| `YSMJointMapper` | YSM 骨骼名 → EF biped 关节 ID 映射 (Root=0..Elbow_L=19) |
| `YSMMesh` | EF `HumanoidMesh` 子类：贴图替换、运行时模型 ID、按部件序号的运行时变换注入（O(1) 数组访问） |
| `YSMMeshLibrary` | 网格注册/懒转换门禁 + **LRU 淘汰**（见性能节）+ `MeshReleaser`/`RenderBridgeRegistry` 注册表（与 gpu/cpu 渲染包解耦） |
| `TextureStore` | 纹理管线全域：字节注册、PNG/JPEG/WebP/AVIF 解码、异步上传（每帧时间预算）、延迟释放、pack/缓存文件布局、`sanitize` 路径穿越防护 |
| `ManifestStore` | 生成缓存清单：内存镜像 + 版本合并后台写（渲染线程零文件 I/O） |
| `JointTable` | EF 参考双足骨架 20 关节表单一数据源（原三处重复） |
| `YsmMaidMeshSupport` | 女仆 YSM 模型网格选择桥（`EntityMaid.isYsmModel()` → 转换后的 YSM 网格；仅 EFTLM 安装时生效） |

### 运行时脚本系统 (`com.ysmef.compat.model.runtime`)

| 类 | 职责 |
|---|---|
| `YSMRuntimeModel` | 编译骨骼表 + Molang 动画，按 modelId 缓存；**默认形态可见性**（静态求值 parallel scale 通道，层级传播 `effMinScale`，战斗模式套用）；**后台预编译**（首次绘制不再渲染线程编译）；**逐玩家动画器清扫**（15s 周期，60s 未用回收） |
| `YSMPlayerAnimator` | 每玩家脚本求值：平行 → 状态 → 条件覆盖；mapped bones 只留 scale 通道，unmapped 完整变换；`effMinScale < 0.01` 隐藏；identity delta 跳过注入；**异步求值**（双缓冲，非本地玩家后台线程） |
| `YSMRuntimeBridge` | 渲染帧桥接：战斗模式中玩家使用 `applyEntityVisibility()` 读取持久轮盘开关，其他实体使用 `applyDefaultVisibility()`，随后调用 `YsmMeshSecondaryMotion.apply()`；非战斗模式桥接分支支持完整脚本求值 |

### GPU 渲染 (`com.ysmef.compat.gpu`)

| 类 | 职责 |
|---|---|
| `YsmGpuRenderPath` | 直接 GPU 蒙皮路径：每帧 CPU 只合成关节矩阵（`poses×toOrigin`，OM 数学），部件段（bind 增量 + 隐藏标志）战斗模式下**静态缓存只上传一次**；一次 `glDrawArrays` 绘制；`u_proj = proj×mv×pose` 与 EF 计算路径数值等价（模拟验证逐位一致）；Iris/Oculus 光影包激活时自动回退 EF 路径 |
| `YsmGpuMesh` | 静态 VBO（32B/顶点：pos+uv+2_10_10_10 法线+boneId+partId）+ 动态骨 SSBO（144B/条）+ 部件段缓存 |
| `YsmBoneSkinShader` | 皮肤着色器（桌面 `bone_skin.vsh/fsh` GL 4.3 / Android `bone_skin_es.vsh/fsh` GLES 310 自动选择）：`boneMat = joint×part` 与 EF 计算着色器逐项一致；复刻 MC 光照/雾/overlay/光照贴图语义；半透明纹理双 Pass |
| `YsmGpuCapability` | GL 能力探测（桌面 SSBO/420pack/显式属性位置/2_10_10_10，**Android OpenGL ES 3.1**），失败自动回退 |
| `YsmGpuRenderEnable` | **YSM 分支检测 + GPU 开关联动**（见下节） |
| `YsmIrisComputePath` / `YsmIrisMesh` | 光影包下的优化计算路径：关节-only 上传、部件段变更门控、缓存 uniform/顶点格式；按网格动态大小 SSBO，渲染超出 EF MAX_JOINTS 的模型 |

### CPU 渲染 (`com.ysmef.compat.cpu`)

| 类 | 职责 |
|---|---|
| `YsmCpuRenderPath` | CPU 蒙皮路径：每帧 CPU 逐顶点蒙皮（`(pose×toOrigin×partDelta)×bindPos` 加权和，与 EF 计算着色器逐项一致，支持多关节权重），poseStack 在 CPU 端应用（EF drawPosed 同款契约），顶点流式写入复用动态 VBO，整模型单次 `glDrawArrays(GL_TRIANGLES)`；隐藏部件跳过、索引/NaN 边界防御；由 `SkinnedMeshCpuRenderMixin` 在 EF 回退 CPU 渲染着色器（drawPosed）时接管，仅光影包激活等场景让位 |
| `YsmCpuMesh` | 每网格动态 VBO（24B/顶点：pos+uv+2_10_10_10 法线）+ 复用 CPU 累积缓冲：低内存占用，每帧零分配（适配 <2G 内存工况） |
| `YsmCpuSkinShader` | CPU 皮肤着色器（桌面 `cpu_skin.vsh/fsh` `#version 330` / Android `cpu_skin_es.vsh/fsh` `#version 300 es`）：顶点已是相机空间，着色器只应用普通 RenderSystem proj/mv（vanilla 实体着色器契约）；复刻 MC 光照/雾/overlay/光照贴图语义；半透明纹理双 Pass；**无需 SSBO / 计算着色器** |

### YSM 分支兼容（`YsmGpuRenderEnable` + mixin 族）

| 分支 | 检测依据 | GPU 开关 | 渲染抑制 mixin |
|---|---|---|---|
| **ModernYSM** | 存在 `rip.ysm.gpu.*` | **联动 ModernYSM** `UseGpuRenderer`/`UseCompatibilityRenderer`（反射实时读取，含其运行时自动禁用） | `ModernYsm*Mixin`（新签名：`onRenderPlayerPre(Player,...)Z` 等，返回 false） |
| **OpenYSM** | 存在未混淆 `client.event.ReplacePlayerRenderEvent`（无 `rip.ysm.gpu.*`） | 本模组 `enableGpuRender` 配置 + **模型选择界面勾选框**（追加到配置界面 performance 分组） | `OpenYsm*Mixin`（旧事件签名：`onRenderPlayerPre(RenderPlayerEvent$Pre)V` 等） |
| **官方 2.6.5（混淆发行版）** | 存在 2.6.5 特定混淆类（`O0o...` 等）；不是另一种独立发行形态 | 本模组 `enableGpuRender` 配置 + 模型选择界面勾选框 | `Ysm*Mixin`（混淆目标，软跳过） |

- 所有抑制 mixin 均为 `require=0` 软注入：只对签名实际存在的分支生效，未匹配的可选注入可跳过；这不保证所有版本或其他错误都不会导致崩溃。目标形式有两种：`OpenYsm*`/`ModernYsm*`/`YsmUnobf*`/`YsmExtraPlayerOverlayMixin`/`YsmAnimationTransitionGuardMixin` 等用**字符串目标**（那些类不在编译类路径上），而 9 个混淆发行版目标用**类字面量**（混淆类在 `libs/ysm-2.6.5.jar` 里，因此可编译）；`RenderSystemAccessorMixin` 是原版 `RenderSystem` 的 `@Accessor`，没有 `require` 元素也不该有。混淆发行版与 OpenYSM/ModernYSM 的共享类（`CustomProjectileRenderer` 等，签名一致）由 `YsmUnobf*Mixin` 覆盖。
- **排障须知**：`require=0` 的"没匹配上"**不打任何日志**（只有目标**类**整体缺失才会 WARN，目标**方法/描述符**不匹配时 Mixin 完全静默）。因此"哪个分支的抑制生效了"只能从行为判断；升级 YSM 或 Epic Fight 后若出现重复渲染或换装失效，先怀疑这里。
- 抑制内容：第三人称玩家渲染、第一人称手臂、背景手、投射物、鱼钩、载具、载具预览：战斗模式下全部让位给原版/EF 渲染。
- ModernYSM 场景下配置界面不重复添加复选框（其自带 `UseGpuRenderer` 勾选项）。

### 渲染集成 (`com.ysmef.compat.renderer`)

| 类 | 职责 |
|---|---|
| `YSMPlayerRenderer` | 补丁玩家渲染器 (`PHumanoidRenderer`)，LOWEST 优先级注册；条件盔甲/头/鞘翅层 |
| `YSMMeshSelector` | 网格选择：`YSMModelAccess` 读当前模型 → `YSMMeshLibrary` 查找 → 设置运行时模型 ID + 当前玩家 |
| `YSMModelAccess` | 模型选择解析：集成服务器 NBT → 模型同步通道 → 客户端 capability NBT（20 tick 缓存） |
| `YSMBattleMode` | 战斗模式判定：`PlayerPatch.isEpicFightMode()` |
| `YSMRenderHook` | `RenderLivingEvent.Pre` HIGHEST：EF 接管时取消事件 + 用原版渲染器（含 `PatchedItemInHandLayer`）绘制臂架模型，恢复武器渲染 |

### 事件与 Mixin

主配置 `ysm_epicfight_compat.mixins.json`（**32** 个客户端 mixin + 1 个 common）：`ModernYsm*`（3）、`OpenYsm*`（4，含配置界面）、`YsmUnobf*`（4）、混淆版 `Ysm*`（8）、`PPlayerRendererMixin`、`RenderSystemAccessorMixin`（着色器光照方向）、`SkinnedMeshCpuRenderMixin`（EF drawPosed 回退拦截 → 本模组 CPU 蒙皮路径）、`RenderItemBaseMixin`、`EpicFightRenderLivingEventMixin`、`DiscreteInputActionTriggerMixin`、`MouseHandlerMixin`、`YsmRouletteConfigExpressionMixin`、`YsmAnimationTransitionGuardMixin`、`YsmLivingMovementPredicateMixin`、`YsmArmaturePoseMixin`、`YsmExtraPlayerOverlayMixin`（战斗模式抑制纸娃娃，见配置 `disableExtraPlayerInBattleMode`）；common 段为 `AnimationManagerValidationMixin`（服务端动画注册表校验豁免，见下）；可选配置 `ysm_epicfight_compat.eftlm.mixins.json`（TLM 女仆渲染挂钩，`required: false`，TLM 缺席时安全）。

### 多人联机模型同步 (`com.ysmef.compat.network`)

| 机制 | 细节 |
|---|---|
| **通信协议** | 独立通道 `ysm_epicfight_compat:model_sync`；握手版本检查（YSM id 51/52 模式），握手完成前不交换模型数据 |
| **模型广播包** | `S2CSetModelAndTexturePacket`（YSM id 4 模式）：entityId + modelId + textureId + disabled + UUID（客户端以 UUID 为主键注册，对重生/跨维度更稳） |
| **服务端广播时机** | 入世界握手 → `PlayerEvent.StartTracking` 推送 → 每 40 tick 差异扫描广播（检测需序列化全量玩家 NBT，2 秒周期是吞吐折中） |
| **服务端数据源** | `YsmCapabilityReader` 读 `ServerPlayer` ForgeCaps NBT（`yes_steve_model:model_id`），无 YSM 类依赖 |

---

## 性能优化

| 机制 | 细节 |
|---|---|
| **懒转换（无开机加载）** | 模型首次渲染时才转换（后台池，≤4 线程），期间回退 EF biped；命中验证缓存则免解密直接恢复 |
| **LRU 模型缓存** | 超过 `lazyModelCacheSize`（默认 64）时淘汰最久未用模型：释放 GPU 缓冲、纹理与共享网格（均延迟 5 tick 释放，防同帧引用闪烁/use-after-free）、编译脚本与逐玩家动画器；下次使用从验证缓存瞬时恢复 |
| **模板描述符降采样** | 轮盘动画相似度描述符每 8 帧存 1 帧 + 流式加载（旧全帧格式自动迁移），描述符文件从数百 MB 降至几十 MB 级 |
| **并发转换信号量** | `Semaphore(2)` 限制同时转换的模型数（每个转换持有解密包 + 几何数组，大模型数百 MB），控制峰值内存 |
| **逐实体动画器清扫** | 每 15s 清除 60s 未使用的逐玩家动画器（大模型每个 ~300-400KB），玩家离开后不再残留 |
| **运行时模型后台预编译** | 网格转换/缓存恢复后立即在后台编译 Molang 脚本（大模型 ~100ms 不再卡首帧）；渲染线程遇在途预编译先回退显示 |
| **异步脚本求值** | 非本地玩家的 Molang 求值在后台单线程池（双缓冲发布），渲染线程只做网格推送；LOD 距离降频（40/64 格 → 30/10Hz） |
| **Molang 求值优化** | 查询/变量路径编译期内联为整数 ID（`double[]` 槽位替代 HashMap）；函数调用参数零分配（ThreadLocal 复用）；变量引用编译期预分类；纯数字表达式常量折叠；函数调用携带精确 `argCount`，不复用陈旧参数槽 |
| **热路径探测缓存** | YSM 预览模式 250ms TTL；EF compute setup 按网格实例缓存；CPU GL 能力只探测一次；shader-pack 检测全项目单一实现 |
| **轮盘映射 sidecar** | 每模型一个小 JSON 原子写（旧聚合文件兼容读取），转换期间负缓存避免每 tick 读盘；`exactHash` 复用单个 ByteBuffer，无逐 float 堆分配 |
| **GPU 路径** | 静态几何一次上传 + 每帧仅关节矩阵（战斗模式 ~3KB 而非 ~114KB）+ 单次 draw call；与 EF 计算路径数值等价（模拟验证） |
| **CPU 蒙皮路径** | 无计算着色器/SSBO 设备的兜底渲染：逐顶点 CPU 蒙皮（大模型 ~1.2 万顶点/帧）写入复用缓冲，每帧零分配；每网格仅 24B/顶点动态 VBO（内存占用远低于计算/GPU 管线，适配 <2G 内存工况）；单次 draw call |
| **纹理管线** | 图片解码移入后台池；GL 上传按每帧 10ms 预算分时排空（大纹理不再卡首绘）；淘汰纹理延迟释放防止闪烁 |
| **关键帧增量游标** | 每通道摊销 O(1) 关键帧查找（循环回绕自动复位） |
| **零分配矩阵合成** | `bindWorldInv` 预计算；`composeBone` 复用持久 scratch；逐帧无 Matrix4f/数组分配 |

---

## 配置

客户端配置 `config/ysm_epicfight_compat-client.toml`：

| 选项 | 默认 | 说明 |
|---|---|---|
| `enableGpuRender` | true | GPU 蒙皮路径开关。ModernYSM 加载时忽略本项（联动其 `UseGpuRenderer`）；OpenYSM/LegacyYSM 下生效，可在 YSM 模型选择界面勾选，GPU 路径不可用时自动置为 false（仿 ModernYSM） |
| `lazyModelCacheSize` | 64 | LRU 模型缓存上限（8-512） |
| `scriptAsyncEval` | true | 非本地玩家脚本异步求值 |
| `disableExtraPlayerInBattleMode` | true | 战斗模式下抑制 YSM 左上角"额外玩家渲染"（纸娃娃）：纸娃娃每帧经实体渲染分发器触发第二次完整 EF 补丁渲染管线（实测 100+ 帧 → 20-30 帧的元凶）；战斗模式中模型已可见于世界内，默认关闭纸娃娃 |

调试/验证系统属性（JVM 参数，不需要改配置文件）：

| 属性 | 说明 |
|---|---|
| `-Dysm_ef_compat.force_cpu_render=true` | 强制跳过 EF 计算着色器、始终走本模组 CPU 蒙皮路径（在支持计算着色器的硬件上验证回退链） |
| `-Dysm_ef_compat.disable_gpu=true` | 禁用 GPU 蒙皮路径（回退到 EF 计算着色器 / 本模组 CPU 蒙皮） |
| `-Dysm_ef_compat.disable_iris_compute_path=true` | 禁用优化 Iris 计算路径（A/B 验证用，回退 EF 自带 Iris 路径） |
| `-Dysm_ef_compat.enable_iris_compute_path=true` | **不再被读取**：优化 Iris 路径自 2026-09-20 起默认开启。该开关已在代码中移除（`ENABLED` 只看 disable 那个）；启动参数里留着它不会有任何效果，也不会报错 |
| `-Dysm_ef_compat.diag=true` | 开启诊断日志（渲染路径跳过原因、逐帧计时） |

当前有效布尔开关的取值语义统一（`SystemFlags.enabled`）：`-D名` 或 `-D名=true` 为开，**`-D名=false` 为关**：早期实现用 `getProperty(名) != null` 判断"存在即开"，于是写 `=false` 反而把功能打开（对 `disable_*` 类开关则是仍保持关闭），四个开关都有这个坑，现已统一并加单测钉住。`0`/`no`/`off`（不区分大小写、忽略首尾空格）同样读作关。


### 模型包大小限制

| 属性 | 说明 |
|---|---|
| `-Dysm_ef_compat.max_package_bytes=...` | `.ysm` 包/模型源文件大小上限（默认 512 MiB，防御畸形大文件） |
| `-Dysm_ef_compat.max_decompressed_bytes=...` | `.ysm` 解压后二进制载荷大小上限（默认 512 MiB，防御解压炸弹） |

---

## 测试

单元测试（`src/test`，JUnit 5，无需 Minecraft 运行时）：

```powershell
.\gradlew.bat test
# 含真实 .ysm 解密链黄金用例：
.\gradlew.bat test "-Dysmef.golden.ysm=C:\path\to\model.ysm"
```

- **Molang 求值器**（12）：算术/变量/三元/比较/`??`/函数/语句序列/除零 sanitize/错误回退/常量折叠/函数参数计数
- **CityHash 固定向量**（3）：自举向量 + 范围变体一致性（正确性由真实 .ysm 文件尾哈希端到端钉死）
- **winefox 明文黄金用例**（4）：195 骨骼几何、49 动画、pre/post 关键帧真值（`src/test/resources/golden/winefox/`）
- **二进制关键帧 pre/post**（3）：按序列化器磁盘布局编码，锁定 pre/post 语义修复
- **`sanitize` 路径穿越**（5）+ **关节表**（3）
- **`YsmModelPackageTraversalTest`**（4）：读路径模型 ID 的穿越/绝对路径/盘符/NUL 拒绝与合法相对 ID 接受
- **真实 .ysm 解密链**（3，需 `-Dysmef.golden.ysm`）：CityHash 尾哈希校验、XChaCha20+MT19937+zstd、二进制解析

---

## 已知限制

1. **渲染路径回退链**：GPU 蒙皮需 GL 4.3+（Android 需 OpenGL ES 3.1，ES 路径已去除桌面专属 GL 调用，真机验证待 Android 环境）；不满足时依次回退 EF 计算着色器 → 本模组 CPU 蒙皮（桌面 GL 3.3+ / OpenGL ES 3.0+，无缺面）→ EF drawPosed（三角化已修复，渲染完整）。macOS（GL 4.1 无计算着色器）走 CPU 蒙皮
2. **Iris/Oculus 光影包**：光影包激活时 GPU/CPU 直连路径让位 EF 计算路径，由本模组的优化 Iris 路径（关节-only 上传、无 MAX_JOINTS 上限）接管：**自 2026-09-20 起默认开启**。它曾长期不可达（无任何外部引用），第一次真正绘制时暴露出一个**重传门控缺项**：part 段只在"增量出现/消失"时才重传，而二次运动是每帧变化的数值 → part 段只上传过一次并冻结（实测 `[physics]` 报 "59 of 59 bone(s) moving" 而画面无位移）。缺陷已修复并实机验证：9 个模型（单个 51–597 部件、最高 103,998 顶点）全部 `partCount` 一致、物理可见、零 ERROR、零 draw failed。**该验证的边界**：一台机器、一种光影，且"超 MAX_JOINTS(1000) 容量"这一能力未被触达（本次最大 597 部件 + 约 20 关节，未越界），描边/GUI 通道亦未行使。不适用时用 `-Dysm_ef_compat.disable_iris_compute_path=true` 退回 EF 自带 Iris 路径（它同样带增量）；计算着色器不可用时由三角化已修复的 drawPosed 兜底
3. **懒转换首用延迟**：模型首次渲染若缓存未命中，后台转换期间短暂回退 EF biped（几帧）；异步纹理上传同理（纹理出现前 1-2 帧为缺失纹理）
4. **多人联机同步要求专用服务器安装本模组**（服务端仅做 NBT 读取与广播）；未安装时回退 EF biped
5. **远程玩家模型需本地可用**：模型包必须在 `config/yes_steve_model/{builtin,custom,auth}`；会话中途新下载的模型需 F3+T 或 `/ysm model reload` 触发重新生成
6. **混淆目标依赖版本**：官方 2.6.5（＝完全混淆构建）的可读类只有 `mixin/` 包，其 `client.*` 目标全部为 2.6.5 特定混淆名，由 `Ysm*Mixin` 覆盖；OpenYSM/ModernYSM 走未混淆/新签名的 `OpenYsm*`/`ModernYsm*`/`YsmUnobf*` 家族。`mods.toml` 已把 Epic Fight 限制在 `[20.14.17,20.15)`、YSM 限制在 `[2.6,2.7)`，升级依赖需按描述符重新定位并更新契约
7. **贴图格式**：PNG/JPEG 直读；WebP/AVIF 经 YSM ImageStream 反射解码（OpenYSM/ModernYSM 内置，官方 2.6.5 缺失时跳过并告警）；BMP 不支持
8. **战斗模式默认可见性**：以冻结默认环境静态求值 parallel scale 通道决定变体可见性，个别条件化变体可能首帧可见后被运行时覆盖
9. **缓存健壮性**：manifest 记录输出哈希，缓存恢复前逐文件校验；损坏只重转该模型；所有输出原子写
10. **安全上限**：`.ysm` 源文件与解压后载荷默认各限 512 MiB（`-Dysm_ef_compat.max_package_bytes` / `-Dysm_ef_compat.max_decompressed_bytes` 可覆盖），超大但受信任的模型需显式调高；二进制解析对 bone/cube/face 等段落计数同样设上限
11. **轮盘映射迁移**：v1.9.0 起新增每模型映射 sidecar（`config/ysm_epicfight_compat/extra_animation_mappings/`），旧聚合文件 `extra_animation_mappings.json` 仍会被兼容读取，但不再写入
12. **EF 站姿与模型裙摆余量的冲突（剩余穿模）**：Epic Fight 的默认 idle 是"一前一后"的迈步站姿，行走/冲刺/坠落也带腿部摆幅；而 `wine_fox/01_taisho_maid` 按"双腿并拢直立"的 rest 姿态建模，裙摆与腿只有约 **0.10 格**余量。EF 接管腿部动画后，腿会进入裙摆。当前骨段碰撞能约束部分布片，但不能保证整片裙摆不穿模：修正后的逐帧探针在坠落片段记录到 **7/36** 片布与腿部碰撞体发生接触；把碰撞体扩大到 2.0× 时，由于布片从一开始就在体积内，现有求解器会跳过这类初始重叠，并没有改善余量。将布片重绑到最近的腿，在 EF 走/跑/坠/跳与合成步态下、混合比例 0.2–1.0 均未增加间隙。已验证的改进方向是增加模型的裙摆与腿部间隙，或调整 EF 腿部动作；若要靠模组进一步解决，需要按裙摆表面而非单个骨段求解，并处理初始相交。测量见 `build/reports/ysm-ef-skirt-round.md`、`build/reports/ysm-rebind-round.md`。
13. **连续尾巴的关节归属**：当尾巴从髋部向上弯曲时，不能按每段几何高度独立选择 EF 身体关节；`wine_fox/01_taisho_maid` 的旧缓存把 `Tail`–`Tail4` 放在 `Torso`，把 `Tail5`–`Tail7` 放在 `Chest`，上身动作会在尾巴中段拉开两组网格。现在整条尾巴以最靠近身体且有几何的尾巴骨骼决定身体关节；转换器源码指纹变化会令旧缓存重新生成。
14. **布料（位置约束）求解器已实现但未接线**：`YsmMeshCloth` → `YsmClothSolver` → `YsmClothTuning` 这条"把裙摆当粒子网格约束"的链路在源码里完整存在（含 8 次约束松弛、身体体积排斥、钉住粒子的蒙皮放置），但**渲染路径没有任何地方调用它**：`YsmMeshCloth` 在整个 `src` 中唯一的出现是 `YSMReloadTrigger` 里的 `clear()`。当前所有二次运动（头发/尾巴/裙摆）都走 `YsmMeshSecondaryMotion` 的摆锤求解器。因此配置里的 `secondaryMotionMaxParticles`、`secondaryMotionIterations`、`secondaryMotionBodyRadius` 以及 `secondaryMotionGravity`（布料重力）当下**不产生可见效果**；`secondaryMotionGravity` 的注释已按此更正。接线还是删除需要一次带游戏内观察的决定，本轮只把状态写明。
15. **裙片联动的边界与坐标系**：`YsmPhysicsTopology` 只在同一物理类别的相邻骨段之间自动建立联动；模型的 `Tail` 根部虽靠近后裙片 `BM`，仍不能作为裙片邻居。父子骨段关系保留在子段一侧，但 `YsmPhysicsCoupling` 对布片子段使用父段的零相对弯曲作为联动目标：父段的摆动已在 `YsmMeshSecondaryMotion` 的矩阵合成中传给子段，再复制到子段自身摆动会重复旋转下摆。两处约束以真实女仆模型及小型骨架测试覆盖。EF 奔跑中的大幅前倾和腿部跨步仍会造成局部穿模；当前改动针对裙片散开，不承诺完全消除裙腿相交。
16. **几何约束的准确性边界**：模型的骨架父子关系负责确定部件链，几何检查负责剔除不能可靠摆动的骨段并测量接触锚点；作者动画中的 `follows` 在驱动骨段存活时仍优先于骨架关系。几何检查剔除中间骨段后，父段必须继续沿骨架寻找最近的存活骨段。对 `ysm-model-repo` 的 906 个文件离线扫描中，当前读取器可解析 736 个、其中 642 个组装出 17,918 个物理段；旧父段查找会把 764 个有存活上层骨段的子段错当成根。接触锚点只在有空间上接近的支撑几何时替换原枢轴；同一批模型中，旧规则有 901 个移动后的锚点距支撑包围盒超过 0.1 格，修正后为 0。另有 370 个移动后的锚点仍在自身几何包围盒外超过 0.01 格，多由原始枢轴就在几何外且锚点位移被限制所致，不能只凭此距离断定模型错误。完整扫描数据见 `build/reports/ysm-physics-parent-geometry-corpus.md`。**自动选择哪些骨段参与物理以及物理类别仍有名称回退规则**（`YsmPhysicsChains`、`YsmPhysicsParts.categoryOf`）；几何检查不等于完全摆脱命名，无法解析的 170 个文件也尚未被这一扫描覆盖。
17. **贴头几何的刚性约束**：`YsmHeadContactConstraint` 在建立物理段时读取骨架上的映射头关节与双方真实网格；只有头部附近的几何占比足够、接触距离足够近，且接触点横跨头部左右两侧，才将该骨段留在头部动画上。单侧发束、只有少数根部顶点碰到头的长发不满足组合条件；不根据 `BaseHair` 等名称直接判定。对已安装 `wine_fox` 的 18 款带 `BaseHair` 模型，转换后的网格全部通过固定判定，女仆款的刘海、长发与两侧发束仍参与物理。模型库 906 个文件中可解析 736 个；离线候选集原有 17,918 个物理段，新增规则将其中 487 个贴头部件判为刚性。审计结果见 `build/reports/ysm-head-contact-wine-fox.md` 与 `build/reports/ysm-head-contact-corpus.md`。这些是离线几何结果，游戏内所有模型的主观观感仍需抽样验证。

## 参考实现

原 README 列出的参考项目：OpenYSM（格式与网络协议）、ModernYSM（GPU 渲染与资源管理）、LegacyYSM（GeckoBuilder 约定）、YSMParser（C++ 加密交叉验证）、EpicFight_TouhouLittleMaid（补丁渲染器范例）。
