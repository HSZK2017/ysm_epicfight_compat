# YSM Epic Fight Compat

**兼容 Yes Steve Model (YSM) 2.6.5 与 Epic Fight 20.14.17 渲染管线的 Forge 1.20.1 模组**

在史诗战斗模式下，将玩家当前选用的 YSM 自定义角色模型转换为史诗战斗的骨骼网格格式，使 YSM 模型以完整轮廓呈现，并由史诗战斗的蒙皮骨骼驱动攻击、行走等全部战斗动画。

---

## 架构概览

```
本地 YSM 模型文件 (builtin/custom/auth)
    │
    ├── 目录包 (ysm.json + models/main.json)   → YSMGeoModel.parse (Bedrock 几何)
    └── 二进制包 (*.ysm)                        → YsmFileCrypto 解密 → zstd 解压
                                                     │
                                                     ▼
                                           YsmBinaryReader.read
                                                     │
                                                     ▼
                                           YSMGeoModel.fromBinary
                                                     │
                                                     ▼
                                           EFMeshJsonWriter.write
                                           ├── 主网格 JSON (animmodels/entity/<id>.json)
                                           │   · Blender 坐标系、预三角化角点流 (每四边形 6 角点)
                                           │   · 骨骼级部件 ("y/<boneName>")
                                           │   · 顶点焊接、关节映射、宽高缩放
                                           └── 运行时 JSON (ysm_runtime/entity/<id>.json)
                                               · 骨骼表 (层级、绑定矩阵、关节绑定)
                                               · Molang 动画 (parallel/state/condition)
                                                     │
                                                     ▼
                                           YSMMeshLibrary.ensureModel (懒转换, 1.4.0)
                                           · 首次使用某模型时才转换该模型 (后台池)
                                           · 命中缓存 (manifest 指纹+输出哈希校验) → 免解密直接恢复
                                           · 转换结果合并入 manifest (逐模型增量写)
                                                     │
                                                     ▼
                                           YSMMeshLibrary.findMesh / findTexture
                                           · MeshAccessor 按需加载 (EF 网格 JSON 从
                                             PathPackResources 实时读取, 无需重载资源)
                                           · 贴图 → TextureManager 按需上传
```

---

## 模块说明

### 模型解析 (`com.ysmef.compat.ysm`)

| 类 | 职责 |
|---|---|
| `YsmModelPackage` | 统一入口——按 modelId 加载目录包或二进制包，返回几何 + 贴图 + 属性 + 脚本动画 (`ScriptAnim`) + 动画控制器 (`ScriptController`, 探针输入) |
| `YsmBinaryReader` | 二进制格式反序列化：`format` 版本链 (legacy V1/V15、modern 16+)，几何段、贴图表、动画、**动画控制器** (1.5.0 起解析，供动态骨骼探针)、声音/函数跳过；字节序 LE，VarInt LEB128 |
| `YsmFileCrypto` | `.ysm` 解密管线：XChaCha20 解密 → MT19937 白化 → 魔改 zstd 块头洗牌 → 标准 zstd 流式解压 |
| `ScriptJson` / `ScriptAnim` / `Molang` | YSM 脚本动画的解析与编译：Bedrock 动画 JSON → Molang 表达式编译 (`v./q./query./ctrl./math.` 查询/变量) → `YSMRuntimeModel` 加载时编译 |

### 几何转换 (`com.ysmef.compat.model`)

| 类 | 职责 |
|---|---|
| `YSMGeoModel` | Bedrock 几何解析——cube 8 顶点、6 面 (box UV / per-face UV)、镜像、膨胀、cube 枢轴旋转；骨骼层级、绑定变换链。`fromBinary` 处理预烘焙的二进制面数据 |
| `EFMeshJsonWriter` | 生成 EF animmodels JSON + 运行时 JSON。骨骼级部件 (`y/<boneName>`)、**预三角化** (每四边形扇形 6 角点: `(0,1,2)+(2,3,0)`) 匹配 EF 的 `glDrawArrays(TRIANGLES)` 与 CPU `getTriangulated` 约定；Blender 坐标系、顶点焊接、关节映射、宽高缩放 |
| `YSMJointMapper` | YSM 骨骼名 → EF biped 关节 ID 映射 (Root=0..Elbow_L=19)；运行时 armature 验证 |
| `YSMMesh` | EF `HumanoidMesh` 子类——贴图替换、运行时模型 ID、骨骼部件绑定变换供应商 (用于脚本注入)。`draw` 强制走计算着色器路径 (见下方"渲染路径"节) |
| `YSMMeshLibrary` | 网格/贴图注册中心 + 生成门禁。manifest 记录每个输出 (网格 JSON/运行时 JSON/贴图字节) 的 SHA-256 与大小；缓存恢复前逐文件校验，任何缺失/损坏 → 强制全量重建。所有文件写入均为原子写 (tmp + rename) |

