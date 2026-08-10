# YSM Epic Fight Compat

**兼容 Yes Steve Model (YSM) 与 Epic Fight 20.14.17 渲染管线的 Forge 1.20.1 模组**

在史诗战斗模式下，将玩家当前选用的 YSM 自定义角色模型转换为史诗战斗的骨骼网格格式，使 YSM 模型以完整轮廓呈现，并由史诗战斗的蒙皮骨骼驱动攻击、行走等全部战斗动画。非战斗模式保持 YSM 自身渲染与动画系统不变。

同时支持 YSM 三种发行形态：**官方 2.6.5（部分未混淆）**、**OpenYSM（未混淆 fork）**、**ModernYSM（现代开源分支）**，以及完全混淆的构建变体。

---

## 功能特性

| 特性 | 说明 |
|---|---|
| **运行时网格转换** | YSM Bedrock 几何（目录包 / 加密 .ysm 二进制包）→ EF SkinnedMesh JSON + 运行时脚本 JSON，懒转换（后台池），manifest 指纹 + SHA-256 校验缓存 |
| **GPU 蒙皮渲染** | ModernYSM 风格骨骼蒙皮路径（骨 SSBO + 皮肤着色器，单次 draw call），自动回退 EF 计算着色器路径 / CPU 路径 |
| **GPU 开关联动** | ModernYSM 加载时链接其 `UseGpuRenderer`/`UseCompatibilityRenderer` 同步开关；OpenYSM/LegacyYSM 使用本模组配置，并可在 YSM 模型选择界面勾选 |
| **Molang 运行时** | 每玩家脚本求值：平行（变体可见性）、状态（idle/walk/...）、条件（hold/use/vehicle）动画，LOD 距离降频，异步求值 |
| **懒加载与 LRU** | 模型按需转换、验证缓存恢复、LRU 淘汰（GPU 缓冲/纹理/脚本整体释放）、世代废弃任务 |
| **多人联机同步** | 独立通道同步玩家模型选择（专用服务器安装本模组） |
| **TLM 女仆兼容** | 女仆 YSM 模型渲染挂钩（EpicFight_TouhouLittleMaid 可选）。TLM 自带 GEO 模型包由 EFTLM 模组自行处理 |

---

## 架构概览

```
本地 YSM 模型文件 (config/yes_steve_model/{builtin,built,custom,auth})
    │
    ├── 目录包 (ysm.json + models/main.json)   → YSMGeoModel.parse (Bedrock 几何)
    └── 二进制包 (*.ysm)                        → YsmFileCrypto (XChaCha20+MT19937+zstd)
                                                     │
                                                     ▼
                                           YsmBinaryReader → YSMGeoModel.fromBinary
                                                     │
                                                     ▼
                                           EFMeshJsonWriter.write
                                           ├── 主网格 JSON (animmodels/entity/<id>.json)
                                           │   · Blender 坐标系、预三角化角点流
                                           │   · 骨骼级部件 ("y/<boneName>")
                                           │   · 顶点焊接、关节映射、宽高缩放
                                           └── 运行时 JSON (ysm_runtime/entity/<id>.json)
                                               · 骨骼表 (层级、绑定矩阵、关节绑定)
                                               · Molang 动画 (parallel/state/condition)
                                                     │
                                                     ▼
                                           YSMMeshLibrary（懒转换 + 缓存恢复 + LRU）
                                                     │
                                                     ▼
                                           渲染：YsmGpuRenderPath（GPU 蒙皮）
                                             → EF 计算着色器路径（回退）
                                             → CPU 路径（最后回退）
```

---

## 模块说明

### 模型解析 (`com.ysmef.compat.ysm`)

| 类 | 职责 |
|---|---|
| `YsmModelPackage` | 统一入口——按 modelId 加载目录包或二进制包，返回几何 + 贴图 + 属性 + 脚本动画 (`ScriptAnim`) |
| `YsmBinaryReader` | 二进制格式反序列化：`format` 版本链 (legacy V1/V15、modern 16+)，几何段、贴图表、动画；字节序 LE，VarInt LEB128 |
| `YsmFileCrypto` | `.ysm` 解密管线：XChaCha20 解密 → MT19937 白化 → 魔改 zstd 块头洗牌 → 标准 zstd 解压 |
| `ScriptJson` / `ScriptAnim` / `Molang` | 脚本动画解析与编译；Molang 求值器（查询/变量整数 ID 内联、零分配函数调用、常量折叠，见性能节） |

