# T1 检测链路取证报告（物理部件发现：纳入内置默认控制器 + 骨骼交叉验证）

任务：`task-1` T1　执行者：`detect-lead`　日期：见文件 mtime
状态：**compileJava 通过；`YsmPhysicsBindingTest`(23) + `EFMeshJsonWriterPhysicsSectionTest`(6) 共 29 项测试，0 失败 0 跳过**

---

## 0. 一句话结论（先看这个）

1. **内置默认控制器集已接入**：`YsmPhysicsBinding` 现在同时读「模型自己的 controller 表 + YSM 内置 `misc/4_default_controllers` 表」，并带 fail-closed 与可观测日志。
2. **交叉验证用的是"能不能动"而不是"名字像不像"**：候选动画必须驱动至少一根**该模型真实拥有、且自身或子树带几何体**的骨骼，否则拒绝并试下一个候选；拒绝原因写进日志。
3. **对 `wine_fox/01_taisho_maid`：命中数 = 0，必须继续走骨骼名回退**。它 195 根骨骼里与内置 `Hair_Physics` 的 58 根只有 1 个**同名**（`ElytraLocator`），而该骨骼 **0 个立方体、0 个子骨骼** —— 不可模拟。若强行选中 `Hair_Physics`，运行时会拿到 1 个非几何部件的 authored 列表 → `YsmPhysicsParts.buildSegment` 全部返回 null → `Source.NONE` → **该模型物理从 59 根直接掉到 0 根**（比现状更糟）。
   → 因此 Lead 提出的硬指标「重跑转换后 `01_taisho_maid.json` 里出现 `physics` 段」**在不造成回归的前提下不可达成**，建议改为见 §6 的替代指标。
4. **改动的真实收益要说准（见 §4，全量 29 个目录包逐条核对）**：新规则与旧规则在 **28/29** 个模型上结果相同；**没有一处回归**；真正被修好的是 `wine_fox/17_mini`（旧规则选中 `parallel0`，其唯一命中骨骼存在但无几何体 → 运行时 `Source.NONE` → **物理全灭**；新规则跳过它并选中 `pre_parallel0`，得到 6 根可用部件）。另有 2 个模型（`06_hanfu`、`22_elf`）旧规则写出的是"全是不存在骨骼"的段，运行时本就回退，新规则只是不再写这份无用数据。
5. **内置表在本机的直接收益是"诊断"而非"新增物理"**：29 个目录包里没有任何模型能接受来自内置表的动画（它们的骨架与 `Hair_Physics` 无共同可动骨骼）。它的价值是：① 让"内置动画被绑定但驱不动这个模型"从**静默**变成**一行日志**；② 修好 17_mini；③ 为骨架确实继承默认控制器的第三方模型（本机未出现）正确接线；④ 防止未来"第一个像物理的就选"再次关掉物理。
6. **现场那 83 个运行时 JSON 是旧构建产物**：0/83 有 `physics` 段 → 它们是"authored 路径尚未生效"时生成的。按 §3.2，当前代码在**重新转换后**会让 13 个模型首次带上 authored 物理（另有 16 个继续走回退）。所以本任务的收益**以一次模型重新转换为前提**。

---

## 1. 改动清单（写范围内）

| 文件 | 改动 |
|---|---|
| `model/runtime/YsmPhysicsBinding.java` | 新增 `Sources`（模型表 + 内置表 + 可模拟骨骼名）、`Selection`、纯函数 `select(Sources)`；候选顺序 = 模型 controller → 模型 `pre_parallel*`/`parallel*` → 内置 controller；同名以模型自身定义优先；候选须命中 >0 才接受；parts 过滤到可模拟骨骼。删除旧的 2 参数 `discover`/`physicsAnimationName`（防止未来调用方再次丢掉内置表，正是 T1 的 bug 类别） |
| `ysm/YsmModelPackage.java` | 新增内置控制器集加载器 `builtinControllers()`（缓存一次、失败 fail-closed + 一行 WARN）；`loadFolder` 改用新 API，并把"从内置集继承来的物理动画定义"一并放进 `allScriptAnims`（否则运行时 `authoredAnimation(name)` 取不到表达式）；新增 `configRoot()` 取证挂钩（`-Dysmef.golden.ysm_config_root` / 环境变量 `YSMEF_YSM_CONFIG_ROOT`，游戏内不设置） |
| `model/EFMeshJsonWriter.java` | 新增 `simulatableBoneNames(YSMGeoModel)`（自身有立方体，或子树内有立方体 —— 与运行时 `descendantCentroid` 同义）；`writePhysicsSection(root, Sources)` 改为返回 `Selection`；转换时打印 "no usable physics animation; refused X" 一行 |
| `src/test/.../YsmPhysicsBindingTest.java` | 新增 6 项规则测试（内置集路由、阴影规则、跳过候选、非几何命中拒绝、无骨骼表时的既有行为） |
| `src/test/.../EFMeshJsonWriterPhysicsSectionTest.java` | 新增几何判定测试 + **真机端到端转换测试**（读真实 YSM 配置根，产出 `tmp_verify/T1_java_forensics.md`） |
| `src/test/resources/golden/physics/*_bones.json` | 新增 3 个真实骨骼名 fixture（195/141/149 根 + 可模拟子集），取自真机 `models/main.json` |

