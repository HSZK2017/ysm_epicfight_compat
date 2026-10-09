# 更新日志 / Changelog

## 未发布 / Unreleased

- 拆开头部接触判定与物理部件构建的双向依赖，共用独立的几何测量工具；移除从未接入渲染的粒子布料求解器及四个无效配置项。
- Removed the dependency cycle between head-contact classification and physics-part construction. Deleted the unused particle-cloth solver and its four inactive settings.

## v1.10.0 — 2026-10-09

### 中文摘要

- **战斗渲染**：阎魔刀瞬身等残影快照沿用创建时的模型部件可见状态，不再把所有条件形态一起显示。
- **二次运动**：连续尾巴按根部的身体关节绑定，避免尾尖与尾根在大幅动作中分离；裙片按几何与骨架关系改进联动，EF 奔跑时更接近整体。
- **几何约束**：几何检查剔除不可靠的物理段后，子段继续寻找最近的有效上层骨段；与头部有充分双侧几何接触的发片保持固定，不再依赖 `BaseHair` 命名。针对 906 个 `.ysm` 文件做了离线扫描，其中 736 个可解析；实际游戏观感仍需按模型抽样检查。
- **启动体验**：官方 YSM 2.6.5、OpenYSM、ModernYSM 的 Epic Fight 旧版不兼容警告在本模组加载时被精确拦截；其他加载警告保留。
- **已知限制**：EF 的前倾和大步幅动作仍可能使裙摆与腿局部相交；自动几何约束无法覆盖所有模型，可使用模型专属 `physics_overrides`。

### English summary

- **Combat rendering**: afterimages from Yamato dodge effects and similar snapshots now retain the model part visibility evaluated when the snapshot is created, instead of showing every conditional form.
- **Secondary motion**: articulated tails stay bound to the body's joint at their root; skirt panels are coupled using geometry and skeleton relationships so they behave more like one garment during Epic Fight sprinting.
- **Geometric constraints**: when an unreliable physics segment is excluded, its children find the nearest surviving ancestor. Hair geometry with substantial contact across both sides of the head stays rigid without relying on names such as `BaseHair`. An offline scan covered 906 `.ysm` files; 736 were parseable. In-game appearance still needs per-model checks.
- **Startup**: the obsolete YSM/Epic Fight incompatibility warning is suppressed when this bridge is installed, for official YSM 2.6.5, OpenYSM, and ModernYSM. Other loading warnings remain visible.
- **Known limit**: large forward lean and leg strides can still cause local skirt clipping. Per-model `physics_overrides` remain available where automatic geometry rules are insufficient.

The entries below preserve the detailed development history, including observations later corrected by subsequent work.

### 中文

#### 已知限制记入文档：EF 站姿与模型裙摆余量的冲突（按限制结案，不修）

Epic Fight 的 idle/移动姿态是"迈步站姿"，而部分 YSM 模型按"双腿并拢直立"建模、裙摆与腿仅约 0.10 格余量（例：`wine_fox/01_taisho_maid`），于是 EF 接管动画后腿会从裙摆穿出，站立不动即可见。两条物理途径均被测量否决：腿部碰撞胶囊放大 2.0× 触及 **0/36** 片布；布片重绑到最近的腿在 EF walk/run/fall/jump 与合成步态下、任何混合比例（0.2–1.0）都不增加间隙。结论：模型余量与 EF 姿态幅度的固有冲突，当前骨段物理无法保证完全消除穿模；模型几何或 EF 站姿需要相应调整。已记入 README「常见问题与限制」。测量：`build/reports/ysm-ef-skirt-round.md`、`build/reports/ysm-rebind-round.md`

#### 第二十六轮：四个系统开关统一取值语义（`=false` 曾是"开"）

这四个开关此前都在调用点用 `System.getProperty(名) != null` 判断——**属性的"存在"就是信号**，于是启动参数里写 `-Dysm_ef_compat.disable_gpu=false`（想撤销）反而保持禁用，`-D…force_cpu_render=false` 反而强制 CPU 路径。写 `=false` 去撤销一个开关是人第一件会试的事，而它做的事恰好相反；四处各有一份拷贝，四处同坑。

改法：新增 `com.ysmef.compat.SystemFlags.enabled(名)` 作为**单一实现**——`-D名`/`-D名=true` 为开，**`=false` 为关**，`0`/`no`/`off`（不分大小写、忽略首尾空格）同样读作关，缺省为关；四个调用点（`diag`、`disable_gpu`、`force_cpu_render`、`disable_iris_compute_path`）全部改用它，源码里已无 `getProperty(...) != null` 形式的读取。属性名与原有语义方向不变，所以既有启动参数仍然有效（`-D…disable_gpu` 依旧表示禁用）。

**验证**：新增 `SystemFlagsTest`（5 条，纯 Java 无需 Minecraft），其中 `explicitFalseIsOff` 钉的正是这个坑——**它在旧的 `!= null` 规则下必然失败**，在新规则下通过（本次为"由构造保证"的对照，未再跑一次旧实现来验证）。`gradlew build` → **43 suites / 352 tests / 0 failures**（347 + 5）。README 的调试属性表已补上统一的取值语义说明。

#### 第二十五轮：优化 Iris 路径改为默认开启（多模型实机验证后）

第二十四轮修复了该路径的物理缺陷但保持 opt-in，理由是"验证面太窄"。本轮按用户实测扩到 **9 个模型**，日志核对后翻转默认值。