### 几何转换 (`com.ysmef.compat.model`)

| 类 | 职责 |
|---|---|
| `YSMGeoModel` | Bedrock 几何解析——cube 8 顶点、6 面（box UV / per-face UV）、镜像、膨胀、cube 枢轴旋转；骨骼层级、绑定变换链 |
| `EFMeshJsonWriter` | 生成 EF animmodels JSON + 运行时 JSON。骨骼级部件 (`y/<boneName>`)、预三角化（每四边形 6 角点）匹配 EF 的三角绘制约定；顶点焊接、关节映射、宽高缩放 |
| `YSMJointMapper` | YSM 骨骼名 → EF biped 关节 ID 映射 (Root=0..Elbow_L=19) |
| `YSMMesh` | EF `HumanoidMesh` 子类——贴图替换、运行时模型 ID、按部件序号的运行时变换注入（O(1) 数组访问） |
| `YSMMeshLibrary` | 网格/贴图注册中心 + 懒转换门禁 + **LRU 淘汰**（见性能节）。manifest 记录输出 SHA-256 与大小，缓存恢复前逐文件校验；原子写 |
| `YsmMaidMeshSupport` | 女仆 YSM 模型网格选择桥（`EntityMaid.isYsmModel()` → 转换后的 YSM 网格；仅 EFTLM 安装时生效） |

### 运行时脚本系统 (`com.ysmef.compat.model.runtime`)

| 类 | 职责 |
|---|---|
| `YSMRuntimeModel` | 编译骨骼表 + Molang 动画，按 modelId 缓存；**默认形态可见性**（静态求值 parallel scale 通道，层级传播 `effMinScale`，战斗模式套用）；**后台预编译**（首次绘制不再渲染线程编译）；**逐玩家动画器清扫**（15s 周期，60s 未用回收） |
| `YSMPlayerAnimator` | 每玩家脚本求值：平行 → 状态 → 条件覆盖；mapped bones 只留 scale 通道，unmapped 完整变换；`effMinScale < 0.01` 隐藏；identity delta 跳过注入；**异步求值**（双缓冲，非本地玩家后台线程） |
| `YSMRuntimeBridge` | 渲染帧桥接：战斗模式 → `applyDefaultVisibility()`；非战斗模式 → 完整脚本求值 |

### GPU 渲染 (`com.ysmef.compat.gpu`)

| 类 | 职责 |
|---|---|
| `YsmGpuRenderPath` | 直接 GPU 蒙皮路径：每帧 CPU 只合成关节矩阵（`poses×toOrigin`，OM 数学），部件段（bind 增量 + 隐藏标志）战斗模式下**静态缓存只上传一次**；一次 `glDrawArrays` 绘制；`u_proj = proj×mv×pose` 与 EF 计算路径数值等价（模拟验证逐位一致）；Iris/Oculus 光影包激活时自动回退 EF 路径 |
| `YsmGpuMesh` | 静态 VBO（32B/顶点：pos+uv+2_10_10_10 法线+boneId+partId）+ 动态骨 SSBO（144B/条）+ 部件段缓存 |
| `YsmBoneSkinShader` | 皮肤着色器（桌面 `bone_skin.vsh/fsh` GL 4.3 / Android `bone_skin_es.vsh/fsh` GLES 310 自动选择）：`boneMat = joint×part` 与 EF 计算着色器逐项一致；复刻 MC 光照/雾/overlay/光照贴图语义；半透明纹理双 Pass |
| `YsmGpuCapability` | GL 能力探测（桌面 SSBO/420pack/显式属性位置/2_10_10_10，**Android OpenGL ES 3.1**），失败自动回退 |
| `YsmGpuRenderEnable` | **YSM 分支检测 + GPU 开关联动**（见下节） |

### YSM 分支兼容（`YsmGpuRenderEnable` + mixin 族）

