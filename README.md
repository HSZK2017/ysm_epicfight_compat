# YSM Epic Fight Compat

让你的 **Yes Steve Model（YSM）角色模型使用 Epic Fight 的战斗动画**，适用于 Minecraft 1.20.1 / Forge。

- **战斗模式**：本模组转换当前 YSM 模型，由 Epic Fight 驱动攻击、行走等动作。
- **非战斗模式**：继续使用 YSM 原有的渲染与动画。
- **多人游戏**：同步玩家选用的模型；专用服务器也需要安装本模组，观看者本地需要有对应模型包。

支持官方 YSM 2.6.5、OpenYSM 和 ModernYSM 的兼容分支。官方 2.6.5 本身就是混淆发行版；升级 YSM 分支或 Epic Fight 后，需要重新检查兼容性。

## 阅读导航

| 你的目的 | 从这里开始 |
|---|---|
| 安装并使用模组 | [安装与使用](#安装与使用) |
| 调整性能或显示效果 | [常用配置](#常用配置) |
| 模型不显示、穿模或光影异常 | [常见问题与限制](#常见问题与限制) |
| 编译项目 | [构建与测试](#构建与测试) |
| 理解代码、修改功能 | [项目如何工作](#项目如何工作) → [开发入口](#开发入口) |
| 查类职责、渲染细节和验证记录 | [技术参考](docs/technical-reference.md) |
| 规划扩展或调整模块边界 | [架构设计与演进约束](docs/ARCHITECTURE.md) |

## 安装与使用

### 环境与依赖

| 项目 | 要求或基准版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 构建基准为 47.4.16；模组元数据允许 47.x |
| Epic Fight | 20.14.17 为兼容基准；声明范围为 `>=20.14.17, <20.15` |
| Yes Steve Model | 官方 2.6.5 为兼容基准；声明范围为 `>=2.6, <2.7`；也适配 OpenYSM / ModernYSM 分支 |
| 女仆联动（可选） | Touhou Little Maid 1.5+ 与 EpicFight_TouhouLittleMaid（`ef_tlm`）1.1+ |

版本范围表示允许加载的范围，不代表范围内的所有发行版都经过验证。YSM 分支的事件签名或混淆名变化可能影响兼容性。

### 使用步骤

1. 将 Forge、Epic Fight、选用的 YSM 发行版和本模组安装到同一游戏实例中。
2. 按 YSM 的方式导入模型，并在 YSM 模型选择界面选中角色。
3. 进入 Epic Fight 战斗模式，检查角色是否保持 YSM 外形并使用战斗动作。
4. 退出战斗模式，检查是否恢复 YSM 原有渲染与动画。

本模组不要求你手动导出 Epic Fight 网格。模型在首次使用时后台转换，之后优先读取校验通过的缓存。首次转换期间可能短暂显示 Epic Fight 默认人形；贴图上传期间也可能短暂显示缺失纹理。

### 多人游戏与女仆联动

- 专用服务器需要安装本模组，用于读取并广播玩家的模型选择。
- 模型选择同步不等于模型包传输。观看远程玩家的客户端仍需在本地拥有对应 YSM 模型。
- 会话中途新下载的模型，可用 `F3+T` 或 `/ysm model reload` 触发重新加载。
- 女仆联动处理的是女仆的 **YSM 模型**；TLM 自带 GEO 模型包由 EFTLM 模组处理。

## 常用配置

客户端配置文件：`config/ysm_epicfight_compat-client.toml`。

| 选项 | 默认值 | 用途 |
|---|---|---|
| `enableGpuRender` | `true` | 启用本模组 GPU 蒙皮。OpenYSM / 官方 YSM 使用此项；ModernYSM 使用自身 GPU 配置联动 |
| `lazyModelCacheSize` | `64` | 内存中保留的模型数，范围 8–512。降低可减少驻留资源，但切换模型时可能需要重新恢复资源 |
| `scriptAsyncEval` | `true` | 在后台计算非本地玩家的脚本动画 |
| `disableExtraPlayerInBattleMode` | `true` | 战斗模式中关闭 YSM 的额外玩家预览（纸娃娃），减少重复渲染 |
| `enableSecondaryMotion` | `true` | 启用头发、尾巴、裙摆等部件的二次运动 |
| `secondaryMotionGravityAcceleration` | `24.0` | 当前摆锤二次运动的向下加速度 |

ModernYSM 下，本模组 GPU 路径跟随其 `UseGpuRenderer` / `UseCompatibilityRenderer`；本模组的 `enableGpuRender` 不控制该分支，也不重复添加界面复选框。

**注意二次运动配置的区别**：`secondaryMotionGravity` 属于尚未接入渲染的布料求解器，不是上表的 `secondaryMotionGravityAcceleration`。当前 `secondaryMotionGravity`、`secondaryMotionMaxParticles`、`secondaryMotionIterations`、`secondaryMotionBodyRadius` 不产生可见布料效果。更多参数见源码 `config/YSMCompatConfig.java`。

## 常见问题与限制

| 现象或场景 | 说明与检查方向 |
|---|---|
| 首次使用模型时短暂变成默认人形 | 后台转换尚未完成；后续使用优先恢复缓存 |
| 联机时看不到他人的模型 | 检查专用服务器是否安装本模组，以及观看者本地是否有对应模型包 |
| 升级依赖后重复渲染或换装失效 | 检查 YSM 分支和 Mixin 目标签名；方法未匹配时可能没有日志 |
| GPU 不可用 | 自动选择可用回退路径；GPU 直连需桌面 GL 4.3+ / GLES 3.1+，本模组 CPU 路径需 GL 3.3+ / GLES 3.0+ |
| 使用 Iris / Oculus 光影包 | GPU / CPU 直连让位光影计算路径；优化 Iris 路径默认开启，异常时可加 `-Dysm_ef_compat.disable_iris_compute_path=true` 回退 |
| 裙摆被腿穿出 | 部分模型按双腿并拢姿态制作，与 EF 站姿和腿部动作不匹配。当前物理无法保证修复；需要调整模型裙摆余量或 EF 腿部姿态 |
| WebP / AVIF 贴图不显示 | 依赖 YSM 的反射解码支持；相应实现缺失时跳过并告警。支持 PNG / JPEG，不支持 BMP |
| 超大模型包被拒绝 | 源文件与解压载荷默认各限 512 MiB；调整方法见技术参考 |

Android ES 路径仍待真机验证。Iris 优化路径原有实机验证仅覆盖一台机器、一种光影、9 个模型，未覆盖超 1000 关节容量及描边 / GUI 通道。

需要诊断日志时，在 JVM 参数中加入 `-Dysm_ef_compat.diag=true`。完整调试参数、缓存规则、条件化变体限制及裙摆验证记录见[技术参考](docs/technical-reference.md)。

## 构建与测试

### 构建前准备

项目使用 **Java 17 toolchain**。当前 `gradle.properties` 中的 Gradle JVM 与 JDK 17 路径是本机绝对路径，并关闭了 JDK 自动检测；在其他机器构建前，需要改为自己的安装路径或调整检测设置。

当前构建脚本从 `libs/` 读取以下文件，即使不使用女仆联动，源码构建也需要这些本地编译依赖：

```text
libs/
├── ysm-2.6.5.jar
├── touhoulittlemaid-1.5.3.jar
└── ef_tlm-1.1.1.jar
```

Epic Fight 和 zstd-jni 由 Gradle 获取。运行时女仆联动仍为可选功能。

### Windows 命令

在项目根目录执行：

```powershell
.\gradlew.bat build
.\gradlew.bat test
```

当前版本产物：`build/libs/YSM_EpicFight_Compat-1.20.1-1.9.0-all.jar`，内嵌 zstd-jni。修改项目版本后，文件名随之改变。

可选：提供真实 `.ysm` 文件，运行解密链黄金用例：

```powershell
.\gradlew.bat test "-Dysmef.golden.ysm=C:\path\to\model.ysm"
```

升级 OpenYSM 或 ModernYSM 时，可用对应源码检查 Mixin 目标的方法签名：

```powershell
.\gradlew.bat test "-Dysmef.fork=open" "-Dysmef.fork.source=C:\path\to\OpenYSM" --tests com.ysmef.compat.contract.MixinTargetSignatureTest
.\gradlew.bat test "-Dysmef.fork=modern" "-Dysmef.fork.source=C:\path\to\ModernYSM" --tests com.ysmef.compat.contract.MixinTargetSignatureTest
```

单元测试无需启动 Minecraft，覆盖解析、Molang、哈希、路径校验等逻辑；渲染效果仍需进入游戏验证。测试分类见[技术参考](docs/technical-reference.md)。

## 项目如何工作

理解代码时，先区分 **模型准备** 和 **每帧渲染**。解密、转换与缓存恢复发生在模型准备阶段；每帧渲染使用准备好的网格和当前动画姿态。

### 1. 模型准备：把 YSM 模型变成 EF 可以使用的资源

```text
玩家当前 modelId
    ↓ YSMModelAccess / YSMMeshSelector：读取选择、查找网格
YSMMeshLibrary：已有网格？校验缓存？需要后台转换？
    ↓ 需要转换时
YsmModelPackage：加载目录包或 .ysm 二进制包
    ├─ 目录包 → YSMGeoModel.parse
    └─ .ysm 包 → YsmFileCrypto → YsmBinaryReader → YSMGeoModel.fromBinary
    ↓
EFMeshJsonWriter：生成 EF 网格 JSON 与运行时 JSON
    ↓
网格注册 + TextureStore 纹理准备 + ManifestStore 缓存清单
    ↓
模型可供渲染；运行时骨骼与脚本数据后台预编译
```

两个输出各有用途：**网格 JSON** 提供顶点、贴图坐标与关节绑定；**运行时 JSON** 提供骨骼层级、绑定矩阵与脚本动画。缓存经过指纹和文件哈希检查，损坏模型单独重新转换。

### 2. 每帧渲染：把当前姿态应用到准备好的模型

```text
玩家渲染入口 → 选择转换后的 YSMMesh
    ↓
Epic Fight 提供关节姿态
    ↓
YSMRuntimeBridge：应用部件可见性与二次运动
    ↓
按光影状态、硬件能力和配置选择可用渲染路径
    ├─ GPU 直连蒙皮
    ├─ Iris / Oculus 光影计算路径
    └─ EF 计算、本模组 CPU 或 EF drawPosed 回退
```

上图表示条件分支，不表示每帧依次执行所有路径。战斗动作由 EF 驱动；玩家部件可见性还会读取持久的轮盘开关，随后应用二次运动。非战斗模式继续由 YSM 自身渲染。

## 开发入口

Java 包根目录为 `src/main/java/com/ysmef/compat/`。

| 想了解或修改什么 | 包 / 关键类 | 主要职责 |
|---|---|---|
| 模型包、解密和格式解析 | `ysm/`：`YsmModelPackage`、`YsmFileCrypto`、`YsmBinaryReader` | 将目录包或二进制包读成统一模型数据 |
| 几何、骨骼映射和转换输出 | `model/`：`YSMGeoModel`、`YSMJointMapper`、`EFMeshJsonWriter` | 将 YSM 几何转换成 EF 网格和运行时数据 |
| 加载、缓存、纹理与释放 | `model/`：`YSMMeshLibrary`、`TextureStore`、`ManifestStore` | 按需准备模型并管理资源生命周期 |
| 可见性、脚本与二次运动 | `model/runtime/`：`YSMRuntimeBridge`、`YSMPlayerAnimator`、`YsmMeshSecondaryMotion` | 把运行时结果应用到模型部件 |
| 玩家选择与渲染接管 | `renderer/`：`YSMModelAccess`、`YSMMeshSelector`、`YSMPlayerRenderer` | 确定画哪个模型、由谁绘制 |
| GPU / CPU / 光影路径 | `gpu/`、`cpu/` | 根据能力和场景完成蒙皮与绘制 |
| YSM 发行分支识别 | `ysm/YsmFork`、`gpu/YsmGpuRenderEnable` | 识别分支并决定 GPU 开关归属 |
| 多人同步 | `network/` | 读取与广播模型选择 |
| 渲染冲突、事件签名适配 | `mixin/`、`event/` | 连接 YSM、EF 和游戏渲染事件 |
| 配置 | `config/YSMCompatConfig` | 参数、默认值与范围的定义来源 |

建议阅读顺序：`YSMMeshSelector` → `YSMMeshLibrary` → `YsmModelPackage` → `EFMeshJsonWriter` → `YSMRuntimeBridge` → 对应渲染路径。

修改转换输出时，还要检查 `build.gradle` 的生成器指纹规则：目前它根据 `model/` 下 Java 文件内容生成缓存指纹，范围外的相关改动不会自动纳入该指纹。

## 更多资料

- [架构设计与演进约束](docs/ARCHITECTURE.md)
- [技术参考：类职责、Mixin、性能、调试参数与完整限制](docs/technical-reference.md)
- [更新记录](CHANGELOG.md)
- [MIT 许可证](LICENSE)