### 运行时脚本系统 (`com.ysmef.compat.model.runtime`)

| 类 | 职责 |
|---|---|
| `YSMRuntimeModel` | 编译的骨骼表 + Molang 动画 (parallel/state/condition)，按 modelId 缓存，懒加载。支持**默认形态可见性**：静态求值 `pre_parallel`/`parallel` 的 scale 通道 (冻结 Molang 环境、t=0)，以层级传播 `effMinScale` 识别变体骨骼 (缩放→0) 并缓存；战斗模式仅套用此隐藏集 |
| `YSMPlayerAnimator` | 每玩家每帧的脚本求值：平行动画 (variant 可见性 + 副骨骼闲时动作) → 状态动画 (idle/walk/run...) → 条件覆盖 (hold/use/vehicle)。mapped bones 只留 scale 通道 (EF 拥有主姿态)；unmapped bones 完整变换；`effMinScale < 0.01` → 隐藏；identity delta → 跳过变换注入 |
| `YSMRuntimeBridge` | 渲染帧桥接：ThreadLocal 记录当前渲染的玩家 → `YSMMesh.draw` 时调用 `apply`：战斗模式 → `applyDefaultVisibility()` (变体隐藏 + 变换清空)；非战斗模式 → 完整脚本求值 |

### 渲染集成 (`com.ysmef.compat.renderer`)

| 类 | 职责 |
|---|---|
| `YSMPlayerRenderer` | 补丁玩家渲染器 (`PHumanoidRenderer<...>`)。替换 mesh + 条件盔甲层 + 手/箭/蜂/斗篷层；`prepareModel` 同步 visible/hidden 部件 |
| `YSMMeshSelector` | 网格选择——`YSMModelAccess` 读当前模型 → `YSMMeshLibrary` 查找网格/贴图 → 设置运行时模型 ID + 当前玩家 |
| `YSMModelAccess` | 模型选择解析——按顺序回退：集成服务器 NBT (单人/自建) → **模型同步通道** (见下方"多人联机同步"节，专用服务器) → 客户端 capability NBT (20 tick 缓存) |
| `YSMBattleMode` | 战斗模式判定——`PlayerPatch.isEpicFightMode()` |

### 战斗模式管控