| 分支 | 检测依据 | GPU 开关 | 渲染抑制 mixin |
|---|---|---|---|
| **ModernYSM** | 存在 `rip.ysm.gpu.*` | **联动 ModernYSM** `UseGpuRenderer`/`UseCompatibilityRenderer`（反射实时读取，含其运行时自动禁用） | `ModernYsm*Mixin`（新签名：`onRenderPlayerPre(Player,...)Z` 等，返回 false） |
| **OpenYSM** | 存在未混淆 `client.event.ReplacePlayerRenderEvent`（无 `rip.ysm.gpu.*`） | 本模组 `enableGpuRender` 配置 + **模型选择界面勾选框**（追加到配置界面 performance 分组） | `OpenYsm*Mixin`（旧事件签名：`onRenderPlayerPre(RenderPlayerEvent$Pre)V` 等） |
| **官方 2.6.5** | 同上（未混淆 GUI/事件类） | 同上 | 同上 |
| **完全混淆构建** | 存在混淆类（`O0o...` 等） | 同上 | `Ysm*Mixin`（混淆目标，软跳过） |

- 所有抑制 mixin 均为字符串目标 + `require=0` 软注入：只对签名实际存在的分支生效，任何分支下都不会崩溃；官方/OpenYSM 与 ModernYSM 的共享类（`CustomProjectileRenderer` 等，签名一致）由 `YsmUnobf*Mixin` 一并覆盖。
- 抑制内容：第三人称玩家渲染、第一人称手臂、背景手、投射物、鱼钩、载具、载具预览——战斗模式下全部让位给原版/EF 渲染。
- ModernYSM 场景下配置界面不重复添加复选框（其自带 `UseGpuRenderer` 勾选项）。

### 渲染集成 (`com.ysmef.compat.renderer`)

| 类 | 职责 |
|---|---|
| `YSMPlayerRenderer` | 补丁玩家渲染器 (`PHumanoidRenderer`)，LOWEST 优先级注册；条件盔甲/头/鞘翅层 |
| `YSMMeshSelector` | 网格选择——`YSMModelAccess` 读当前模型 → `YSMMeshLibrary` 查找 → 设置运行时模型 ID + 当前玩家 |
| `YSMModelAccess` | 模型选择解析：集成服务器 NBT → 模型同步通道 → 客户端 capability NBT（20 tick 缓存） |
| `YSMBattleMode` | 战斗模式判定——`PlayerPatch.isEpicFightMode()` |
| `YSMRenderHook` | `RenderLivingEvent.Pre` HIGHEST：EF 接管时取消事件 + 用原版渲染器（含 `PatchedItemInHandLayer`）绘制臂架模型，恢复武器渲染 |

### 事件与 Mixin

主配置 `ysm_epicfight_compat.mixins.json`（21 个客户端 mixin）：`ModernYsm*`（3）、`OpenYsm*`（4，含配置界面）、`YsmUnobf*`（4）、混淆版 `Ysm*`（8）、`PPlayerRendererMixin`、`RenderSystemAccessorMixin`（着色器光照方向）；可选配置 `ysm_epicfight_compat.eftlm.mixins.json`（TLM 女仆渲染挂钩）。

### 多人联机模型同步 (`com.ysmef.compat.network`)

| 机制 | 细节 |
|---|---|
| **通信协议** | 独立通道 `ysm_epicfight_compat:model_sync`；握手版本检查（YSM id 51/52 模式），握手完成前不交换模型数据 |
| **模型广播包** | `S2CSetModelAndTexturePacket`（YSM id 4 模式）：entityId + modelId + textureId + disabled + UUID（客户端以 UUID 为主键注册，对重生/跨维度更稳） |
| **服务端广播时机** | 入世界握手 → `PlayerEvent.StartTracking` 推送 → 每 20 tick 差异扫描广播 |
| **服务端数据源** | `YsmCapabilityReader` 读 `ServerPlayer` ForgeCaps NBT（`yes_steve_model:model_id`），无 YSM 类依赖 |

---

## 性能优化（移植 OpenYSM / ModernYSM 方案）

