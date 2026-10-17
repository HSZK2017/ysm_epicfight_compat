# Dependency Handoff

English · [简体中文](DEPENDENCIES.md)

This document records the **v1.10.3 build baseline** and distinguishes the mods players install from the JARs maintainers compile against. Download from the upstream release pages; files may be renamed when placed in `libs/`, but their contents must not be modified. Version ranges come from this project's `mods.toml`; they do not mean every release in a range has been tested in game.

## Runtime

| Component | Baseline and source | When to install |
| --- | --- | --- |
| Minecraft / Forge | 1.20.1 / [Forge 47.4.16](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) | Required; Java 17 |
| Epic Fight | [20.14.17, Forge 1.20.1](https://modrinth.com/mod/epic-fight/version/20.14.17-mc1.20.1-forge) | Required; this project declares `[20.14.17,20.15)` |
| Yes Steve Model | [Official 2.6.5, Forge 1.20.1](https://modrinth.com/mod/yes-steve-model/version/Zqooxsd2); the separately verified OpenYSM / ModernYSM forks are also supported | Required; this project declares `[2.6,2.7)` |
| Touhou Little Maid | [1.5.3, Forge 1.20.1](https://modrinth.com/mod/touhou-little-maid/version/g1SKoGQJ) | Maid integration only; this project declares `1.5+` |
| EpicFight: TouhouLittleMaid (`ef_tlm`) | [1.1.1, Forge 1.20.1](https://modrinth.com/mod/epicfight_touhoulittlemaid/version/2vK2kKrN) is the compile baseline | Maid integration only; this project declares `1.1+` |
| Epic Fight Avalon | [20.12.6.4, Forge 1.20.1](https://www.curseforge.com/minecraft/mc-mods/epic-fight-avalon/files/7619938) | Required when `ef_tlm` is installed: the `mods.toml` of its 1.1.1 JAR declares `epic_fight_avalon` as a mandatory dependency |

To install this project, put only `YSM_EpicFight_Compat-1.20.1-1.10.3-all.jar` in the game's `mods` folder. It bundles `zstd-jni` and does not bundle Epic Fight, YSM, Touhou Little Maid, `ef_tlm`, or Avalon. Without maid integration, the last three optional mods are not needed. Clients and dedicated servers should use matching required mods; when the server has this mod, model packages a viewer is missing are downloaded from the server automatically (except auth models; see the server configuration in the README).

## Build inputs

`build.gradle` fetches Forge, Epic Fight, and `com.github.luben:zstd-jni:1.5.6-3` from Maven automatically, and reads the three **renamed upstream JARs** below from the repository's `libs/`. These SHA-1 values match the primary release files reported by the upstream Modrinth version API; the SHA-256 values are checksums of the files currently in this repository.

| `libs/` file | Original upstream file | SHA-1 (upstream and local) | SHA-256 (local) |
| --- | --- | --- | --- |
| `ysm-2.6.5.jar` | `ysm-2.6.5-forge+mc1.20.1-release.jar` | `151ac7b24da8beeca1a20864565743cfd77af286` | `25b5e902b96f4c298690208f8b433cbc31737c23f87590354dbd86f00207bc8f` |
| `touhoulittlemaid-1.5.3.jar` | `touhoulittlemaid-1.5.3-forge+mc1.20.1.jar` | `9097a2a57e7ed639bca072dd62e4a9257e86db89` | `8c341567e57aa3c006f9fa85c7a5e3bc36a53b1cca4e3538c3db705eeeea1bbc` |
| `ef_tlm-1.1.1.jar` | `[史诗战斗：车万女仆]EpicFight_TouhouLittleMaid-1.20.1-1.1.1.jar` | `5e6124fded01c142688e9c8045582a77a8834f26` | `f679fa89202648e15d1dddfe39aa3c144b48863bffc3152d1be0c908ae31056e` |

These three JARs are currently tracked by Git, so they are also included in the source archives GitHub generates automatically. The repository's MIT license applies only to this project's own code and does not replace the upstream files' own terms. Before changing how dependencies are distributed, maintainers should separately check the upstream licenses and build reproducibility. Third-party `.ysm` models, textures, and test samples derived from them must not be added to the repository or release assets.

## Maintenance and verification

1. Before upgrading an upstream dependency, check the official release page, the `META-INF/mods.toml` inside the JAR, the file hashes, and the license; update `gradle.properties`, `build.gradle`, this project's `mods.toml`, and the Chinese and English READMEs and docs together. When replacing files in `libs/`, keep the version and checksum records in this table.
2. Run `./gradlew.bat clean build`, `./scripts/summarize-tests.ps1`, and `./scripts/verify-release.ps1`. When upgrading a YSM fork, also run the `MixinTargetSignatureTest` from the README, then check rendering and integrations with the [in-game acceptance matrix](TESTING.en.md).
3. When publishing, confirm that CI's build, tests, and JAR verification all passed for **the same commit**; upload only the `-all.jar` verified for that commit and its SHA-256. Do not use the JAR without the `-all` suffix as the player installation package.
