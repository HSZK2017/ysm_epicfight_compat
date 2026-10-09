# YSM Epic Fight Compat

English · [简体中文](README.md)

Use **Yes Steve Model (YSM) character models with Epic Fight combat animations** on Minecraft 1.20.1 / Forge.

- **Combat mode:** this mod converts the selected YSM model so Epic Fight can drive attacks, movement, and other combat animations.
- **Outside combat:** YSM keeps its own rendering and animation system.
- **Multiplayer:** the selected model ID is synchronized. Dedicated servers also need this mod, and viewers need the corresponding model package locally.

The supported YSM families are the official 2.6.5 release, OpenYSM, and ModernYSM. The official 2.6.5 release is obfuscated. Changes to a YSM fork or Epic Fight may require updated compatibility hooks.

### What's new in 1.10.0

- Afterimages now preserve the model part visibility evaluated when they are created, rather than showing every conditional form.
- Better coupling between skirt panels and consistent joint binding along articulated tails reduce panel separation and detached tail sections during large movements.
- Hair pieces with substantial geometric contact across the head remain fixed to it. The rule does not depend on names such as `BaseHair`.
- When this bridge is installed, YSM's obsolete startup incompatibility warning for Epic Fight is suppressed. Other warnings remain visible.

Epic Fight's strong forward lean and long strides can still cause local skirt and leg intersections. See the [changelog](CHANGELOG.md) for details.

## Guide