| 机制 | 细节 |
|---|---|
| **懒转换（无开机加载）** | 模型首次渲染时才转换（后台池，≤4 线程），期间回退 EF biped；命中验证缓存则免解密直接恢复 |
| **LRU 模型缓存** | 超过 `lazyModelCacheSize`（默认 64）时淘汰最久未用模型：释放 GPU 缓冲、纹理（延迟 5 tick 释放，防同帧引用闪烁）、编译脚本与逐玩家动画器；下次使用从验证缓存瞬时恢复 |
| **并发转换信号量** | `Semaphore(2)` 限制同时转换的模型数（每个转换持有解密包 + 几何数组，大模型数百 MB），控制峰值内存 |
| **逐实体动画器清扫** | 每 15s 清除 60s 未使用的逐玩家动画器（大模型每个 ~300-400KB），玩家离开后不再残留 |
| **运行时模型后台预编译** | 网格转换/缓存恢复后立即在后台编译 Molang 脚本（大模型 ~100ms 不再卡首帧）；渲染线程遇在途预编译先回退显示 |
| **异步脚本求值** | 非本地玩家的 Molang 求值在后台单线程池（双缓冲发布），渲染线程只做网格推送；LOD 距离降频（40/64 格 → 30/10Hz） |
| **Molang 求值优化** | 查询/变量路径编译期内联为整数 ID（`double[]` 槽位替代 HashMap）；函数调用参数零分配（ThreadLocal 复用）；变量引用编译期预分类；纯数字表达式常量折叠 |
| **GPU 路径** | 静态几何一次上传 + 每帧仅关节矩阵（战斗模式 ~3KB 而非 ~114KB）+ 单次 draw call；与 EF 计算路径数值等价（模拟验证） |
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

---

## 构建

```bash
./gradlew build
```

- 产物：`build/libs/YSM_EpicFight_Compat-1.20.1-1.5.0-all.jar`（内嵌 `zstd-jni 1.5.6-3`，jar-in-jar）
- 本机网络证书校验失败时可加 `-Dnet.minecraftforge.gradle.check.certs=false`
- 依赖：Forge 1.20.1-47.4.16+、Epic Fight 20.14.17+（Modrinth）、YSM 2.6+（`libs/ysm-2.6.5.jar` 本地 flatDir）、zstd-jni（jarJar）；可选 TLM 1.5+ / ef_tlm 1.1+
- 参考源码：`参考/` 目录下 `OpenYSM`（格式/网络协议）、`ModernYSM`（GPU 渲染/懒加载/内存优化）、`LgeacyYSM`（GeckoBuilder 约定）、`YSMParser`（C++ 加密交叉验证）、`EpicFight_TouhouLittleMaid`（补丁渲染器范例）

---

## 已知限制

1. **GPU 路径要求 GL 4.3（或等价 ARB 扩展），Android 要求 OpenGL ES 3.1**：满足时使用 GPU 蒙皮路径（Android 上自动选择 GLES `#version 310 es` 着色器变体）；否则自动回退 EF 计算着色器路径；计算着色器不可用时回退 CPU 路径（CPU 蒙皮转换网格可能缺面，已告警）。macOS（GL 4.1）与旧 GPU 直接走回退链
2. **Iris/Oculus 光影包**：光影包激活时 GPU 路径让位 EF 计算路径（其内建 Iris 支持），避免绕过光影着色器
3. **懒转换首用延迟**：模型首次渲染若缓存未命中，后台转换期间短暂回退 EF biped（几帧）；异步纹理上传同理（纹理出现前 1-2 帧为缺失纹理）
4. **多人联机同步要求专用服务器安装本模组**（服务端仅做 NBT 读取与广播）；未安装时回退 EF biped
5. **远程玩家模型需本地可用**：模型包必须在 `config/yes_steve_model/{builtin,custom,auth}`；会话中途新下载的模型需 F3+T 或 `/ysm model reload` 触发重新生成
6. **混淆目标依赖版本**：混淆构建变体的 mixin 目标为 YSM 2.6.5 特定名（官方/OpenYSM/ModernYSM 由未混淆/新签名 mixin 覆盖，无需维护）；升级 YSM 需按描述符重新定位
7. **非 PNG/JPEG 贴图**（WebP/AVIF/BMP）不支持解码，跳过并告警
8. **战斗模式默认可见性**：以冻结默认环境静态求值 parallel scale 通道决定变体可见性，个别条件化变体可能首帧可见后被运行时覆盖
9. **缓存健壮性**：manifest 记录输出哈希，缓存恢复前逐文件校验；损坏只重转该模型；所有输出原子写
