# 更新日志 / Changelog

## 未发布 / Unreleased

### 中文

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
