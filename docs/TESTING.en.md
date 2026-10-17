# Testing and In-Game Acceptance

English · [简体中文](TESTING.md)

## Limits of the automated tests

After `./gradlew.bat clean build`, `./scripts/summarize-tests.ps1` lists the number of tests actually executed and the skip count for each test class. CI also shows these numbers in the build summary. Without local models, a game installation, or fork sources, the related cases are skipped conditionally; do not add third-party models to the repository just to reduce the skip count.

Cache regression tests use temporary meshes, runtime files, and texture bytes, and cover manifest generation, per-entry validation, same-length corruption, missing files, and cleanup of stale files. Deferred release tests cover release when due, requeueing, and immediate release on reload. `PivotRuleCorpusSweepTest`, `NeckRuleCorpusSweepTest`, and `ShoeLegStaticAuditTest` are measurement tools for a private sample library; the candidate rule differences in their reports are not predefined pass thresholds. Geometric invariants are verified by separate assertion tests.

When updating OpenYSM or ModernYSM, provide the corresponding source locally and run the `MixinTargetSignatureTest` command from the README. Target checks for the official YSM JAR run in the default build.

## In-game acceptance matrix

The items below depend on actual rendering, external mods, or private models. After every change to rendering, the model cache, animation, or physics state, run at least the rows affected by the change. Record the game version, YSM fork, Epic Fight version, mod version, and results; keep screenshots, recordings, and model packages on your own machine.

| Scenario | Steps | Expected result |
|---|---|---|
| First conversion and cache restoration | Select a model and enter combat mode for the first time; restart the game and enter it again | After conversion, mesh, textures, and animations are consistent; after the restart the model restores from the cache |
| Resource reload and eviction | With `lazyModelCacheSize=8`, load more than 8 models in turn, switch back to the first, and press `F3+T` | Evicted models reload; no lasting missing textures, vanishing meshes, or duplicate rendering |
| Afterimages of conditional forms | Trigger the dodge teleport with the Yamato from Nightfall or DevilMineCraft; choose a model with several conditional forms | The afterimage shows only the forms actually visible when it was created, not every form layered together |
| Weapon placement on small and large models | Use a small and a large YSM model in turn, switching between ordinary EF weapons and weapons from Nightfall, WeaponsOfMiracles, DevilMineCraft, epicmoonmod, and AnnoyingVillagers | Main-hand and off-hand items follow each model's palms; weapons with sheath or separate blade bones keep their overall position |
| Skirt during wide-stride running | With `wine_fox/01_taisho_maid` in EF combat mode, keep running, turn, and stop | Skirt panels stay roughly together; brief local clipping is acceptable, but the skirt should not keep splaying into strips or detach from the body |
| Continuous tails | With a model that has a segmented tail, run, stop suddenly, and turn | The tail stays continuous from tip to root; the tip never moves on its own |
| Entity and world isolation | Give two entities the same model, then switch saves or worlds and respawn an entity with the same UUID | Each entity simulates independently; the new world does not inherit the old world's swing state |
| Joining a server without this mod | On the client, first use several wheel animations in combat mode (generating `pub_*` templates), then join a dedicated server without this mod | No longer kicked with `animation_unsync` |
| Automatic model download | Both server and client have this mod; the client lacks a model, or holds a same-named model with different contents, and another player wearing it enters combat mode | The client log shows `downloading` / `received model`; the server's version is displayed afterwards; the client's `config/yes_steve_model` is unchanged; after disconnecting, singleplayer goes back to the local model |
| Models that cannot be downloaded | Have a player wear a model from the server's `auth` folder (`shareAuthModels=false`), or use a server without this mod | The client log states the reason once per missing model; the remote player falls back to the EF default model |
| YSM forks and warnings | Start with official YSM, OpenYSM, and ModernYSM in turn and enter combat mode | Rendering and input work on the compatible fork; the old YSM×EF compatibility warning no longer appears, while other warnings remain visible |

Model assets are for local acceptance only. The repository and CI do not store `.ysm` files, model textures, conversion output, or samples extracted from these assets.