| 机制 | 实现 |
|---|---|
| **主网格仅主模型** | `YSMRuntimeBridge.apply` 战斗模式分支 → `YSMRuntimeModel.applyDefaultVisibility()`：静态求值 parallel 动画 scale 通道 (冻结 Molang 环境)，骨骼层级传播 `effMinScale`，缩放→0 的变体骨骼隐藏 (武器/表情/附件)；清空运行时变换；不加载状态/条件动画、不做 Molang 求值 |
| **阻止 YSM 第三人称渲染** | `YSMRenderHook` (RenderLivingEvent.Pre HIGHEST)：EF 接管时取消事件 → YSM 的 RenderPlayerEvent.Pre 处理器 (NORMAL, 未 receiveCanceled) 收不到 → YSM CustomPlayerRenderer 不执行 |
| **阻止 YSM 第一人称手臂** | `YsmArmRenderMixin`：注入 YSM `ReplacePlayerHandRenderEvent.onRenderArm` (混淆名)，战斗模式 ← `ci.cancel()` → 跳过 YSM 手臂渲染 → 原版手臂渲染 |
| **阻止 YSM 背景手** | `YsmBackgroundHandMixin`：注入 YSM `RenderFirstPlayerBackground.onRenderHand` (混淆名)，同上 |
| **解除战斗模式武器拦截 (YSM)** | `YsmPlayerRenderMixin`：注入 YSM `ReplacePlayerRenderEvent.onRenderPlayerPre` (混淆名)，战斗模式 `ci.cancel()` → YSM 不再 cancel RenderPlayerEvent.Pre → 原版 PlayerRenderer 继续 → `YSMRenderHook` 以原版渲染器接管 → EF `PatchedItemInHandLayer` 正常绘制武器。原路径 (YSM 渲染器发起的嵌套 RenderLivingEvent.Pre) 中 renderer 是 YSM 渲染器、其 layers 无 PlayerItemInHandLayer，武器永远不绘制 |
| **解除战斗模式武器拦截 (OpenYSM)** | `OpenYsmPlayerRenderMixin`：目标为 OpenYSM (未混淆 fork，同 modId `yes_steve_model`) 的 `ReplacePlayerRenderEvent.onRenderPlayerPre` (字符串 targets，不依赖 OpenYSM jar 编译)，行为同上。OpenYSM 的 CustomPlayerRenderer 由 `GeoReplacedEntityRenderer.renderEntityWithTexture` **手动 post** `RenderLivingEvent.Pre`，但拦截点同为 RenderPlayerEvent.Pre 处理器，修复方式一致 |
| **阻止 YSM 弹射物/载具变体** | `YsmProjectileRenderMixin` (投射物)、`YsmFishingHookRenderMixin` (鱼钩) → 注入 YSM 自定义渲染器 (混淆名) 的 entry 方法，战斗模式 owner → `setReturnValue(true)` 跳过自定义渲染；`YsmVehicleRenderMixin` (载具)、`YsmVehiclePreviewMixin` (预览) → 按乘客判定战斗模式 |
| **阻止盔甲渲染** | `YsmConditionalArmorLayer`：通过 `addPatchedLayerAlways` 覆盖 EF 默认 `WearableItemLayer`，玩家使用 YSM 网格时 `renderLayer` 返回 → 盔甲模型 (不对齐 YSM 网格) 不绘制；非 YSM 玩家盔甲不受影响 |
| **阻止原版头/鞘翅模型** | `YsmConditionalHeadLayer` / `YsmConditionalElytraLayer`：同盔甲模式覆盖 EF 默认 `PatchedHeadLayer` / `PatchedElytraLayer`，YSM 网格玩家不绘制原版头 (带头饰时) / 鞘翅模型 |

### 渲染路径 (1.2.0 修复)

| 机制 | 细节 |
|---|---|
| **强制计算着色器路径** | EF 的 `SkinnedMesh.draw` 仅在客户端配置 `use_compute_shader=true` 时走 GPU 计算着色器路径，默认值 (false) 走 CPU 蒙皮路径——**CPU 路径渲染转换网格会丢三角面** (经双目录翻转配置实测复现/消除)。`YSMMesh.draw` 现在无视配置，存在计算着色器 setup 时直接调用 `drawWithShader` (反射读取私有字段 `computerShaderSetup`)，无 setup (老显卡) 才回退 CPU 路径并告警 |
| **副作用** | 计算着色器路径同样支持每帧隐藏标记与逐部件变换 (经 `VanillaComputeShaderSetup.drawWithShader` 反编译验证)，战斗模式/运行时动画均不受影响 |

### 性能优化 (1.4.0, 移植 OpenYSM 方案)