未动（他人在改）：`YsmDynamicBoneSolver` / `YsmMeshSecondaryMotion` / `YsmPhysicsParts` / `YsmPhysicsChains` / `YsmBodyColliders` / `YsmPhysicsSimulator`。

---

## 2. 已确证的文件级事实（可复核）

| 事实 | 证据 |
|---|---|
| 内置默认控制器集 = `builtin/misc/4_default_controllers`，其 `player.pre_parallel_0` 播放 `Hair_Physics` | `controller/main_controllers.json`；sha256 = `B5B6C66973767875B0D76D43DDDFBCF99B61FFC63098F7326A29C3C6F94239AE`，与仓库既有 golden fixture `golden/physics/default_controllers.json` **逐字节相同**（说明项目早已认定它就是那套默认控制器） |
| `Hair_Physics` 全 `builtin` 树内**只有一个**文件定义 | 全量扫描 `*.animation.json`：仅 `misc/4_default_controllers/animations/main.animation.json` |
| `Hair_Physics` 驱动 58 根骨骼，全部在 rotation 通道带 `ysm.second_order` | `4_default_controllers/animations/main.animation.json` |
| `01_taisho_maid` 几何 195 根；**自身或子树带几何**的 175 根 | `models/main.json` |
| 名字交集 = **1**（`ElytraLocator`），**可模拟交集 = 0** | `ElytraLocator` 在该模型上 `cubes` 为空、无任何子骨骼 |
| 该模型自己的 114 个动画里**一个物理调用都没有** | 无 `ysm.second_order(`/`first_order(`/`bone_rot(`/`bone_pos(`，也无名字含 physics 的动画；其 `pre_parallel0..7`、`parallel*` 全是普通关键帧 |
| 内置集的 `pre_parallel1`/`pre_parallel2` 等**不构成漏判** | 各模型若自己定义了同名动画，按 Bedrock 规则以自身为准（有单元测试钉住） |

**必须先讲清的关键区分（Lead 的两次侦察结论看似矛盾，实为两件事）**：
- 名字层面：模型有 1 根骨骼与 `Hair_Physics` 同名（`ElytraLocator`）→ 若规则是"命中数 > 0"就会**误选**。
- 可模拟层面：这 1 根是 YSM 骨架模板里人人皆有的定位骨，无几何体、无子骨骼 → **命中数 0**。
因此规则里"命中"的定义必须是**能动的骨骼**，否则 `ElytraLocator` 这一个模板骨骼会让内置 `Hair_Physics` 被几乎**每一个**模型误判为"自己的物理动画"（见 §3 全量扫描：26 个内置模型里 17 个命中数恰为 1，全是同一根定位骨）。

---

## 3. 端到端取证

