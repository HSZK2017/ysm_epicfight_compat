# Technical Reference

English · [简体中文](technical-reference.md) · [Back to README](../README.en.md)

This document covers model parsing, class responsibilities, performance mechanisms, debug parameters, and known limitations, for development and troubleshooting. Read the README first for installation, build setup, and the main flow.

The performance figures and in-game verification records here come from the original README and are not remeasurements in the current environment. Class and test counts may also change as the source evolves.

## Contents

- [Modules](#modules)
- [Performance optimizations](#performance-optimizations)
- [Configuration and debug parameters](#configuration)
- [Test categories](#tests)
- [Full known limitations and verification boundaries](#known-limitations)

## Conventions

- GPU, CPU, and Iris are conditionally selected paths; do not treat them as one fixed execution chain.
- `require=0` lets unmatched optional injections be skipped. It does not mean every exception is safe, and it provides no log evidence that a method matched.
- Secondary motion uses the `YsmMeshSecondaryMotion` bone-segment solver; the particle-cloth solver that was never connected to drawing, and its inactive settings, have been removed. The effective gravity setting is `secondaryMotionGravityAcceleration`.
- See the README for the version ranges of installed dependencies; the current build's local dependencies also include two maid-integration jars, which are optional at runtime.

## Modules

### Model parsing (`com.ysmef.compat.ysm`)

| Class | Responsibility |
|---|---|
| `YsmModelPackage` | Single entry point: loads a directory or binary package by modelId and returns geometry + textures + properties + script animations (`ScriptAnim`); both the modelId and paths inside the package are guarded against traversal and symlinks, and source file size is capped |
| `YsmBinaryReader` | Binary format deserialization: the `format` version chain (legacy V1/V15, modern 16+), geometry sections, texture table, animations; little-endian, VarInt LEB128 |
| `YsmFileCrypto` | `.ysm` decryption pipeline: XChaCha20 decryption → MT19937 whitening → modified zstd block-header shuffle → standard zstd decompression |
| `ScriptJson` / `ScriptAnim` / `Molang` | Script animation parsing and compilation; Molang evaluator (inlined integer IDs for queries/variables, zero-allocation function calls, constant folding; see the performance section) |

### Geometry conversion (`com.ysmef.compat.model`)

| Class | Responsibility |
|---|---|
| `YSMGeoModel` | Bedrock geometry parsing: 8 vertices and 6 faces per cube (box UV / per-face UV), mirroring, inflation, cube pivot rotation; bone hierarchy and bind transform chain |
| `EFMeshJsonWriter` | Generates EF animmodels JSON + runtime JSON. Bone-level parts (`y/<boneName>`), pre-triangulation (6 corners per quad) to match EF's triangle drawing convention; vertex welding, joint mapping, width/height scaling |
| `YSMJointMapper` | Maps YSM bone names → EF biped joint IDs (Root=0..Elbow_L=19) |
| `YSMMesh` | EF `HumanoidMesh` subclass: texture replacement, runtime model ID, runtime transform injection by part index (O(1) array access) |
| `YSMMeshLibrary` | Mesh registration/lazy conversion gate, resource release on the render thread, the `MeshReleaser`/`RenderBridgeRegistry` registry; LRU decisions are held by `YsmModelResidency` |
| `YsmModelResidency` / `YsmModelManifestEntry` | The former holds usage order and instantiation markers and selects eviction candidates; the latter unifies the manifest entry format for lazy conversion and full generation |
| `TextureStore` | The whole texture pipeline: byte registration, PNG/JPEG/WebP/AVIF decoding, asynchronous upload (per-frame time budget), deferred release, pack/cache file layout, `sanitize` path traversal protection |
| `ManifestStore` | Generated cache manifest: in-memory mirror + version-merging background writes (zero file I/O on the render thread) |
| `JointTable` | Single source of data for the 20-joint table of EF's reference biped skeleton (previously duplicated in three places) |
| `YsmMaidMeshSupport` | Mesh selection bridge for maid YSM models (`EntityMaid.isYsmModel()` → converted YSM mesh; active only when EFTLM is installed) |

### Runtime script system (`com.ysmef.compat.model.runtime`)

| Class | Responsibility |
|---|---|
| `YSMRuntimeModel` | Compiles the bone table + Molang animations, cached by modelId; **default form visibility** (statically evaluates the parallel scale channel, propagates `effMinScale` through the hierarchy, applied in combat mode); **background precompilation** (the first draw no longer compiles on the render thread); **per-player animator sweep** (every 15 s, reclaiming those unused for 60 s) |
| `YSMPlayerAnimator` | Per-player script evaluation: parallel → state → conditional overrides; mapped bones keep only the scale channel, unmapped bones get the full transform; hidden when `effMinScale < 0.01`; identity deltas skip injection; **asynchronous evaluation** (double-buffered, background thread for nonlocal players) |
| `YSMRuntimeBridge` | Render-frame bridge: in combat mode, players use `applyEntityVisibility()` to read persistent wheel toggles, other entities use `applyDefaultVisibility()`, then `YsmMeshSecondaryMotion.apply()` is called; the non-combat bridge branch supports full script evaluation |

### GPU rendering (`com.ysmef.compat.gpu`)

| Class | Responsibility |
|---|---|
| `YsmGpuRenderPath` | Direct GPU skinning path: each frame the CPU only composes joint matrices (`poses×toOrigin`, OM math); part segments (bind delta + hidden flag) are **cached statically and uploaded only once** in combat mode; drawn with a single `glDrawArrays`; `u_proj = proj×mv×pose` is numerically equivalent to the EF compute path (bit-identical in simulation); falls back to the EF path automatically when an Iris/Oculus shader pack is active |
| `YsmGpuMesh` | Static VBO (32 B/vertex: pos+uv+2_10_10_10 normal+boneId+partId) + dynamic bone SSBO (144 B/entry) + part-segment cache |
| `YsmBoneSkinShader` | Skinning shader (automatically selects desktop `bone_skin.vsh/fsh` GL 4.3 or Android `bone_skin_es.vsh/fsh` GLES 310): `boneMat = joint×part` matches the EF compute shader term by term; reproduces MC lighting/fog/overlay/lightmap semantics; two passes for translucent textures |
| `YsmGpuCapability` | GL capability probe (desktop SSBO/420pack/explicit attribute location/2_10_10_10, **Android OpenGL ES 3.1**), with automatic fallback on failure |
| `YsmGpuRenderEnable` | **YSM fork detection + GPU toggle linkage** (see the next section) |
| `YsmIrisComputePath` / `YsmIrisMesh` | Optimized compute path under shader packs: joint-only upload, gating on part-segment changes, cached uniforms/vertex formats; a dynamically sized SSBO per mesh renders models exceeding EF's MAX_JOINTS |

### CPU rendering (`com.ysmef.compat.cpu`)

| Class | Responsibility |
|---|---|
| `YsmCpuRenderPath` | CPU skinning path: per-vertex CPU skinning each frame (weighted sum of `(pose×toOrigin×partDelta)×bindPos`, matching the EF compute shader term by term, with multi-joint weights); the poseStack is applied on the CPU (the same contract as EF drawPosed); vertices are streamed into a reused dynamic VBO, and the whole model is drawn with a single `glDrawArrays(GL_TRIANGLES)`; hidden parts are skipped, with index/NaN bounds defenses; `SkinnedMeshCpuRenderMixin` takes over when EF falls back to its CPU render shader (drawPosed), yielding only in cases such as an active shader pack |
| `YsmCpuMesh` | Per-mesh dynamic VBO (24 B/vertex: pos+uv+2_10_10_10 normal) + reused CPU accumulation buffers: low memory footprint and zero allocations per frame (suitable for systems with under 2 GB of memory) |
| `YsmCpuSkinShader` | CPU skinning shader (desktop `cpu_skin.vsh/fsh` `#version 330` / Android `cpu_skin_es.vsh/fsh` `#version 300 es`): vertices are already in camera space, so the shader only applies the normal RenderSystem proj/mv (the vanilla entity shader contract); reproduces MC lighting/fog/overlay/lightmap semantics; two passes for translucent textures; **no SSBO or compute shader required** |

### YSM fork compatibility (`YsmGpuRenderEnable` + the mixin family)

| Fork | Detected by | GPU toggle | Render suppression mixins |
|---|---|---|---|
| **ModernYSM** | Presence of `rip.ysm.gpu.*` | **Linked to ModernYSM's** `UseGpuRenderer`/`UseCompatibilityRenderer` (read live via reflection, including its own runtime auto-disable) | `ModernYsm*Mixin` (new signatures: `onRenderPlayerPre(Player,...)Z` etc., returning false) |
| **OpenYSM** | Presence of the unobfuscated `client.event.ReplacePlayerRenderEvent` (without `rip.ysm.gpu.*`) | This mod's `enableGpuRender` setting + a **checkbox in the model selection screen** (added to the performance group of the config screen) | `OpenYsm*Mixin` (old event signatures: `onRenderPlayerPre(RenderPlayerEvent$Pre)V` etc.) |
| **Official 2.6.5 (obfuscated release)** | Presence of obfuscated classes specific to 2.6.5 (`O0o...` etc.); not a separate distribution form | This mod's `enableGpuRender` setting + the model selection screen checkbox | `Ysm*Mixin` (obfuscated targets, soft skip) |

- All suppression mixins are `require=0` soft injections: they only take effect on forks where the signature actually exists, and unmatched optional injections may be skipped; this does not guarantee that no version or other error can cause a crash. Targets take two forms: `OpenYsm*`/`ModernYsm*`/`YsmUnobf*`/`YsmExtraPlayerOverlayMixin`/`YsmAnimationTransitionGuardMixin` and others use **string targets** (those classes are not on the compile classpath), while the 9 obfuscated release targets use **class literals** (the obfuscated classes are in `libs/ysm-2.6.5.jar`, so they compile); `RenderSystemAccessorMixin` is an `@Accessor` on vanilla `RenderSystem`, which has no `require` element and should not have one. Classes shared between the obfuscated release and OpenYSM/ModernYSM (`CustomProjectileRenderer` etc., with identical signatures) are covered by `YsmUnobf*Mixin`.
- **Troubleshooting note:** a `require=0` "no match" **logs nothing** (only a wholly missing target **class** produces a WARN; when a target **method/descriptor** does not match, Mixin is completely silent). Which fork's suppression took effect can therefore only be judged by behavior; after updating YSM or Epic Fight, suspect this first if duplicate rendering appears or outfit changes stop working.
- What is suppressed: third-person player rendering, first-person arms, background hands, projectiles, fishing hooks, vehicles, vehicle previews; in combat mode all of these yield to vanilla/EF rendering.
- With ModernYSM, the config screen does not add a duplicate checkbox (it has its own `UseGpuRenderer` option).

### Render integration (`com.ysmef.compat.renderer`)

| Class | Responsibility |
|---|---|
| `YSMPlayerRenderer` | Patched player renderer (`PHumanoidRenderer`), registered at LOWEST priority; conditional armor/head/elytra layers |
| `YSMMeshSelector` | Mesh selection: `YSMModelAccess` reads the current model → `YSMMeshLibrary` lookup → sets the runtime model ID + current player |
| `YSMModelAccess` | Model selection resolution: integrated server NBT → model sync channel → client capability NBT (20-tick cache) |
| `YSMBattleMode` | Combat mode check: `PlayerPatch.isEpicFightMode()` |
| `YSMRenderHook` | `RenderLivingEvent.Pre` at HIGHEST: when EF takes over, cancels the event and draws the armature model with the vanilla renderer (including `PatchedItemInHandLayer`), restoring weapon rendering |
| `YsmAnchoredItemLayer` / `YsmWeaponPoseBridge` | The EF weapon layer receives the original patch and pose; within the same player render scope, the corresponding array entries are replaced with the YSM hand/tool joint poses copied during mesh drawing. Mapped by joint name, for both hands; other joints and weapon-specific corrections are still handled by EF |

### Events and Mixins

The main configuration `ysm_epicfight_compat.mixins.json` (**32** client mixins + 1 common): `AnimationRegistryClientFilterMixin` (removes generated templates before reporting the animation registry, see below), `ModernYsm*` (3), `OpenYsm*` (4, including the config screen), `YsmUnobf*` (4), obfuscated `Ysm*` (8), `PPlayerRendererMixin`, `RenderSystemAccessorMixin` (shader light direction), `SkinnedMeshCpuRenderMixin` (intercepts EF's drawPosed fallback → this mod's CPU skinning path), `EpicFightRenderLivingEventMixin`, `DiscreteInputActionTriggerMixin`, `MouseHandlerMixin`, `YsmRouletteConfigExpressionMixin`, `YsmAnimationTransitionGuardMixin`, `YsmLivingMovementPredicateMixin`, `YsmArmaturePoseMixin`, `YsmExtraPlayerOverlayMixin` (suppresses the paper-doll preview in combat mode; see the `disableExtraPlayerInBattleMode` setting); the common section is `AnimationManagerValidationMixin` (server-side exemption from animation registry validation, see below); the optional configuration `ysm_epicfight_compat.eftlm.mixins.json` (TLM maid render hooks, `required: false`, safe when TLM is absent).

### Multiplayer model sync (`com.ysmef.compat.network`)

| Mechanism | Details |
|---|---|
| **Protocol** | Dedicated channel `ysm_epicfight_compat:model_sync`; handshake version check (modeled on YSM ids 51/52); no model data is exchanged before the handshake completes |
| **Model broadcast packet** | `S2CSetModelAndTexturePacket` (modeled on YSM id 4): entityId + modelId + textureId + disabled + UUID (the client registers by UUID as the primary key, which is more robust across respawns and dimension changes) |
| **Server broadcast timing** | Join-world handshake → push on `PlayerEvent.StartTracking` → diff-scan broadcast every 40 ticks (detection requires serializing every player's full NBT, so the 2-second period is a throughput trade-off) |
| **Server data source** | `YsmCapabilityReader` reads the `ServerPlayer` ForgeCaps NBT (`yes_steve_model:model_id`), with no dependency on YSM classes |
| **Version negotiation** | The selection sync handshake string is still `1.0.0` (versions compare it for equality, so changing it would stop sync between old and new versions); the model transfer capability is expressed by the Forge channel version `1.1.0`: each side reads the peer's channel version from `NetworkHooks.getConnectionData` and sends id 4–7 messages only when the peer is ≥1.1, so older peers never receive unknown discriminators |
| **Animation registry report filter** | `AnimationRegistryClientFilterMixin` (client section) removes `ysm_epicfight_compat:public/pub_*` after `CPCheckAnimationRegistryMatches(int, String[])` is constructed and rewrites `animationCount` to match (the server reads strings according to that count); it is redundant with the server-side exemption `AnimationManagerValidationMixin`, the former covering servers without this mod. Both are soft injections; `AnimationRegistryGuard.reportExemptionTarget()` checks their targets at startup and logs the result |

#### Model package transfer (`ModelTransferServer` / `ModelTransferClient`)

The models YSM itself syncs to clients are stored in `config/yes_steve_model/cache/client/`, encrypted with a key held only by the YSM runtime, so this mod cannot read them. This mod's conversion needs the model source files, so clients without them used to show only the EF default model.

| Step | Details |
|---|---|
| **Trigger** | When the client receives a player's model selection (`ModelSyncClient`), or any caller (including maids) looks up a model that is missing locally, it queries once per model per connection (`C2SModelQueryPacket`) |
| **Server response** | `S2CModelInfoPacket`: status + package size + SHA-256 + server ID. The hash covers the `ModelBlob` serialization (file contents sorted by relative path) and is independent of modification times and the containing root directory; it is cached by a metadata signature so unchanged packages are not reread. A LAN host's own client gets `SAME_FILES` |
| **Client decision** | Local package hash matches → use the local one; `server_models/<server ID>/` already has a copy with the same hash → enable it directly; otherwise request a download (`C2SModelDownloadPacket` carries the requested hash, and the server only accepts requests matching what it offered) |
| **Transfer** | `S2CModelChunkPacket` chunks of ≤32 KiB each, rate-limited by `kibPerTickPerPlayer`; packaged on a single background thread, with at most `maxConcurrentTransfers` packages held in memory at once; per connection ≤512 queries and ≤64 queued downloads |
| **Writing to disk** | Chunks are written sequentially to a temporary file → SHA-256 verified → unpacked in a staging directory (entry count, total size, and every path checked; `..`, absolute paths, duplicate entries, and trailing data are rejected) → moved into place |
| **Activation** | `ServerModelOverlay` makes that model ID resolve only to the server's copy (replacing the four YSM root directories, with no fallback to local files); `YSMMeshLibrary.onModelAvailabilityChanged` releases the old mesh and textures and invalidates any in-progress conversion of the old source (marked per model, not affecting other models), and the next lookup converts again from the new source. On disconnect all overrides are removed and local files are restored |
| **Limits** | Models in the auth directory are not sent by default (`shareAuthModels`); nothing is downloaded when a size limit on either side is exceeded, or when the server lacks this mod or runs an older version, and the log states the reason |

---

## Performance optimizations

| Mechanism | Details |
|---|---|
| **Lazy conversion (no loading at startup)** | A model is converted only when first rendered (background pool, ≤4 threads), falling back to the EF biped meanwhile; a verified cache hit restores it directly without decryption |
| **LRU model cache** | Beyond `lazyModelCacheSize` (default 64), the least recently used model is evicted: GPU buffers, textures, and shared meshes are released (all deferred by 5 ticks to prevent same-frame reference flicker/use-after-free), as are compiled scripts and per-player animators; the next use restores instantly from the verified cache |
| **Template descriptor downsampling** | Wheel animation similarity descriptors store 1 of every 8 frames + streaming load (the old full-frame format is migrated automatically), shrinking descriptor files from hundreds of MB to tens of MB |
| **Concurrent conversion semaphore** | `Semaphore(2)` limits how many models convert at once (each conversion holds the decrypted package + geometry arrays, hundreds of MB for large models), controlling peak memory |
| **Per-entity animator sweep** | Every 15 s, per-player animators unused for 60 s are cleared (~300–400 KB each for large models), so nothing lingers after players leave |
| **Background precompilation of runtime models** | Molang scripts compile in the background right after mesh conversion/cache restoration (~100 ms for large models no longer stalls the first frame); if precompilation is still in flight, the render thread displays the fallback first |
| **Asynchronous script evaluation** | Molang evaluation for nonlocal players runs on a single-threaded background pool (double-buffered publishing), and the render thread only pushes meshes; LOD reduces the rate by distance (40/64 blocks → 30/10 Hz) |
| **Molang evaluation optimizations** | Query/variable paths are inlined at compile time as integer IDs (`double[]` slots instead of a HashMap); zero-allocation function call arguments (ThreadLocal reuse); variable references preclassified at compile time; constant folding of purely numeric expressions; function calls carry an exact `argCount` and never reuse stale argument slots |
| **Hot-path probe caches** | YSM preview mode has a 250 ms TTL; EF compute setup is cached per mesh instance; CPU GL capability is probed only once; shader pack detection has a single implementation across the project |
| **Wheel mapping sidecars** | One small JSON per model, written atomically (the old aggregate file is still readable); negative caching during conversion avoids reading from disk every tick; `exactHash` reuses a single ByteBuffer with no per-float heap allocation |
| **GPU path** | Static geometry uploaded once + only joint matrices per frame (~3 KB in combat mode instead of ~114 KB) + a single draw call; numerically equivalent to the EF compute path (verified in simulation) |
| **CPU skinning path** | Fallback rendering for devices without compute shaders/SSBOs: per-vertex CPU skinning (~12,000 vertices/frame for large models) written into reused buffers with zero allocations per frame; only a 24 B/vertex dynamic VBO per mesh (far less memory than the compute/GPU pipelines, suitable for systems with under 2 GB of memory); a single draw call |
| **Texture pipeline** | Image decoding moved to the background pool; GL uploads are drained in time slices with a 10 ms per-frame budget (large textures no longer stall the first draw); evicted textures are released with a delay to prevent flicker |
| **Incremental keyframe cursors** | Amortized O(1) keyframe lookup per channel (reset automatically on loop wraparound) |
| **Zero-allocation matrix composition** | `bindWorldInv` precomputed; `composeBone` reuses persistent scratch space; no Matrix4f/array allocations per frame |

---

## Configuration

Client configuration `config/ysm_epicfight_compat-client.toml`:

| Option | Default | Description |
|---|---|---|
| `enableGpuRender` | true | Toggle for the GPU skinning path. Ignored when ModernYSM is loaded (linked to its `UseGpuRenderer`); effective on OpenYSM/LegacyYSM, where it can be ticked in the YSM model selection screen; automatically set to false when the GPU path is unavailable (as in ModernYSM) |
| `lazyModelCacheSize` | 64 | LRU model cache limit (8–512) |
| `scriptAsyncEval` | true | Asynchronous script evaluation for nonlocal players |
| `disableExtraPlayerInBattleMode` | true | Suppresses YSM's top-left "extra player rendering" (paper doll) in combat mode: every frame the paper doll triggers a second full EF patched render pipeline through the entity render dispatcher (measured as the cause of a drop from 100+ fps to 20–30 fps); since the model is already visible in the world during combat mode, the paper doll is off by default |

Debug/verification system properties (JVM arguments; no config file changes needed):

| Property | Description |
|---|---|
| `-Dysm_ef_compat.force_cpu_render=true` | Force-skips the EF compute shader and always uses this mod's CPU skinning path (to verify the fallback chain on hardware that supports compute shaders) |
| `-Dysm_ef_compat.disable_gpu=true` | Disables the GPU skinning path (falls back to the EF compute shader / this mod's CPU skinning) |
| `-Dysm_ef_compat.disable_iris_compute_path=true` | Disables the optimized Iris compute path (for A/B verification; falls back to EF's own Iris path) |
| `-Dysm_ef_compat.enable_iris_compute_path=true` | **No longer read**: the optimized Iris path has been on by default since 2026-09-20. The switch has been removed from the code (`ENABLED` only checks the disable switch); leaving it in the launch arguments has no effect and produces no error |
| `-Dysm_ef_compat.diag=true` | Enables diagnostic logging (reasons rendering paths were skipped, per-frame timings) |

All currently effective boolean switches share the same value semantics (`SystemFlags.enabled`): `-Dname` or `-Dname=true` turns a switch on, and **`-Dname=false` turns it off**. The early implementation checked `getProperty(name) != null` ("present means on"), so writing `=false` actually turned the feature on (or, for `disable_*` switches, left the feature off). All four switches had this pitfall; it is now unified and pinned by unit tests. `0`/`no`/`off` (case-insensitive, ignoring surrounding whitespace) are also read as off.


### Model package size limits

| Property | Description |
|---|---|
| `-Dysm_ef_compat.max_package_bytes=...` | Size limit for `.ysm` packages/model source files (default 512 MiB, guarding against malformed huge files) |
| `-Dysm_ef_compat.max_decompressed_bytes=...` | Size limit for the decompressed `.ysm` binary payload (default 512 MiB, guarding against decompression bombs) |

---

## Tests

Unit tests (`src/test`, JUnit 5, no Minecraft runtime needed):

```powershell
.\gradlew.bat test
# Including the golden test for the real .ysm decryption chain:
.\gradlew.bat test "-Dysmef.golden.ysm=C:/path/to/model.ysm"
```

- **Molang evaluator** (12): arithmetic/variables/ternary/comparison/`??`/functions/statement sequences/division-by-zero sanitizing/error fallback/constant folding/function argument counts
- **CityHash fixed vectors** (3): bootstrap vectors + consistency of range variants (correctness is pinned end to end by the trailing hash of real .ysm files)
- **winefox plaintext golden tests** (4, local private sample): 195-bone geometry, 49 animations, pre/post keyframe ground truth (`.local-test-fixtures/golden/winefox/`; skipped when not provided)
- **Binary keyframe pre/post** (3): encoded in the serializer's on-disk layout, locking in the pre/post semantics fix
- **`sanitize` path traversal** (5) + **joint table** (3)
- **`YsmModelPackageTraversalTest`** (4): rejects traversal/absolute paths/drive letters/NUL in model IDs on the read path, and accepts valid relative IDs
- **Real .ysm decryption chain** (3, requires `-Dysmef.golden.ysm`): CityHash trailing hash check, XChaCha20+MT19937+zstd, binary parsing

---

## Known limitations

1. **Rendering path fallback chain:** GPU skinning needs GL 4.3+ (Android needs OpenGL ES 3.1; the ES path has had desktop-only GL calls removed, and on-device verification awaits an Android environment); otherwise it falls back in order to the EF compute shader → this mod's CPU skinning (desktop GL 3.3+ / OpenGL ES 3.0+, no missing faces) → EF drawPosed (triangulation fixed, renders completely). macOS (GL 4.1, no compute shaders) uses CPU skinning
2. **Iris/Oculus shader packs:** when a shader pack is active, the direct GPU/CPU paths yield to the EF compute path, which this mod's optimized Iris path (joint-only upload, no MAX_JOINTS limit) takes over: **on by default since 2026-09-20**. It was unreachable for a long time (no external references at all), and the first time it actually drew it exposed a **missing re-upload gate**: part segments were only re-uploaded when a delta "appeared/disappeared", but secondary motion changes values every frame → part segments were uploaded once and then froze (measured: `[physics]` reported "59 of 59 bone(s) moving" while nothing moved on screen). The defect has been fixed and verified in game: across 9 models (51–597 parts each, up to 103,998 vertices), every `partCount` matched, physics was visible, with zero ERRORs and zero draw failures. **Limits of that verification:** one machine and one shader pack; the "beyond MAX_JOINTS (1000) capacity" capability was never reached (the largest case was 597 parts + about 20 joints, within bounds), and the outline/GUI passes were not exercised. If it does not suit, use `-Dysm_ef_compat.disable_iris_compute_path=true` to return to EF's own Iris path (which also includes the delta); when compute shaders are unavailable, drawPosed with its fixed triangulation is the last resort
3. **First-use delay from lazy conversion:** if the cache misses on a model's first render, the EF biped is shown briefly (a few frames) while it converts in the background; asynchronous texture upload is similar (a missing texture for 1–2 frames before the texture appears)
4. **Multiplayer sync requires this mod on the dedicated server** (the server reads and broadcasts model selections, and sends clients the model packages they lack); without it, remote players fall back to the EF biped, and models the client lacks cannot be obtained automatically
5. **Remote players' models must be available locally:** model packages must be in `config/yes_steve_model/{builtin,custom,auth}`; models newly downloaded mid-session need F3+T or `/ysm model reload` to trigger regeneration
6. **Obfuscated targets depend on the version:** the only readable classes in official 2.6.5 (a fully obfuscated build) are in the `mixin/` package, and all its `client.*` targets are 2.6.5-specific obfuscated names covered by `Ysm*Mixin`; OpenYSM/ModernYSM use the unobfuscated/new-signature `OpenYsm*`/`ModernYsm*`/`YsmUnobf*` families. `mods.toml` limits Epic Fight to `[20.14.17,20.15)` and YSM to `[2.6,2.7)`; upgrading a dependency requires relocating targets by descriptor and updating the contract
7. **Texture formats:** PNG/JPEG are read directly; WebP/AVIF are decoded through YSM's ImageStream via reflection (built into OpenYSM/ModernYSM; skipped with a warning when missing in official 2.6.5); BMP is not supported
8. **Default visibility in combat mode:** variant visibility is decided by statically evaluating the parallel scale channel in a frozen default environment, so an occasional conditional variant may be visible for the first frame before the runtime overrides it
9. **Cache robustness:** the manifest records output hashes, and every file is checked before cache restoration; damage only reconverts that model; all output is written atomically
10. **Safety limits:** `.ysm` source files and decompressed payloads are each limited to 512 MiB by default (overridable with `-Dysm_ef_compat.max_package_bytes` / `-Dysm_ef_compat.max_decompressed_bytes`); very large but trusted models need these raised explicitly; binary parsing also caps the counts of sections such as bones/cubes/faces
11. **Wheel mapping migration:** since v1.9.0 there is a per-model mapping sidecar (`config/ysm_epicfight_compat/extra_animation_mappings/`); the old aggregate file `extra_animation_mappings.json` is still read for compatibility but no longer written
12. **Conflict between EF stances and the model's skirt clearance (remaining clipping):** Epic Fight's default idle is a staggered stance with one foot forward, and walking/sprinting/falling also swing the legs; `wine_fox/01_taisho_maid` is modeled in a "legs together, standing upright" rest pose with only about **0.10 blocks** of clearance between skirt and legs. Once EF takes over leg animation, the legs move into the skirt. The current bone-segment collisions constrain some panels but cannot guarantee the whole skirt never clips: the corrected per-frame probe recorded **7/36** cloth panels contacting the leg colliders during a falling segment; enlarging the colliders to 2.0× did not improve the clearance, because the panels start inside the volume and the current solver skips such initial overlaps. Rebinding panels to the nearest leg did not increase the gap under EF walk/run/fall/jump or synthetic gaits at blend ratios of 0.2–1.0. The verified directions for improvement are increasing the clearance between the model's skirt and legs, or adjusting EF's leg motion; solving it further in the mod would require solving per skirt surface rather than per bone segment, and handling initial intersection. Measurements are in `build/reports/ysm-ef-skirt-round.md` and `build/reports/ysm-rebind-round.md`.
13. **Joint assignment for continuous tails:** when a tail curves up from the hips, the EF body joint must not be chosen independently from each segment's geometric height; the old cache for `wine_fox/01_taisho_maid` put `Tail`–`Tail4` on `Torso` and `Tail5`–`Tail7` on `Chest`, so upper-body motion pulled the two mesh groups apart mid-tail. Now the whole tail uses the body joint determined by the tail bone closest to the body that has geometry; the change in converter source fingerprint causes old caches to be regenerated.
14. **The old particle-cloth path has been removed:** the old solver was never connected to rendering; only the bone-segment solver drives hair, tails, and skirts. The four old particle-cloth settings never changed what was drawn and have been removed from the config spec; old values of the same names in existing config files no longer take part in solving.
15. **Boundaries and coordinate frame of skirt panel coupling:** `YsmPhysicsTopology` only creates coupling automatically between adjacent bone segments of the same physics category; although the model's `Tail` root is close to the back skirt panel `BM`, it still cannot be a skirt neighbor. Parent-child segment relationships stay on the child side, but `YsmPhysicsCoupling` uses the parent's zero relative bend as the coupling target for cloth child segments: the parent's swing is already passed to the child in `YsmMeshSecondaryMotion`'s matrix composition, and copying it into the child's own swing would rotate the hem twice. Both constraints are covered by the real maid model and small skeleton tests. The strong forward lean and long strides of EF sprinting still cause local clipping; the current changes target skirt panels splaying apart and do not promise to eliminate skirt-leg intersection completely.
16. **Accuracy boundaries of geometric constraints:** the model skeleton's parent-child relationships determine part chains, and geometric checks remove bone segments that cannot swing reliably and measure contact anchors; an author's `follows` in animations still takes precedence over the skeleton relationship while the driving segment survives. After a geometric check removes a middle segment, the parent must keep searching up the skeleton for the nearest surviving segment. In an offline scan of 906 files in `ysm-model-repo`, the current reader parsed 736, of which 642 assembled 17,918 physics segments; the old parent lookup would have treated 764 child segments that had a surviving ancestor segment as roots. Contact anchors replace the original pivot only when supporting geometry is spatially close; in the same set of models, the old rule had 901 moved anchors more than 0.1 blocks from the supporting bounding box, and the corrected rule has 0. Another 370 moved anchors are still more than 0.01 blocks outside their own geometry's bounding box, mostly because the original pivot was already outside the geometry and the anchor displacement is limited; this distance alone does not prove the model is wrong. The full scan data is in `build/reports/ysm-physics-parent-geometry-corpus.md`. **Automatic selection of which segments take part in physics, and of physics categories, still has name-based fallback rules** (`YsmPhysicsChains`, `YsmPhysicsParts.categoryOf`); geometric checks do not mean naming has been eliminated entirely, and the 170 files that could not be parsed are not yet covered by this scan.
17. **Rigid constraint for geometry touching the head:** `YsmHeadContactConstraint` reads the mapped head joint on the skeleton and both sides' real meshes when building physics segments; a segment stays on the head animation only when enough of its geometry is near the head, the contact distance is close enough, and the contact points span both the left and right sides of the head. One-sided hair strands, and long hair where only a few root vertices touch the head, do not satisfy the combined condition; nothing is decided directly from names such as `BaseHair`. For the 18 installed `wine_fox` models with `BaseHair`, every converted mesh passed the fixed check, while the maid models' bangs, long hair, and side strands still take part in physics. Of the 906 files in the model repository, 736 parse; the offline candidate set originally had 17,918 physics segments, and the new rule makes 487 head-contact parts among them rigid. Audit results are in `build/reports/ysm-head-contact-wine-fox.md` and `build/reports/ysm-head-contact-corpus.md`. These are offline geometric results; the subjective look of every model in game still needs spot checks.

## Reference implementations

Reference projects listed in the original README: OpenYSM (format and network protocol), ModernYSM (GPU rendering and resource management), LegacyYSM (GeckoBuilder conventions), YSMParser (C++ cross-verification of the encryption), EpicFight_TouhouLittleMaid (example patched renderer).