| 机制 | 细节 |
|---|---|
| **懒转换 (无开机模型加载)** | 去除 `ensureGeneratedBlocking` 开机全量阻塞转换 (27 模型 ≈ 3s)。模型在**首次渲染使用**时才转换：`YSMMeshLibrary.ensureModel` 先校验该模型的 manifest 指纹 + 输出哈希 (命中则免解密从缓存恢复)，未命中则提交后台池转换 (≤4 线程)，期间回退 EF biped，完成下一帧生效。生成包是 PathPackResources，运行时写入的网格 JSON 无需资源重载即可被 EF 按需加载器读到 |
| **逐模型缓存恢复** | manifest 逐模型增量写入；单模型输出 (网格/运行时 JSON/贴图缓存) 按 mhash/rhash/texhash 校验后才信任，坏缓存只重转该模型。`/ysm model reload` 与 F3+T 改为 `invalidateAll`：下一帧按需重新校验/转换，未变更模型零成本恢复 |
| **运行时模型免磁盘 stat** | `YSMRuntimeModel.get` 原每帧每玩家 `Files.getLastModifiedTime`；现仅查缓存，由转换完成/reload 路径显式 `invalidate(modelId)`/`invalidateAll()` |
| **逐部件变换数组化 (GPU 路径)** | `YSMMesh` 的 `runtimeTransforms` String 映射改为按部件序号的 `OpenMatrix4f[]`，EF 计算着色器每帧逐部件变换上传从 String 哈希查找变为 O(1) 数组访问 |
| **关键帧增量游标** | 每个编译通道分配 `channelId`，动画器持 `int[] channelCursor` (OpenYSM InterpolationLookup)：时间单调推进时从上一窗口继续扫描 (摊还 O(1))，循环回绕/动画重启自动复位 |
| **零分配矩阵合成** | `bindWorldInv` 模型加载期预计算；`composeBone` 复用持久 scratch 矩阵与 `composed[]`，逐帧不再分配 Matrix4f/数组；`importFromMojangMatrix` 语义以 16 字段直写复刻 (含转置映射)，`partMats[]` 复用同一 OpenMatrix4f |
| **距离降频 (LOD 刷新率)** | OpenYSM `getRefreshRate` 移植：本地玩家/40 格内每帧全量求值；40–64 格 30Hz；>64 格 10Hz。降频帧只把上次求值结果重放进网格 (跳过 Molang 求值/关键帧/查询刷新)，远处实体渲染成本大幅下降 |
| **部件映射缓存** | 动画器按网格实例缓存 part→骨骼 索引映射，push 循环不再逐帧做字符串截取 + HashMap 查找 |

### 贴图处理

| 机制 | 细节 |
|---|---|
| **PNG/JPEG 解码** | 使用 `NativeImage.read(ByteArrayInputStream)` (堆缓冲)，**避免 `NativeImage.read(byte[])`**——后者将整个字节数组 `malloc` 到 64KB LWJGL MemoryStack，大型贴图 (>64KB) 直接 OOM |
| **原始 RGBA** | Legacy 二进制贴图 → `setPixelRGBA` 逐行写入 (MC ABGR 像素打包) |
| **注册** | `TextureManager.register` 为 `ysm_epicfight_compat:textures/<model>/<tex>.png`，名称非法字符替换为 hash 后缀 |

### 事件与 Mixin

| 类 | 目标 |
|---|---|
| `PPlayerRendererMixin` | EF `PPlayerRenderer.getMeshProvider` HEAD：返回 YSM 网格替代默认 biped |
| `YSMRenderHook` | `RenderLivingEvent.Pre` HIGHEST：EF 接管时取消事件 + 调用 `renderEngine.renderEntityArmatureModel` |
| `YSMCompatClientEvents` | `PatchedRenderersEvent.Add` (LOWEST 注册)、`AddPackFindersEvent` (生成资源包 + **TLM 网格同门禁生成**，包仓库构建前完成)、`FMLClientSetupEvent` (兜底扫描)、`RegisterClientReloadListenersEvent` (F3+T 刷新) |
| `YSMReloadTrigger` | YSM 模型重载命令检测 + 断开世界时清空模型选择缓存 |

### 多人联机模型同步 (1.3.0)
| 机制 | 细节 |
|---|---|
| **通信协议** | 参照 OpenYSM 2.6.5 网络协议 (`参考/OpenYSM/.../network`)：独立通道 `ysm_epicfight_compat:model_sync`，登录时接受任意版本，握手用 S2C/C2S 版本检查包 (YSM id 51/52 模式) 在 netty Connection 上记录协商版本 (AttributeKey)，握手完成前不交换模型数据 |
| **模型广播包** | `S2CSetModelAndTexturePacket` (YSM id 4 模式)：`entityId` (VarInt) + `modelId` (UTF) + `textureId` (UTF) + `disabled` (bool)，末尾追加玩家 `uuid`——客户端以 UUID 为主键直接注册 (替代 YSM 的 entityId 实体加入回调，对重生/跨维度更稳) |
| **服务端广播时机** | 参照 OpenYSM `CapabilityEvent`：玩家入世界 → 发版本检查握手；`PlayerEvent.StartTracking` → 向追踪者推送被追踪者模型 (onStartTracking)；服务端每 20 tick 差异扫描 (entityId/modelId/textureId 变化 → 换模/换贴图/重生) → `sendToTrackingEntityAndSelf` 广播 |
| **服务端数据源** | `YsmCapabilityReader` 读 `ServerPlayer.saveWithoutId` 的 ForgeCaps NBT (`yes_steve_model:model_id` → `model_id`/`select_texture`/`disabled`)，与客户端 SP 路径同源，无 YSM 混淆类依赖；玩家无模型 (或 disabled) → 广播空 modelId，客户端清除该条目回退 EF biped |
| **客户端注册** | `ModelSyncClient` (common)：UUID → (modelId, textureId) 映射；`YSMModelAccess.getCurrentModel` 在集成服务器读取失败后回退到该映射；断开世界 (LoggingOut) 清空，F3+T 重载**不清空** (服务端只按变化广播，清空会导致远程玩家被钉在 biped) |

