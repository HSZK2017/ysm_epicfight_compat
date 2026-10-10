# 依赖交接 / Dependency handoff

本表固定 **v1.10.1 的构建基准**，并区分玩家需安装的模组与维护者编译时使用的 JAR。下载应从上游版本页进行；文件名可在放入 `libs/` 时重命名，内容不可改。版本范围来自本项目 `mods.toml`，不表示范围内的每个发行版都经过实机测试。

This records the **v1.10.1 build baseline**. Install the runtime mods separately; the three renamed JARs in `libs/` are compile inputs. Declared version ranges are loader contracts, not a claim that every version was tested.

## 运行环境 / Runtime

| 组件 | 基准与来源 | 安装条件 |
| --- | --- | --- |
| Minecraft / Forge | 1.20.1 / [Forge 47.4.16](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) | 必需；Java 17 |
| Epic Fight | [20.14.17, Forge 1.20.1](https://modrinth.com/mod/epic-fight/version/20.14.17-mc1.20.1-forge) | 必需；本项目声明 `[20.14.17,20.15)` |
| Yes Steve Model | [官方 2.6.5, Forge 1.20.1](https://modrinth.com/mod/yes-steve-model/version/Zqooxsd2)；也支持经单独验证的 OpenYSM / ModernYSM 分支 | 必需；本项目声明 `[2.6,2.7)` |
| Touhou Little Maid | [1.5.3, Forge 1.20.1](https://modrinth.com/mod/touhou-little-maid/version/g1SKoGQJ) | 仅女仆联动；本项目声明 `1.5+` |
| EpicFight：TouhouLittleMaid (`ef_tlm`) | [1.1.1, Forge 1.20.1](https://modrinth.com/mod/epicfight_touhoulittlemaid/version/2vK2kKrN) 为编译基准 | 仅女仆联动；本项目声明 `1.1+` |
| Epic Fight Avalon | [20.12.6.4, Forge 1.20.1](https://www.curseforge.com/minecraft/mc-mods/epic-fight-avalon/files/7619938) | 安装 `ef_tlm` 时必需：其 1.1.1 JAR 的 `mods.toml` 声明 `epic_fight_avalon` 为强制依赖 |

安装本项目时只把 `YSM_EpicFight_Compat-1.20.1-1.10.1-all.jar` 放入游戏的 `mods` 目录。它内嵌 `zstd-jni`，不内嵌 Epic Fight、YSM、Touhou Little Maid、`ef_tlm` 或 Avalon。女仆联动不用时，不需要后三个可选模组。客户端与专用服务器应使用相互匹配的必需模组；模型包仍需在观看者本地可用。

Install only this project's `-all.jar` in `mods`. It bundles `zstd-jni`, but does not bundle the other mods. Maid integration requires all three optional rows above; without maid integration they are unnecessary.

## 构建输入 / Build inputs

`build.gradle` 自动从 Maven 获取 Forge、Epic Fight 与 `com.github.luben:zstd-jni:1.5.6-3`，并从仓库 `libs/` 读取下列三个**重命名的上游 JAR**。这些 SHA-1 与上游 Modrinth 版本 API 的主发行文件一致；SHA-256 是本仓库当前文件的校验值。

| `libs/` 文件 | 上游原文件 | SHA-1（上游与本地） | SHA-256（本地） |
| --- | --- | --- | --- |
| `ysm-2.6.5.jar` | `ysm-2.6.5-forge+mc1.20.1-release.jar` | `151ac7b24da8beeca1a20864565743cfd77af286` | `25b5e902b96f4c298690208f8b433cbc31737c23f87590354dbd86f00207bc8f` |
| `touhoulittlemaid-1.5.3.jar` | `touhoulittlemaid-1.5.3-forge+mc1.20.1.jar` | `9097a2a57e7ed639bca072dd62e4a9257e86db89` | `8c341567e57aa3c006f9fa85c7a5e3bc36a53b1cca4e3538c3db705eeeea1bbc` |
| `ef_tlm-1.1.1.jar` | `[史诗战斗：车万女仆]EpicFight_TouhouLittleMaid-1.20.1-1.1.1.jar` | `5e6124fded01c142688e9c8045582a77a8834f26` | `f679fa89202648e15d1dddfe39aa3c144b48863bffc3152d1be0c908ae31056e` |

这三个 JAR 当前被 Git 跟踪，因而也会进入 GitHub 自动生成的源码压缩包；仓库的 MIT 许可证仅适用于本项目自有代码，不能替代上游文件各自的授权。维护者调整依赖分发方式前，应单独核对上游许可与构建可复现性。第三方 `.ysm` 模型、贴图及其派生测试样本不得加入仓库或发布资产。

The three upstream JARs are currently tracked, so GitHub's automatic source archives include them. This repository's MIT license does not change their upstream terms. Keep private `.ysm` models, artwork, and derived fixtures out of Git and release assets.

## 维护与验证 / Maintenance

1. 升级上游前，核对官方版本页、JAR 内 `META-INF/mods.toml`、文件哈希和许可证；同步修改 `gradle.properties`、`build.gradle`、本项目 `mods.toml` 及中英文 README。更换 `libs/` 时保留此表的版本与校验记录。
2. 运行 `./gradlew.bat clean build`、`./scripts/summarize-tests.ps1`、`./scripts/verify-release.ps1`。升级 YSM 分支时另运行 README 中的 `MixinTargetSignatureTest`；再按 [游戏内验收矩阵](TESTING.md) 检查渲染与联动。
3. 发布时核对 CI 对**同一个提交**的构建、测试与 JAR 校验均通过；只上传该提交验证过的 `-all.jar` 及其 SHA-256。不要用无 `-all` 后缀的 JAR 作为玩家安装包。

For an upstream update, verify the official file, `mods.toml`, checksum, and license first. Then update the version declarations and this table, run the automated checks, and complete the relevant in-game checks before publishing the CI-verified `-all.jar`.
