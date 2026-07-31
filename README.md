# YSM Epic Fight Compat

**兼容 Yes Steve Model 与 Epic Fight 渲染管线的 Forge 1.20.1 模组**

在史诗战斗模式下，将玩家当前选用的 YSM 自定义角色模型转换为史诗战斗的骨骼网格格式，使 YSM 模型以完整轮廓呈现，并由史诗战斗的蒙皮骨骼带动攻击、行走等全部战斗动画。

---

## 架构概览

```
进入游戏 / F3+T
     │
     ▼
扫描本地 YSM 模型文件（builtin/custom/auth）
     │
     ├── 目录包 (ysm.json + models/main.json) ──→ YSMFolderDeserializer 对等解析
     └── 二进制包 (*.ysm) ──→ XChaCha20 解密 → YsmZstd 清洗 → zstd-jni 解压
                                    │
                                    ▼
                         YSMBinaryDeserializer 序列忠实旁读
                                    │
                                    ▼
                        ┌── 内置动画 < 跳过 > ──┐
                        │  几何+贴图+属性  < 提取 >
                                    │
                                    ▼
                    生成 Epic Fight animmodels 网格 JSON
                    （Blender 坐标系、静态 biped 关节映射、
                      蒙皮权重、宽高缩放、顶点焊接）
                                    │
                                    ▼
                    写入资源包 → MeshAccessor 注册
                    贴图 → 动态 TextureManager 注册
                                    │
                                    ▼
        ┌────────────────战斗渲染 ────────────────┐
        │  YSMRenderHook (HIGHEST) 接管渲染      │
        │  Mixin 劫持 PPlayerRenderer.getMesh()   │
        │  根据玩家当前模型 id + 贴图名匹配网格   │
        └──────────────────────────────────────────┘
```

---

## 资源与参考

### YSM 源码（OpenYSM / LgeacyYSM / 发布 jar）

| 文件/模块 | 用途 |
|---|---|
| `OpenYSM/ServerModelManager.java` | 确定运行时模型文件夹布局（`builtin/custom/auth`） |
| `OpenYSM/YSMFolderDeserializer.java` | 模型目录包解析规范的**权威来源**——`ysm.json` 清单、`files.player.model.main` 几何路径、`files.player.texture` 贴图列表、`properties` 缩放/默认贴图 |
| `OpenYSM/YSMBinaryDeserializer.java` | 二进制格式的**完整布局**——`format` 字段（≤4→LegacyV1, 4~15→LegacyV15, ≥16→Modern），几何段 (`parseModels`)、贴图表 (`parseTextureFiles`)、动画/控制器/声音跳过长度 |
| `OpenYSM/YSMBinarySerializer.java` | 确认几何面数据已烘焙 cube 旋转（`bakeFaceToRaw` 中 `cubeBakeMat`），二进制读出的位置/法线/UV 直接可用 |
| `OpenYSM/YsmCrypt.java` | .ysm 解密入口 `decryptYsmFile`——Linux/Mac 头部、LE crypto 版本(3)、尾部 key+iv、`modifiedChaChaDecrypt` + `MT19937Xor` + 魔改 zstd |
| `OpenYSM/geckolib3/geo/render/built/GeoBone.java` | 运行时骨骼 `pivotX/Y/Z`（`-x,y,z` 原始 bedrock 单位）、`rotX/Y/Z`（`-r,-r,+r` 弧度） |
| `OpenYSM/geckolib3/util/RenderUtils.java` | 骨骼绑定链数学：`prepMatrixForBone = T(pivot)·Rz·Ry·Rx·T(-pivot)`，与 JSON 文件夹解析器保持一致 |
| `LgeacyYSM/geckolib3/geo/render/GeoBuilder.java` | Cube 原点 `-(x+sx)/16`、旋转 `(-x,-y,+z)`、镜像/膨胀处理 |
| `LgeacyYSM/geckolib3/geo/render/built/GeoCube.java` | GeoQuad 顶点顺序与 UV 分配（box / per-face UV、mirror/非 mirror UV 方向） |
| `发布 jar (ysm-2.6.5).class` | 验证运行时能力附加键 `yes_steve_model:model_id`（ModelInfoCapability **仅服务端挂载**）、`yes_steve_model:animatable`（客户端 PlayerCapability，不可序列化） |

### YSMParser（C++）

| 文件 | 用途 |
|---|---|
| `YSMParserV3.cpp` | 独立实现作为**加密和解密的交叉验证参考**——XChaCha20 滚动状态、CityHash seed、MT19937 白化种子、魔改 zstd 块头洗牌全部一一核对通过 |
| `CryptoAlgorithms.cpp` | `ModifiedChaChaDecrypt` / `MT19937Xor_Decrypt` / `DecompressZstd` 的 C++ 对等实现，确认 Java 移植的算法精度 |

### Epic Fight