### YSM 混淆名映射 (2.6.5 release jar)

YSM release jar 的 932 个类被混淆；9 个 Mixin 安全类 (含 mixins.json 中引用的) 保留原名。本模组打了 7 个混入 YSM 类/方法的 Mixin，目标类与混淆方法名如下 (可通过扫描 jar 中类的方法描述符重新定位)：

| 实名 | 混淆类 (com.elfmcys.yesstevemodel.*) | 混淆方法名 |
|---|---|---|
| `ReplacePlayerHandRenderEvent#onRenderArm(RenderArmEvent)V` | `ooOOOoOO000oo0o00o00o000` | `Oo0Oo0o00O00Oo0OOoOOoooo(RenderArmEvent)V` |
| `RenderFirstPlayerBackground#onRenderHand(RenderHandEvent)V` | `O000O0O00ooo000O0oOOoo00` | 同上 (不同描述符) |
| `ReplacePlayerRenderEvent#onRenderPlayerPre(RenderPlayerEvent$Pre)V` | `O0oOOo00o0oooOo0OoO0OOo0` | `Oo0Oo0o00O00Oo0OOoOOoooo(RenderPlayerEvent$Pre)V` |
| `CustomProjectileRenderer#renderProjectile(Projectile,FF,...)Z` | `O0oOooooo00Ooooo0OoOOOO0` | 同上 |
| `CustomFishingHookRenderer#tryRenderCustomHook(FishingHook,FF,...)Z` | `oO0Ooooooo0O0OOOO00OoOo0` | 同上 |
| `CustomVehicleRenderer#renderVehicle(Entity,FF,...)Z` | `OOoO0O0OooOO0o00oOoOOoO0` | 同上 |
| `ModelPreviewRenderer#renderVehicleModel(Entity,L...;F)V` | `OoO00Oo00Ooo0OoOoo00o000` | 同上 |

所有混淆方法共享同一名称 `Oo0Oo0o00O00Oo0OOoOOoooo`，Mixin `@Inject` 以完整描述符区分。

### OpenYSM 兼容 (未混淆 fork)

OpenYSM (`参考/OpenYSM`，同 modId `yes_steve_model`、同包名，未混淆) 与 release jar 互斥。`OpenYsmPlayerRenderMixin` 以字符串 targets 引用 OpenYSM 类 (`com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent`)，编译期不依赖 OpenYSM jar；运行于 release YSM 时该 target 缺失 → Mixin 仅告警并跳过 (非 strict 配置不崩溃)，运行于 OpenYSM 时正常生效，其余 7 个混淆名 Mixin 同样仅告警跳过。

### 动态骨骼物理 (1.5.0)