### 3.1 运行时 JSON（现场实况，改前）
`...\config\ysm_epicfight_compat\resourcepack\assets\ysm_epicfight_compat\ysm_runtime\entity\`

- 全量扫描：**83 个运行时模型 JSON，带 `"physics"` 段的有 0 个**。
- `wine_fox\01_taisho_maid.json` 顶层键只有 `bones / animations / scale / camera`，**没有 `physics` 段**，也就**没有 `animation` 字段**（该字段只在写 `physics` 段时写入）。
- 与现场 `latest.log` 唯一那行 `[... ]: 59 simulated bone(s) from bone names (this model declares no physics animation)` 完全一致：**AUTHORED 路径在这台机器上从未生效过一次，所有模型都在走骨骼名回退**。
- 注：`animmodels\entity\` 是另一套缓存，不是运行时消费的模型 JSON。

### 3.2 转换路径（用生产代码重跑，Java 侧）
命令（`tmp_verify/T1_java_forensics.md` 由该测试生成）：

```
$env:YSMEF_YSM_CONFIG_ROOT='C:\Users\ASUS\AppData\Roaming\.minecraft\versions\EPIC mod test\config\yes_steve_model'
.\gradlew.bat test --tests "com.ysmef.compat.model.EFMeshJsonWriterPhysicsSectionTest" --console=plain
```

`YsmModelPackage.load()` + `EFMeshJsonWriter.writePhysicsSection()` 实跑结果：

| 模型 | 自带 controller / 动画 | 选中动画 | declared | matched | parts | 拒绝原因 |
|---|---|---|---|---|---|---|
| `wine_fox/01_taisho_maid` | 1 / 103 | **(无)** | 58 | 0 | 0 | `Hair_Physics (drives 58 bone(s), none of which this model has geometry for)` |
| `wine_fox/22_elf` | 1 / 181 | **(无)** | 4 | 0 | 0 | `pre_parallel7 (drives 4 bone(s), none ...)` |
| `wine_fox/03_astronaut` | 3 / 145 | `parallel0` | 24 | 7 | 7 | — |
| `misc/3_default_boy` | 0 / 57 | **(无)** | 58 | 0 | 0 | `Hair_Physics (drives 58 bone(s), none ...)` |
| `misc/4_default_controllers` | 5 / 121 | `Hair_Physics` | 59 | 58 | 58 | — |

（`*_bones.json` 三个 fixture 亦取自真机数据：195→175、141→125、149→132 根可模拟骨骼。）

### 3.3 两套独立实现交叉复核
- `tmp_verify/T1_forensics.ps1`：**不依赖任何 Java 代码**，纯 PowerShell 5.1 直接读原始 JSON 重算规则；输出 `T1_forensics_allsweep.txt`（全量 29 个目录包，含每个候选的"drives N, present M"轨迹）。
- 与 Java 结果在上表 5 个模型上**逐项一致**（含 `03_astronaut` 的 `parallel0 / declared=24 / matched=7`）。
- 脚本早期两个 bug 已被这套交叉复核照出来并修掉（拒绝项覆盖已接受项计数、非确定性字典序），**这正是做双实现的价值**。

---

## 4. 改前 / 改后（全量 29 个目录包，逐条核对）

统计口径（每一步都要分开，否则结论会错）：
- **真实旧规则** = 只看模型自己的 controller ∪ 自己的 `parallel*`；无内置表、无交叉验证、parts 不过滤。
- 对每个模型的旧候选记三个数：`drives`（该动画驱动的骨骼数）、`exists`（其中存在于本模型骨骼表的数量）、`present`（其中**自身或子树带几何体**的数量）。
- 运行时行为分三种：`exists=0` → authored 列表全部落空 → `selected.isEmpty()` → 回退骨骼名（无害）；`exists>0 ∧ present=0` → 选中了但 `buildSegment` 全 null → `Source.NONE` → **物理关闭**；`present>0` → 正常 authored 物理。

| 模型 | 旧规则选中的（自带表）drives/exists/present | 新规则选中 | 判定 |
|---|---|---|---|
| `builtin/wine_fox/17_mini` | `parallel0` 1 / 1 / **0** | `pre_parallel0`（declared 11, matched 6） | **被修好**：旧=物理关闭，新=6 根 authored 部件 |
| `builtin/wine_fox/06_hanfu` | `parallel0` 24 / **0** / 0 | （无）回退 | 行为不变；旧只是多写了一份无用段 |
| `builtin/wine_fox/22_elf` | `pre_parallel7` 4 / **0** / 0 | （无）回退 | 行为不变；同上 |
| 其余 26 个 | 与"新规则选中"一致（无候选 / 有候选且 present>0） | 同旧 | 不变 |

结论：**28/29 行为不变，0 处回归，1 处修复（17_mini）**，另有 2 处产出数据变干净（不再写"全是不存在骨骼"的段）。

明细（含 `[own]`/`[builtin]` 来源标注与 exists/present 三个计数）：`tmp_verify/T1_before_after.csv`、`tmp_verify/T1_forensics_allsweep.txt`。

**新规则在本机的接受/拒绝分布**：13 个目录包拿到 authored 物理（`builtin/default`、`misc/4_default_controllers`、`wine_fox/{03_astronaut,08_sta,09_hailuo,10_zhiban,15_kluonoa,17_mini,18_wedding,20_survivor,21_saint}`、`custom/valencina*2`），16 个继续回退（§5）。

---

## 5. 仍然"检测不到物理"的名单（16 / 29 个目录包）

这些模型在新规则下**没有 authored 物理**，继续走骨骼名回退分类 —— 对它们是**正确结果**（模型确实没写物理，或内置动画驱动不了它们）：

| 模型 | 被拒候选 | driven / present |
|---|---|---|
| `builtin/misc/1_alex` | Hair_Physics | 58 / 0 |
| `builtin/misc/2_steve` | Hair_Physics | 58 / 0 |
| `builtin/misc/3_default_boy` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/01_taisho_maid` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/02_new_year` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/04_kongfu` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/05_magical` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/06_hanfu` | parallel0 | 24 / 0 |
| `builtin/wine_fox/07_jk` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/11_salesperson` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/12_little` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/13_matured` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/14_momo` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/16_tactics` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/19_nine_tailed` | Hair_Physics | 58 / 0 |
| `builtin/wine_fox/22_elf` | pre_parallel7 | 4 / 0 |