| 文件 | 用途 |
|---|---|
| `epic-fight-20.14.17 反编译/javap` | 1.20.1 运行时 API 签名——`PPlayerRenderer.getMeshProvider`（`remap=false` 等位注入基础）、`PatchedLivingEntityRenderer.render` 流程、`EpicFightRenderTypes.replaceTexture/getTriangulated`、`Armature/Joint` 关节模型 |
| `assets/animmodels/entity/biped.json` | EF 网格 JSON 格式规范——12 个标准 humanoid 部件名、`vertices.{positions,normals,uvs,vcounts,vindices,weights,parts}`、`render_properties` 贴图路径 |
| `animations/biped/living/idle.json` | 离线模拟使用的实际动画数据，验证 YSM 转换网格在 EF 动画驱动下的变形范围与 EF 自身网格一致 |
| `RenderUtils / Armature / JointTransform` | 蒙皮数学验证——`pose × bindWorld⁻¹`（差值蒙皮），`initOriginTransform` 绑定世界矩阵的逆 |

### EpicFight_TouhouLittleMaid（参考兼容范例）

| 文件 | 用途 |
|---|---|
| `EFTLM_Meshes.java` | `Meshes.MeshAccessor.create` + `loadSkinnedMesh` 注册模式——运行时自定义网格 → EF 网格注册表的标准方法 |
| `PatchedMaidRenderer.java` | 补丁渲染器的超类选择——`PHumanoidRenderer<E,T,M,R,AM>` 的模式参考 |
| `Model/Mesh/MaidMesh.java` | 自定义 `SkinnedMesh` 的子类构造器签名——`(Map, Map, SkinnedMesh parent, RenderProperties)`，与 `loadSkinnedMesh` 的 `MeshContructor` 接口一致 |

---

## 实现方法

### 1. 模型扫描与解码

**目录模型** (`ysm.json` + `models/main.json`): 解析 `ysm.json` 清单 → 取出 `files.player.model.main`（几何 JSON 路径）→ `YSMGeoModel.parse` 解析 Bedrock 几何。此解析器逐行对等 `YSMFolderDeserializer.bakeFaceToRaw`：cube 原点 `-(x+sx)/16`、cube 枢轴旋转 `rotZ(cr_z)·rotY(-cr_y)·rotX(-cr_x)`、镜像/膨胀分支、box UV / per-face UV。

**二进制模型** (`*.ysm`):
1. 解密——`YsmFileCrypto.decryptYsmFile` 移植自 `OpenYSM/YsmCrypt`：读取 ASCII 头部末尾的 `crypto`（LE）、尾部 key+iv → `modifiedChaChaDecrypt`（XChaCha20 滚动状态）× `MT19937Xor` × 跳过 (2+n) → `washZstd`（YSM 魔改块头洗牌成标准 zstd）→ `ZstdInputStream` 流式解压（避免不携带 content-size 帧的 `decompressedSize` 报错）
2. 读取——`YsmBinaryReader.read` 支撑完整 `format` 版本链（≤4→LegacyV1, 4~15→LegacyV15, ≥16→Modern），逐一匹配 `YSMBinaryDeserializer` 的各段字节偏移，跳过动画/控制器/声音/语言，仅提取几何、贴图、属性；字节序严格 `LITTLE_ENDIAN`（YSM C++ 侧约定）

### 2. 几何转换

**Cube 构建** (`YSMGeoModel.buildCube`): 复制 `GeoCube.createFromPojoCube` 的 8 个顶点、6 个面、box UV / per-face UV、镜像（cubeMirror | boneMirror）、膨胀、cube 枢轴旋转。**关键修复**——`makeQuad` 中每面持有独立的顶点副本（原实现共享 `Vector3f` 引用导致 cube 旋转时对角点被原地应用 2~3 次，装饰骨骼产生数十块的散射偏差）

**骨骼绑定链** (`EFMeshJsonWriter.walkBone`): 沿骨骼层级 `T(pivot)·Rz(ry)·Ry(ry)·Rx(rx)·T(-pivot)` 累积绑定世界矩阵，对等 `RenderUtils.prepMatrixForBone`

**EF 坐标系转换**: EF 网格 JSON 按 Blender 空间存储，其加载器施加 `BLENDER_TO_MINECRAFT_COORD = rotX(-90°)` → `(x,y,z)_b → (x,z,-y)_mc`。转换器将 MC 绑定空间写为 `(px, -pz, py)`

**关节映射** (`YSMJointMapper`): 静态 biped 关节 id 表（Root=0 ~ Elbow_L=19，与 `biped.json` armature 中 `"joints"` 有序列表对齐）；骨骼名标准化（lowercase + 去分隔符）→ EF 关节名；运行期验证一次（`validateArmatureOnce`）

**宽高缩放**: YSM 模型渲染时 `poseStack.scale(width, height, width)`，EF 不会独立应用此缩放，因此将 scale 直接烘焙进顶点位置