| Goal | Start here |
|---|---|
| Install and use the mod | [Installation and use](#installation-and-use) |
| Review this release | [What's new in 1.10.0](#whats-new-in-1100) → [Changelog](CHANGELOG.md) |
| Adjust performance or appearance | [Common settings](#common-settings) |
| Diagnose missing models, clipping, or shaders | [Known issues and limits](#known-issues-and-limits) |
| Build the project | [Build and test](#build-and-test) |
| Understand or change the code | [How it works](#how-it-works) → [Developer entry points](#developer-entry-points) |
| Look up class responsibilities and validation notes | [Technical reference](docs/technical-reference.md) (Chinese) |
| Plan extensions and module boundaries | [Architecture guide](docs/ARCHITECTURE.md) (Chinese) |

## Installation and use

### Requirements

| Component | Requirement or tested baseline |
|---|---|
| Minecraft | 1.20.1 |
| Forge | Built against 47.4.16; mod metadata permits 47.x |
| Epic Fight | 20.14.17 baseline; declared range `>=20.14.17, <20.15` |
| Yes Steve Model | Official 2.6.5 baseline; declared range `>=2.6, <2.7`; OpenYSM and ModernYSM forks are also supported |
| Maid integration (optional) | Touhou Little Maid 1.5+ and EpicFight_TouhouLittleMaid (`ef_tlm`) 1.1+ |

The declared ranges permit loading; they do not mean every release in those ranges has been tested. Changes to YSM event signatures or obfuscated names can break compatibility.

### Getting started

1. Install Forge, Epic Fight, one supported YSM distribution, and this mod in the same game instance.
2. Import a model with YSM and select it in YSM's model selection screen.
3. Enter Epic Fight combat mode and check that the character retains the YSM appearance while using combat animations.
4. Leave combat mode and check that YSM's usual rendering and animations return.

You do not need to export Epic Fight meshes manually. A model is converted in the background on first use and then loaded from a verified cache. During first conversion, the Epic Fight default player mesh may appear briefly; a missing texture may also appear briefly while textures are uploaded.

### Multiplayer and maid integration

- Install this mod on dedicated servers so they can read and broadcast players' model selections.
- Model selection synchronization does not transfer model packages. Each viewer still needs the relevant YSM model locally.
- For a model downloaded during an active session, use `F3+T` or `/ysm model reload` to refresh resources.
- Maid integration handles **YSM models used by maids**. The `ef_tlm` mod handles Touhou Little Maid's own GEO model packages.

## Common settings

Client configuration: `config/ysm_epicfight_compat-client.toml`.

| Option | Default | Purpose |
|---|---|---|
| `enableGpuRender` | `true` | Enables this mod's GPU skinning for OpenYSM and official YSM. ModernYSM uses its own linked GPU settings |
| `lazyModelCacheSize` | `64` | Models kept in memory, range 8–512. Lower values use less resident memory but may require resources to be restored when switching models |
| `scriptAsyncEval` | `true` | Evaluates animation scripts for nonlocal players in the background |
| `disableExtraPlayerInBattleMode` | `true` | Hides YSM's extra player preview in combat mode to avoid duplicate rendering |
| `enableSecondaryMotion` | `true` | Enables secondary motion for hair, tails, skirts, and similar parts |
| `secondaryMotionGravityAcceleration` | `24.0` | Downward acceleration for the active pendulum based secondary motion |

On ModernYSM, this mod's GPU path follows ModernYSM's `UseGpuRenderer` / `UseCompatibilityRenderer` options. This mod's `enableGpuRender` does not control that fork, and no duplicate checkbox is added.

The unused particle-cloth settings `secondaryMotionGravity`, `secondaryMotionMaxParticles`, `secondaryMotionIterations`, and `secondaryMotionBodyRadius` have been removed. Hair, tails, and skirts use the active bone-chain solver; see `config/YSMCompatConfig.java` for its settings.

If a part of a particular model is still treated as swinging when it should remain fixed, create `config/ysm_epicfight_compat/physics_overrides/<model ID>.json`, for example:

```json
{ "rigid": ["SomeBone"], "limitDeg": { "Tail5": 8 } }
```

`rigid` makes the named bone follow its original joint exactly; `limitDeg` caps a named bone's swing angle. If the model ID contains `/`, use the same nested directory structure. The automatic head contact rule needs no override, but geometric detection cannot cover every model.

## Known issues and limits

| Symptom or situation | Explanation or what to check |
|---|---|
| Default player mesh appears briefly on first use | Background conversion has not finished; later uses prefer the cache |
| Other players' models are missing in multiplayer | Check that the server has this mod and each viewer has the model package locally |
| Duplicate rendering or broken part selection after a dependency update | Check the YSM fork and Mixin target signatures; a missing optional method hook may produce no log message |
| GPU rendering is unavailable | The mod selects an available fallback. Direct GPU needs desktop GL 4.3+ / GLES 3.1+; this mod's CPU path needs GL 3.3+ / GLES 3.0+ |
| Iris / Oculus shader packs | Direct GPU / CPU drawing yields to a shader compatible path. The optimized Iris path is enabled by default; use `-Dysm_ef_compat.disable_iris_compute_path=true` to fall back if it misbehaves |
| Skirt intersects legs during Epic Fight sprinting | Panel coupling is improved, but some models were built with legs together and have too little space for Epic Fight's forward lean and long strides. Local clipping may remain; adjust the skirt geometry or Epic Fight leg poses |
| Hair on top of the head or tail sections detach | Version 1.10.0 adds geometric head contact and consistent joint binding along tails. A remaining model specific issue can be constrained with `physics_overrides` |
| Old YSM / Epic Fight compatibility warning appears | This mod intercepts only that Epic Fight warning in supported YSM builds; other YSM loading warnings are retained |
| WebP / AVIF textures do not appear | Decoding depends on YSM's reflective support. If unavailable, the texture is skipped with a warning. PNG and JPEG are supported; BMP is not |
| A very large model package is rejected | Source and decompressed payload limits default to 512 MiB each; see the technical reference for overrides |

The Android ES path still needs testing on a device. The optimized Iris path has been tested on one machine, one shader pack, and nine models; models above the 1,000 joint capacity and outline / GUI rendering paths have not been covered.

For diagnostic logs, add `-Dysm_ef_compat.diag=true` to the JVM arguments. The [technical reference](docs/technical-reference.md) (Chinese) lists other debug switches, cache rules, conditional variant limits, and skirt validation notes.

## Build and test

### Prerequisites

The project uses a **Java 17 toolchain**. Point `JAVA_HOME` to a JDK 17 installation. Gradle selects the toolchain from that environment variable, so no machine-specific path needs to be edited in the repository.

The build reads these local compile dependencies from `libs/`. They are needed to compile from source even when maid integration is not used:

```text
libs/
├── ysm-2.6.5.jar
├── touhoulittlemaid-1.5.3.jar
└── ef_tlm-1.1.1.jar
```

Gradle obtains Epic Fight and zstd-jni. Maid integration remains optional at runtime.

### Windows commands

Run from the project root:

```powershell
.\gradlew.bat clean build
.\scripts\verify-release.ps1
```

`build` includes all default tests. The second command checks the release JAR's version, mod metadata, Mixin files, converter fingerprint, and bundled zstd-jni. GitHub Actions runs the same checks on every push and pull request, saves the verified JAR, and retains test reports on failure. To rerun tests alone, use `.\gradlew.bat test`.

The release artifact is `build/libs/YSM_EpicFight_Compat-1.20.1-1.10.0-all.jar`, which includes zstd-jni. Its name changes when the project version changes.

To run the optional end to end `.ysm` decryption test with a real model:

```powershell
.\gradlew.bat test "-Dysmef.golden.ysm=C:\path\to\model.ysm"
```

For tests using a game installation, set `-Dysmef.golden.ysm_config_root=C:\path\to\config\yes_steve_model`. Corpus sweeps use the `YSMEF_YSM_CORPUS_ROOT` environment variable to locate `.ysm` packages. Those tests skip when their inputs are not configured.

When updating OpenYSM or ModernYSM, check Mixin target method signatures against the corresponding source:

```powershell
.\gradlew.bat test "-Dysmef.fork=open" "-Dysmef.fork.source=C:\path\to\OpenYSM" --tests com.ysmef.compat.contract.MixinTargetSignatureTest
.\gradlew.bat test "-Dysmef.fork=modern" "-Dysmef.fork.source=C:\path\to\ModernYSM" --tests com.ysmef.compat.contract.MixinTargetSignatureTest
```

Unit tests run without launching Minecraft and cover parsing, Molang evaluation, hashes, path validation, and more. Visual rendering still needs in-game testing. Test categories are listed in the [technical reference](docs/technical-reference.md) (Chinese).

## How it works

The code has two main stages: **model preparation** and **per frame rendering**. Decryption, conversion, and cache restoration happen during preparation; rendering uses the prepared mesh and current animation pose.

### 1. Prepare a YSM model for Epic Fight

```text
Selected player model ID
    ↓ YSMModelAccess / YSMMeshSelector: selection and mesh lookup
YSMMeshLibrary: existing mesh, valid cache, or background conversion?
    ↓ When conversion is needed
YsmModelPackage: load a directory package or binary .ysm package
    ├─ Directory → YSMGeoModel.parse
    └─ .ysm → YsmFileCrypto → YsmBinaryReader → YSMGeoModel.fromBinary
    ↓
EFMeshJsonWriter: generate Epic Fight mesh JSON and runtime JSON
    ↓
Mesh registration + TextureStore + ManifestStore cache manifest
    ↓
Ready to render; runtime bones and animation scripts precompile in the background
```

The **mesh JSON** holds vertices, texture coordinates, and joint bindings. The **runtime JSON** holds the bone hierarchy, bind matrices, and scripted animations. Cached files are checked against a fingerprint and file hashes; a damaged model is converted again independently.

### 2. Apply the current pose each frame

```text
Player rendering entry point → select the converted YSMMesh
    ↓
Epic Fight supplies the joint pose
    ↓
YSMRuntimeBridge applies part visibility and secondary motion
    ↓
Choose a rendering path based on shaders, hardware, and configuration
    ├─ Direct GPU skinning
    ├─ Iris / Oculus shader compatible compute path
    └─ Epic Fight compute, this mod's CPU path, or Epic Fight drawPosed fallback
```

These are conditional paths, not steps all executed every frame. Epic Fight drives combat animations. Part visibility also includes persistent animation wheel toggles before secondary motion is applied. Outside combat, YSM remains responsible for rendering.

## Developer entry points

Java source package root: `src/main/java/com/ysmef/compat/`.

| Area | Package / key classes | Responsibility |
|---|---|---|
| Model packages, decryption, parsing | `ysm/`: `YsmModelPackage`, `YsmFileCrypto`, `YsmBinaryReader` | Read directory or binary packages into common model data |
| Geometry, joint mapping, conversion output | `model/`: `YSMGeoModel`, `YSMJointMapper`, `EFMeshJsonWriter` | Convert YSM geometry into Epic Fight meshes and runtime data |
| Loading, caching, textures, cleanup | `model/`: `YSMMeshLibrary`, `TextureStore`, `ManifestStore` | Prepare models on demand and manage resource lifetimes |
| Visibility, scripts, secondary motion | `model/runtime/`: `YSMRuntimeBridge`, `YSMPlayerAnimator`, `YsmMeshSecondaryMotion` | Apply runtime results to model parts |
| Model selection and rendering takeover | `renderer/`: `YSMModelAccess`, `YSMMeshSelector`, `YSMPlayerRenderer` | Decide which model is drawn and by which renderer |
| GPU, CPU, and shader paths | `gpu/`, `cpu/` | Skin and draw through the available path |
| YSM fork identification | `ysm/YsmFork`, `gpu/YsmGpuRenderEnable` | Identify the fork and determine who owns the GPU toggle |
| Multiplayer synchronization | `network/` | Read and broadcast model selections |
| Rendering conflicts and event signatures | `mixin/`, `event/` | Connect YSM, Epic Fight, and game rendering events |
| Configuration | `config/YSMCompatConfig` | Define options, defaults, and allowed ranges |

Suggested reading order: `YSMMeshSelector` → `YSMMeshLibrary` → `YsmModelPackage` → `EFMeshJsonWriter` → `YSMRuntimeBridge` → the relevant rendering path.

When changing conversion output, also check the generator fingerprint rule in `build.gradle`. It hashes Java sources under `model/`; related changes outside that directory do not automatically affect the cache fingerprint.

## More information

- [Architecture guide and extension constraints](docs/ARCHITECTURE.md) (Chinese)
- [Technical reference: classes, Mixins, performance, debug switches, and limits](docs/technical-reference.md) (Chinese)
- [Changelog](CHANGELOG.md) (Chinese and English)
- [MIT license](LICENSE)