| 机制 | 细节 |
|---|---|
| **动态骨骼识别（控制器探针）** | 不再依赖骨骼命名（YSM 命名混乱：中文模型的"眼尾"含"尾"、前臂"ForeArm"含"ear"）。`DynamicBoneProbe` 借用模型自带的**动画控制器 + parallel 脚本**：在两个合成 molang 环境（静止 vs 快速旋转/奔跑：`yaw_speed=600°/s`、头部偏航 60°、冲刺地速、下落速度、`position_delta`）中重放状态机（含 transitions/on_entry/on_exit）与平行动画，对每个骨骼的旋转/位移通道取两环境最大差值，超过阈值（或弱信号+运动变量链分析）即为动态骨骼。类别先取名称提示，未知命名按绑定几何启发（头下→发、躯干后方→尾、腰下→裙）。结果在转换时写入运行时 JSON 的 `dyn` 字段；探针异常/旧缓存回退面部安全版名称分类器 |
| **真实物理模拟** | 纯 Java Verlet 粒子链（参考 AnimationPhysics 的运动学锚定+链式约束+静止姿态弹簧架构，无原生库依赖）：重力 + 锚点加速度惯性伪力（快速移动/转身时头发裙摆自然摆动）+ 时间修正 Verlet 积分 + 刚性段距离约束 + 按类别刚度回拉（尾巴/耳朵回弹快、头发自由下垂）+ 暖启动（首帧对齐动画姿态，无爆燃） |
| **身体碰撞箱** | 从已映射骨骼自动构建胶囊碰撞体（Torso→Chest、Chest→Head、双臂、双腿、头骨球），每帧由 EF 实时姿态定位；粒子被推出碰撞体（绑定姿态已重叠的粒子/碰撞体对永久忽略，发根在头骨内不再抖动）；半径按关节 bind→render 伸缩系数换算。配置 `dynamicPhysics.collision` 可关 |
| **脚本姿态保留** | 物理每帧围绕"脚本动画求值后的静止姿态"摆动（compose→physics→recompose 三遍式），YSM 自带的耳朵抖动等脚本动作作为静止姿态保留，物理只叠加二次运动 |
| **眼睛飞走修复** | ① 面部/脚本载体骨骼（eye/lid/brow/mouth/眼/睫/眉/嘴/molang* 等）永不参与物理——旧版"眼尾"被误判为尾巴骨骼导致眼角部件被单摆甩飞又弹回；② `query.yaw_speed` 差分加 `wrapDegrees`——快速转头越过 ±180° 边界时不再产生数千度/秒的尖峰踢飞所有 yaw 驱动脚本通道 |
| **版本** | GENERATOR_VERSION 升至 5：旧缓存全部重转以获得探针数据 |

---



### YSM 源码与格式

| 目录 | 文件 | 用途 |
|---|---|---|
| `参考/OpenYSM` | `ServerModelManager.java`, `YSMFolderDeserializer.java`, `YSMBinaryDeserializer.java`, `YsmCrypt.java` | YSM 文件夹/二进制格式反序列化——几何布局、纹理表、动画/控制器跳过、加密/解压算法 |
| `参考/OpenYSM` | `geckolib3/geo/render/built/GeoBone.java`, `RenderUtils.java` | 骨骼绑定链：`T(pivot)·Rz·Ry·Rx·T(-pivot)`，枢轴/旋转坐标约定 |
| `参考/LgeacyYSM` | `geckolib3/geo/render/GeoBuilder.java`, `GeoCube.java`, `GeoQuad.java` | Cube 8 顶点构造、面 UV 分配、镜像/膨胀分支 |
| `参考/YSMParser` | `YSMParserV3.cpp`, `CryptoAlgorithms.cpp` | 加密/解密的**C++ 交叉验证参考**——XChaCha20、CityHash、MT19937、魔改 zstd 块头洗牌 |

### Epic Fight

| 文件/目录 | 用途 |
|---|---|
| `参考/epicfight` | EF API 参考 (NeoForge 版，部分接口与 Forge 1.20.1 有差异) |
| `20.14.17 runtime jar` | 反编译/javap 验证运行时 API——`PatchedLivingEntityRenderer`、`WearableItemLayer`、`EpicFightRenderTypes`、`PHumanoidRenderer`、`ClientConfig` |
| `assets/animmodels/entity/biped.json` | EF 网格 JSON 格式规范——12 标准部件、6 角点/四边形预三角化格式、`render_properties` 贴图路径 |
| `参考/EpicFight_TouhouLittleMaid` | 参考兼容范例——`MeshAccessor.create` 注册模式、`PHumanoidRenderer` 补丁渲染器模式 |
| `参考/AnimationPhysics` | 动态骨骼物理架构参考——运动学锚定头、逐段刚体、6DOF 约束、`JointSpring` 静止姿态力矩、暖启动姿态同步（本模组以纯 Java Verlet 复现其架构，无原生库） |
| `参考/EpicFight-Skin` | 物理与 EF 姿态回写集成参考——物理结果写回 armature pose 的时机与插值缓存 |

---

## 构建