**顶点焊接** (`EFMeshJsonWriter.VertexKey`): 基于量化键值——位置 ×1000、法线 ×100、UV ×4096、关节 id ——合并近似相同的顶点以减少 VBO 规模

**12 部件分组**: 按 EF biped 关节→身体部位`{head, torso, leftArm, rightArm, leftLeg, rightLeg}`+6 个空覆盖层（hat/jacket/sleeves/pants），保证 `HumanoidMesh` / `PPlayerRenderer.prepareModel` 不访问 null 部件

### 3. 贴图处理

**文件夹模型**: 贴图为 PNG 文件 → `NativeImage.read` → `DynamicTexture` → `TextureManager.register`

**二进制模型（Legacy 格式）**: 贴图格式为**原始 RGBA**（`imageFormat=-1`、`[宽, 高]` 由纹理表字段提供）→ 按 MC 的 ABGR 像素打包逐行写入 `NativeImage`（`setPixelRGBA`）

所有贴图以 `ysm_epicfight_compat:textures/<模型id>/<贴图名>.png` 注册，名称中非 ASCII 字符落在 hash 后缀以避免 ResourceLocation 非法字符

### 4. 网格注册

1. 通过 `AddPackFindersEvent` 注册 `config/ysm_epicfight_compat/resourcepack` 为常驻资源包
2. `Meshes.MeshAccessor.create`（public static）以 `entity/<meshId>` 登记每个网格，与 `EFTLM_Meshes` 的注册模式完全一致
3. 网格 JSON → `JsonAssetLoader` → `loadSkinnedMesh(YSMMesh::new)` 完成加载

### 5. 渲染依赖

**Renderer 注册**: `PatchedRenderersEvent.Add`（`EventPriority.LOWEST`）将 `YSMPlayerRenderer` 加入 EF 渲染表，`initLayerLast` 补充未被显式补丁的 vanilla 层（护甲/鞘翅）

**Mixin 劫持** (`PPlayerRendererMixin`): 字节码级注入 `PPlayerRenderer.getMeshProvider` 头部——补救 `YSMMeshSelector` 返回 YSM 网格，不依赖渲染器的最终注册归属，即使在 EF 战斗环节通过内层管道使用原始 PPlayerRenderer 也能生效

**渲染接管** (`YSMRenderHook`): `RenderLivingEvent.Pre` 的 `EventPriority.HIGHEST` 先于 YSM & EF 的 `NORMAL` 处理器——读 `EpicFightCapabilities.getEntityPatch→overrideRender` 判断 EF 接管权，调用 `renderEngine.renderEntityArmatureModel` + 取消原版路径；GUI/背包分支独立处理 `LocalPlayerPatch.setModelYRotInGui`

**贴图替换** (`YSMMesh.draw`): EF 管线绘制时重新绑定 render type 的贴图——`EpicFightRenderTypes.replaceTexture(ysmTex, vanillaRenderType)` 保留原版透明度/轮廓/剔除状态，仅贴图对象为 YSM 纹理

### 6. 运行时能力读取

**问题**: `ModelInfoCapability`（`yes_steve_model:model_id`）在 `CapabilityEvent.onAttachCapabilities` 中附加仅服务端（`!isClientSide`），客户端 ForgeCaps 始终为空

**解决** (`YSMModelAccess.readFromIntegratedServer`): 使用 `ServerLifecycleHooks.getCurrentServer()` → `ServerPlayer` → `saveWithoutId` 读取服务端持久化的 modelId / selectTexture → `/ysm model reload` 时通过 `CommandEvent` 检测并重新生成网格

---

## 构建

`build\libs\YSM_EpicFight_Compat-1.20.1-1.0.0-all.jar`——内嵌 `zstd-jni`（jar-in-jar）

依赖：
- Epic Fight 20.14.17+ (Modrinth maven)
- Yes Steve Model 2.6.5 (libs 文件夹本地 jar)
- Mixin 0.8.5
- zstd-jni 1.5.6-3 (jarJar 嵌套)
- JOML 1.10.5, Gson 2.10

---

## 限制

1. **大网格 OOM**: 顶点 >40k 的模型在游戏内构造 ComputeShaderSetup 时偶发 LWJGL MemoryStack 溢出（已加入堆栈打印，后续可对大网格强制走软件蒙皮路径）
2. **非 PNG 贴图**: BMP/Webp/AVIF 格式的贴图不支持解码（日志告警并跳过）
3. **远程纯客户端**: 无法访问整合/专用服务器的 `ModelInfoCapability`，回退 EF 默认 biped
4. **YSM 绑定姿势差异**: 转换网格在 YSM 绑定姿势烘焙——部件枢轴/旋转不同导致的轻微肢体变形是经典蒙皮复用的固有现象，与 EF 自身模型在战斗动画中的行为一致