**日志核对（光影开启 + `-Dysm_ef_compat.enable_iris_compute_path=true`）**
- 9 个模型全部走到本模组 Iris 路径，且**每个都 `mesh.getPartCount() == iris partCount`**：`wine_fox/01_taisho_maid` 143/17,640、`17_mini.ysm` 135/19,680、`Wither2.3.ysm` 412/41,802、`minecraft_warden2.0.ysm` 204/32,826、一个 205/89,424、`…` 328/75,564、`…` 597/25,776、一个 364/**103,998**、一个 188/40,284、一个 51/11,412。
- 物理：162 次 `[physics] frame` 采样，`N of N bone(s) moving`。
- **零 ERROR、零 `[iris-diag]` 异常、零 `Iris path draw failed`**。
- 唯一的 WARN 是两类**既有且预期**的：①每个模型首次使用时的懒转换首帧回退（`no converted base mesh … Falling back`，README 已记载）；②**模型脚本自身**的 Molang 语法错误（如 `'['`、`']'`、缺右括号的 `((…)/v.time;`），解析器照常告警并降级为 0——这不是回归，Molang 只在 P0-1 改过参数暂存，与解析无关。
- 两个**看似可疑但与本模组无关**的日志已逐条排除：`GL_INVALID_ENUM (id=1280)` 的上下文是 `AAAParticles`（Effekseer）初始化，前后 40 行内无任何 `com.ysmef` 帧；`UnsupportedOperationException: Reflective setAccessible(true) disabled` 来自 netty 的 Android 探测（`PlatformDependent.isAndroid` ← Forge `NetworkConstants.<clinit>`），是 JDK17+ 下的已知噪音。

**改动**：`YsmIrisComputePath.ENABLED` 由"opt-in"改为"默认开启，`-Dysm_ef_compat.disable_iris_compute_path=true` 关闭"。`enable_iris_compute_path` 开关**移除**——它现在只会是一个不生效的参数，README 已把这一点写明（留着不报错，但没有任何效果）。

**该默认值所依赖的证据边界（与代码注释里写的一致）**：一台机器、一种光影；**"超 MAX_JOINTS(1000) 容量"这一能力未被触达**（本次最大 597 部件 + 约 20 关节，未越界），描边/GUI 通道亦未行使。若某个光影包或显卡与该路径不合，用 disable 开关退回 EF 自带 Iris 路径即可。

**验收**：`gradlew build` → 42 suites / 347 tests / 0 failures。

#### 第二十四轮：优化 Iris 路径的物理缺陷定位并修复（实机验证通过）

第二十三轮把该路径改回 opt-in 止血，并留下两个假设（"没有 part 上传" / "part 序号错位"）。本轮先用**一次测量**把两者都排除，再找到真因。

**测量**（第二十二轮新加的 `[iris-diag]`，一次运行出结果）
```
optimized Iris compute path active: model='wine_fox/01_taisho_maid', 143 parts, 17640 vertices
[iris-diag] mesh.getPartCount()=143, iris partCount=143, non-identity deltas=59, vertices=17640
```
`143=143` → 序号空间一致（另用实例里的真实网格 JSON 静态核对：143 个部件键，131 个 `y/<骨骼>`）；`deltas=59` → 物理结果**确实**已发布到 mesh（59 = 模拟骨骼数）。连同上传偏移（`jointCount*64`）、`part_offset`（`= poses.length`）、SSBO 绑定（`poseSsbo` → binding 0）与 EF 着色器的读取位置（`iris_mesh_transformer.comp:122` 读 `poses[part_offset + elem.part_idx]`）**全部对得上**。

**真因：重传门控缺一项。** GPU 路径的重传条件是 `!partSectionValid || **anyTransform** || hiddenChanged || identityChanged`；Iris 路径只有 `!partSectionValid || lastJointCount != jointCount`——它的两个标记只能察觉"增量出现/消失"（null ↔ 非 null），**察觉不到每帧变化的数值**。于是 part 段只在增量第一次出现那一帧上传过一次（此刻位移接近 identity），此后永不重传：求解器照常运算，画面里的部件被冻结。改法：照 GPU 路径补上 `anyTransform`，上传条件改为 `dirty || anyTransform`（物理不动的模型仍不上传，门控收益保留）。

**实机验证（2026-09-20，光影开启 + `-Dysm_ef_compat.enable_iris_compute_path=true`）**：部署件与 `build/libs` 产物逐字节一致；`optimized Iris compute path active` 与 `[iris-diag] … deltas=59` 同时出现；`[physics] frame` 持续 `59 of 59 bone(s) moving, collision active`；**二次运动可见（用户确认）**；零 ERROR、零异常。

**当前状态**：缺陷已修复并验证，但该路径**仍保持 opt-in**（默认关闭）——验证面只有一台机器、一个模型（`wine_fox/01_taisho_maid`）、一种光影，而该路径的其余能力（超 MAX_JOINTS 容量模型、描边/GUI 通道、开销曲线）仍未行使，且这条路径已经产生过一次回归。改默认值应是一个单独的决定。

**教训延续**：第二十三轮的错误（"该类完全不上传 part 段"）源于单文件 grep 的缺席证据；本轮改用"正向测量 + 与可用实现的逐项差异"定位，一次即中。两者都留在注释里。

#### 第二十三轮：P1-4 引入的回归 —— 优化 Iris 路径默认关闭（我把它接上线，它从没画过一帧）

**这是我上一轮决定的后果，先说清楚责任**：P1-4 把 `YsmIrisComputePath` 从不达变可达。该路径此前**从未执行过一次**（没有任何外部引用），而我在接线时**没有**把它默认设成关闭。第一次真正启用（光影开启、去掉 A/B 开关）就暴露了它是**不完整的**：模型失去二次运动，而求解器一直在算。

**证据（同一份日志，同一段帧区间）**
```
[physics] frame 240:  dt=12ms max swing=43.2deg max displacement=0.084 blocks, 59 of 59 bone(s) moving, collision active
[physics] frame 720:  dt=2ms  max swing=56.8deg max displacement=0.124 blocks, 59 of 59 bone(s) moving, collision active
[physics] frame 1200: dt=2ms  max swing=60.0deg max displacement=0.206 blocks, 59 of 59 bone(s) moving, collision active
optimized Iris compute path active: model='wine_fox/01_taisho_maid', 143 parts, 17640 vertices
```
即**物理在算、渲染没用上**。不是求解器的问题。

**根因**：物理结果存放在 per-part 运行时增量（`YSMMesh#getPartTransform`），而 `YsmIrisComputePath` **完全不上传 part 段**——文件里与"part"有关的只有 `part_offset` 这个 uniform 名、以及激活日志里打印的部件数；它的上传调用只有 `uploadMat4/uploadMat3`（uniform）与 `BufferUploader.invalidate()`。更糟的是该类自己的 javadoc 第 50 行写着 "re-uploads the part section only when it changes"，**这段代码不存在**。骨骼矩阵每帧上传，所以身体动画正常；增量从不上传，所以位移到不了着色器。对照实现是正确的：`YsmGpuRenderPath` 有完整的 part 缓存与 `anyTransform`/`hiddenChanged`/`identityChanged` 三路门控（`:820-862`），那是修复的参照。

> **⚠️ 上述"根因"已被推翻（同日撤回，保留原文以免读者以为从未有过这个判断）。** 我只 grep 了 `YsmIrisComputePath` 一个文件就下了结论，实际实现位于 `YsmIrisMesh.fillPoses`（`:261-287`）：每帧从 `mesh.getPartTransform(p)` 刷新 `partStaging`、带 identity 变更门控、`glBufferSubData` 写到 `jointCount * MAT4_BYTES`，javadoc 也已写明。而且该调用**确实被调用**（`YsmIrisComputePath:421`），`part_offset`（`u[3]`）被设为 `poses.length`（`:512`），**与写入偏移一致**。所以"没有 part 上传"与"偏移不匹配"两条都不成立。真实原因**尚未查明**；剩余疑点按优先顺序是：(1) 该路径 element 缓冲里的 part 序号是否与 `YSMMesh#getPartTransform` 期望的序号一致；(2) 该路径绘制的那几帧里，物理结果是否已经写进 mesh。默认关闭的处置不变（物理已恢复），但在查明并实机验证之前不再改动这条路径——**教训：单文件 grep 不能当作"代码不存在"的证据，尤其是交叉引用的两个类。**

**本轮处置（立即 fail-closed）**：把该路径改为**默认关闭、显式 opt-in**——`-Dysm_ef_compat.enable_iris_compute_path=true` 才启用，`disable_iris_compute_path` 仍然有效且优先。光影下回到 EF 自带 Iris 路径绘制（它带增量，所以物理可见）。加上上一轮新加的 skip-reason 日志，本次运行会明确打印"optimized Iris compute path is not used (off: it uploads no per-part runtime deltas …)"，不会再出现"不知道走的哪条"。README 的已知限制第 2 条与调试属性表已同步。

**验收**：`gradlew build` → 42 suites / **347 tests / 0 failures**；新产物 `-all.jar` sha256 `75CCFA93A62FC1A17CDE2CEAD67DC1582858F0BE3ECA370DD7512DB7FE79E052`。

**实机确认（同日，光影开启、不加任何 `-D`，部署件与当时 `build/libs` 产物逐字节一致）**：物理效果回归。日志三条对得上——
- Render thread 出现新加的归因行（原文）：`optimized Iris compute path is not used (off: it uploads no per-part runtime deltas, so secondary motion would be invisible; opt in with -Dysm_ef_compat.enable_iris_compute_path=true. Epic Fight's own Iris path draws instead)` → 本模组 Iris 路径已让位，由 EF 自带 Iris 路径绘制；
- `[physics] frame 240 … 1440` 连续六次采样全部 `59 of 59 bone(s) moving`（max swing 43–47deg，dt 7–12ms）→ 求解器照常；
- **没有** `optimized Iris compute path active` 行 → 该路径确实没画。
- 零 ERROR、零异常；`GPU skinning path skipped its first draw … reason=shader-pack-in-use` 与 `has 11283 unique vertices (>8192); using Epic Fight's compute path` 与光电场景一致。

至此"接线 → 启用 → 暴露缺陷 → 止损 → 归因可见"这条链闭合：默认关闭下物理可见，且日志明确说明为什么没走本模组的 Iris 路径。

**未做（下一轮，需要实机迭代）**：真正的修复是把 GPU 路径的 part 段上传 + 变更门控移植过来（读 `YSMMesh#getPartTransform(ordinal)`、按 `YsmIrisMesh` 的缓冲布局写入、只在变化时重传），然后**用 `enable_iris_compute_path=true` 实机确认物理可见**再考虑改默认值。那是一处 495 行 GL 类里的改动，且我这边没有实机回路，草率动手只会再引入一个同类回归——所以本轮只做止血，不做修复。

**教训（写给后来者）**：把一段从未执行过的代码接上线，**应当以默认关闭的形式上线**，让"接上"与"启用"分成两步；这次的代价是你的物理消失了一轮。

#### 第二十二轮：首次实机验证 —— 两条新诊断命中，并暴露两处"改了但看不见"的问题

前四轮（P0–P3）最后一次都以"无游戏内验证"收尾。本轮用真实实例跑了两遍：一遍无光影（该实例 EF 配置为 `use_compute_shader = true`），一遍**开启光影 + `-Dysm_ef_compat.disable_iris_compute_path=true`**。部署件与构建件逐字节一致（sha256 `8905148324743533…`、7,275,373 字节、同一 mtime），因此日志描述的就是被改的代码。

**实机确认（原文引用）**
- **P1-4**：`skinning paths registered (gpu=true, cpu=true, irisCompute=true)`。该实例的 EF 配置**正是 `use_compute_shader = true`**，也就是修复前必然失效的那个配置——那时 `drawPosed` 不被调用、CPU 路径不加载、GPU 路径（当时只靠 CPU 路径的静态调用被加载）也不加载，两者同时为 null。日志里随后出现 `GPU skinning path active (bone SSBO + skinning shader): model='wine_fox/06_hanfu', 86 parts`：**在这个配置下 GPU 直接蒙皮路径确实画了**。
- **P1-9**：`animation registry exemption target resolved: public void yesman.epicfight.api.animation.AnimationManager.validateC…`，与 `javap` 对 EF 20.14.17 的预测一致。
- **P2-b**：`biped armature joint layout verified` —— 改成读 `JointTable` 的那个校验在真实 EF 骨架上通过。
- 全链路：`converted model 'wine_fox/01_taisho_maid' -> 2940 quads`（worker 线程）→ `rendering 'Valletta997' with converted YSM base mesh`；**零 ERROR、零异常**，两遍都以 `Stopping!` 干净退出。
- 光影那遍：`GPU skinning path skipped its first draw: model=wine_fox/01_taisho_maid reason=shader-pack-in-use` —— 光影激活时 GPU 直连路径按文档让位。
- 顺带独立印证两条审查结论：官方 2.6.5 确实没有可读的 `client.*` 类（Mixin `was not found` + `YSM fork identified as LEGACY_YSM`）；以及大模型确被改道（`has 11283 unique vertices (>8192); using Epic Fight's compute path`）。

**本轮修的两处（都由实机暴露）**
1. **A/B 开关本身不可观测。** `YsmIrisComputePath.tryRender` 的第一行是 `if (DISABLED || !REFLECTION_OK || poses == null) return false;`——**不打任何日志**。于是"打开了 kill switch"与"这台机器没有 Oculus"或"反射失败"在日志里长得一模一样，而 README 恰恰把这个开关写成 A/B 验证手段：开关状态看不见，比较就无法归因。这与 P1-4 修的是同一类缺陷（功能有没有真的接上，必须能从日志读出来），只是低一层。改法：首次 decline 时打一行 INFO，并说明是三者中的哪一种。
2. **两个重力键的注释互相矛盾。** 实机实例的 `config/ysm_epicfight_compat-client.toml` 里，`secondaryMotionGravityAcceleration` 的注释仍写 "separate from the **retired** secondaryMotionGravity"，而 P1-8 已把另一个键的注释改成"它是**布料**求解器的重力"。同一份配置里两句话对不上，已改。（已存在的 `.toml` 不会自动刷新旧注释，只有键缺失/越界时 Forge 才重写；本条不需要为此重跑。）

**验收**：`gradlew build` → 42 suites / **347 tests / 0 failures**；新产物 `-all.jar` sha256 `FA32B446E9FB0067EB9FEDEEC00E09AE39F25D995D26451EED9EA008E585A2DC`。

**仍未验证（下一步就是它）**：**优化 Iris 路径的"启用"那一臂从未被跑到。** 两遍里它都在 decline（第一遍无光影，第二遍被开关关掉）。要验证 P1-4"把它接上线"这个决定本身，需要**光影保持开启、去掉/置空该 `-D` 参数**再跑一遍。归因信号现成：启用时打 `optimized Iris compute path active: model='…', N parts, M vertices`（每网格一次），被关掉时打新加的 `optimized Iris compute path is not used (disabled by -Dysm_ef_compat.disable_iris_compute_path…)`——**两行必有其一**，不会再出现"都不知道走了哪条"的情况。

#### 第二十一轮：代码审查后的 P3 修复（静默失败补诊断 / 资源释放 / 死代码状态写明）

四项确定做，其余技术债逐条留档。本轮**没有**删除那条未接线的布料链，也**没有**动工作区里既有的一千多行未提交改动——两者都需要你自己的决定，理由写在末尾。

**P3-1 四处掩盖持续性故障的静默 `catch` 补上因由。** 它们都把"读失败"与"没有"折叠成同一个返回值：`ManifestStore` 让**损坏的** manifest 与**不存在**的 manifest 都变成 `null`（于是持续性损坏被当作普通缓存未命中，每个模型反复重转，日志里什么都没有）；`YsmCapabilityReader` 让"读不出 capability"变成"这个玩家没有模型"（而模型同步会把这个结论广播出去）；`YSMMeshLibrary` 的缓存校验失败与"校验判定为不一致"同为一个 `false`；`TextureStore` 的纹理缓存哈希读取失败与"哈希不匹配"同为一个 `false`。四处都**保持返回值不变**（恢复动作本来就是对的），各补一行 `LOGGER.debug` 说明是哪一种。

**P3-2 退出世界时释放 `CURRENT_ENTITY` ThreadLocal。** `YSMRuntimeBridge` 用一个 ThreadLocal 保存"当前正在为其准备网格的实体"，每次绘制结束清除——但一个会话的最后一次绘制正是"世界在帧中途被离开"时那次**没走完**的绘制，于是旧实体及其 `ClientLevel`、区块会被一直持有到下次绘制。改法：在 `YSMReloadTrigger.onDisconnect` 里显式 `clearCurrentEntity()`（与那里已有的 `YsmMeshCloth.clear()`、`YsmClasses.invalidate()` 同一位置、同一理由）。

**P3-3 `TextureStore.sanitize` 的死存储。** 分段循环里对 `..`/`.` 段设置的 `stripped = true` 从未被读取（`_hash` 后缀在字符循环之后、分段循环之前就已追加），删掉。这是**安全相关函数**里的死状态，留着会让下一位读者以为它有意义。

**P3-4 探针输出纳入 `.gitignore`。** T8/T11/T12 在测试运行时把测量表写到 `tmp_verify/`，而它不在忽略列表里——审查期间我自己的两次测试运行就让工作区多出这类未跟踪文件。已加 `tmp_verify/` 及说明（它们是可再生输出，真正的 fixture 在 `src/test/resources`）。

**P3-5 未接线子系统状态写入文档。** README「已知限制」新增第 12 条：`YsmMeshCloth` → `YsmClothSolver` → `YsmClothTuning` 这条位置约束布料链在源码里完整存在，但**渲染路径没有任何地方调用它**（`YsmMeshCloth` 在整个 `src` 中唯一的出现是 `clear()`），当前所有二次运动都走摆锤求解器；因此 `secondaryMotionMaxParticles`、`secondaryMotionIterations`、`secondaryMotionBodyRadius` 与 `secondaryMotionGravity`（布料重力）当下不产生可见效果。接线还是删除需要一次带游戏内观察的决定。上一轮已把 `secondaryMotionGravity` 的配置注释按同一事实更正。

**验证（全部实跑）**：`gradlew build` → **42 suites / 347 tests / 0 failures / 0 errors / 4 skipped**，与上一轮同数（本轮为诊断、资源释放与文档，未新增测试）。

**本轮与后续明确未做（P3 剩余技术债）**
1. **未删除也未接线那条布料链（约 1200 行）**：删除等于替你扔掉一整块已实现的功能（含 8 次约束松弛、身体体积排斥、钉住粒子的蒙皮放置），接线等于在没有游戏内验证的条件下开启一条从未跑过的新求解器。两者都不是审查方该单方面做的决定；本轮只把状态写到 README 与配置注释里。同类状态还有 `YsmSecondOrder.Bank`（`prime()`/`isPrimed()` 无调用者）与 `YSMMeshLibrary.generateAll()`（无调用者，连带唯一的陈旧文件清理不执行）——已在第十九/二十轮 CHANGELOG 记录，尚未在代码处加标记。
2. **八个巨型类未拆分**（`YsmMeshSecondaryMotion` 1542 行 … `YsmExtraAnimationLibrary` 808 行）：拆分需要先给这些零测试覆盖的类补上行为测试，否则只是把风险换个位置。
3. **两处包环未打破**（`model ↔ ysm`、`model.runtime ↔ renderer`）：改动面横跨缓存与渲染分派，收益是"架构图与代码一致"，风险不成比例。
4. **README 配置表仍只列 18 个键中的 4 个**：纯文档，但要逐个核对默认值，本轮预算不足。
5. `YsmGpuRenderEnable` 中"250ms TTL 使每帧成本成为一次 volatile 读"的注释与实际（两个字段都非 volatile）不符；`T12_ProbeTest` 的 `mean`/`pivotMean` 两个死方法；`ImplementationPathAcceptanceTest` 用 `getParameterTypes()[0]` 会对无参重载抛 AIOOBE 而非按名报错。
6. **工作区里那 1169 行未提交改动（6 改 + 13 未跟踪）**：属于你的在制品，我不替你提交；模组仓库之外的 workspace 残渣（`tmp_verify\`、`tmp_decompile\`、若干 `.ps1`）同理。

#### 第二十轮：代码审查后的 P2 修复（缓存记账 / 关节表单源 / 开关方向 / 服务端与网络）

六项小修，承接第十九轮。本轮**未做完** P2 清单里的三项，理由逐条写在末尾。

**P2-a LRU 淘汰先判后摘。** `trimIfNeeded` 原来先把 victim 从 `ACCESS_ORDER` 摘掉、再判断它是否正在转换（`PENDING`）或已失败（`FAILED`）——被跳过的那条于是仍留在 `MESHES` 里却不再被 LRU 追踪。而循环的终止条件正是 `ACCESS_ORDER.size() <= cap`，等于用"被追踪数"当"已加载数"：上限被低估（可长期超过 `lazyModelCacheSize`），被跳过的条目**再也不会被选中淘汰**，只能等整表失效。改法：先在锁内判断、要淘汰才摘；跳过的留下，下一轮 trim 再试。

**P2-b 关节表回到单一数据源。** `YSMPlayerRenderer.validateArmatureOnce` 曾自带**完整第二份** 20 关节 name+id 表——于是"专门用来发现关节表不一致"的那个校验，校验的是它自己那份：`JointTable` 变了而副本没变，校验通过、网格生成器却按新表布局。改法：校验改为遍历 `JointTable`；并新增 `JointTableTest.noSecondCopyOfTheLayoutInTheRenderPath`，扫描 renderer 包内的源码，若再出现"关节名 + id"的字面量组合就转红（源码文本检查是本项目已用的手法，只有它能看见"重新声明"这件事）。

**P2-d ModernYSM 开关读取失败不再"默认开"。** `YsmGpuRenderEnable` 有三处兜底全部偏向"启用 GPU 路径"：字段解析失败 `return true`、单字段读失败 `boolOf(..., true)`、兼容渲染器读失败回退 `false`（`!compatOn` → 开）。用户关掉 GPU 渲染通常正是因为那条路径出过问题，**猜"开"是唯一不能猜的方向**。改法：任一处读不到值即视为"关"，并打一次 WARN 说明该路径保持关闭及如何改回；`gpuOn` 兜底改 `false`、`compatOn` 兜底改 `true`（都指向"关"）。

**P2-e 服务端握手不再对每个收件人重复序列化。** `sendModelToPlayer` 每次调用都 `readSnapshot(target)`，而它在握手时按"每个收件人 × 每个在线玩家"调用一次——即全量玩家 NBT 序列化被付了 **O(n²)** 次。改法：直接复用 `LAST` 里周期扫描已经取到的快照（尚未被扫描到的新玩家才真读一次），实体 id 仍取**在线实时值**（它会在重生后变化，客户端按 UUID 建表）。

**P2-f 网络三处收口。** ①`setChannelVersion` 仍保持 YSM 的"先到先得"，但**允许我们自己的版本覆盖已钉住的错值**——否则该连接的模型同步永远不可能成立（`isConnectionValid` 恒 false、200 tick 重试也换不掉非空属性），而只有一行日志解释。②版本不匹配的告警从"每 JVM 一次"改为**每连接一次**（原来第二个不匹配的服务器是静默的）。③`ModelSyncClient` 的注册表加了**容量上限 4096 + 名称长度校验**：这些条目来自我们无法控制的服务端，原来既无上限也无内容检查，恶意服务端可用不存在的 UUID 把它撑到 OOM（那些 UUID 只在离开世界时才清）。

**P2-i 清理两处失真。** 删掉 `YSMMeshLibrary` 里声明后**从未读写**的 `TEXTURE_TRANSLUCENT`（活的在 `TextureStore`），并留一行注释说明删的是"没人碰过所以不可能错、正因如此值得删"；`YsmGpuRenderPath` 的类 javadoc 原本描述"每帧合成每个部件的矩阵并整块填充 SSBO"，与函数体（关节-only 上传 + 部件段变更门控、`joint×part` 在着色器里合成）**相反**，已按实际改写（README 的对应段落原本才是准确的一方）。

**验证（全部实跑）**
- `gradlew build` → **42 suites / 347 tests / 0 failures / 0 errors / 4 skipped**（第十九轮为 346，+1 为新的关节表漂移测试；该测试已随套件运行并通过）。
- `manifest`/`TextureStore`/`YsmCapabilityReader` 等未改动的路径由既有 347 条测试覆盖，全部仍绿。
- 新增的源码漂移测试**未经变异验证**（即"故意放回一份副本看它转红"这一轮没做，已由结构确认：其正则匹配的正是被删除的那种字面量组合）。列在此处以免被读成已验证。

**本轮明确未做（P2 剩余项，附理由）**
1. `GlRenderState.capture()` 每 draw 一个 `Snapshot` + 4 次 GL 状态查询：**没有安全的复用写法就不改**——单一共享实例在嵌套绘制（描边通道、半透明双 Pass）下会把内层捕获的状态当成外层要恢复的状态，反而改错 GL 状态；真要复用需要一个按嵌套层级分配的池。代价是一个 4 布尔对象/次绘制，收益不足以承担这个风险。
2. `TextureStore` 热路径的 `ResourceLocation.toString()` 与 `findTexture` 的全表兜底扫描：需要把若干 static map 的键类型从 String 换成 `ResourceLocation`，属于面更广的重构；逐帧代价约 300B/玩家量级，列入下一轮而非塞进本轮。
3. 掩盖持续性故障的 4 处静默 `catch`（`ManifestStore:115` 把"损坏"与"缺失"折叠成同一个 null、`YSMMeshLibrary:662/695`、`YsmCapabilityReader:40`、`TextureStore:601`）补诊断：纯诊断改动、零风险，但本轮预算用尽；四个位置已在审查报告中逐条列明。
4. 服务端周期扫描仍为每 2s 一次全量玩家 NBT（改为直接读 capability 需依赖 YSM 混淆类，改为拉长间隔属产品决策）；8 个巨型类拆分；两处包环；README 配置表只列了 18 个键中的 4 个。

**未验证**：与前两轮相同——无游戏内验证，本轮所有结论只到"编译 + 单元测试 + 静态核对"。渲染、网络与服务端的实际行为仍需实机确认。

#### 第十九轮：代码审查后的 P1 修复（渲染路径注册 / 探针断言 / 文档覆盖地图 / 上限与豁免可观测）

六项，承接第十八轮。仍只动审查报告点名的位置。

**P1-4 渲染路径靠"哪段调用图先碰到类"来决定是否存在。** 三条路径都在自己的 `static {}` 里自注册，所以：`YsmCpuRenderPath` 只由 `SkinnedMeshCpuRenderMixin` 加载（EF 打开 `use_compute_shader` 时 `drawPosed` 根本不被调用 → 永不加载）；`YsmGpuRenderPath` 只因为 CPU 路径调了它一个静态方法而跟着加载；`YsmIrisComputePath` 与 `YsmIrisMesh` **只互相引用、没有任何外部入口**，因此 `registerIris` 从未执行、`RenderBridgeRegistry.iris()` 恒为 null——README 记载、`-Dysm_ef_compat.disable_iris_compute_path` 专门为 A/B 而存在的"优化 Iris 计算路径"，在任何机器、任何配置下都不可达。改法：给三条路径各加一个 `ensureRegistered()`（空体，加载类即注册），在 `YSMCompatClientEvents` 的 client setup 里 `enqueueWork` 显式加载一次，并打印 `skinning paths registered (gpu/cpu/irisCompute)`——全 true 用 INFO，任一为 false 用 WARN，因为"某条路径根本不存在"以前是完全静默的，而 `enableGpuRender`、`-Dysm_ef_compat.force_cpu_render` 会因此变成空操作。

**P1-5/P1-6 探针测试的评分结论改为断言。** T11 的"all of them cloth"、T12 的形状规则（肢体=胶囊、躯干/头=球）、T12 的"收窄 skip 判据是否真的救回面板"、T12 的"胶囊跨度是否等于骨长"、T8 的单位换算结论，此前**只打印不断言**——回归时表格照样漂亮。现在各自断言，并带反空洞下限（面板数、体积数、检查条数）。T8 的容差按实测设定：11 块面板里最差偏差 **0.023**，所以"常数 0.7"实际是"0.7±2.3%"，原来那个"constant 0.70"的说法比数据更硬。

**P1-7 官方 2.6.5 的覆盖地图是错的。** README 与 4 个 `YsmUnobf*` javadoc 都声称那些 `com.elfmcys.yesstevemodel.client.*` 目标"在官方 2.6.5 里也存在"——**官方 jar 里 `client/`、`geckolib3/`、`molang/` 一个可读类都没有**（955 个类里只有 `mixin/` 包 22 个可读），所以官方发行版**就是**"完全混淆构建"（README 表格里那两行本是同一件事）。更危险的是这条错误会诱导维护者删掉 9 个真正生效的混淆目标 mixin。同时补上排障须知：`require=0` 的"没匹配上"**不打任何日志**，只有目标**类**缺失才 WARN；客户端 mixin 计数 31→**32**，并把原先没列出的 8 个与 common 段那个一起写进清单。

**P1-8 `secondaryMotionMaxChains` 的默认值不约束任何东西。** 键的默认值就是 96，而代码把 `96` 当作"未设置"→ `Integer.MAX_VALUE` → 无限，同时该键的注释却承诺"这是给声明了几百根骨骼的模型的兜底"。改法：新增显式哨兵 `-1`（默认值，范围 `[-1,512]`），只把历史值 **24** 继续读作"未设置"（它低于真实模型 4–60 的区间，兑现它会把真正的裙子截断），**96 现在如实生效**；判定逻辑抽成 `maxChainsFor(int)` 以便测试（单测够不到 Forge 配置，这正是 96 能一直等于"无限"而无人发现的原因）。另：`YsmPhysicsTuning.gravity` 字段被 `secondaryMotionGravity` 填充却**没有任何积分器读取**（只有日志打印它），而该键真正的作用域是布料求解器——注释说它"LEGACY, ignored"，事实是"摆锤不用、布料在用"。改法：删掉该字段与构造参数，日志改印 `gravityAcceleration()`（真正被积分的那个数），配置注释改写为说明真实消费者，并注明布料链当前未接线。

**P1-9 动画注册表豁免"装没装上"不可观测。** `AnimationManagerValidationMixin` 是 `require=0` 软注入（正确：EF 升级不该变成启动失败），但软注入不匹配时**没有**诊断，后果是"用轮盘桥的玩家一进服就被踢"而日志里毫无线索。这里**没有**把它硬化成 `require=1`（那会把一次 EF 改名变成启动崩溃），而是新增 `AnimationRegistryGuard.reportExemptionTarget()`，在模组构造期按名字+参数类型反射解析目标并明确记录：命中 INFO，签名变了/方法没了 ERROR 并写明后果。

**验证（全部实跑）**
- 负例先行：把 `classifyBone` 的容器回溯关掉（退回纯名字规则）→ T11 新断言**转红**，列出 `RB3=0.00, FM1=0.00 …` 全部裙片；把 `BLOCKS_PER_MODEL_UNIT` 从 0.7 改成 1.0 → T8 转红（偏差 0.313）。两处改动均已还原。
- 还原后 `gradlew build` → **42 suites / 346 tests / 0 failures / 0 errors / 4 skipped**（第十八轮为 344，本轮 +2：`maxChainsFor` 规则与日志重力各一条）。
- P1-9 的诊断在 EF 20.14.17 上按 `javap` 核对：`validateClientAnimationRegistry(CPCheckAnimationRegistryMatches, ServerGamePacketListenerImpl)` 确实存在且描述符完全匹配 → 游戏内走 INFO 分支；把该诊断单独编译成探针、在缺少 Minecraft 依赖时运行 → 落到 `catch (Throwable)` 打 WARN，**不崩**（这是它必须有的行为）。

**未验证（本轮最重要的一条）**：**Iris 路径从"不可达"变成了"可达"，而本轮没有任何游戏内验证**——它现在会在"EF 计算着色器可用 + 光影包激活 + 反射解析成功"时接管绘制。这条路径是作者自己写的、有内部回退与 kill switch，且此前不可达纯属意外；但"接上线"与"接上线且正确"是两件事。**上线前请用 `-Dysm_ef_compat.disable_iris_compute_path=true` 做一次 A/B**，并看启动日志确认三条路径都 registered。除此外所有渲染相关结论仍只到"编译 + 单测 + 静态核对"一级，无游戏内验证。

**本轮未做完（明确留作下一轮）**：T11 另外 3 个测试（`meshUnitsAndNeighbourGaps`、`panelSeamGapsUnderSwing`、`differentialRotationBetweenNeighbouringPanels`）仍以测量表为主，尚未加不变量断言——它们的数字是"品质阈值"而非"对错"，需要先与作者确认哪个量该成为契约，而不是由审查方凭空定阈值；T8 内部仍有一份 `hangs()` 判据副本未改为调用生产分类器。

#### 第十八轮：代码审查后的 P0 修复（Molang 参数槽重入 / 二进制段落计数 / 测试任务防陈旧）

三项，来自一次五维代码审查（正确性 / 架构 / 安全 / 性能 / 可维护性，加权 6.6/10）。只动这三处，P1/P2/P3 与工作区既有的未提交改动一律未碰。

**P0-1 `Molang` 函数参数暂存重入——静默算错值。** 参数在调用前写入一个 per-thread 暂存数组；求值第 i 个参数时会执行内层调用，内层从下标 0 开始写**同一个数组**，把外层已写好的 `0..i-1` 覆盖掉。**修复前实测**：`math.max(5, math.min(1, 2))` 得 `1`（应为 5）、`math.max(9, math.abs(-3))` 得 `3`（应为 9）、`ysm.outer(1, 'x', ysm.inner('y', 2))` 的第 0 个参数由 `1.0` 变 `0.0`。触发条件是"函数调用出现在参数位置 ≥1"——本文件 `:661` 的注释自己就拿 `ysm.second_order('头发垂直', math.clamp(...), 1.5, 0.6, 0)` 举例。改法：新增 `ArgPool`（per-thread，按嵌套层级各借一个数组，`acquire`/`release` 用 `try/finally` 配对），`ARG_SLOTS` 与 `MIXED_SLOTS` 两个 ThreadLocal 合并为 `ARG_POOL` 一个：层级不同则数组不同，嵌套不再互相覆盖，热路径仍是零分配（数组跨帧复用；深度受 `Parser.MAX_PARSE_DEPTH` 约束）。顺带纠正一处此前的误判：数值调用嵌套在字符串调用里**本来就是安全的**（两者用的是不同 ThreadLocal），会互相覆盖的只有"字符串调用嵌套字符串调用"。

**P0-2 `YsmBinaryReader` 时间轴事件计数无上限。** `new String[timelineEventsCount]` 直接使用流中未校验的 varint，是本文件 9 处计数里**唯一**没有 `1_000_000` 上限的一处，且在第一次 `readString()` 因缓冲区耗尽而失败**之前**就完成分配；伪造的 `.ysm` 可声明 `Integer.MAX_VALUE`（约 8–17 GB），而 `YsmModelPackage.load` 只捕获 `Exception`/`StackOverflowError`，接不住 `OutOfMemoryError`。改法：照抄同文件既有写法加 `if (< 0 || > 1_000_000) throw new IllegalStateException("unreasonable timeline event count: …")`。`skipAnimations` 里的同名计数**有意未加**：它只驱动一个随缓冲区耗尽而终止的循环，不做任何分配。

**P0-3 `test` 任务没有防陈旧门。** 本机 `gradlew test` 会报 `BUILD SUCCESSFUL` 而 `:test` 是 `UP-TO-DATE`——测试根本没跑、报告是上一轮的 XML（本轮审查第一次执行正是如此）。改法：`outputs.upToDateWhen { false }` + `doFirst { delete build/test-results }`。

**验证（全部实跑）**
- 负例先行：三条新断言在修复前**必红**，实测 `expected: <5.0> but was: <1.0>`、`expected: <1.0> but was: <0.0>`、以及 P0-2 的 `got: java.lang.IllegalStateException: Invalid string length 8352, remaining 1`——最后这条正说明旧行为是"先分配、再报一个与计数无关的解析错"。
- 修复后：`cleanTest test` → **42 suites / 344 tests / 0 failures / 0 errors / 4 skipped**（原 41/337；新增 `MolangTest` 4 条 + `YsmBinaryReaderSectionCountTest` 3 条）。
- P0-3 验收：连续两次 `gradlew test` **两次都出现** `> Task :test`（修复前第二次是 `UP-TO-DATE`）。
- 未破坏既有能力：真实 `.ysm` 黄金用例 3/3 通过，篡改 1 字节后 3/3 失败——说明改动 `test` 配置后 `-Dysmef.golden.ysm` 转发仍有效。

**未验证**：无游戏内验证（无客户端、无专用服务器）；三项都只到"编译 + 单元测试 + 实跑探针"这一级。`Molang` 的零分配特性只做了代码层确认，未做分配计数测量。P0-1 的修复只覆盖脚本求值本身，未评估"此前算错的值是否已进入过任何缓存产物"（`config/ysm_epicfight_compat` 下的运行时 JSON 与已转换网格不含求值结果，因此预期不受影响，但未逐一核对）。

#### 第十七轮：腿穿裙摆 — 碰撞体从"球"改成"沿肢体的胶囊"

用户实测更正了症状：**不是"没按重力下垂"，而是"腿在向前迈步时裙摆依然还在下垂，导致腿穿模穿出去"** —— 是碰撞让位问题，不是空气阻力问题。用户判断正确。

**根因：碰撞体的形状，不是尺寸。** 用真实网格（11283 顶点）逐关节对比"模组构建的体积"与"真实肢体包络"：

```
joint  部件                体积半径  真实 p85  真实 max   覆盖率
1/4    Thigh_R/L (大腿)     0.087    0.458     0.512     19% / 17%   ← 腿几乎全在体积外
2/5    Leg_R/L  (小腿)      0.084    0.188     0.425     44.7% / 19.7%
7      Torso                0.200    0.339     0.647     58.9% / 30.9%  (CLAMPED from 0.31)
9      Head                 0.200    0.898     1.040     22.3% / 19.2%  (CLAMPED from 0.29)
```

**调半径无解**：肢体顶点云"长而细"，球要容下它只能长到**腿长**，而那么大的球会吞掉裙摆空间；原来的 15 百分位就是那个妥协（细球放在腿中段）。钳位数据也证明上限在**向下压**（头 0.29→0.20、躯干 0.31→0.20），抬高上限只会让躯干/头去吞裙摆。**形状错了，不是数字错了。**

**改法**：四肢（Thigh/Leg/Knee）改用**沿肢体轴的胶囊**，躯干/胸/头保留球。轴取**几何自身主方向**（协方差幂迭代）——构建发生在转换期、只有转换后的网格，而这项目出过 16 倍事故正是因为混用两种帧；管半径取**垂直于轴**的距离的 85 百分位，垂直距离**不含长度**，所以百分位选的是"肢体自身的粗细"而不是"粗细与长度的混合"（后者正是 15 百分位落到 1/6 的原因）。**球 = 两端重合的胶囊**，推出/解析/skip 仍只有一份实现。改后大腿 **tube r 0.1800 / span 0.5974**，小腿 0.1503 / 0.1879。

**同时修掉一个真缺陷：`skipFor` 的边距过宽。** 其第二个条件原本是"体积中心离静息质心 < 半径 + 摆动可达"——措辞上是"这块布**能碰到**这个体积"就跳过，而真正该跳过的只是"体积**把布片静息位置本身包住**"。实测（生产几何）它在**六块明确在体外的面板**（`FM`/`FR`/`BM`/`BM2`/`BR` 及左腿 `FL`）上误触发 → **大腿被要求忽略它本该挡住的面板，这就是"腿穿过去"的直接机制**。判据收窄后救回七块中的五块。

**张力未被完全消除（如实记录）**：`FM1`（离轴 0.1523）与 `FR1`（0.1283）**确实在 0.180 半径内**，收窄判据救不了这两块——"体积把静息位置包住"正是 skip 规则存在的理由（推出去 = 喷射）。执行者**刻意没有**继续叠加"取消包含跳过 + 接触法向弹簧投影"这层响应改动，理由充分："它不再是解释症状所必需，而在已验证改动之上再叠一层未验证的响应改动，正是前几轮出错的模式"。该层记为下一步。

**一条更硬的不变量（旧的是错的，已改写而非放宽）**：旧不变量断言"任何落在 `半径 + 摆动可达` 内的体积都应被跳过"——**它把这个缺陷编码成了契约**。现重写为双向：**被包含的绝不施加、在体外的绝不被跳过**。红证据：回退收窄后报 `2 clear pairs are skipped, which is the defect that let a leg through a skirt`。

**两条"修复前必红"的测试**，都驱动**生产 collider + 真实网格顶点**：①形状规则变异体（退回球）时报 `span 0.0` + `tube radius 0.08685396`；②前片被推开且**只去掉法向速度、保留切向**（切向也被杀就是本项目已修过一次的粘滞停止）。执行者还自查纠错了一处**假保证**：那条测试早先驱动**合成 collider**，退回球的变异体让它**保持绿**。

**姿势下的覆盖（解析论证，非实测）**：胶囊存在 bind 空间、两帽心每帧走 `pose × toOrigin`，**刚体变换保距** → span 与轴不随姿态漂移；屈膝改变的是**小腿**（`骨长 × sin(膝弯)`：30°→0.3636、60°→0.6297 blocks），而小腿在 joint 2/5 有自己的胶囊。缺口：胶囊覆盖大腿**几何**（0.5974）而骨长 0.7272，作者若把大腿画得比骨短会在髋部留一段。

**部署注意**：`E:\.minecraft\versions\EPIC mod test` 下有**正在运行的 Forge 服务端**，装 jar 必须**先停服务端**（覆盖被 JVM 加载的 jar 不安全，且旧类在内存里会让"改好了吗"无法判断）。本轮全程未写 `mods/` 下任何文件。

**验证**：`cleanTest test build` → **337 tests / 41 suites / 0 failures / 0 errors / 4 skipped**；`javap` 确认产物含 `isLimbJoint` / `pushOutOfCapsule` / `fromGeometry`。

**未验证**：观感（无人进游戏）；`FM1`/`FR1` 两块仍被包含 → 仍不会被大腿推开（已知残余）；四肢体积变大是否把裙摆整体外推（只有数值）；姿势下的胶囊未实测；只测了一台模型的腿几何。纪律再次被验证：**`compileTestJava` 失败时 `test` 会复用上一轮 XML，陈旧报告与真失败长得一模一样**（本轮又踩两次）。

#### 第十六轮：衣物分类修复（follow=0.0 → 0.92）+ 裙摆连续性量化

用户实测反馈：**「裙摆即便在默认状态下依然在腿后部而非前面」**、**「移动起来看起来像是布条而非连续整体」**。

**问题 1（根因，已修）：衣物根本没被分类。** 日志（装着第十五轮 jar）里逐件读出：`LongHair/BaseHair/Bangs follow=0.6` ✓、`Tail..Tail7 follow=0.8` ✓、但 **43 块裙板 `FM/FL/FR/BM/BL/BR/LF/LB/LM/RF/RB/RM*` 全部 `follow=0.0`** ✗。原因是 `verticalFollow()` **只看骨骼自身名字**，而这台模型的面板叫 `FM`、`FL1`、`RB3`——名字里什么都不含，信息全在**容器**里（`FM <- FrontClothe <- clothe <- UpBody`）。所以"裙摆垂向地面"这个特性在这台模型上**从未生效过**（0.0 = 旧行为 = 被姿态牵着走），这正是裙摆停在腿后而不落到腿前的原因。

**修法（结构判据，不是加名字）**：先看骨骼自身名字；否则**向上走到第一个"读得出家族"的祖先**并继承它。**"在第一个具名区域停下"就是这条规则的全部精度**——若读遍所有祖先，"布料"会变成整具骨架的属性（每根骨骼最终都会经过 `UpBody`）。名字表只作兜底：加一个 `FM` 进表对下一个模型立刻失效，这个项目已反复吃过这类亏。

真实模型 195 根骨骼上：**CLOTH 5 → 48**（43 块面板全部 0.92），HAIR 20 → 21（全 0.60 未变），TAIL 8 → 8（全 0.80 未变），且**无过分类**——除 5 个容器本身外，没有任何手臂/腿/耳/嘴/眼/挂件骨骼变成衣物（逐根列名核对）。（执行者自查纠错：第一版把上溯深度限成 3，第四层 20+ 根仍是 0.0，同一缺陷只是下移一层；现在深度只是防成环的护栏。）

**"修复前必红"证据**：用单行变异禁用容器遍历后，真实模型判定回到 `{CLOTH=5, HAIR=20, TAIL=8}`——**与用户日志里的数字逐字一致**，这就是"这条规则确实是他所报问题的根因"的证明。

**问题 2（量化，但刻意未调参）**：
- **一处事实更正**：`knitsOf` **已经**让父收子为 partner（`child == parentOf[i]`），`sewnTogether` 的注释还论证过"排除父子会让整件衣服无同件耦合"。不对称的是反方向，且那是刻意的（子是在父之下复合的）。所以"跨层没耦合"这个前提是错的。
- **实测发现作者本来就把面板画成分离的布条**：静止状态下相邻面板顶点间隙 **0.11–0.31 blocks**（`FM↔FM1` 0.3094、`BL↔BM` 0.1074、`BL3↔BM3` 0.3059）。这意味着"像布条"有相当一部分是**模型形状**，物理无法也不该消除它。
- **耦合强度不是瓶颈**：60 fps 下每帧闭合 32%、12 帧到 99%（五分之一秒）。
- **摆动列的数值被污染、不可靠**：网格 fixtures 的 `positions` 在 loader 的 Blender 帧，而同一文件旁的 `pivot` 在 Minecraft 帧，绕一个转另一个混了两套约定。**因此本轮不发布替它假设过坐标的数字，也没有在无可靠度量时调 `COHERENCE`** —— 那只会制造"看起来改善了"的假象。正确顺序是先解决帧不一致、拿到可信的顶点间隙，再决定耦合方向。

**验证**：`cleanTest test build` → **332 tests / 40 suites / 0 failures / 0 errors / 4 skipped**（上一轮 323/39）。改动集中在 `YsmPhysicsParts` 的分类与 `Segment` 的 `Category` 分量；两个按位置构造 `Segment` 的范围外测试文件用兼容构造覆盖而未改动（已 grep 核实它们不调用新入口）。未回退链式复合修复、重力跟随权重、限位锥面以 target 为轴。

**未验证**：未进游戏（观感无人看过）；"像布条"的物理成分未量化（受上述帧问题阻塞）；其他模型未测（结构判据"应当"泛化，但未测）；`isEntityUpsideDown` 例外、`chainLimitFor` 均分、服务端行为均未验。

#### 第十五轮：重力跟随 — 头发自然下垂 / 裙摆垂向地面

需求（用户实测反馈后提出）：**头发要自然受重力下垂**；**身体前倾冲刺时裙摆应垂直于地面，而不是与身体在同一轴线上**。

**同一个机制的两个面。** 原来钟摆弹簧的**目标方向**恒等于"姿态静息方向"，所以部件被姿态牵着走。60° 前倾时，离竖直方向的余量是：

| 前倾 | L=0.09 | L=0.18 | L=0.26 |
|---|---|---|---|
| 45° | 20.22° | 28.30° | 32.11° |
| 60° | 26.82° | 38.05° | **43.28°** |

**改法**：`目标方向 = normalize((1−b)·姿态静息方向 + b·世界竖直向下)`，`b` 按部件类别给：**衣物 0.92 / 尾巴 0.80 / 头发 0.60 / 未分类 0.0（= 旧行为）**。新增配置旋钮 `secondaryMotionGravityFollow`（默认 1.0，全局缩放，0 = 退回旧行为）。

**改后**（同样量"离竖直的余量"）：45° 前倾 `1.50/2.06/2.34°`；60° 前倾 `1.87/2.57/2.91°`。验收目标 ≤10°，最差余量 3.4 倍。权重单调性实测（60°/L=0.26）：`b=0→43.28°, 0.3→30.65°, 0.6→16.55°, 0.8→7.68°, 0.92→2.91°, 1.0→0.00°`。解析解与求解器实测吻合到 **0.01°**（两套独立算法互证）。

**一处关键设计修正（Lead 原指令被数值证据推翻）**：Lead 最初要求"限位锥面仍以**姿态静息方向**为轴"。实测证明这条会让特性根本无法实现——保持 rest 为轴时，60° 前倾 + 20° 根限位把裙板钉在离姿态 20° 处 = **离垂直恒为 40.00°，`b` 从 0 到 1 一动不动**（变异体实测 `39.999996°`）。最终改为**以 target 为轴**，三条代价封顶：锥面大小不变、`own` 仍被作者额度封顶、碰撞 `skipFor` 的 reach 改为从姿态量到锥面。

**`downTarget` 取 `(0,-1,0)` 常量的取证**：`MathUtils.getModelMatrixIntegral` 的 pitch/roll 位是硬编码 `fconst_0`（8 个），`PatchedEntityRenderer.mulPoseStack` 全 jar 只有一个 `rotateXYZ` 命中 → 模型矩阵的旋转只有 yaw。而"前倾"来自动画写进 `poses[Root]`，所以它进 `rest`、**不进**模型矩阵 —— 因此模型空间的 -Y 始终是世界竖直向下。**已知例外**：原版 `isEntityUpsideDown`（Dinnerbone 名牌 Z-180）会让真向下变成 `(0,+1,0)`，已在常量注释写明，未复现。

**顺带修掉两个既有问题**：
1. **编译在地板下是断的。** 提交 `e1329b8` 恢复了三个文件却让 `YsmPhysicsSimulator` 保持删除，5 个文件引用不存在的类；编译器在第一个错误处即停，**所以此前几轮看到的"compileJava 成功"是假象**。已"完成那次回退"（删 `YSMPlayerAnimator.advancePhysics` 的 per-chain 循环，保留其挂骨汇报日志），并留注释说明该类为何被有意移除（第二个失效引擎，锚点在 bind 空间常量上、永不产生摆动，复活它会造成双真相）。
2. `YsmClothTuning` 引用的 `SECONDARY_MOTION_MAX_PARTICLES/ITERATIONS/BODY_RADIUS` 三个键在配置里不存在（改动前靠抛异常→catch→默认值兜住）。已按 `YsmClothSolver` 原有默认（4000/8/0.22）补齐，取值逐位相同。

**验证**：`cleanTest test build` → **323 tests / 39 suites / 0 failures / 0 errors / 4 skipped**。三个单行变异体各让 6 条测试变红（M1 `39.999996°`、M2 `43.274845°`、M3 `24.127811°`——**M3 最有教育意义：数值"好转"19° 其实是压在限位锥面上 20.00001°**）。`b=0` 与旧行为的等价性用**整棵树**证明：把那行改回旧 target 后全量 `323/6 failed`，且那 6 条**全部且仅仅**是本轮新增的断言，既有测试零失败；文件 SHA256 逐字节还原。

**纪律记录**：`compileTestJava` 失败时 `test` 会**复用上一轮的 XML**，陈旧报告与真失败无法区分。**改完测试文件必须先确认 `compileTestJava` 成功，再相信测试报告。**

**未验证**：未进游戏（观感无人看过，权重"好不好看"只能实测，故保留 `secondaryMotionGravityFollow` 旋钮）；`isEntityUpsideDown` 例外未复现；分类在真实模型骨骼表上的分布未实测（`follow=` 日志列专为此设）；碰撞 reach 变宽的削弱方向无量值测试。

#### 第十四轮：修链式复合（Java 求值顺序）与绘制关节不一致

症状（用户装上第十三轮之后进游戏实测反馈）：**尾巴会移到下半身的位置**；**部分裙摆不能与裙子连接成一体，分片四散约 15°**。用户判断是"坐标问题"——**不是坐标**。

**根因 A（P0，尾折到下半身）：链式复合的 Java 求值顺序。**

`YsmMeshSecondaryMotion.resolveSegment` 里把子段自己的 delta 复合到父段之下时写的是：
```java
Matrix4f delta = state.jomlDeltas[index];          // delta 就是本段自己的槽位
delta.set(state.jomlDeltas[parent]).mul(jomlScratch.set(state.jomlDeltas[index]));
```
Java **先求值接收者链** `delta.set(parent)`，**再求值实参**。而 `delta` 就是 `jomlDeltas[index]`，所以第一步已把本段自己的旋转覆盖成父矩阵，第二步的实参读回来的是**父** → 实际得到 `父 × 父`。scratch 没有被别名，**被别名的是"拷贝发生在覆盖之后"这个顺序**。

后果：`delta_n = T(P_根)·R_根^(2^(n-1))·T(-P_根)` —— 深段**完全绕开自己的枢轴**，且复合角逐级平方。铁证：现场日志的 `whole` 列 `15.6 / 31.2 / 62.4 / 124.8 / 110.4 / 139.2 / 81.6` 正是 `2^n × 15.6° mod 360` 折进 [0,180]，**7/7 逐位吻合**。修后复合角变为线性：`8.643 / 17.287 / 25.930 / 34.574`。

**注意**：这一条**不能用角度断言抓住**——link1 在两种写法下角度几乎相同（17.286905 vs 17.286928），**枢轴位置却是错的**（link2 位移 0.2327 → 0.0883 格）。回归测试必须断言矩阵/枢轴，不能只断言角度。

**根因 B（P1，同一个"看起来像坐标错"的家族）：网格烘焙关节与运行时物理表不一致。**

`EFMeshJsonWriter` 的网格烘焙走**单参** `YSMJointMapper.resolveJointId(bone)`（布料的容器链一路走到 Chest，得 joint 8），而运行时骨骼表走**双参** `resolveJointId(bone, model)`（髋部规则把它改成 Torso，joint 7）。两侧不一致涉及 **56 根骨骼 / 1800 of 11283 顶点（16.0%）**，其中 **49 根是物理段**。后果：胸部一转 ψ，绘制与仿真就差 `2sin(ψ/2)×0.240` 格（20°→0.083、30°→0.124 格），尾尖被胸刚性带走可达 0.42 格——这正是双参规则当初要修的问题（"裙子挂在胸上，胸部一扭整条裙子被甩离髋部，任何物理调参都补偿不了"），但它在网格侧**从未生效**。

修法：新增单一决策点 `EFMeshJsonWriter.bakedJointId(Bone, YSMGeoModel)`，让两侧调用**同一个函数**；`YsmExtraFrameWriter` 的采样动画关节同步对齐（其模板缓存键含关节号，不会复用旧模板）。不一致降到 **0 根 / 0 顶点**。

**根因 C（P2，同一家族）：面板之间没有耦合。** `YsmPhysicsParts.wireNeighbours()` 明确排除父子对，而裙子面板恰恰是 `FR -> FR1 -> FR2` 这种父子链，所以同一片裙子的面板之间零耦合。已修：`sewnTogether` 现在包含父子对，耦合强度从帧率相关的固定 0.25/帧改成时间基 `1 - 2^(-dt/0.03)`。

**被证伪的假设（记录下来，避免重复）**：Lead 最初怀疑"bind 枢轴与网格坐标系错位"。用真实网格 11283 个顶点逐段比对后**自行证伪**：Head/UpperBody 的枢轴-质心偏差（0.441/0.306）与尾骨同量级，说明枢轴本来就在正确框架里；正确判据是 `bindWorld × pivot × scale`，与日志 `L` 列逐位吻合（最差 0.0004）。**上翘狐尾的 "rest 朝上" 是模型形状，不是 bug**——但尾尖三段力臂仅 0.021–0.046 格，是**倒立摆**，其静息角由链条额度而非重力决定。

**缓存代际**：`ManifestStore.GENERATOR_VERSION` 13 → 14（物理段重写）→ **15**（网格关节修复）。不升号则旧网格按 hash 被信任、上述修复对已转换的模型静默无效——第十三轮已经因同类问题吃过一次亏。

**验证**：`gradlew cleanTest test build` → **304 tests / 38 suites / 0 failures / 0 errors / 4 skipped**（第十三轮基线 297/36/0）。两个根因各有一条**"修复前必红"**的回归测试：
- 链式复合：回退旧写法后 `everyCompositionLevelAddsExactlyOneLinkRotation` 报 `link 1 ... m30 expected -0.16903356 but was -0.18797776`；
- 绘制关节：修复前 `theMeshBakeAndTheRuntimeTableAgreeOnTheJoint` 报 `FM1: the mesh bakes joint 8 while the runtime bone table carries joint 7 ... expected: <7> but was: <8>`。

**仍未修（已知余留）**：`chainLimitFor` 的链条额度过**均分**——尾尖三段力臂只占 11.8%，却吃掉 42.9% 的额度（它们力气小、总位移仅 28 mm）。数值分析显示 120° 上限本身是必需的形状闸、**不该下调**，正确做法是改为力臂×质量加权分配（公式见 `tmp_verify/T7_findings.md` §D3）。**未验证**：未跑游戏（画面观感无人看过）、重转换管线未实机确认、面板间隙的精确基线待重跑。游戏内判据见 `VERIFICATION_physics_round14.md`。

#### 第十三轮：让动态骨骼成为真物理，并把"物理部件"改成作者声明驱动

症状（现场日志 `logs/latest.log`，模型 `wine_fox/01_taisho_maid`）：拆分出来的带骨骼网格已经能绑在正确位置，但**没有真正的物理运动**——像硬片一样随剧烈动作甩飞又瞬间归位。

**根因（三条，都已在代码与日志里定位）：**

1. **静止角被钳死。** `YsmDynamicBoneSolver.EXTERNAL_AUTHORITY` 把所有外部力（重力＋枢轴伪力＋离心＋欧拉＋空气）的总和钳到弹簧自身权重的 0.3 倍；对默认 2.36 Hz 那是 66 rad/s²，而重力项 `(d×g)/L` 在 L≈0.08–0.13 上是 186–308 rad/s²，恒定饱和。日志证据：角色站立不动时 `max swing=40.0deg, max displacement=0.088 blocks` 在 17760 帧里**逐帧常数不变**，`LongHair2` 卡 `40.0/40.0`、`Tail` 卡 `20.0/20.0`。另有一个独立佐证：旧代码下 22 块裙板在 5 格/s 时的角度全部恰好 `asin(0.3)=17.46°`、spread 0.000。
2. **作者物理被当作关键帧回放。** `YsmMeshSecondaryMotion.apply()` 里只要 `Source.AUTHORED` 就整条跳过求解器，改走 `applyAuthored()` 把 Molang 表达式算出的角度直接写成部件变换。YSM 的"物理"只有一阶 lerp ＋ 二阶弹簧这些标量滤波器，没有力臂、惯量、质量、碰撞（见 `OpenYSM_physics_survey.md` 的源码调查），所以这条路本质上就是"硬片跟着表达式甩"。
3. **部件识别退化。** 物理部件本应由模型自己的动画控制器声明，但代码只读模型自带的 controller；内置模型依赖的是 YSM 全局默认控制器（`misc/4_default_controllers` 的 `player.pre_parallel_0` → `Hair_Physics`），于是所有模型都退化成按骨骼名猜——`Tail4..Tail7` 被选成部件却**从不运动**（`axis=(0,0,0)`），因为链条角度预算被祖先吃光、额度为 0。

**改动：**

- `YsmDynamicBoneSolver`：删掉外部力硬钳位（`EXTERNAL_AUTHORITY`/`REFERENCE_ANGLE`/`DRAG_AUTHORITY`），静止角回到解析平衡 `Lω²sinθ = g·sin(φ−θ)`；限位从"每帧硬拽 ＋ 角速度×0.25"改成**约束投影**（去掉外向分速并保留 15% 恢复系数，切向速度完整保留）；惯量 `I = mL²` 显式化，力臂真正进入响应（长部件更慢）；子步规则改用总恢复刚度 `√(ω²+g/L)`。实测：旧求解器有 8 件恰好停在 `asin(0.3)=17.4576°`（正是日志里报 17.4/17.5 的那 8 件），新求解器 16/16 等于独立二分法解出的平衡角。
- `YsmMeshSecondaryMotion`：`AUTHORED` 不再回放关键帧，**所有部件一律走求解器**；作者物理动画降级为"骨骼清单 ＋ 每骨骼弹簧参数"来源。
- `YsmPhysicsParts`：链条额度改为随关节数缩放并保留每关节下限——`chainLimitFor = min(limit×关节数, max(15°×关节数, 120°))`，7 关节马尾从 **0.0°/8.6° 每节变成 17.1°/节**，2 关节件与改动前逐位一致（回归保护）；新增"几何必须属于自己"与"mapped 骨骼不许成为部件"两条判据；碰撞半径改为垂直于悬挂轴的厚度中位数。
- `YsmPhysicsBinding`：候选动画＝模型自己的 controller ＋ 模型 `parallel*` 族 ＋ **YSM 内置默认控制器集**（fail-closed、读不到打日志）；并增加**交叉验证**——候选动画必须真的驱动至少一根"该模型自身或子树带几何体"的骨骼，否则拒绝并记录原因。这一条直接避免了一次 59→0 的回归（强选 `Hair_Physics` 会让 `01_taisho_maid` 一块都不动）。
- `YsmPhysicsChains`：新增"链必须携带属于自己的几何"判据。
- 两个**配置死键真正接通**：`secondaryMotionGravityAcceleration` 与 `secondaryMotionAirDrag` 此前只被日志读取，现在进入求解器（`airDrag` 的实测调参表见配置文件注释：真实裙板 5 格/s 下 0.9 → 22/22 件超 20° 根部限位、最差 54.8°；0.2 → 0/22）。
- `ManifestStore.GENERATOR_VERSION` 13 → 14。**这一步是必须的**：磁盘 `manifest.json` 里 `"generator":13` 与旧常量相等，已转换的模型会继续复用旧产物，新的 `"physics"` 段永不写出——用户装上新 jar 会看到"改动完全没生效"（本机 83 个运行时 JSON 全部 `with-physics=0`）。缓存代际一变，模型会重新转换。

**验证：** `gradlew cleanTest test build` → **297 tests / 36 suites / 0 failures / 0 errors / 4 skipped**（改动前基线 225/31/0）；独立对抗性验收套件 26 条，7 个 mutant 全部能变红。数值证据与调参表见 `tmp_verify/` 下 T1–T5 报告。

**未验证（只能进游戏）：** 画面是否真的"自然下垂/跑动摆动"（本套件无渲染能力）；重转后 `physics` 段是否真的出现；`airDrag` 观感取舍；服务端行为。游戏内判据见 `tmp_verify/T5_verification_report.md` §9。

#### A 最后一步的施工图（下一轮从这里开始，锚点已核实）

现状：滤波器（`YsmSecondOrder` + `Bank`，与参考实现逐位一致）与求值环境（`YsmPhysicsMolangEnv`，`second_order`/头部角度/三种速度/`v.*`）都已就绪，**214 测试 0 失败**。剩下的三件事，按依赖顺序：

1. **`ysm.bone_rot(name).x/.y/.z` 的向量成员访问**（链式物理的前提）。我们的 `Molang` 是纯标量求值器，函数只能返回一个 double，而作者的链式写法是 `ysm.second_order('B1x增量', ysm.bone_rot('BackHairB1').x, 2, 0.5, 0.2)`。**不要**用文本改写（`bone_rot_x`）绕过：那会让作者写的表达式与我们的解释产生分歧，而 A 的全部价值就是"数值上与 YSM 一致"。正确做法是在解析器的调用分支（`Molang` 里 `anyString`/`exprArgs` 那两段，约 600–630 行）之后接一个后缀 `.x/.y/.z` 处理：读到成员名就记录分量下标（x=0,y=1,z=2），并把 env 调用换成新的 `callVectorFunction(name, strings, numbers, count, component)`；未跟成员的向量调用返回 x 分量（YSM 里作者从不这样用）。`Env` 用 default 方法给出旧行为，保证既有环境零影响。
2. **`YsmPhysicsMolangEnv` 增加 `ysm.bone_rot`**：由调用方每帧填入一张 `名字 -> 已算出的骨骼旋转（度、骨骼局部、X/Y 反号，与 YSM 的 `BoneRotation` 一致）` 表。**顺序是关键**：YSM 是同一次求值里"先算的骨骼供后面的表达式读取"，所以图层必须按动画里 bone 的出现顺序单遍推进，而不是并行或两遍。
3. **`YsmPhysicsLayer`（新类）+ 接入渲染路径**：
   - 每模型持有它那份物理动画（`YsmPhysicsBinding` 已经能发现"哪份动画是物理动画"，`YsmPhysicsParts.Source.AUTHORED` 就是判据）；
   - 每帧：`filters.update(dt)` → 跑 timeline 变量（`v.HP_x=v.HP_x_0-v.HP_x_1` 这类，靠 `animation_length: 0.0101` 每帧一次）→ 按顺序对每根骨骼求值 `rotation`/`position` 表达式 → 填 (2) 的表；
   - 把结果写成 per-part `partTransform`：**现成机制直接用** —— `YsmMeshSecondaryMotion` 里已经有 `T(bindPivot)·R·T(-bindPivot)` + `bindSwingOf` 共轭，作者给的是**骨骼局部度数**，而我们写的是模型空间旋转，所以照旧要过一遍 `deformation` 共轭；
   - 判据：`parts.source() == AUTHORED`（模型声明了物理动画）走新路，否则（如 `01_taisho_maid`）**回落**到自研摆锤。两条路互斥，因为都写同一批 part 的 transform。
   - 验证：用 `src/test/resources/golden/physics/hair_physics.animation.json` 当夹具 —— 断言"改变 `q.vertical_speed` 后，`BackHairA1` 的旋转以滤波器应有的滞后跟上"、`v.HP_x_x` 链式增量确实把父骨增量喂给了子骨、以及静止输入时整层输出恒为 0（不动任何骨骼）。

#### 第十一轮（A 第一步续）：让作者的表达式真的能碰到它自己的滤波器

上一轮落地了 `YsmSecondOrder`（滤波器）与 `Bank`（每实体一池）。这一轮补上"表达式怎么调到它"：

**1. `Molang` 的一处真实缺口：带字符串的调用会丢数字。** 解析器早就支持字符串实参，但走的是 `callStringFunction(name, String[])` —— 只给字符串、**数字实参解析完就丢掉**。而作者的物理表达式恰恰是混合的：
`ysm.second_order('头发垂直', math.clamp(-5*q.vertical_speed+v.hv,-10,150), 1.5, 0.6, 0)` —— 名字在实参 0，输入在 1，频率/系数/response 在 2/3/4。于是给 `Env` 加了：

- `wantsMixedArguments()`：**默认 false**，这一条是兼容性的关键 —— 不想要的环境行为与以前逐字一致，连数字实参都**不求值**（表达式可能带赋值副作用，替一个用不到它的环境求值属于行为变更而不是新增）。
- `callMixedFunction(name, strings, numbers, count)`：`strings[i]` 为 null 表示该位是表达式、`numbers[i]` 为 0 表示该位是字面量；重用的是每线程 scratch（与既有 `ARG_SLOTS` 同一套路，注释写明只在调用期间有效）。

**2. `YsmPhysicsMolangEnv`：物理动画求值所面对的那个环境。** 词表不是猜的，是从真实 `Hair_Physics` 里挑出来的：`ysm.second_order`、`ysm.head_yaw/head_pitch`、`q.ground_speed/vertical_speed/yaw_speed`、`v.*`。三处容易错、写进注释：

- `q.yaw_speed` 是**度/秒**，`q.ground_speed/vertical_speed` 是**格/秒**（即每 tick 位移 ×20）；
- 头部的 yaw/pitch 取自**头部动画本身**而不是身体朝向 —— 作者用它防穿模，所以它必须领先身体而不是跟随；
- `q.yaw_speed` 同样取**跨 tick 的差值**（`getYRot()-yRotO` ×20），理由与求解器的转向驱动一致：yaw 是按 tick 量化的，逐帧差分出来的是台阶。

**新增测试**（`YsmSecondOrderOracleTest` 扩到 8 条）：作者表达式确实摸到了自己的滤波器（首次原样返回、两个名字两个滤波器、收敛后读到的是输出而非输入）、混合调用的数字是**求值后的值**、无名调用不建状态槽也不抛异常、未知函数返回 0、动画变量往返（`v.HP_x_0=…; v.HP_x=v.HP_x_0-v.HP_x_1;` 这条链式惯用法）。

**214 条测试、0 失败**，jar 已安装。

**A 只剩最后一步**：战斗模式下把这份 parallel 物理层求值出来并叠加到 EF 姿势上（接入点就是现在写 per-part `partTransform` 的地方），并补上 `ysm.bone_rot(name)` + timeline 增量，让链式物理动画也能跑。

#### 第十一轮（开始做 A）：把 YSM 的二阶滤波器原样移植进来，并用"与参考实现逐位一致"来定义"忠实"

A 的目标是：**有作者物理动画的模型，直接跑作者写的那份动画**，而不是我们自己发明一套摆锤。第一块地基是 YSM 的全部物理本体——一个一维标量二阶滤波器：

- `YsmSecondOrder`：逐字移植 `OpenYSM` 的 `SecondOrder`（k1/k2/k3、`inputDot` **每次调用只算一次**而不是每子步重算、`ceil(dt/(sqrt(4k2+k1²)−k1))` 子步、作者频率限幅 0–5Hz、系数限幅 0–1、response 不限幅）。唯一刻意的差别是**子步数封顶 256**（YSM 没有上限，两秒的卡顿会变成两秒的子步；我们的调用方本来就会把 dt 限到 0.05，所以这个上限只在卡顿时生效，代价是那一帧略硬）。
- `YsmSecondOrder.Bank`：每实体一池、按作者写的**名字**（内化 id）分槽，形状与 YSM 的 `PhysicsManager` 一致；**首次出现的 key 原样返回输入**（YSM 的防抽筋保护，否则新加载的模型上每片布都会从自身旋转原点跳到位）；`update(dt)` 每帧推进一次，`clear()` 清空。
- **忠实性是测量出来的，不是声称的**：新增 `YsmSecondOrderOracleTest`，把参考实现 `SecondOrder` 原样抄进测试当 oracle（只把 `Mth.clamp`/`Mth.PI` 就地展开，注释里写明出处），用作者表达式真实会产生的随机游走输入序列，跨 8 组参数 × 4 种帧长 × 300 帧比对，**要求逐位相等（tolerance = 0.0）**。滤波器是递推式，差在第一位就会在第 100 帧放大——所以"能对上"必须是精确相等才有意义。另有 clamp 行为、静止输入必须静止、dt=0/NaN 必须保持不动、以及 bank 的首次返回/分槽独立/每帧只推进一次/清空后像新的一样。

**209 条测试、0 失败**，jar 已安装。

**A 剩下的两步（下一步做）**：① 把 `ysm.second_order`、`ysm.bone_rot`、`ysm.head_pitch/yaw`、`q.ground_speed/vertical_speed/yaw_speed`、`v.*`、timeline 变量接进我们的 `Molang.Env`（`callStringFunction` 就是 `second_order('名字', …)` 的入口）；②战斗模式下在 EF 姿势之上叠加这份 parallel 物理层的骨骼旋转——写 per-part `partTransform` 的地方正好是现成的接入点，所以是**替换**而非并存：有作者物理的模型走新路，没有的（如 `01_taisho_maid`）才回落到自研摆锤。

#### 第十轮：读 YSM 自己的源码（参考/OpenYSM），照搬它的步长规则；并说清"YSM 的物理到底是什么"

读完 `参考\OpenYSM` 后有三件事必须写下来，因为它们改变了后面该怎么做：

1. **YSM 的物理是"作者写的表达式 + 一个二阶滤波器"，不是刚体仿真。** `SecondOrder.java` 是一个**一维标量**二阶系统（k1=coef/(πf)、k2=1/(2πf)²、k3=response·coef/(2πf)，半隐式欧拉，并把**输入的变化率**通过 k3 前馈），`SecondOrderFunction.java` 把它暴露成 `ysm.second_order('名字', 输入, 频率, 系数, response)`，状态按**字符串名字**存在每实体的 `PhysicsManager` 里，每渲染帧推进一次（`dt=ΔrenderTicks/20`）。**没有质量、没有力臂、没有重力、没有碰撞、没有链**——这些在 YSM 里根本不存在。
2. **作者才是物理的驱动源。** 真实的 `Hair_Physics` 动画里：链的根用身体运动量驱动（`q.ground_speed`、`q.vertical_speed`、`q.yaw_speed`、`ysm.head_pitch`、作者自己的 `v.hv/v.hg`）；**下一节用"上一节骨骼旋转的逐帧增量"驱动**（`v.HP_x = v.HP_x_0 - v.HP_x_1`，靠 `animation_length: 0.0101` 的 timeline 每帧算一次），再往下一节会**减去根增量的一部分**以免链过度累加；防穿模也是作者手写的 clamp（`ysm.head_pitch>-10 ? ... : -10`）。也就是说：**"哪些骨头是物理骨、参数多少、怎么串起来"这件事本身就是那份动画**——这正是你最早说的"借用 YSM 的动画控制器去找物理部件"。
3. **这套模型（`wine_fox/01_taisho_maid`）在 YSM 里没有物理动画**（日志原话：`this model declares no physics animation`）。**也就是说在 YSM 本体里它的裙子是纯跟随动画、不摆动的**；本模组给它加的物理是**我们自己发明的**，不存在"和 YSM 一致"这回事。有 `Hair_Physics` 的模型（如 wine fox 系列）才是有作者调好的物理的。

**本轮照搬的（小、稳、可测）**：

- **积分步长不再固定 3 段**，改用 YSM `SecondOrder#update` 的稳定性判据——`stable = sqrt(4k2 + k1²) − k1`，`k1=2ζ/ω_n`、`k2=1/ω_n²`，再 `substeps = ceil(dt/stable)`（上限 16）。固定段数不可能对所有部件都对：作者频率可写 1–5 Hz，5 Hz 的稳定步长只有 2 Hz 的三分之一。测试 `aStiffSpringOnALongFrameStaysStable`（5/2.36/1 Hz × ζ=0/0.3/1.0 共 9 组，每帧最长步长 0.05s 并持续抽动枢轴，200 帧内全程有限、不越限、方向仍是单位向量）。
- **转向（yaw）驱动**：这是求解器里唯一来自身体**转动**而非平移的量，也是 YSM 作者第一个会用的量（`q.yaw_speed`）。在身体随动坐标系里，挂在离竖轴水平距离 r 处的布片受到两个惯性力：离心 `ω²r`（永远朝外）与欧拉 `α×r`（急转时更大）。二者与重力同量纲地加入 `gravity`，因此同样受 `EXTERNAL_AUTHORITY` 约束。**转速取自 `yRot - yRotO`（跨一个 tick 的差值）而不是逐帧差分**——这正是"台阶"问题的解药：跨 tick 的差恰是该 tick 的平均角速度，也正是作者写 `q.yaw_speed` 时用的量；角加速度再用与枢轴相同的 50ms 一阶平均滤一次，并在求解器里限幅（±12 rad/s、±60 rad/s²）。测试 `aTurningBodySwingsTheClothOutward`（转 5 rad/s 必须把布片推向**外侧**、静止必须一动不动、方向符号要对）与 `aSnapTurnSwingsTheClothSideways`（急转的欧拉项把布片推向切向）。

**202 条测试、0 失败**，jar 已安装。

#### 第九轮（我上一轮引入的回归）：一侧裙摆跑到正面——"只从 chainRoot 取部件"把裙子选成了半边

你的描述"**一侧的裙摆还行了，另一侧跑到了正面**"直接指向"左右不对称"，而上一轮的日志正好自证了这件事——把 `bones of` 那行和上一份日志对比：

```
上一轮：RB3, RB2, FL1, FL2, RB, RF, RF3, RF2, RM, RM2, RM3, LongRightHair×2, FM2, BL, BL3, BL2, FM1, BM, BM2, BM3, BR, BR3, BR2   ← 24 根，左右都在
本轮  ：RB3, RB2, RB3, FL1, FL2, FM2, FM1, FM2, FFM1, FFM1_1, FFM2, FFM2_1, FFM3, FFM3_1, LM2, LM3                        ← 16 根，右后只剩两根，左中/前中整片在
```

**右侧的 `RB`、`RF*`、`RM*` 和整条 `B*` 全都不在名单里，而左侧的 `LM*`、`FL*`、`FFM*` 都在。** 于是"被模拟的那半边在摆、没被模拟的那半边钉死在姿势上"——屏上就是"一侧还行，另一侧被拖到了别处（看起来跑到正面）"。这正是我上一轮为了"整片丢弃而不是切断"新加的那行代码造成的：

```java
if (!chain.chainRoot()) continue;   // ← 回归
```

我当时的假设是"`chainRoot` 就是一片的顶端"。**它不是。** `YsmPhysicsChains.isChainRoot` 问的是"我上面那根骨头的名字像不像会垂下来的东西"，它决定的是**这一片该按根部 20° 还是按下摆 60° 限位**，跟"部件从哪里开始"没有关系。于是：

- `RB` 的父骨是 `RightClothe`（"clothe" 命中提示词）→ `chainRoot=false` → **被我的 `continue` 丢掉**；
- `RB2`/`RB3` 的父骨叫 `RB2`/`RB`（不命中）→ `chainRoot=true` → 留下来；
- 结果选出来的不是"裙子"，而是"父骨名字恰好不像布料的那几根"。**由作者的命名拼写决定哪半边裙子有物理。**

顺带暴露出第二个问题（同一段代码里）：日志里 `RB3` 和 `FM2` **各出现两次**。因为 `RB3`、`RB2`、`RB` 三个都可以是候选（各自问自己的父骨），而每个候选都会收集"自己所在的整片"，所以 `RB2` 的片包含 `RB3`、`RB` 的片又包含两者。原来这些收集都写进**同一个** `selected` 列表，靠 `out.contains(i)` 天然去重；我改成先收集进局部 `piece` 再 `addAll`，去重就丢了——同一根骨头进段表两次，两条都往同一个 mesh part 写 delta（后写的赢），还双双占掉骨骼上限。

**修复：**

1. **`YsmPhysicsParts.selectBones`（新方法，独立可测）**：对**每一个**候选都收集整片，并在合并前 `piece.removeIf(selected::contains)` —— 骨头只模拟一次；上限仍然按"整片进/整片出"执行，并计数上报。
2. **`YsmPhysicsChains.build` 不再按 `maxChains` 截断候选表。** 以前候选表也被截到 24，于是"前 24 个候选（按骨骼表顺序）"就悄悄成了"存在的部件集合"：在一条二十几根骨头的裙子上，这一刀正好落在裙子中间，**哪些片有物理由骨骼顺序决定——作者没选过，看日志的人也看不出来**。真正约束开销的是"可模拟骨骼数"，现在只由 `selectBones` 按整片执行；候选表只留一个病态表的后备上限。
3. 新增 `YsmPhysicsSelectionTest`（4 条，用与实机同形的骨骼表，并且刻意把右侧写成叶子在前、左侧写成根在前，与实机一致）：
   - 父骨叫 `RightClothe` 的面板顶端**必须**被选中；
   - 同一根骨头**只能被选中一次**（这正是重复段的来源）；
   - 上限**整片丢弃**并计数，左侧三根骨的片要么全进要么全出；
   - 没有下垂部件的模型选不出东西、也不抛异常。

**测试：199 条、0 失败**（上一轮是 195 条）。jar 已替换安装。

**还需要你做一件事**：把配置里的 `secondaryMotionMaxChains` 从 **24 改成 96**（新默认值；24 是旧默认）。这套模型的下垂部件超过 24 根骨头，上限一到就会整片丢弃，日志里会出现 `left out of the simulation by secondaryMotionMaxChains=24` 的警告——那条警告就是"某些裙片完全不动"的原因。

**仍未验证**：这一轮同样是编译 + 单元测试 + 磁盘产物（运行期 JSON 的骨骼表/关节、mesh JSON 的 parts）三方一致，**没有游戏内视觉确认**。另外要分清两种现象，它们要分开修：

- **站着不动也在摆/偏**：那是受力问题（上一轮修的 tick 阶梯），把新日志的 `own` 角度发我即可判断。
- **只有在移动时整片向后/向外飘**：那是空气阻力驱动的稳定偏角（每个关节最多 `asin(0.3)≈17.5°`，三节链会累加到 40–50°）。这是当前模型的**固有行为**而不是 bug，但如果你觉得幅度太大，我可以把"外力上限"从按关节改成按整条链共享。

#### 第八轮（你补充的描述定位）：面板"悬浮在周围空间"的真因——求解器把姿势的"台阶"读成了加速度

你这轮的描述是关键的一句：**"裙片以片状存在，与裙摆不相连，悬浮在周边的空间中"**，而且**站着不动也有**。
"站着不动也有"就把动力学全部排除了——静止时重力力矩≈0、身体速度=0，任何"力"的调参都不该让裙子长期偏着。于是回去看上一份日志的**数字本身**：

```
RM(root) 18.8deg  FL1(root) 20.0deg  RB(root) 20.0deg  FM1(root) 18.0deg  BR 14.4deg ...
max swing = 18.8 ~ 20.0deg，持续保持在 20/24 根骨骼"在动"
```

**这个 18–20° 不是巧合，它是 `EXTERNAL_AUTHORITY = 0.3` 的饱和平衡角**：外力被上限截断后，弹簧的平衡位置是 `asin(0.3) = 17.5°`，再被 20° 的根部限位夹住。也就是说：**有一股方向在逐帧翻转的力，长期把每一片都顶到了上限附近**，每片方向还都不一样——屏上就是"一圈互不相连、悬浮在身周的裙片"。

**这股力是什么：把 tick 的台阶当成加速度来做二阶差分。**

- EpicFight 的姿势数组是按 **tick（20Hz）** 更新的，而求解器按**帧**（日志里 dt=2~10ms）跑。
- 于是枢轴坐标是**阶梯信号**：连着几帧不变，然后"跳"一下。对台阶做一阶差分，速度在跳变帧上是真值的数倍、其余帧是 0；做二阶差分，加速度在跳变帧上巨大、下一帧同样巨大但反号——**逐帧翻转的锤子**。
- 这个值被 `MAX_PIVOT_ACCEL=120` 截断后是重力(24)的 **5 倍**；而 `EXTERNAL_AUTHORITY` 又把"所有非弹簧力之和"限制在 `0.3·ω_n²`，所以受力方向虽然乱，幅度却恒定——正好把每片稳定压在 17.5° 附近。
- 更糟的是**竖直分量能翻转重力**：`gravity.y = -24 - accelY`，当枢轴向下加速（起跳/下落/落地，或台阶跳变的负向尖峰）`accelY = -120` 时，有效重力变成 **+96，向上的 4 倍重力**——这就是前面几轮"布片飞起"的来源。布料会往上飞，不是"甩"。

**修复：**

1. **先平均，再差分**（`PIVOT_SMOOTHING_SECONDS = 0.05`）。把枢轴坐标按 50ms 时间常数做指数平均后再求速度/加速度：长于一个 tick、短于任何值得展示的身体动作（一次出拳给肩部加速约 0.1s）。**输入的量化是游戏循环的性质，不是布料的性质**，所以物理不应该对它敏感。
2. **竖直分量单独设界**（`MAX_GRAVITY_CANCELLATION = 0.9`）。枢轴的竖直加速度最多抵消 90% 的重力：自由落体里布料失重漂浮是对的、保留，但**任何情况下都不允许把重力反向**——本求解器里没有"裙子自己升空"这种结果。
3. **链预算与关节预算分开**（`YsmPhysicsParts.chainAllowance`）。原来把根部的 20° 限额当成整条链的预算往下传，于是日志里 `RM 18.8° → RM2 2.0° → RM3 0.0°`：面板在腰部折断、下面整段僵直。现在每个关节有自己的限额（根部 20°、下摆 60°），整片受 60° 约束；日志新增 `own/whole/allowed` 三列，可直接看出"这一段转了多少 / 整片累计转了多少 / 本帧最多能转多少"。
4. **枢轴的空间顺序错了：缩放必须在骨骼链之后**（`YsmPhysicsParts.pivotInMeshSpace`）。写网格时顶点是 `scale × (bindWorld × corner)`——链在模型自身单位里算，最后整体缩放一次；物理枢轴也必须同序。原来是 `bindWorld × (scale × pivot)`，只要链上有任何一根骨骼带静止旋转，`T(p)RT(-p)` 的平移就会被多缩一次。实测这套模型：右手边面板偏 **5.6cm**、后中面板偏 **0.9cm**——不是整体平移，而是**每片各偏各的**，腰口那圈挂点被拉成一条条不同的曲线，也就是"接缝裂开"。
5. **碰撞的每帧步长上限**：`COLLISION_STEP_FRACTION` 原本是"每次迭代"的上限，而循环最多 8 次，等于一帧可以转 2 倍限位。现在整次调用共用一个预算；`hit=` 也改成报告"质心实际走过的弧长"，而不是"历次修正量之和"（旧口径会把同一段修正按迭代次数放大，日志里那个 0.16 格就是这么来的——远大于该片整段摆动的 0.05 格）。
6. **`secondaryMotionMaxChains` 截断不再切碎部件**：原来按骨骼数截前 N 个，会把跨在截断处的部件劈成"一半模拟、一半僵直"（同一动作两半回答不同）。现在整片丢弃并**打警告**（日志里会写明丢了几片、该把配置调多大）。你的配置里还是旧默认 24，新默认是 96。
7. **诊断加强**：每段现在打印摆动的**轴**（模型空间）与**静止方向**。角度只能说明"转了多大"，说明不了"绕哪转"——绕自身悬挂方向摆（轴水平、垂直于静止方向）= 布料在飘；绕身体竖轴转 = 被拧着走。这两种在旧日志里数字一模一样，看不出来。

**测试：`gradlew clean build` → 195 条，0 失败。** 新增：

- `YsmDynamicBoneSolverTest#howThePoseArrivesDoesNotChangeHowTheClothBehaves`：同一个 2cm、1Hz 的怠速摆动，分别以"按 tick 阶梯给"和"逐帧精确给"送入，两者必须一致（修复前阶梯那份会被顶到 17.5°）。写成不变量而不是阈值，是因为被违反的正是这条不变量。
- `aRealBodyAccelerationStillSwingsThePiece`：真实身体加速度仍然要能甩起布料（否则等于把物理关了）。
- `aFallingPivotNeverLiftsThePiece`：枢轴以 400 格/s² 下坠时方向始终朝下——布料不许爬升。
- `theCollisionStepIsAFrameBudgetNotAnIterationBudget`：单次调用转过的角度不超过每帧预算。
- `YsmPhysicsPivotSpaceTest`：悬挂点在自身摆动下**必须不动**（24 片全查）；5.6cm / 0.9cm 的实测量；无旋转链上两种顺序等价（不能误伤多数模型）。

**未验证的部分（照旧要说清）**：以上全部是编译 + 单元测试 + 磁盘上产物（`ysm_runtime/*.json` 的 `joint`/`scale`、mesh JSON 的 parts/joint）三方一致的结论，**没有任何一条经过游戏内视觉确认**。请再跑一次：站着别动，看日志里各片的 `own` 角度——如果修复生效，怠速时应该接近 0（而不是现在的 18–20），并且 `axis` 应该接近水平、垂直于 `rest`。

#### 第七轮（用户定位）：裙子绑到了上半身 chest，而且是缓存导致的"改了没生效"

你的判断是对的。把模型的祖先链打出来：

```
FM1 → 8 Chest   ← FM ← FrontClothe ← clothe ← UpBody [MAPPED here]
RM  → 8 Chest   ← RightClothe ← clothe ← UpBody [MAPPED here]
BM  → 8 Chest   ← BackClothe  ← clothe ← UpBody [MAPPED here]
```

**24 片裙骨全部绑定到 joint 8（Chest，上半身）**，没有一个绑到 hips（joint 7）。原因是 `YSMJointMapper` 的映射只能按名字往上找最近的可映射祖先，而作者把服装容器 `clothe` 放在了 `UpBody` 下面。

- YSM 自己不在乎：它直接播放 `clothe` 的动画，裙子跟着动画走。
- 但本模组把每个顶点刚性绑定到**一个** EF 关节；容器动画一旦丢失，整条裙子就变成"焊在胸口上"。而 EpicFight 的攻击动画会大幅扭转胸腔——**焊在胸口的裙子每次挥砍都被甩离髋部**。
- **物理再好也救不了**：它被要求跟随的姿势本身就是错误身体部位的。这解释了前面六轮为什么全部收效有限。

**修复（`YSMJointMapper.resolveJointId(bone, model)`）：**

- 仅对**非直接映射**的骨骼生效——直接映射的骨骼是身体部位本身，作者说了算（`UpBody`/`UpperBody`/`Elytra`/手臂全部不受影响）。
- 用**几何**判断：比较该骨骼自身几何高度与它所继承关节的那根映射骨骼的几何高度；不高于对方上方 `GARMENT_ABOVE_MARGIN`（0.25 格）就改判为 Torso。
- 为什么是"容差"而不是"阈值"：实测这套模型的**腰就在 chest 骨骼的高度上**（`UpBody` pivot 1.2313，映射到 Torso 的 `DownBody` 也是 1.2313），裙子最上一排几何中心 1.2957、chest 几何中心 1.2483，只差 0.05 格。要求"明显下垂"会把最上一排和全部容器留在胸口、只有下排改判——那不是修好，是把裙子撕成两半。
- 不用名字表：`clothe` 是这个模型取的名字，下一个模型叫 `qunzi`、`SkirtGroup` 或者什么都不叫。

#### 更重要的第二个发现：`GENERATOR_VERSION` 一直没升，所以前面的改动可能从未真正生效

`ManifestStore.GENERATOR_VERSION` 是"生成物格式版本"——不一致就整份重新转换。**它是 11，而磁盘上的 manifest 也确实写着 11**，也就是说：

- 我早先对 `EFMeshJsonWriter` 的改动（运行时 JSON 的 `physics` 段、`scale` 段）**对已经转换过的模型从未生效**——这也正是日志里一直写"from bone names（此模型没有声明物理动画）"的原因之一（这个模型确实没有，但换成有声明动画的模型也一样读不到）。
- 已升到 **12**，下次启动会整份重转，于是绑定修正、`physics` 段、`scale` 段会一起生效。

这条教训写进了 `GENERATOR_VERSION` 的注释里：改 `EFMeshJsonWriter` 就必须升它，忘了不会报错，只会"静默地什么都不发生"。

新增 `GarmentJointBindingTest`：24 片裙骨与 3 个容器都必须绑 hips；`UpBody`/`UpperBody`/`Elytra`/`ElytraLocator` 必须留在 chest；头/臂/腿不受影响；无 model 参数时退回原名字查找。

全量 `gradlew clean build`：**183 条测试、0 失败**。

### 中文

#### 第六轮：物理已经对了，缺的是"布料连续性"

上一轮的碰撞修正生效：`collision` 由 10 个球降到 **6 个**，且这次日志里 **24 根骨骼中 23 根的 `hit=0`**——碰撞已基本不参与。但逐段角度暴露出真正剩下的问题：

| 裙片 | 摆角 | 它的邻居 | 摆角 |
|---|---|---|---|
| `FM1` 正前中 | **20.0°** | `FL1` 左前 | 5.1° |
| `RM` 右中 | 12.1° | `BM` 后中 | 4.6° |
| `LongRightHair` | 17.3° | `BR` 右后 | 5.0° |

**同一件裙子上相邻裙片相差 15°。** 22 片各自是一个独立摆——力臂、静止方向、朝向都不同，所以对同一个身体运动给出不同答案；而它们是**彼此独立的网格部件、中间没有几何**，于是这个差值在屏上不是一条曲线，而是一道缺口。这就是"四散"最后的来源。

- **加入布料连续性（相邻耦合）。** 构建时按模型自身几何为每片找出"缝在一起"的邻居（枢轴相距 < 0.18 格、静止方向相差 < 40°、最多 4 个，且排除已经在链上耦合的父子）。求解后把每片的方向朝邻居均指向**拉**过去（默认 25%/帧）。
- **是"拉"而不是"约束"**：真正被推动的那片仍然带头，邻居跟着它走，而不是全部被抹平成平均值——否则裙子就只会整体动、不再响应局部受力。机制抽成可测的 `relaxDirection` 并加测试：一次拉动恰好消掉设定比例、绝不越过目标、反复拉动收敛、缺失邻居/零强度时完全不动。
- 邻居关系用**枢轴**而不是几何计算：需要一起动的是布料被缝住的地方，不是它垂到哪里。

全量 `gradlew clean build`：**177 条测试、0 失败**。

### 中文

#### 第五轮：碰撞球本来就长在裙子里面

上一轮的两个修复**确实生效了**，日志证实：所有 `chain` 从 60.0 变成 **20.0**，`max displacement` 从 **0.158 降到 0.055** 格。但 `max swing` 仍卡在 20.0° 上限，且单独一项 `FM1(root, 20.0deg, hit=0.282)` 说明有东西在持续推它。

把实测日志里的 10 个碰撞球与模型自身几何放在一起算，答案很直接：

```
FM1:  joint 4 距其静止质心 0.138 格，而它自己的摆幅需要 0.226 格才够
FL1:  joint 4  0.124 / 0.227      RM2:  joint 1  0.134 / 0.227
RF2:  joint 1  0.173 / 0.224      RB2:  joint 1  0.172 / 0.226   ...
```

**22 片裙里有 10 片的静止位置就落在腿部碰撞球内部或触手可及处**；另外 `RB` 的静止质心落在**右前臂**碰撞球（joint 12）内部——一个以髋部高度、前臂为中心、直径 0.2 格的球，长在裙子里。**球在布料里面，就不可能"管理"布料**：要么把它弹出去，要么推一帧、下一帧被角度上限拉回来，于是永久卡在上限上——这就是"飘飞"。

两条修正：

1. **手臂不再参与衣物碰撞。** 上臂/肘/前臂/手的球对布料不是支撑而是扫掠：角色一动胳膊就扫过裙子空间，接触即甩飞、下一帧又放手。保留躯干核心与**腿**（裙子真正需要避开的）。
2. **"在部件工作空间里"的体积一律跳过。** 判据从"静止质心落在球内"扩展为"体积离静止质心比「体积半径 + 部件自身摆幅」还近"——即该体积位于部件够得着的范围之内，两者不可能同时满足。抽成纯函数 `volumeIsInsideWorkspace` 并直接用真实模型 + 真实球体测试。
3. 顺带修正半径估计：把"到几何中心距离的分位数"从 0.35 降到 **0.15**。对四肢而言，这个分布由**长度**决定而非粗细，中位数几乎等于半条大腿；低分位才落在"肢体粗细"上，也就是布料碰撞体该近似的量。

**代价与取舍（明确说明）**：这套女仆裙的腿球既然长在裙子内部，跳过它们意味着**裙子不再与大腿碰撞**。这是有意的取舍——用"裙子不再被弹飞"换"裙子可能穿过腿"。若之后要两者兼得，正确的做法是把腿部碰撞体从**球**换成**胶囊**（沿大腿轴的一条细柱），而不是继续调半径。

新增 `MaidSkirtCollisionTest`（用日志里的真实球体与模型真实几何）：断言所有落在部件工作空间内的体积都会被跳过、且远离部件的体积（如头部）不会被跳过（防止这条规则把碰撞整个关掉）、以及手臂关节不在集合内。

全量 `gradlew clean build`：**172 条测试、0 失败**。

### 中文

#### 第四轮：上一轮的两个修复都被"中和"了（各有各的原因）

实测日志显示 `max swing` 仍是 60.0° 顶格、`max displacement` 仍是 0.158 格，与修复前**逐字相同**。查下来是两个独立的失效，都不是"力度不够"，而是**根本没生效**：

1. **root 仍然拿到 segment 的 60° 上限，因为上限算早了。** `YsmPhysicsParts` 在构建 draft 时就用"最近的**可能**成为段的祖先"决定上限，而真正装配时那个祖先（`FM`、`FL`、`FrontClothe` —— 没有可用几何的容器骨骼）会被丢弃，于是这些裙片**行为上是 root、上限却按 segment 算**。日志原文就是证据：`FM1(name,root,... 41.2deg/60.0chain)` —— 一个 root 跑在 60° 上限上，是它应有硬上限的三倍。现在上限在**父链最终确定之后**再算。
2. **碰撞是唯一绕开所有约束的写入者。** 它不是力，而是位置修正，直接改写 `direction`——因此既不受积分里的外力上限约束，也不受弹簧影响。日志里 `FM2 ... hit=0.112`：一帧内把质心推了 0.112 格（约 7 像素）；对一片贴着大腿的裙片，推力沿表面法线，方向就是**向上**——这正是"布片飞起"的字面来源。现在**每帧最多只允许转动自身上限的 1/4**，同一段重叠分几帧解完（不到 1/15 秒，肉眼不可见），读起来像布料从腿上滑开而不是被弹飞。

两条规则都抽成可测函数并加了回归测试（`YsmPhysicsPartsLimitTest`、`collisionCannotThrowAPieceItsWholeLimitInOneFrame`、`aBoundedCollisionStillResolvesTheOverlap`），因为它们失效时既不崩也不报错，只是让裙子多摆三倍——这一轮就是为此付的代价。

顺带核对：本机配置 `secondaryMotionMaxAngleRootDegrees = 20.0`、`secondaryMotionMaxAngleDegrees = 60.0` 均正常，所以问题不在配置。

全量 `gradlew clean build`：**169 条测试、0 失败**。

### 中文

#### 第三轮（实测日志定位）：真正的元凶是枢轴惯性力，不是空气阻力

上一轮我把上限加在空气阻力上，**修错了力**。你给的实测日志推翻了它：`max swing` 分布几乎没变（33.6° → 43.9°/54.3°/60.0°），说明阻力从来不是主导项。日志里还暴露了第二个问题：**每一片裙骨都显示 `60.0chain`**，而 root 本应是 20°。

**元凶是枢轴自身加速度产生的惯性力。** 它此前允许到 `MAX_PIVOT_ACCEL = 400` blocks/s²；在这套裙子 `L`≈0.14 的力臂上，那是 `400/0.14 ≈ 2800 rad/s²`，而弹簧能力只有 **220 rad/s²**——**十三倍**。走路时髋部每一步都在加速，于是每一片都被自己的惯性力顶到 clamp，方向各不相同。这就是"四散飞开"。

- **加入总上限 `EXTERNAL_AUTHORITY`（默认 0.3）**：**除弹簧以外**的所有力（重力＋惯性力＋空气阻力）之和，不得超过弹簧能力的 `0.3` 倍；弹簧本身不设上限，因此永远拥有最后发言权。`asin(0.3)` = 17.5°，刻意小于最紧的角度上限（root 默认 20°），所以**任何外力都不足以把部件顶到自己的上限**——这是"衣服必须留在模型上"的那一条规则。
- **上限按部件自己的角度上限缩放**：上限更紧的部件（更硬的根部）拿到更小的外力上限，保证外力永远够不到它的 clamp。
- `MAX_PIVOT_ACCEL` 400 → 120 blocks/s²：超过这个数按数据毛刺处理。
- **修掉 root 被放宽到 60° 的问题**：我上一轮加的"躯干例外"把每个裙片的 root 从 20° 抬到了 60°，而链预算又是从顶端继承的，等于整条裙子都被放宽。现在该例外**只放宽 segment，不放宽 root**。

#### 用你的明文源码离线复现（这次能双向验证）

`MaidSkirtCoherenceTest` 的样本就是你给的 `builtin/wine_fox/01_taisho_maid`：

- 从 `models/main.json` 量出 22 片裙骨真实的**悬挂方向**：只有 3–25° 偏离竖直——因此可以排除"重力把外扩裙摆压平"这一假设。
- 新增按**真实步频**驱动枢轴的测试（2 步/秒、髋部摆幅 0.18 格 → 峰值加速度约 40 blocks/s²，正是走路时真实存在的量级）。
- **把 `EXTERNAL_AUTHORITY` 改成 100（等于取消上限）后**：`walkingDoesNotPinThePanelsAtTheirLimits` 报 **16/22 被顶到上限**，`aRunDoesNotPinEveryPanelAtItsLimit` 报 **22/22**——即线上看到的现象在离线环境完整复现。改回 0.3 后两者均为 0。
- 另有：走路必须仍能看到布料运动、同一件裙子各片位移差 < 0.30 rad、静止时裙片必须垂下。

`aLongerPieceTurnsMoreSlowlyThanAShortOne` 与质量测试改为在**上限以下**的轻轻推动下测量，并注明：超过上限后力臂/质量不再起作用是**设计如此**，因为那时是上限在决定角度。

全量 `gradlew clean build`：**163 条测试、0 失败**。

### 中文

#### 根因找到并复现：空气阻力压过了弹簧，所有裙片被顶到各自的上限

前几轮一直在查坐标与变换——**那是错的方向**。真正的证据在实测日志里：

```
155 次采样：max swing 最小 33.6°、中位 45.2°、最大 60.0°
```

- **从不低于 33.6°**：静止时该收敛到 0 的东西一直维持在三十几度，说明这不是"在摆动"，而是**平衡**——被一个恒定力按住。
- **最大值正好 60.0°**：正是单段的夹角上限。

模型里唯一的恒力是空气阻力。算一下就清楚了（用这套女仆裙自己的数值：`L`≈0.1、`m`=0.56、2.36Hz → `ω_n²`=220）：

| 项 | 量级 |
|---|---|
| 弹簧的最大回复能力 | **220 rad/s²** |
| 我写的空气阻力（5 格/秒跑动） | **≈401 rad/s²** |

阻力比弹簧强约 1.8 倍，于是平衡点不是"弹簧能撑住的角度"，而是**上限**：24 片裙各自被顶到自己的极限，方向各不相同——这就是"四散飞开"。

- **修复：给阻力加上限 `DRAG_AUTHORITY`（默认 0.4，即最多占弹簧能力的 40%）。** 风可以把布料吹起来，但不允许压过弹簧。稳态拖尾角约 `asin(0.4)`≈24°，跑动仍有摆动，但最后说话的是弹簧，裙片因此保持在一起而不是各奔极限。

#### 用你给的明文模型源码做了离线复现

新增 `MaidSkirtCoherenceTest`，固定样本取自你提供的 `builtin/wine_fox/01_taisho_maid`：从 `models/main.json` 读 22 片裙骨的枢轴链与各自 cube（算出真实的力臂与质量），再以 10ms 帧长把求解器跑到稳态。

- `aRunDoesNotPinEveryPanelAtItsLimit`：跑动时不允许有任何裙片停在极限上。
- **把 `DRAG_AUTHORITY` 临时改成 100（等于取消上限）后该测试立刻失败：`22 of 22 panels settled onto their limit`**——缺陷在离线环境下被完整复现，且证据就是"全部 22 片都被顶到上限"。改回 0.4 后 0 片被顶。
- 另外三条：跑动仍要把布料吹起来（不能靠"关掉风"蒙混）、同一件裙子的各片拖尾角差要小于 0.25 rad（一致性）、身体静止时裙片必须垂下（<0.02 rad）。

`aHeavierPieceIsBlownAboutLess` 相应改为在**上限以下**的微风里测量质量效应，并注明：超过上限后质量不再起作用是**设计如此**，因为那时是上限在决定角度。

全量 `gradlew clean build`：**160 条测试、0 失败**。

### 中文

#### 第二轮：把变换本身钉死，并给整条链设上限

第一轮修完（碰撞不再越过上限、碰撞体收敛、重力换键）后实测：`max swing` 由 99.4° 降到 40.8°、碰撞半径由 0.42/0.36 降到 0.26、`gravity 24.0` 生效——三项都落地了，但裙片仍然散。于是改为**先把"每部件变换"本身证明掉**，再谈现象。

- **把变换抽成可测函数并证明它。** `buildSegmentDelta` 与 `bindSwingOf` 现在可测；新增 `YsmSegmentDeltaTest` 断言渲染真正执行的那条恒等式：
  `deformation × delta == T(P) × Q × T(-P) × deformation`（`P = deformation × bindPivot`），
  在任意刚体形变（含平移+旋转）与 40 组随机姿态、多个点位上成立。并单独断言 `Mr × S == Q × Mr` 与"从矩阵提取的旋转与点变换一致"——任何一处发生转置，这两条会立刻失败。共 8 条，全部通过。
- **整条链共用一个摆动预算（本轮唯一的实质性行为改动）。** 之前每根骨骼各按自己的上限夹紧，于是三段式裙片最坏可以是 `20° + 60° + 60° = 140°`：根部被held住而裙摆各开各的。现在子骨骼继承父链预算（`budget = min(自身上限, 父链预算)`），且只允许用掉父链**剩余**的额度，因此一条链整体不会超过它顶端被给的上限。对这套女仆裙（每片 root=20°）意味着整片最多偏 20°，而不是根部 20° + 摆 60°。
- **新增判定用日志：位移（blocks）。** 角度会说谎——同样 40°，两厘米的发丝是一毫米，前臂长的裙片是半个方块。汇总行现在给 `max displacement=N blocks`，逐骨骼详情给 `moved N blocks` 与 `Ndeg/Mchain`。这就是"抖动"与"散架"的分界线，也是下一轮唯一需要看的数字。

#### 排查过程中的两个教训（写给后来者）

- **本机的 Gradle up-to-date 判定不可信**：源码改了但 `test` 任务报 UP-TO-DATE，于是连续几轮都在读**同一份旧 XML 报告**；中间还有一次 `setColumn` 编译不过，`compileJava` 直接失败，旧报告同样被继续读。现在每次取结果前都会先删 `build/test-results`，并确认报告文件的时间戳。
- **第一版"证明"是错的，错在测试而不是产品代码**：测试把模型空间旋转直接当成绑定空间旋转传进 `buildSegmentDelta`，漏掉了 `bindSwingOf` 的共轭。修好测试后恒等式成立。

### 中文（先前条目）

#### 修复：裙摆/发丝"化作破片四散飞开"

实测日志（`wine_fox/01_taisho_maid`，24 块裙片）定位到两个缺陷，都在碰撞与角度上限的**执行顺序与判据**上：

```
frame 14160: dt=8ms, max swing=99.4deg, 24 of 24 bone(s) moving, collision active
FM1(name,root, ... 65.2deg, hit=0.02)    ← root 上限本应是 20°
FM2(name,seg,  ... 89.7deg, hit=0.191)   ← seg 上限本应是 60°
```

- **碰撞在上限之后写入，且不受上限约束。** 推出把质心送到碰撞体表面，由此产生的方向只取决于表面有多远，与作者设定的摆幅无关。日志里每一根骨骼都被稳定钉在 99.4°（上限 60°/20°）。现在改为**先碰撞、后夹紧**，上限拥有最后发言权；代价是被碰撞体压住的部件会略微留在体内。
- **"包含静止位置"的碰撞体被当作碰撞处理，于是变成弹射。** 碰撞体是按骨骼几何粗估的球，直径往往比肢体本身大；绕着髋部自然下垂的裙片，其静止质心本就在球内。对它做推出等于把每块裙片沿各自方向径向弹开——屏上就是裙子炸成破片。现在 `skipFor` 同时跳过"包含枢轴"和"包含静止质心"两类体积：碰撞只负责阻止部件在运动**进入**身体，不再与作者画的姿势争论。
- **碰撞体尺寸收敛。** 半径由"到几何中心距离的中位数"改为 35 分位，上限 0.42 → 0.26 格（球体只近似肢体核心，而不是连肢体外的衣物空间一起覆盖）。
- **重力改用独立配置键 `secondaryMotionGravityAcceleration`（默认 24）。** 旧键 `secondaryMotionGravity` 的含义是"移动时的额外下垂"（默认 8），语义变了就不能沿用同一个键——否则老配置文件里的 8 会被当成真实重力静默生效（实测日志正是 `gravity 8.0`）。旧键保留但不再被新物理读取。
- 日志增强：每根骨骼的详情行现在打印 `当前摆角/上限`（如 `65.2deg/20.0`），"摆角超过上限"这类问题一眼可见，不必再靠换算。

新增回归测试：`collisionCannotPushThePieceBeyondItsLimit`（碰撞永远不能越过上限）、`aVolumeContainingTheRestPositionIsSkipped`（包含静止位置的体积必须被跳过，且求解器确实把 `pivot + rest × lever` 交给判据）。全量 147 条测试、0 失败。

### 中文（先前条目）

#### 动态骨骼物理重做（发丝下垂 / 跑动裙摆）

- **修复"像硬片一样甩飞又归位"的根因：缺少力臂。** Epic Fight 的蒙皮是 `pose × toOrigin × partTransform`（见 `vanilla_mesh_transformer.comp`），所以每部件变换作用在**模型绑定空间**里；旧实现写进去的是一个纯旋转，等于让整块部件绕**模型原点（脚下）**旋转，于是每一片头发都拿到了一根和模型等长的力臂。现在写入的是 `T(pivot) × R × T(-pivot)`，pivot 取该骨骼在绑定空间中真正的枢轴（`YsmPhysicsParts`）。
- **每根骨骼各自成为一段摆，而不是整块共用一个旋转。** 旧分类器只保留每片悬挂物的"顶部"一根骨骼，所以十段马尾是一块刚体；现在模型声明的每根物理骨骼（回退路径下则是该片携带几何的每根骨骼）都有自己的枢轴与动力学，段与段之间按最近的已模拟祖先做变换合成，因此不会在关节处被撕开。
- **真正的物理量。** 新增 `YsmDynamicBoneSolver`：以被动画驱动的枢轴为支点的受迫摆，含重力、枢轴加速度带来的惯性力（伪力）、指向动画姿态的角弹簧、阻尼、以及**空气阻力**——模型自身坐标系里的空气以实体速度反向流动，这正是"跑起来裙子被吹开、头发向后飘"的来源。每段有力臂 `L`、相对质量、碰撞半径。
- **碰撞箱。** 新增 `YsmBodyColliders`：按**身体关节**（头/胸/躯干/髋/大腿/小腿/上臂/前臂）用模型自身的绑定几何生成球体，半径取几何到自身中心的中位距离；每帧用与蒙皮完全相同的 `pose × toOrigin` 重新放置。采用位置式（PBD）推出并做迭代，因为"力臂球面"与"碰撞体表面"是两个必须交替满足的约束。
- **配置项调整**（`enableSecondaryMotion` 默认改为**开启**，因为部件来源不再是猜测）：
  - `secondaryMotionGravity` 语义由"移动时的额外下垂"变为**真实重力**（默认 8 → 24 blocks/s²）；
  - 新增 `secondaryMotionAirDrag`（默认 0.9）、`secondaryMotionCollision`（默认 true）；
  - `secondaryMotionStiffness` / `secondaryMotionDamping` 仍按原单位读取，内部换算为摆的固有频率 `sqrt(k)/2π` 与阻尼比 `c/(2√k)`，因此老配置文件的观感含义不变；
  - `secondaryMotionMaxChains` 现在按**骨骼**计数（默认 24 → 96，上限 512）。

#### 新的模型部件匹配方法：从 YSM 动画控制器读取

- **不再靠骨骼名字猜。** 新增 `YsmPhysicsBinding`：解析模型的**动画控制器**（目录包读 `ysm.json → files.player.animation_controllers` 指向的 JSON；二进制 `.ysm` 读控制器段——原先被整体跳过的部分，现改为 `readAnimationControllers`），再在控制器播放的动画里找**物理控制动画**。判定证据有二：
  - 控制器的状态播放了该动画（或动画名落在 YSM 持续求值的 `parallel*` 家族里）；
  - 该动画的**旋转通道**里出现 YSM 自己的物理函数 `ysm.second_order` / `ysm.first_order`，或依赖另一根骨骼已模拟的旋转 `ysm.bone_rot` / `ysm.bone_pos`。
- **连带恢复作者写的参数与链条。** `ysm.second_order(名字, 输入, 频率, 系数, 响应)` 的频率与阻尼被读作该骨骼的弹簧参数（老配置的全局值只作回退）；`ysm.bone_rot('X')` 给出"这根骨骼跟着哪根"，并且会跟踪时间轴变量跳转（真实的酒狐模型是 `v.HP_x_0 = ysm.bone_rot('BackHairA1').x` → `v.HP_x = v.HP_x_0 - v.HP_x_1` → 骨骼读 `v.HP_x`，只解析直接形式会让整头发髻都变成链根）。
- **刻意不收的两类**：只在 `position` 通道上做弹簧的骨骼（酒狐用这种方式做"眼睛跟随延迟"，绕枢轴旋转是错的运动），以及旋转通道其实是一句脚本的占位骨骼（如 `molang` 的 `v.hv=0;v.hg=0;`）。
- **回退保留**：模型没有任何声明时，仍走原有的骨骼名分类器（`YsmPhysicsChains`），只是现在会把它识别出的那片展开成"携带几何的整棵子树"，而不是只摆动顶部。
- 发现结果在转换时写入运行时 JSON 的新 `physics` 段（同时写入 `scale`，因为网格顶点带 `width_scale`/`height_scale` 而骨骼表里的 pivot 不带，物理需要把两者换算到同一单位）。

#### 可观测性

- `[physics]` 日志现在逐模型报告：模拟了多少根骨骼、来源是"模型自己的物理动画"还是"骨骼名"、重力/空气阻力/碰撞体数量、以及每个骨骼的频率、力臂`L`、相对质量、当前摆角与碰撞接触量；每 240 帧一行汇总（dt、最大摆角、多少根在动、碰撞是否生效）。另有一次性告警覆盖两种"看起来没动"的情况：模型被画在非自身绑定骨架上（物理跳过），以及碰撞体本帧无法放置。

#### 测试

- 新增 `YsmPhysicsBindingTest`（17 条）：用**真实模型数据**做样本——YSM 自带的默认控制器文件与 `Hair_Physics` 动画，以及酒狐宇航服变体的 `pre_parallel0`/`parallel0`。断言能找到 `BackHairA1`/`MaWei_LeftA1`/`TuEr_R3` 等骨骼、读到作者写的 1.7/0.5、经时间轴变量恢复 `BackHairB2 → BackHairA1` 的链条，并断言 `LeftEyelidBase`（位置弹簧）与 `molang`（纯脚本）**不**被旋转。
- 新增 `YsmDynamicBoneSolverTest`（21 条）：力臂（同样推力下长段转得更慢）、重力使姿态留下的横向发丝下垂、跑动时的空气阻力使部件向后飘、质量越大被吹动越少、角度上限、卡顿帧被钳制、零杠杆/NaN 输入安全、碰撞推出与贴面接触。
- 新增 `YsmAnimationControllerParseTest`（5 条）：直接测生产解析器对真实控制器文件的两个要点——`states.animations` 的两种写法（裸名与 `{"动画名": "条件"}`），并断言转移条件不会被当成动画名。
- 全量 `gradlew test`：19 个测试类、0 失败。

#### 未验证

- 以上均为**编译 + 单元测试**验证（测试跑在真实 YSM 模型数据上）。游戏内观感、以及各模型的碰撞体半径是否合适，需要在客户端实测后按 `[physics]` 日志与肉眼调整。上一版实测暴露的裙摆散射问题已按上面的日志证据修复，但同样只经过单元测试验证，需再看一次实测日志确认。

### 中文（先前条目）

#### 新功能

- **YSM 内置“原版史蒂夫/艾利克斯模型”回退到原版双层骨架（biped）**：YSM 内置模型包（杂项模型）中的 `misc/2_steve`（原版史蒂夫模型）与 `misc/1_alex`（原版艾利克斯模型）本身就是原版玩家骨架 + 玩家自己的 Mojang 皮肤（YSM 自己就把这两个 id 写死为 `isCustomSkinModel`，用玩家皮肤渲染，无皮肤时回退到原版 wide/slim 皮肤）。选中它们时本模组不再转换、也不再绘制 YSM 网格：
  - `YSMMeshSelector.selectMesh` 对这两个 id 直接返回 null，Epic Fight 使用默认 biped 网格；`PPlayerRendererMixin` / `YSMPlayerRenderer` 两条网格选择路径因此都走原版网格
  - YSM 自身的渲染被跳过（YSM 发布包混淆路径 + OpenYSM/ModernYSM 非混淆路径共 6 个 mixin：第三人称 `ReplacePlayerRenderEvent`、第一人称手臂 `ReplacePlayerHandRenderEvent`、第一人称手部 `RenderFirstPlayerBackground`），改由原版 `PlayerRenderer` 渲染——战斗模式下仍由 `YSMRenderHook` 走 Epic Fight 管线，因此攻击/受击动画、武器挂点、原版盔甲/头盔/鞘翅图层全部保持正常
  - 结果与原先 YSM 渲染在视觉上一致（同一套皮肤、同一套原版骨架），但省掉一次无意义的模型转换与网格绘制，并让 `YsmConditionalArmorLayer` / `YsmConditionalHeadLayer` / `YsmConditionalElytraLayer` 恢复正常显示原版盔甲
  - 其余模型（含 `default`、`misc/3_default_boy`、`misc/4_default_controllers`）不受影响，仍走完整 YSM 网格/动画桥接

#### 可观测性

- **YSM 分支指纹显式化并在启动时声明**：三个已知 YSM 构建（原始闭源混淆版 / 社区破译重写版 OpenYSM / 其现代化后继 ModernYSM）**刻意共用 `modId = "yes_steve_model"`**、版本也都落在本模组 `[2.6,2.7)` 契约内，因此 Forge 模组列表与版本约束都无法区分它们——但三者内部差异足以让功能静默失效（provider 包位置、是否存在可读类名、是否自带 native 核）。新增 `com.ysmef.compat.ysm.YsmFork` 作为**单一事实来源**，在 `FMLClientSetupEvent` 输出一行判定：
  - 指纹按特异性排序探测（实测自三个 jar）：`forge/capability/PlayerCapabilityProvider` → ModernYSM；`capability/PlayerCapabilityProvider` / 可读的 `client/event.ReplacePlayerRenderEvent` → OpenYSM；以上皆无（类名全混淆）→ 原始版
  - `Info` 同时暴露 `playerCapabilityProviderClass()` / `obfuscatedPlayerCapabilityProviderClass()` / `hasUnobfuscatedRenderHooks()` / `shipsNativeCore()` / `obfuscated()`，供能力解析、GPU 开关与配置界面共用
  - `YsmGpuRenderEnable` 不再自带一份探测（原先它用上一代的 `rip.ysm.gpu.*` 特征、且只在 GPU 开关被惰性触发），改为委派 `YsmFork`，并单独输出一行 GPU 开关归属说明
  - `YsmWheelAnimationState` 的能力解析改为向 `YsmFork` 索取确切类名，不再逐个盲试布局——**正是原先盲试链漏掉 ModernYSM 布局，才导致轮盘桥接在 ModernYSM 上静默禁用**

#### 借鉴移植（来源：EpicYSM，MIT）

参考同类模组 [EpicYSM](https://github.com/Argorice/EpicYSM)（同一问题的另一条技术路线）后按序移植了四项设计：

- **按"类是什么"而非名字发现 YSM 类**（新增 `ysm/YsmClasses`）：用加载器的 `ModFileScanData` 取 YSM 的类清单，`extending(parent)` 按继承关系找类，`findPlayerCapabilityHolder(capability)` 按"声明了 `Capability<PlayerCapability>` 静态字段"这一**结构**找能力 provider。`YsmFork` 改为优先用扫描判定分支，指纹表降级为扫描不可用时的兜底；能力 provider 的类名与字段名也优先由扫描给出，因此 ModernYSM 的 `forge.capability`、OpenYSM 的 `capability` 与原始混淆版都能解析，不再依赖"分支 → 类名"的硬编码映射。`ClassData` 访问器在不同 loader 上名称/返回类型不同（Forge 1.20.1 上为返回 ASM `Type` 的 record 访问器），故全部按名反射尝试并对 ASM `Type` 反射取 `getClassName()`，避免引入 ASM 编译依赖
- **用户可编辑的隐藏骨骼覆盖**（新增 `YsmBoneOverrides`）：`config/ysm_epicfight_compat/hidden-bones.txt`（每行一个骨骼名，`#` 注释，`show:` 前缀表示强制显示）与 `config/ysm_epicfight_compat/bone_overrides/<model>.json`（`{"hide":[...],"show":[...]}`）。自动推导隐藏骨骼本质是对他人模型的猜测，这套覆盖把"我们猜错了"从 bug 降级为可配置项。**逐模型条目覆盖全局条目**，因此某个模型能单独撤销全局的 hide；文件按修改时间戳重读，编辑后无需重启
- **共享而非抢占 Epic Fight 的玩家渲染器槽位**（`YSMPlayerRenderer`）：EF 每个实体类型只有一个 patched renderer，后注册者获胜、先前者再也不会被调用。现在注册时把事件中原有的 provider 取出并交给本模组渲染器，本模组不处理的玩家（无 YSM 模型 / 内置原版模型 / 转换未完成）**原样交还给被挤掉的渲染器**；另有每 100 tick 的守护：若其他模组在之后占用了槽位，则把本模组渲染器包裹在其外层，使两者都被调用（拿不到 EF 渲染器表时只告警一行并放弃）
- **外观接管兼容层**（新增 `compat/LookOwners`）：其他模组接管玩家外观时（变身、附身、自制装扮）本模组让位——网格选择返回 null、不再叠加自身图层，交还后自动恢复。判定有两条：**通用**的是"patch 的骨架不再是 EF biped"（无需知道对方是谁；补集判定为"拥有 biped 全部关节即视为 biped"，因此自带网格的武器附属仍由本模组处理）；**具体**的是按模组注册的 `Detector`。同时区分两个概念并按不同粒度缓存：`ownsLook` 按 tick（变身是慢变状态），`hidden` 按帧（传送/过场在动画中途变化，且"被其他模组取消渲染"会记录为一段隐藏期，100ms 内视为仍然隐藏）

#### 测试

- 新增 `YsmBoneOverridesTest`：锁定覆盖的作用域规则——忽略大小写、`show` 胜 `hide`（否则逐模型无法撤销全局设置）、未列名的骨骼保持自动推导结果、空名骨骼跳过不崩
- `YsmForkFingerprintTest` 增加两条**针对真实 jar 的回归测试**（jar 不存在时自动跳过）：断言官方混淆版**确实含有可读类名**（因此名称计数永远不能作为分支判据），并断言它**不含** `capability.PlayerCapability`（因此该锚点确实有区分度）。这两条正是上面那个误判的守卫

#### 新增

- **动画求值限频 `animationEvaluationRateLimitHz`**（0=无限 / 30–240，默认 0 保持原行为）：对非本地玩家的 YSM 脚本与动画求值加一个用户可配的 Hz 上限，是移动端/弱 GPU 的性能旋钮。两次求值之间的帧复用上次已发布的姿态，因此以更新平滑度换取渲染线程时间。该上限与既有的距离 LOD（近处逐帧、远处 30/10 Hz）**叠乘**，实际节奏取两者中较慢者；本地玩家永不受限。
  - 新增 `com.ysmef.compat.animation.EvaluationRateLimiter`（移植自同名参考项目，MIT）：按 Hz 而非 tick 计数设闸，并为每个实体维护**绝对 deadline**，而非 `tick % n`——后者相位与"该实体上次实际求值时刻"无关，且无法表达任意 Hz
  - 关键实现点是**相位延续**：每个被接受的帧都以 `now + interval` 重新排期看似正确，实则不然——在高于目标帧率下每帧都会稍微迟到，deadline 因此逐次后移，实际速率持续低于目标（60 Hz 目标在 144 FPS 循环下收敛到约 48 Hz）。改为在**上一 deadline 基础上**推进整数个间隔；迟到多个间隔的帧一次跨过全部（丢弃错过的采样，绝不补发）
  - 求值上下文携带已解析的动画状态，因此**状态切换立即求值**而不等 deadline——状态转变正是上一次姿态不再可用的时刻；限频与距离 LOD 两道闸**都判定通过后**才推进 deadline，避免为被 LOD 拦下的求值错误排期

#### 新增

- **浮层抑制扩展到官方混淆版**：原先只有 `YsmExtraPlayerOverlayMixin`，目标为可读的 `client.renderer.ModelPreviewRenderer#renderPlayerOverlay`，而官方混淆包既不保留该类名也不保留该方法名（全包无任何类含 `renderPlayerOverlay` 字符串），因此该功能在官方版上一直是失效的。新增 `YsmObfuscatedExtraPlayerOverlayMixin` 覆盖该布局，判定规则抽到 `YsmExtraPlayerOverlaySupport` 由两条路径共用（规则只写一次，不会在两种布局间漂移）
  - **目标由调用链推导而非按名查找**（描述符在混淆后不变）：官方包中唯一的 `IGuiOverlay` 实现是浮层入口 → 反编译其 `render` → 它在读完自身配置后只发出一次进入本模组的调用，描述符为 `(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/player/LocalPlayer;DDFFIF)V`，与可读版 `renderPlayerOverlay` 完全一致 → 该调用的 owner 类即目标
  - 推导过程规避了一个陷阱：**混淆器在同一个包内复用方法名**，调用点的方法名 `Oo0Oo0o00O00Oo0OOoOOoooo` 在其它类上还带着 `(Entity, PoseStack, float)` 与泛型 animatable 两种签名，配置访问类上也有同名成员——只匹配名字会注入到错误方法，故注入串同时携带完整描述符
  - 新增一次性日志：抑制实际触发时记录**经哪条路径**到达浮层。此前该功能完全无日志，"配置生效"与"两个混入都没匹配上该 YSM 构建"在游戏内表现完全相同（浮层照常显示），无法区分；该行同时指明所装构建的类布局

#### 修复

- **混淆浮层混入导致游戏崩溃（`IllegalClassLoadError`）**：辅助类 `YsmExtraPlayerOverlaySupport` 被放在混入包 `com.ysmef.compat.mixin` 内，而 Mixin 视该包（由 `ysm_epicfight_compat.mixins.json` 声明）为独占，**注入进目标类的代码不允许直接引用同包的非混入类**。该失败的形态比一般启动错误更隐蔽：混入本身**报告应用成功**、游戏正常加载进世界，直到被注入的方法**首次执行**（进入战斗模式、渲染浮层）才抛 `IllegalClassLoadError` 直接崩溃。已将辅助类移到 `com.ysmef.compat.renderer`，两个混入改为导入使用
  - 该崩溃同时**反证了目标推导是对的**：崩溃栈为 `O0O00oOoOoooOOooo0OOOoO0.render` → `OoO00Oo00Ooo0OoOoo00o000.Oo0Oo0o00O00Oo0OOoOOoooo` → 本模组注入的 handler，与我按调用链推导的路径完全一致
  - 配套核查结论：浮层内的网格绘制**本来就不走 GPU 蒙皮路径**（`YsmGpuRenderPath` 按设计以 `gui-projection` 跳过 GUI 正交投影，因为 GPU 路径在该上下文会画成塌缩红块）。因此浮层那 20-30 FPS 的损耗**不是 GPU 蒙皮造成的**，而是整条第二渲染管线（骨架姿态 + 各图层 + 网格绘制）——这反向证明"抑制浮层"是正确修法，扩展 GPU 路径支持 GUI 投影并不能解决该损耗

#### 测试

- 新增 `MixinPackageContractTest`（2 项）：断言混入包内每个 `.java` 文件名都以 `Mixin` 结尾（上述崩溃的守卫——它是文件位置约定，纯源码即可检查，无需构建产物），并断言该检查确实扫到了 20 个以上的混入源文件以免因工作目录不对而空过。该测试自身**刻意放在被检查的包之外**（`com.ysmef.compat.contract`），否则会被自己的规则判为违规
- 新增 `EvaluationRateLimiterTest`（10 项）：以**模拟渲染循环测量实际达成速率**而非断言 deadline 算术——60 Hz 目标在 144 FPS 循环下须落在 600±5 次/10s（漂移实现会落在约 480 而失败）、30 Hz 目标在 240 FPS 下须落在 300±5、目标高于帧率时逐帧求值、0/负数即无限、首帧必求值、`force` 覆盖节奏、**上下文变化立即求值**、速率变化立即生效、时钟倒退不得永久卡住、reset 清空状态

#### 修复

- **`LookOwners` 的隐藏状态每帧振荡并刷屏**（首次 Java 版实测发现）：原实现把"记录隐藏"与"清除隐藏"分放在两个调用点，而渲染钩子会在相邻帧分别报告"渲染被取消"与"渲染完成"，于是玩家每帧进入并退出隐藏态，日志产出 **26361 对隐藏/恢复记录，占整个会话日志的 48.81%**。现改为**双时间戳状态机**：`hiddenLately` 每帧被调用一次，把本次信号盖到 `lastHiddenNanos` 或 `lastShownNanos`，隐藏态由"哪个时间戳更新"推导，**仅在状态真正转变时打日志**；`shown()` 因此不再清除状态（每帧清除正是振荡的成因）。修正后同一场景日志占比 **0%**，且隐藏态在信号持续时保持稳定而非逐帧翻转
- **分支判定把官方混淆版误判为 OpenYSM**（首次 Java 版实测发现）：原判定按"可读类名占比"推断——有可读类即判为可读分支。但官方混淆版**本身也带可读类名**（`YesSteveModel` 与整个 `mixin/` 包共 23 个），因此该判据对所有分支都成立，恒判 `OPEN_YSM`，进而按可读分支去期望 `client/event.*` 渲染钩子（官方版并不存在）与 `capability` 能力提供者。现改为**只依据真正有区分度的锚点**：是否存在可读的 `capability.PlayerCapability` 类（官方版整个 `capability` 包都不存在），以及其 provider 是否位于 `forge` 子包（ModernYSM 的多平台布局）。名称计数不再参与判定
- **轮盘动画桥接在 ModernYSM 上不可用**：`resolveCapability()` 只尝试原始版混淆 provider 与 `capability/PlayerCapabilityProvider` 两个类名，而 ModernYSM 把 Forge provider 放在 `forge/capability/` 子包（字段名仍为 `PLAYER_CAP`），于是解析失败、`wheel animation bridge disabled`。现由 `YsmFork` 提供权威类名，并补上 `getModelId` 作为模型 ID 读取的兜底方法名（ModernYSM 的 `GeoEntity` 用该名而非 `getSelectedModelId`）
- **OpenYSM/ModernYSM 三个渲染 hook 注入签名失配**：ModernYSM 2.6.6.6 把 `ReplacePlayerRenderEvent#onRenderPlayerPre`、`ReplacePlayerHandRenderEvent#onRenderArm`、`RenderFirstPlayerBackground#onRenderHand` 从「Forge 事件处理器（void）」重构为「返回 boolean 的静态方法」，原有注入全部静默失效。现按**新旧两套签名并存**（均 `require = 0`）适配：ModernYSM 只匹配新签名，2.6.5 官方版只匹配旧签名，互不干扰

#### 测试

- 新增 `YsmVanillaModelIdsTest`：锁定“原版玩家模型”id 集合恰为 `misc/2_steve` + `misc/1_alex`，并确认 `default` 与杂项包中其余自定义模型不会被误判（误判会导致这些玩家丢失 YSM 网格与动画桥接）
- 新增 `YsmForkFingerprintTest`：锁定四个分支指纹常量与 jar 实测一致、四者互不相同（合并任两个会让探测顺序决定分支判定），并确认只有原始版的 provider 名呈混淆形态、能力字段名按分支区分

### English

#### Features

- **YSM's built-in "vanilla Steve/Alex" models fall back to the plain biped**: the Misc model pack's `misc/2_steve` (Minecraft Steve Model) and `misc/1_alex` (Minecraft Alex Model) are the vanilla player rig skinned with the player's own Mojang skin (YSM itself hardcodes exactly these two ids as `isCustomSkinModel`, falling back to the vanilla wide/slim skin when the profile has none). Selecting either no longer converts or draws a YSM mesh:
  - `YSMMeshSelector.selectMesh` returns null for both ids, so Epic Fight keeps its default biped through both mesh-selection paths (`PPlayerRendererMixin` and `YSMPlayerRenderer`)
  - YSM's own rendering is skipped (six mixins covering the obfuscated release build and the OpenYSM/ModernYSM un-obfuscated paths: third-person `ReplacePlayerRenderEvent`, first-person arm `ReplacePlayerHandRenderEvent`, first-person hand `RenderFirstPlayerBackground`); the vanilla `PlayerRenderer` draws instead, which `YSMRenderHook` still routes through Epic Fight's pipeline in battle mode - so combat animations, weapon anchoring and the vanilla armor/head/elytra layers all keep working
  - Visually identical to YSM's own rendering (same skin, same vanilla rig) while dropping a pointless model conversion and mesh draw, and it lets `YsmConditionalArmorLayer` / `YsmConditionalHeadLayer` / `YsmConditionalElytraLayer` show vanilla armor again
  - Every other model (`default`, `misc/3_default_boy`, `misc/4_default_controllers`, ...) is unaffected and keeps the full YSM mesh/animation bridge

#### Observability

- **YSM fork fingerprints are now explicit and declared at startup**: the three known YSM builds (the original closed-source obfuscated release, the community de-obfuscation/rewrite OpenYSM, and its modernized successor ModernYSM) deliberately share `modId = "yes_steve_model"` and a version inside this mod's `[2.6,2.7)` contract, so neither Forge's mod list nor the version range can tell them apart - yet their internals differ enough to silently break features (provider package location, whether readable class names exist, whether a native core ships). New `com.ysmef.compat.ysm.YsmFork` is the single source of truth and logs one verdict line from `FMLClientSetupEvent`:
  - probes are tried most-specific first (verified against the three actual jars): `forge/capability/PlayerCapabilityProvider` -> ModernYSM; `capability/PlayerCapabilityProvider` / a readable `client/event.ReplacePlayerRenderEvent` -> OpenYSM; none of the above (every class name obfuscated) -> the original release
  - `Info` also exposes `playerCapabilityProviderClass()` / `obfuscatedPlayerCapabilityProviderClass()` / `hasUnobfuscatedRenderHooks()` / `shipsNativeCore()` / `obfuscated()` for the capability lookup, the GPU gate and the config screen to share
  - `YsmGpuRenderEnable` no longer carries its own detection (it used the previous generation's `rip.ysm.gpu.*` markers and only ran lazily when the GPU toggle was touched); it delegates to `YsmFork` and logs the GPU-toggle ownership on its own line
  - `YsmWheelAnimationState`'s capability lookup now asks `YsmFork` for the exact class instead of blindly probing layouts - **that blind chain omitted the ModernYSM layout, which is what silently disabled the wheel bridge on ModernYSM**

#### Fixes

- **Wheel animation bridge unusable on ModernYSM**: `resolveCapability()` only tried the original release's obfuscated provider and `capability/PlayerCapabilityProvider`, while ModernYSM keeps its Forge provider in the `forge/capability/` subpackage (field name still `PLAYER_CAP`); resolution failed and the bridge disabled itself with `wheel animation bridge disabled`. `YsmFork` now supplies the authoritative class name, and `getModelId` was added as a fallback for reading the model id (ModernYSM's `GeoEntity` uses that name, not `getSelectedModelId`)
- **Three render hooks had mismatched injection signatures on OpenYSM/ModernYSM**: ModernYSM 2.6.6.6 refactored `ReplacePlayerRenderEvent#onRenderPlayerPre`, `ReplacePlayerHandRenderEvent#onRenderArm` and `RenderFirstPlayerBackground#onRenderHand` from Forge event handlers (void) into static methods returning boolean, so every existing injection silently failed. Both signature generations are now covered side by side (all `require = 0`): ModernYSM matches only the new form, the 2.6.5 original only the old one

#### Tests

- New `YsmVanillaModelIdsTest`: locks the vanilla-player-model id set to exactly `misc/2_steve` + `misc/1_alex`, and guards `default` plus the other Misc pack models against being caught by it (a false positive would cost those players their YSM mesh and animation bridge)
- New `YsmForkFingerprintTest`: locks the four fork-fingerprint constants against the jars they were verified on, asserts they stay mutually distinct (collapsing any two would let probe order decide the verdict), and checks that only the original release's provider name looks obfuscated and that capability field names are fork-specific

---

## v1.9.0 — 2026-08

### 中文

#### 安全与正确性（P0）

- **Molang 函数参数计数**：`Molang.Env.callFunction` 增加 `argCount`，所有 Env 实现按实际参数个数读取复用槽位，消除“少参调用读到上一次调用的陈旧参数”导致的非确定性脚本求值
- **roaming 变量竞态**：`YsmRoamingState` 改为单线程池顺序计算 + 不可变快照发布，并增加世界代际，修复后台遍历与主线程 `clear+putAll` 竞争以及快速点击轮盘时的丢更新
- **模型包读路径防穿越**：`modelId` 及 manifest 内 model/animation/texture 路径全部经过相对路径校验、`resolveInside` 词法围栏和 `toRealPath` 符号链接围栏；拒绝绝对路径、盘符/UNC、`..` 段
- **解析资源上限**：`.ysm` 源包和解压后载荷默认各限 512 MiB（`-Dysm_ef_compat.max_package_bytes` / `-Dysm_ef_compat.max_decompressed_bytes` 可覆盖）；二进制解析对 bone/cube/face/animation/texture/model 计数设上限并移除恶意计数预分配
- **递归深度防御**：几何 JSON/二进制、EFMesh 写出、运行时骨骼表、Camera solver、轮盘采样和 Molang 解析器均加入深度/环防护；深嵌套 JSON 有 `StackOverflowError` 最后防线
- **渲染线程不再全盘扫描**：`existsLocally` 只 stat 四个可能路径；`ensureModel` 和缓存回退改用该检查，删除缺失模型日志中的全模型目录枚举

#### 性能与稳定性（P1）

- **GL 状态恢复 `finally` 化**：GPU/CPU 直连路径绘制期间的任何异常都会在 `finally` 中恢复 cull/blend/depthTest/depthMask、program、VAO、SSBO 和 light layer，不再污染后续渲染
- **`generateAll` 线程与内存约束**：强制渲染线程调用（EF `Meshes.ACCESSORS` 非线程安全），并复用 `CONVERSION_SLOTS` 限制全量重建的并发峰值
- **Iris VAO 切换**：顶点格式变化时先禁用旧属性，避免残留属性指针采样错误缓冲
- **热路径探测缓存**：YSM 预览模式 250ms TTL、EF compute setup 按网格实例缓存一次、CPU 能力探测只执行一次 `glGetString`；shader-pack 检测合并为 GPU 路径的单一实现
- **轮盘映射持久化重构**：新增每模型 sidecar `config/ysm_epicfight_compat/extra_animation_mappings/<id>.json`，原子写；旧聚合 `extra_animation_mappings.json` 仍兼容读取。新增负缓存避免转换期间每 tick 读盘；`exactHash` 复用单个 ByteBuffer，移除每 float 一次的堆分配

#### 清理（P2）

- `YSMRuntimeBridge` 当前实体 `ThreadLocal` 在 `YSMMesh.draw` 的 `finally` 中清理
- 删除死代码：`YSMJointMapper.jointNameOf` 注释块、`YsmExtraFrameWriter.indexOf`、`YSMMeshLibrary.isGenerated/meshCount/availableModelIds`
- 统一重复实现：骨名 `normalize`、`packNormal`、shader-pack 检测均收敛到单一实现
- 修正 `YsmCpuSkinShader` 过时注释，明确 CPU 路径顶点已为相机空间
- 收紧 `mods.toml` 依赖契约：Epic Fight `[20.14.17,20.15)`、YSM `[2.6,2.7)`
- 离开世界时清理网格选择/模型读取等按玩家累积的诊断集合

#### 测试

- Molang 新增“函数调用收到精确参数个数”回归测试
- 新增 `YsmModelPackageTraversalTest`：锁定读路径穿越/绝对路径/盘符/NUL 拒绝与合法相对 ID 接受

### English

#### Security & correctness (P0)

- Molang function calls now carry an `argCount`; every `Env` implementation reads only that many slots, eliminating stale values from the reused argument buffer
- `YsmRoamingState` now evaluates on its single-threaded pool in submission order and publishes immutable snapshots with a world generation guard - fixes the clear+putAll race and lost rapid-click toggles
- Read-side path traversal defense for model ids and manifest-declared child paths: relative-path validation, lexical `resolveInside`, and `toRealPath` symlink containment
- Resource limits: 512 MiB defaults for `.ysm` source files and decompressed payloads (JVM-property overridable); parser section-count caps; no hostile-count preallocation
- Recursion depth/cycle guards across geometry parsing, mesh writing, runtime bone tables, camera solving, wheel sampling and the Molang parser, with a `StackOverflowError` last resort for deeply nested JSON
- Render-thread directory scans removed: `existsLocally` stats only the four candidate paths

#### Performance & stability (P1)

- GPU/CPU direct draws restore cull/blend/depth/depthMask, program, VAO, SSBO and light layer in `finally` even when a draw throws
- `generateAll` now asserts the render thread and caps concurrent conversions through `CONVERSION_SLOTS`
- Iris VAO disables stale attributes when the vertex format changes
- Hot-path probes cached: YSM preview mode (250 ms TTL), per-mesh EF compute setup, one-time CPU GL capability probe; shader-pack detection now has a single shared implementation
- Wheel mappings persist as per-model atomic sidecars (legacy aggregate still readable), with a negative cache against per-tick disk reads and a reused ByteBuffer in `exactHash`

#### Cleanup (P2)

- Current-entity `ThreadLocal` cleared in `YSMMesh.draw`'s `finally`
- Removed dead code (`YSMJointMapper.jointNameOf`, `YsmExtraFrameWriter.indexOf`, `YSMMeshLibrary.isGenerated/meshCount/availableModelIds`)
- Merged duplicate `normalize` / `packNormal` / shader-pack detection
- Fixed the stale `YsmCpuSkinShader` class comment
- Tightened `mods.toml` contracts: Epic Fight `[20.14.17,20.15)`, YSM `[2.6,2.7)`
- Per-player diagnostic sets are cleared on disconnect

#### Tests

- New Molang regression test for exact function argument counts
- New `YsmModelPackageTraversalTest` locking the read-path traversal defense

---

## v1.8.1 — 2026-08

### 中文

#### 修复

- **多人服务器进服即被踢出**（Epic Fight 20.14.17 动画注册表一致性校验）：EF 服务器会把每个玩家客户端动画注册表与服务器注册表逐项比对，任何不一致直接 `disconnect`（`gui.epicfight.warn.animation_unsync`）。本模组的轮盘模板动画（`ysm_epicfight_compat:public/pub_*`）由各客户端按自身 YSM 模型数据在运行时生成，专用服务器上不可能存在、不同玩家的 id 也互不相同，因此**任何使用轮盘桥的玩家连入 EF 服务器都会被踢**。修复：服务器端 mixin（`AnimationManagerValidationMixin`，common 侧加载）重写 `validateClientAnimationRegistry`，双向豁免本模组生成的模板（服务器注册的与客户端注册的均不计入差异）；判定逻辑（`AnimationRegistryGuard`）按字母数字规范化匹配，同时覆盖历史版本产生的畸形注册名（缺失 `:` `/` 或 `_` 变空格）；初始化时额外调用 `AnimationManager.addNoWarningModId` 对齐官方豁免机制
- **`-all.jar` 构建产物缺失 reobf**：`gradlew jarJar` 单独执行不会触发 `reobfJarJar`，产物保留 named 映射，在专用服务器上直接崩溃（`NoSuchMethodError: MinecraftServer.getPlayerList`）。已在 `build.gradle` 为 `jarJar` 补上 `finalizedBy('reobfJarJar')`
- 测试：新增 `AnimationRegistryGuardTest`（规范名/畸形变体/无关命名空间/大小写）

### English

#### Fixes

- **Kicked on join in multiplayer** (Epic Fight 20.14.17 animation registry consistency check): the server compares each player's client animation registry against the server's and disconnects on any mismatch (`gui.epicfight.warn.animation_unsync`). Wheel templates (`ysm_epicfight_compat:public/pub_*`) are generated per client from that client's own YSM model data, so they can never exist on a dedicated server and ids differ between machines - any player using the wheel bridge was kicked. Fix: server-side mixin (`AnimationManagerValidationMixin`, loaded on the common side) rewrites `validateClientAnimationRegistry` to exempt generated templates on both sides; the matcher (`AnimationRegistryGuard`) normalizes names to alphanumerics to also cover mangled legacy ids; `AnimationManager.addNoWarningModId` is called at init to align with the official exemption mechanism
- **`-all.jar` artifacts missing reobf**: a bare `gradlew jarJar` does not trigger `reobfJarJar`, leaving named mappings in the artifact and crashing dedicated servers (`NoSuchMethodError: MinecraftServer.getPlayerList`). `jarJar.finalizedBy('reobfJarJar')` added in `build.gradle`
- Tests: new `AnimationRegistryGuardTest` (canonical/mangled names, unrelated namespaces, case variants)

## v1.8.0 — 2026-08

### 中文

#### 架构重构（自 v1.5.1 以来的主要代码质量迭代）

- **拆解 `YSMMeshLibrary` 上帝类**（约 1900 行 → 约 1170 行）：按单一职责拆分出
  - `TextureStore`：纹理管线全域（字节注册、PNG/JPEG/WebP/AVIF 解码、异步上传与每帧时间预算、延迟释放、pack/缓存文件布局、路径穿越防护 `sanitize`）
  - `ManifestStore`：生成缓存清单（内存镜像 + 版本合并写 + 独立后台写线程）
  - `JointTable`：Epic Fight 参考双足骨架 20 关节表的单一数据源（原在三个类中重复）
- **拆解 `model ↔ gpu/cpu` 包环**（依赖倒置）：
  - 资源释放经 `MeshReleaser` 接口注册表（`YSMMeshLibrary#registerMeshReleaser`），三个渲染路径类静态自注册
  - 渲染分派经 `RenderBridgeRegistry`（`GpuSkinRender` / `CpuSkinRender` / `IrisSkinRender` 接口），`YSMMesh#draw` 不再直接 import 渲染路径类
  - `model` 包对 `gpu`/`cpu` 包的引用归零，依赖方向变为单向（渲染路径 → 模型数据）
- 清理：删除 5 个临时诊断 mixin（Dispatcher/LivingEntityRender/PatchedLivingRender/RenderEngine/RenderEngineEvents Diag）、轮盘姿势校正死代码（约 120 行）、`Clip.descriptor` 死字段等；`.gitignore` 建立（构建产物不再入库）

#### 修复

- **关键帧 pre/post 语义颠倒**（二进制 .ysm 包）：`readScriptChannel` 与参考序列化器对齐（含 pre 数据的关键帧按 `(pre, flag, post)` 磁盘顺序解析）——此前含 pre 数据关键帧的动画会静默错插值
- **多人模式动画器清扫时钟错误**：清扫改用世界 `gameTime`（原按各实体 `tickCount` 跨实体比较，老玩家触发清扫会每 15 秒误杀其他活跃玩家的动画器）
- **路径穿越任意文件写入**：`sanitize` 中和 `..` / 孤立 `.` 段 + 写入前 `normalize().startsWith(root)` 围栏（恶意 .ysm 模型包无法再借纹理名逃逸资源包根目录）
- **EF 非线程安全静态表**：`MeshAccessor.create`（写 `Meshes.ACCESSORS` HashMap）收敛到渲染线程执行（worker 只入队，渲染线程 drain，带代际校验）
- **同步/异步求值竞争**：同步求值路径与异步 worker 互斥（`evalPending` CAS），消除双线程写同一双缓冲槽与 HashMap 的竞态
- **LRU 淘汰 use-after-free**：被淘汰共享网格的 GL 资源延迟 5 tick 释放（与纹理同一模式），本帧后续绘制不再使用已销毁缓冲
- **共享 VBO 跨绘制竞态**：CPU 路径每帧上传前 orphaning（`glBufferData(NULL)` 重分配）+ `GL_STREAM_DRAW`
- **GL 状态泄漏**：GPU/CPU 两条路径绘制前后保存/还原 cull/blend/depthTest/depthMask；半透明第二遍改 `depthMask(false)`（不再污染深度缓冲）
- **GPU 路径雾距公式**：`bone_skin.vsh` 改为 `fogDistance(u_mv, eyePos)`，与 vanilla `|T + x|` 一致（原式 `|T + R⁻¹x|` 随相机旋转偏差可达模型半径）
- **`query.is_alive` 恒 0**：逐帧求值补写，存活/死亡变体脚本不再判反
- **`hold_offhand:` 动画永不播放**：条件叠加补副手分支
- **异步求值一次失败永久禁用**：改为连续 3 次失败 + 10 分钟无新失败自动恢复
- **模型同步版本握手静默失效**：协议版本不匹配时双端各提示一次（WARN）
- **模板描述符文件膨胀（可达数百 MB）**：相似度描述符降采样存储（每 8 帧取 1）+ 流式加载 + 旧格式自动迁移重写
- **manifest 锁内 O(N²) 磁盘 I/O**：内存镜像 + 后台合并写（渲染线程不再读盘/写盘）
- **首帧网格构建卡顿**：新注册网格在客户端 tick 分帧预热（每 tick 8ms 预算）
- **roaming 变量同步加载卡顿**：模型包加载与 Molang 求值移入后台线程，结果回主线程应用
- **GLES 上下文桌面入口**：ES 下跳过 `glGetProgramResourceIndex`/`glShaderStorageBlockBinding`（shader 已显式 `binding=0`；真机验证待 Android 环境）

#### 测试

- 新增 17 个单元测试（总数 32）：Molang 求值器（11）、CityHash 固定向量（3）、关节表（3）；winefox 明文黄金用例（几何/动画/pre-post 真值）、真实 .ysm 解密链黄金用例、`sanitize` 路径穿越用例、二进制关键帧 pre/post 用例——覆盖 P0~P3 全部修复点

### English

#### Architecture refactor (main quality iteration since v1.5.1)

- **Split the `YSMMeshLibrary` god class** (~1900 -> ~1170 lines) into single-responsibility classes:
  - `TextureStore`: the whole texture pipeline (byte registration, PNG/JPEG/WebP/AVIF decoding, async upload with a per-frame time budget, delayed releases, pack/cache layout, `sanitize` path-traversal defense)
  - `ManifestStore`: generated-cache manifest (in-memory mirror + versioned coalesced writes + dedicated background writer)
  - `JointTable`: single source of truth for the 20-joint Epic Fight biped table (previously duplicated in three classes)
- **Break the `model <-> gpu/cpu` package cycle** (dependency inversion):
  - resource release via the `MeshReleaser` registry (`YSMMeshLibrary#registerMeshReleaser`), self-registered by the three render-path classes
  - draw dispatch via `RenderBridgeRegistry` (`GpuSkinRender`/`CpuSkinRender`/`IrisSkinRender`); `YSMMesh#draw` no longer imports the render-path classes
  - zero `model -> gpu/cpu` imports remain; the dependency is now one-way (render paths -> model data)
- Cleanup: removed 5 temporary diagnostic mixins, the dead wheel-pose correction (~120 lines), the dead `Clip.descriptor` field, etc.; `.gitignore` added (build artifacts no longer tracked)

#### Fixes

- Binary keyframe pre/post semantic swap (`.ysm` packages): `readScriptChannel` now matches the reference serializer's `(pre, flag, post)` disk order - animations with pre-data keyframes no longer interpolate wrongly
- Multiplayer animator-sweep clock bug: the sweep now uses the world `gameTime` (per-entity `tickCount` comparisons killed other players' live animators every 15 s)
- Path-traversal arbitrary file write: `sanitize` neutralizes `..` / lone `.` segments + normalize/startsWith guards before writes
- Epic Fight's non-thread-safe static table: `MeshAccessor.create` (writes `Meshes.ACCESSORS` HashMap) is confined to the render thread (workers enqueue, the render thread drains with a generation check)
- Sync/async evaluation race: the synchronous path now shares the `evalPending` mutex with the async worker
- LRU-eviction use-after-free: evicted shared meshes are released 5 ticks later (same pattern as textures)
- Shared-VBO cross-draw race: per-frame orphaning (`glBufferData(NULL)`) + `GL_STREAM_DRAW` on the CPU path
- GL state leakage: cull/blend/depthTest/depthMask saved and restored around both direct skinning paths; the translucent second pass uses `depthMask(false)`
- GPU-path fog distance: `bone_skin.vsh` now computes `fogDistance(u_mv, eyePos)` = vanilla `|T + x|` (the old `|T + R^-1 x|` drifted with the camera)
- `query.is_alive` never written: now filled per frame (alive/dead variant scripts evaluated the wrong branch)
- `hold_offhand:` animations never played: off-hand overlay branch added
- Async evaluation permanently disabled after one failure: now 3 consecutive failures + automatic recovery after 10 min without new failures
- Silent protocol-version handshake failure: both sides log a one-time WARN on mismatch
- Template descriptor file bloat (hundreds of MB): downsampled descriptors (1 per 8 frames) + streaming load + automatic legacy migration
- Manifest O(N^2) disk I/O under the class lock: in-memory mirror + coalesced background writes
- First-draw mesh-build hitch: freshly registered meshes are prewarmed on the client tick with an 8 ms budget
- Roaming-variable synchronous package load: moved to a background thread, applied on the main thread
- Desktop-only GL entry points on GLES: skipped on ES contexts (shader already declares `binding=0`; real-device verification pending)

#### Tests

- 17 new unit tests (32 total): Molang evaluator (11), CityHash fixed vector (3), joint table (3); plus winefox plaintext golden cases (geometry/animations/pre-post truth), a real `.ysm` decryption golden case, `sanitize` traversal cases and binary keyframe pre/post cases - covering every P0-P3 fix

---

## v1.5.1 — 2026-08

### 中文

#### 新增

- **CPU 蒙皮渲染路径（无需计算着色器）**：新增 `com.ysmef.compat.cpu` 渲染包——每帧 CPU 逐顶点蒙皮（蒙皮乘积 `(pose×toOrigin×partDelta)×bindPos` 与 EF 计算着色器 / GPU 路径逐项一致，并支持多关节加权，不限于刚性单关节顶点），poseStack 在 CPU 端应用（与 EF drawPosed 相同契约），顶点流式写入每网格复用的动态 VBO，整模型单次 `glDrawArrays(GL_TRIANGLES)` 绘制。桌面仅需 OpenGL 3.3、Android 仅需 OpenGL ES 3.0（无 SSBO、无计算着色器），低内存占用（每网格 24B/顶点 VBO + 复用缓冲，每帧零分配），适配 <2G 内存与老旧 GPU 等极端工况
- **CPU 回退 mixin**：`SkinnedMeshCpuRenderMixin` 在 EF `SkinnedMesh#drawPosed` 入口拦截——EF 渲染管线回退到 CPU 渲染着色器时，YSM 转换网格改走本模组 CPU 蒙皮路径；路径不可用时（光影包激活等）原样执行 EF 原路径，不影响任何其他网格或渲染通道
- **CPU 路径覆盖范围**：GUI 模型预览、TLM 女仆等 GPU 路径无法重建相机矩阵的场景同样由 CPU 路径接管（无 poseStack 平移门控）
- **回退链验证开关**：系统属性 `-Dysm_ef_compat.force_cpu_render=true` 强制跳过 EF 计算着色器、始终走 CPU 蒙皮路径，便于在支持计算着色器的硬件上验证回退链

#### 修复

- **修复战斗模式下帧率骤降（100+ 帧 → 20-30 帧）**：YSM 左上角"额外玩家渲染"（纸娃娃）在战斗模式下通过实体渲染分发器每帧触发**第二次完整 EF 补丁渲染管线**（骨骼姿态采样、补丁图层、网格绘制）；现战斗模式下默认自动抑制纸娃娃（新增 `YsmExtraPlayerOverlayMixin`，配置 `disableExtraPlayerInBattleMode` 默认 true），帧率恢复至与关闭该选项一致
- **修复 CPU 路径缺面（根因）**：EF `EpicFightRenderTypes.replaceTexture` 与 `getTriangulated` 共享渲染类型缓存，QUADS 模式实体渲染类型污染缓存，导致 `getTriangulated` 返回未三角化的渲染类型；drawPosed 把 688 个三角形顶点按"每 4 顶点一个 quad"重新分组绘制 → 视觉缺面。现改用缓存无关的 `makeTriangulated`，EF 原始 drawPosed 路径本身也渲染完整
- **消除"计算着色器不可用即缺面"**：无计算着色器 GPU 上的渲染不再缺面（此前 CPU 回退告警 "converted meshes may render incompletely" 的根因已修复，且默认改走本模组 CPU 蒙皮路径）
- 修复 CPU 蒙皮法线未归一化导致的光照不一致（EF drawPosed 不归一化法线；本模组 CPU 路径对蒙皮后法线归一化）
- **诊断日志默认关闭**：所有 [diag] 日志（路径跳过原因、逐实体渲染追踪、逐帧计时）默认静默（`-Dysm_ef_compat.diag=true` 开启），消除战斗/纸娃娃场景下每秒十几条的 log4j 刷屏
- **GPU 路径不可用场景改走 CPU 顶点管线**：GUI 预览、TLM 女仆（poseStack 缺实体-相机平移）等 GPU 路径门控拦截的绘制，由轻量 CPU 蒙皮路径接管（无 compute 调度/输出 SSBO 往返/管线屏障），对核显更友好；CPU 蒙皮热路径内联优化（~2.4ms → ~1.6ms/1.15 万顶点）

#### 兼容性

- **旧 GPU / 集成显卡**：桌面 GL 3.3+ 或 OpenGL ES 3.0+ 即获得完整 CPU 蒙皮渲染；macOS（GL 4.1 无计算着色器）不再受限
- **Iris / Oculus 光影包**：GPU 路径与 CPU 蒙皮路径让位 EF 计算路径（内建 Iris 支持）；计算着色器不可用时由三角化已修复的 drawPosed 兜底

---

### English

#### Added

- **CPU skinning render path (works without compute shaders)**: new `com.ysmef.compat.cpu` package — per-frame CPU vertex skinning (the `(pose×toOrigin×partDelta)×bindPos` product matches Epic Fight's compute shader / GPU path exactly, multi-joint weights supported beyond the rigid single-joint assumption), the poseStack applied on the CPU (same contract as EF's drawPosed), vertices streamed into a reused per-mesh dynamic VBO, the whole model drawn in one `glDrawArrays(GL_TRIANGLES)`. Needs only desktop OpenGL 3.3 / OpenGL ES 3.0 on Android (no SSBO, no compute shader), tiny memory footprint (24 B/vertex VBO + reused buffer per mesh, zero per-frame allocations) — suited for <2 GB RAM machines and old GPUs
- **CPU fallback mixin**: `SkinnedMeshCpuRenderMixin` hooks the head of EF `SkinnedMesh#drawPosed` — whenever Epic Fight's pipeline falls back to its CPU rendering shader, converted YSM meshes take this mod's CPU skinning path instead; when the path declines (shader packs, ...) the original drawPosed runs unchanged, so no other mesh or render pass is affected
- **CPU path coverage**: GUI model previews, TLM maids and other cases where the GPU path cannot rebuild the camera matrix are covered too (no poseStack translation gate)
- **Fallback-chain test switch**: system property `-Dysm_ef_compat.force_cpu_render=true` skips Epic Fight's compute shader and always uses the CPU skinning path, for verifying the fallback chain on compute-capable hardware

#### Fixed

- **Fixed the battle-mode FPS collapse (100+ FPS -> 20-30 FPS)**: YSM's extra player render (the corner paperdoll) dispatches through the entity render dispatcher every frame, which in battle mode runs a SECOND full Epic Fight patched render pipeline per frame (armature pose sampling, patched layers, mesh draw); the paperdoll is now suppressed by default in battle mode (new `YsmExtraPlayerOverlayMixin`, config `disableExtraPlayerInBattleMode` default true), restoring the FPS to match the option-disabled state
- **Fixed the CPU-path missing faces (root cause)**: EF's `EpicFightRenderTypes.replaceTexture` and `getTriangulated` share a render-type cache; QUADS-mode entity render types pollute that cache, so `getTriangulated` returned a non-triangulated render type and drawPosed regrouped 688 triangle vertices as "4 vertices per quad" → visually missing faces. The fallback now uses the cache-independent `makeTriangulated`, so Epic Fight's original drawPosed path also renders completely
- **No more "missing faces whenever compute shaders are unavailable"**: rendering on compute-less GPUs is now complete (the root cause behind the old "converted meshes may render incompletely" warning is fixed, and the default is now this mod's CPU skinning path)
- Fixed CPU-skinning lighting inconsistency from unnormalized normals (EF's drawPosed does not normalize; this mod's CPU path normalizes the skinned normals)
- **Diagnostic logs off by default**: all "[diag]" logs (render-path skip reasons, per-entity render tracing, per-frame timings) are silent unless `-Dysm_ef_compat.diag=true` is set, eliminating the multi-line-per-second log4j spam in battle / paperdoll scenarios
- **CPU vertex pipeline for GPU-path-declined cases**: GUI previews, TLM maids (poseStack lacking the entity-camera translation) and other draws blocked by the GPU path's gates now use the lightweight CPU skinning path (no compute dispatch, no output-SSBO round trip, no pipeline barrier) - friendlier to integrated GPUs; the CPU skinning hot path is inlined (~2.4 ms -> ~1.6 ms per 11.5k vertices)

#### Compatibility

- **Old GPUs / integrated graphics**: complete CPU skinning on desktop GL 3.3+ or OpenGL ES 3.0+; macOS (GL 4.1, no compute shaders) is no longer restricted
- **Iris / Oculus shader packs**: the GPU path and the CPU skinning path yield to Epic Fight's compute path (built-in Iris support); without compute shaders, the triangulation-fixed drawPosed takes over

---

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