```bash
# 本机网络证书校验会失败，需禁用后构建
./gradlew -Dnet.minecraftforge.gradle.check.certs=false jarJar reobfJarJar
```

注意：`jarJar` 打出的 all.jar 必须经过 `reobfJarJar`（强制重跑用 `--rerun-tasks`），否则运行时方法名未映射会直接崩溃 (`NoSuchMethodError`/`AbstractMethodError`)。

产物：`build/libs/YSM_EpicFight_Compat-1.20.1-1.4.0-all.jar` (内嵌 `zstd-jni 1.5.6-3`，jar-in-jar)

依赖：
- Epic Fight 20.14.17+ (`maven.modrinth`)
- Yes Steve Model 2.6.5 (`libs/ysm-2.6.5.jar`, 本地 flatDir)
- Mixin 0.8.5, MixinExtras (YSM 依赖传递)
- JOML 1.10.5, Gson 2.10.1
- zstd-jni 1.5.6-3 (jarJar 嵌套)

---

## 已知限制

1. **大型贴图 OOM**: `< 1.0.0` 版使用 `NativeImage.read(byte[])` 将贴图字节压上 64KB LWJGL MemoryStack，>64KB 的 PNG 直接溢出 (手动 trace 定位至 `NativeImage.java:116` 的 `memorystack.malloc(data.length)`)；**已在 1.0.0 修复**——切换至 `NativeImage.read(InputStream)` (堆分配)
2. **CPU 蒙皮路径丢面 (1.2.0 绕过)**: EF 默认 `use_compute_shader=false` 时走 CPU 蒙皮路径，转换网格会丢三角面。1.2.0 起 `YSMMesh.draw` 强制计算着色器路径规避；无计算着色器支持的 GPU 仍走 CPU 路径 (可能缺面，已告警)
3. **战斗模式默认可见性**: 使用冻结默认环境 (变量未设、查询=0) 静态求值 parallel 动画的 scale 通道来决定变体骨骼可见性。实际变种可能因条件 Molang (`v.xxx`, `q.xxx`) 在某些模型上默认可见 (应为隐藏)，在运行时会被覆盖，但静态求值不可见；此为并行/变体分级设计，恰符合 YSM 的首帧行为
4. **多人联机模型同步依赖**: 远程玩家/本地玩家的模型选择通过 `ysm_epicfight_compat:model_sync` 通道同步，要求**专用服务器也安装本模组** (服务端仅做 NBT 读取与广播，无渲染开销)。服务端未安装时回退 EF 默认 biped (与旧版行为一致)
5. **远程玩家模型需本地可用**: 被渲染玩家的 YSM 模型包必须在本地存在 (`config/yes_steve_model/{builtin,custom,auth}`)。auth 模型由 YSM 自动下载到本地；会话中途新下载的模型需 F3+T 或 `/ysm model reload` 触发网格重新生成后才会显示
6. **YSM 混淆类**: 7 个 Mixin 依赖 YSM 2.6.5 的混淆名；升级 YSM 版本需通过描述符扫描重新定位目标类 (注释中已写方法)。OpenYSM (未混淆 fork) 由 `OpenYsmPlayerRenderMixin` (字符串 targets) 覆盖同一渲染拦截点；其余混淆名 Mixin 在 OpenYSM 下仅告警跳过
7. **非 PNG/JPEG 贴图**: 其他格式 (WebP/AVIF/BMP) 不支持解码，跳过并告警
8. **缓存健壮性 (1.1.0 起, 1.4.0 改逐模型)**: manifest 记录输出哈希，缓存恢复前对**该模型**的输出逐文件校验；被半生成/复制损坏的缓存只强制重转该模型而非永久信任；所有输出文件原子写入，资源重载不会读到半截 JSON
9. **懒转换首用延迟**: 模型首次被渲染时若缓存未命中，转换在后台进行，玩家会短暂回退 EF biped (约几帧) 后切换为 YSM 模型；本地首次换上新模型同理。转换本身 <0.5s/模型，仅一次
10. **动态物理参数为通用值**: 碰撞体半径/刚度按类别取通用常量（头发 0.05、裙子 0.07 等），不读取模型作者可能的自定义物理参数；探针只识别"对运动有响应"的骨骼，纯关键帧循环但名称不含任何提示的部件不会被识别为动态骨骼 (可在配置中关闭碰撞或整体物理)