**这就是为什么 T4（骨骼名/启发式回退链路）同样关键**：用户抱怨的"战斗模式下衣物/头发/尾巴没有真正物理"，在当前这台机器上对**全部 83 个运行时模型**都只能由回退路径解决。T1 的贡献是让"哪些模型没有 authored 物理、为什么没有"变成可观测事实，并挡住会**关掉物理**的错误 authored 段。

---

## 6. 建议 Lead 采用的验收指标（替代原硬指标）

| 原指标 | 问题 | 建议替代 |
|---|---|---|
| 重跑后 `01_taisho_maid.json` 出现 `physics` 段 | 会把它从 59 根模拟骨骼变成 0 根（唯一命中骨无几何体） | ① 该模型**不出现** `physics` 段，且日志出现一行 `[physics] model 'wine_fox/01_taisho_maid': no usable physics animation; refused Hair_Physics (drives 58 bone(s), none of which this model has geometry for)`；② `misc/4_default_controllers` 必须仍有 `physics` 段且 `animation=Hair_Physics`（正向对照，防"一律拒绝"的假修复）；③ `wine_fox/17_mini` 须从"无物理"变为 `physics` 段（`pre_parallel0`，6 根部件）——这是本次改动在本机上唯一的行为级修复，也是最能证明链路真的跑通的哨兵；④ 29 个目录包重跑后逐项与 §4 基线一致（尤其"0 处回归"） |
| — | — | ⑤ 对 29 个目录包跑 `tmp_verify/T1_forensics.ps1`，结果须与 Java 侧一致（§3.3 已建立基线） |

---

## 7. 未证实 / 未验证的部分（不要当作已完成）

1. **没有启动游戏**。所有结论来自真实模型文件 + 生产代码路径；`physics` 段真正进入运行时渲染后的表现（`Source.AUTHORED` 分支、表达式求值、观感）**未在游戏内验证**。要跑通需要一次真实模型重新转换（进服/换模型触发）。
2. **53 个加密 `.ysm` 二进制模型（`custom`，共 109.1 MB）未被扫描**。它们由 `YsmModelPackage` 走 `loadBinary` 路径，本次取证只覆盖目录包（`builtin` 27 + `custom` 2 = 29 个）。若用户实际使用的是某个 `.ysm`，其结论可能不同。加密包需在游戏内或专用 harness 中才能读。
3. **`built` 是指向 `C:\.minecraft\...\built` 的 junction**，内容与 `builtin` 同名同级；本次只扫了 `builtin`。若两者内容不一致（未逐字节核对），以 `builtin` 为准会漏掉 `built` 的差异。
4. **`custom\valencinaV1.20.11` 两个包的 `parallel0` 只有 1 根骨骼命中**（`matched` 4，来自多个候选合并），未逐帧核对观感。
5. 运行时 `YsmPhysicsParts` 在 authored 列表全部产出不了 segment 时返回 `Source.NONE`（而不是回退到骨骼名），这是**他人写范围内**的既存行为；T1 通过拒绝这类列表绕开了它，但**该类文件本身未改动**（建议 Lead 分派给对应负责人复核）。
6. 报告里 `declared/matched` 的语义：`declared` 为被接受候选的驱动骨骼数之和（含跨候选重复），`matched` = `parts.size()`（去重后能动的骨骼数）。

---

## 8. 复现命令

```powershell
# 1) 编译
cd "E:\program\JAVA\epic mod suitable\ysm_epicfight_compat"
Get-Process java -ErrorAction SilentlyContinue   # 有别的 gradle 在跑就等 60s（同一时刻只允许一个）
.\gradlew.bat compileJava --console=plain        # 不要 --offline

# 2) 单元测试 + 真机端到端取证（生成 tmp_verify/T1_java_forensics.md）
$env:YSMEF_YSM_CONFIG_ROOT='C:\Users\ASUS\AppData\Roaming\.minecraft\versions\EPIC mod test\config\yes_steve_model'
.\gradlew.bat test --tests "com.ysmef.compat.model.runtime.YsmPhysicsBindingTest" `
                   --tests "com.ysmef.compat.model.EFMeshJsonWriterPhysicsSectionTest" --console=plain
# 不给该环境变量时，端到端测试自动 skip，其余测试照常运行

# 3) 独立实现交叉复核（不依赖 Java）
.\tmp_verify\T1_forensics.ps1     # 默认跑 4 个指定模型；全量见下
```

产生物：`tmp_verify/T1_java_forensics.md`、`T1_forensics_allsweep.txt`、`T1_before_after.csv`、`T1_forensics_output.txt`、`T1_forensics.ps1`。
