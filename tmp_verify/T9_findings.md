# T9 — gravity-following target direction (hair droop / skirt hanging toward the ground)

Task: `task-9`. Owner: `solver-two`. Scope also covers `task-10` (call site + per-category
weights), assigned to the same owner when the team's slot limit was reached.

## 1. What changed, in one sentence

The spring of every hanging piece used to pull toward the direction the *pose* gave it, which made
the pose the whole of the target and left gravity able to do no more than displace the piece from
it. It now pulls toward `normalize((1-b) · restDir + b · worldDown)` for a per-piece weight `b`, so
gravity decides the direction a piece hangs *in* as well as how far it is displaced from it — one
mechanism for both "hair should droop naturally" and "a skirt should hang toward the ground on a
sprint".

## 2. The API

```java
public void update(SegmentState state, float gravity, float airDrag,
                   float verticalFollow, Vector3f downTarget,   // new, immediately after airDrag
                   Vector3f pivot, Vector3f restDir, float lever,
                   float frequency, float damping, float mass, float maxAngle,
                   Vector3f bodyVelocity, Colliders colliders, float segmentRadius,
                   boolean[] collideAgainst, float dt, Quaternionf out)            // 18 args
```

and the same with `float bodyYawRate, float bodyYawAccel` before `dt` (20 args). Both overloads.
Sent to the Lead the moment the signature landed.

- `verticalFollow`: non-finite falls back to `FALLBACK_VERTICAL_FOLLOW = 0.0F`; out of range is
  clamped to `[0,1]` because a weight above 1 extrapolates the target past vertical and pulls the
  cloth *upwards*.
- `downTarget`: null / non-finite / zero falls back to `(0,-1,0)`. Read and copied, never retained.
- `b = 0` copies `rest` into `target` directly rather than blending with a zero weight, so "zero is
  the old behaviour, bit for bit" is a fact about the code and not about floating-point arithmetic.

### Which of `rest` and `target` each reader uses

| reader | axis | why |
|---|---|---|
| `integrate()` spring term | **target** | this is the change |
| `applySwingLimit()` cone | **target** | see §4 — measured, not preferred |
| `resolveCollisions()` `skipFor` rest centre | **rest** | asks whether a volume contains the point the piece sits at *when the pose is taken at its word* |
| `resolveCollisions()` reach | **rest + target** | the working space, sized from the pose to the cone |
| `state.lastAngle`, returned quaternion | **rest** | that is the bind direction the caller folds the rotation onto; measuring from `target` would report (and apply) zero bend on a piece hanging straight down while visibly off its pose |

## 3. Numerical evidence

Two independent computations, both in this directory, and they agree to two decimals.

- `T9_equilibrium.java` / `T9_equilibrium_table.txt` — analytic, own bisection, no solver involved.
  Balance: `omega^2 sin(phi - theta) = (g/L) sin(theta - alpha)`, with `alpha` the body's lean from
  vertical and `phi` the angle from the posed rest to the spring's target. Self-check: at `b = 0`
  it reproduces the Lead's table (45°/0.26 → 12.89° against the Lead's 12.9; 60°/0.18 → 21.95°
  against 21.9).
- `T9_probe.java` / `T9_probe.txt` — frame-by-frame through the real compiled solver.

`g = 24 blocks/s²`, `omega^2 = 219.9` (2.36 Hz), root limit 20°, levers from the maid model.

### 3.1 Before — `verticalFollow = 0` (what the user reported)

| lean | L=0.09 | L=0.18 | L=0.26 |
|---|---|---|---|
| 45° | 20.22° | 28.30° | 32.11° |
| 60° | 26.82° | 38.05° | 43.28° |

Degrees left between the panel and vertical. Matches the Lead's table on the two rows it shares.
The panel is dragged off vertical by the lean; at 60° and 0.26 blocks it is 43.28° away.

### 3.2 After — cloth weight 0.92

| lean | L=0.09 | L=0.18 | L=0.26 |
|---|---|---|---|
| 45° | 1.50° | 2.06° | 2.34° |
| 60° | 1.87° | 2.57° | 2.91° |

**Acceptance met: ≤ 10° with a 3.4× margin at the worst lever.** Probe and analysis agree exactly.

The weight's whole effect is one line: the target a weight-`b` blend puts at `phi` from the pose is
at `(1-b) · lean` from vertical, so the piece keeps roughly that share of the lean. Measured
settled angle from vertical, 60° lean, L = 0.26 (`T9_probe.txt`):

| b | 0.0 | 0.3 | 0.6 | 0.8 | 0.92 | 1.0 |
|---|---|---|---|---|---|---|
| off vertical | 43.28° | 30.65° | 16.55° | 7.68° | **2.91°** | 0.00° |

Monotonic, and it spans the whole range.

### 3.3 The short-lever regime (`g/(L·omega²) > 1` below 10.91 cm)

At `b = 0` a piece shorter than 10.91 cm has gravity's torque beating the spring's at *every* angle:
the balance has no root, `sin(swing)` would have to exceed 1, and the piece is driven out until
something stops it. Verified the solver does not lose it — no flip, no NaN, direction stays a unit
vector, still points downward (`aLeverTooShortForGravityToBalanceIsHeldByItsLimitNotLost`, levers
0.03/0.05/0.09/0.105).

The physical meaning, now in the class comment: `L < g/omega_n²` is a piece whose own pendulum
frequency `sqrt(g/L)` exceeds the author's spring frequency — it is gravity-dominated, hangs close
to vertical whatever pose it was drawn in, and cannot rest on the animation. The crossover
`g/omega_n² = 0.1091` blocks is the boundary.

## 4. The swing limit — the Lead's fourth question, and the one design decision changed

**The cone's axis moved from `rest` to `target`.** Measured, not preferred:

With the axis on the pose, the allowance is charged for the angle the pose already leans by. At a
60° lean and a 20° root limit the panel is pinned 20° from the pose = **40.00° off vertical, at
every weight from 0 to 1**. The weight moves the target and the stop does not follow it, so the
mechanism is invisible on a sprinting body. `M1` in §5 is exactly that one-line revert, and it
reproduces the reported defect bit for bit.

With the axis on the target the piece is held within the same authored `maxAngle` of wherever it is
being pulled. Measured at a cloth weight and 60° lean: swing from the pose **57.09°**, from the
target **1.22°**, limit 20° — the stop is 19° away and nothing is pinned
(`theSwingLimitDoesNotPinThePanelAtASprintLean`).

What this costs, and why it cannot make a garment come apart:

- The cone is the same size, so the swing from the piece's own target stays inside the authored
  number. The log's `own` is still bounded by the authored limit — it is measured from the pose, so
  it is *larger* than before by exactly the pose-to-target gap.
- The target is bounded relative to the pose: `|target - rest| ≤ verticalFollow · (pi/2)`, and it is
  a weight the classification sets per piece. At `b = 0` the two axes are the same vector, so the
  method is bit-for-bit what it always was.
- The failure the limit exists to prevent — a panel becoming a separate object at a different angle
  from its neighbours — is bounded by the *maximum* deviation (≤ `maxAngle`), which the coupling in
  `relaxTowardsNeighbours` still smooths. The relaxation is unchanged.
- Collision is unaffected: the volumes' own `skipFor` rule is now sized from the pose to the cone
  (`apexGap + 2·maxAngle`), so a piece that could reach a volume is still made to collide with it.

## 5. The "must be red before the fix" runs

Three one-line mutants of the finished file, each run through the full test class and then reverted
byte-for-byte (baseline SHA256 `50D265FA699F943A6FD2FE2C4B5F6B357DC38A189DE886AF3C4EA6516E88E129`).
Full assertion text in `T9_mutant_results.txt`; sources in `T9_mutants/`.

| mutant | change | result |
|---|---|---|
| **M1** | `applySwingLimit(state, this.target, …)` → `this.rest` | 56 tests, **6 FAILED** — the panel sits at **39.999996°** |
| **M2** | target → `this.rest`, weight ignored | 56 tests, **6 FAILED** — **43.274845°**, identical for every weight |
| **M3** | spring term `cross(this.target)` → `cross(this.rest)` | 56 tests, **6 FAILED** — **24.127811°**, on the cone at 20.00001° from the target |

M3 is the instructive one: 24.13° is better than the old 43.28°, so it *looks* like a partial
success on screen and the log reports healthy weights — but the piece is sitting exactly on its stop,
which five of the six failures name. Reverting only the spring term still fails six tests.

## 6. Per-category weights and their basis

In `YsmPhysicsParts.Segment#verticalFollow()`, classified from the bone name by the same
`YSMJointMapper.normalize` the rest of the classifier uses. Hair is matched before cloth and cloth
before tail, because "ponytail"/"twintail" contain "tail".

| category | weight | settles at, 60° lean | why |
|---|---|---|---|
| cloth (skirt, dress, qun, cape, cloak, coat, robe, sleeve, apron, hem…) | **0.92** | 2.91° off vertical | the user's requirement; 8% of the lean is kept deliberately so the garment still answers the animation and reads as worn rather than as an independent object |
| tail (tail, braid, tassel, plume…) | **0.80** | 7.68° | a heavy appendage with a shape of its own, hung from the spine: more gravity-driven than hair, less than cloth |
| hair (hair, bangs, fringe, ahoge…) | **0.60** | 16.55–24.1° | a lock of hair grows out of a skull and has volume; every strand pointing straight down is what *wet* hair looks like. At 0.6 a strand keeps ~40% of the lean, which is what a ponytail does behind a runner |
| unknown | **0.0** | = old behaviour | a wrong guess the other way would hand a body part to gravity; the accepted cost is a piece that does not droop |

`secondaryMotionGravityFollow` (0..1, default **1.0**) scales all three together; 0 is the escape
hatch back to the pre-existing behaviour. It is a scale rather than a single weight because one
number for every piece would make the mechanism unusable on either hair or cloth — the two want
opposite things.

Config keys: `secondaryMotionGravityFollow` (1.0), `secondaryMotionMaxParticles` (4000),
`secondaryMotionIterations` (8), `secondaryMotionBodyRadius` (0.22). The last three were
**missing** and are referenced by `YsmClothTuning`; see §7.

## 7. The build was broken before this task started

`compileJava` failed at `YSMPlayerAnimator.java:103` before any change of mine. Commit `e1329b8`
restored `YsmDynamicBoneSolver`/`YsmMeshSecondaryMotion`/`YSMPlayerAnimator` but left
`YsmPhysicsSimulator.java` deleted (231 lines removed in `610f7fe`), so five files referenced a class
that no longer exists. The compiler stopped at the first one, which is why earlier rounds saw a
green build — they predate that commit.

Fixed by completing the revert rather than resurrecting the class. Its own comment already said it:
the chains were anchored to **bind** data, so the rest tips never moved and the simulation could not
produce a swing however it was tuned. Restoring it would put a second, dead engine back in the tree
competing for the same `physicsDelta`.

Behaviour-preservation argument, which the Lead asked to be evidence rather than assertion:

1. `YSMPlayerAnimator.physicsDelta` was written in exactly one place — `advancePhysics()`, the loop
   that was deleted — and read in exactly one place, the multiply in `composeBone`.
2. The delete left the array at its constructed `identity()` value on every frame, so the read
   multiplied by identity. The multiply was removed *with* the field, so the expression is now
   `localAnim · bindLocalInv`, which is the same product without the step that was provably the
   identity. Nothing depends on a matrix happening to hold the identity.
3. `physicsDtSeconds` / `physicsLastEvalSeconds` existed only to feed that loop's `dt`; both were
   removed, and `grep` shows no other reader.
4. The `physicsLogged` report (which bones were classified) was **kept**, as instructed.
5. `physicsAnchors`, `physicsStates`, `physicsBindRot`, `physicsScratch`, `physicsLocal`,
   `physicsScratchMat`, `isNeutral`, `chainRestTip` had no other reader; `isUnder` is still used by
   `chainNames`-adjacent code and was kept.

Verification: `src/` has **0** references to `YsmPhysicsSimulator` except four javadoc mentions that
explain its removal; `compileJava` succeeds; all test classes green.

## 8. Test list

`YsmDynamicBoneSolverTest` — 56 tests, all green (9 new):

| test | what it pins |
|---|---|
| `aZeroWeightReproducesTheOldSolverExactly` | b=0 vs explicitly `target=rest`, **delta 0.0** on direction and angular velocity, over 3 levers × 4 leans |
| `aClothPanelWithNoWeightStaysOnTheBodyAtSixtyDegrees` | the defect is still measurable (>30° off vertical at b=0) |
| `clothHangsNearTheWorldVerticalAtASprintLean` | **the acceptance test**: ≤10° at 45° and 60°, all three levers |
| `theWrongDownTargetKeepsThePanelOnTheBody` | the false-success guard: right direction ≤10°, body axis >35° |
| `theWeightChangesWhereThePieceComesToRest` | the weight reaches the dynamics: >25° of movement |
| `hairKeepsMoreOfTheLeanThanClothDoes` | cloth < tail < hair, and hair keeps a visible share |
| `aLeverTooShortForGravityToBalanceIsHeldByItsLimitNotLost` | no flip, no NaN, unit vector, still downward below 10.91 cm |
| `aPoseAlreadyVerticalIsUnaffectedByTheWeight` | a standing pose is untouched at every weight |
| `aBrokenWeightFallsBackInsteadOfPullingTheClothUpwards` | NaN / ±Inf / negative → fallback; cannot lift a piece |
| `aMissingDownTargetMeansTheWorldVertical` | null and zero vector both mean `(0,-1,0)` |
| `aCustomDownTargetIsHonouredRatherThanReplaced` | a 30° slope steers the pieces, so wind/slope is possible later |

Also green: `MaidSkirtCoherenceTest` (8), `YsmPhysicsTuningTest` (5), `YsmSegmentDeltaTest` (13),
`YsmPhysicsPartsLimitTest` (21), `FabricCoherenceTest` (5), `YsmPhysicsChainsTest` (20),
`MaidSkirtCollisionTest` (3), `YsmPhysicsSelectionTest` (10), `YsmPhysicsLayerTest` (6),
`YsmPhysicsBindingTest` (23), `YsmBoneOverridesTest` (6), `YsmSecondOrderOracleTest` (14).

## 9. Units — the `width_scale` accident

This project has been bitten by a missing `width_scale` (0.700) factor before, so every number above
was checked for its unit:

- **Levers** 0.09/0.18/0.26 are blocks, taken from the shipped log's `L=` column, which
  `YsmMeshSecondaryMotion` prints from `segment.lever()` — a value `YsmPhysicsParts` derives from
  the mesh's own vertex positions, in the same frame as the pivots.
- **Frequency** is Hz in the API and `omega^2 = (2·pi·f)^2` in the balance; the check is that
  `sqrt(220)/2pi = 2.360 Hz` reproduces the log's 2.36.
- **Damping** is the ratio: the test passes 0.81, which is the config's `24 / (2·sqrt(220))`. The
  old point model applied `24` as an absolute decay; using that as a ratio would have been a silent
  30× error.
- **Angles** are radians inside the solver and degrees only in the config, the log and this document.
- **Gravity** is blocks/s², and the 24 in the tests is `secondaryMotionGravityAcceleration`'s
  default, not the retired `secondaryMotionGravity` (8), whose unit and meaning both changed.
- The one place a scale factor could have entered — `bindRest` being a bind-space vector — is
  handled by `transformDirection(deformation, …)` + `normalize()`, so `lever` is a real length and
  `angleBetween` is scale-free.

## 10. Not verified

- **Anything visual.** No game client was run. Every claim here is a number from the solver or the
  test suite; whether a skirt *looks* right is a judgement this work cannot make.
- **`isEntityUpsideDown`.** Vanilla's "Dinnerbone" name tag makes Epic Fight apply a further 180°
  about Z, which flips the model's Y against the world's and would make the true downward direction
  `(0,+1,0)`. `DOWN_IN_MODEL_SPACE` does not follow that case: a hanging piece on an upside-down
  entity follows the body's axis instead of gravity. Documented in the constant's comment; not
  reproduced or measured.
- **The classification on real models.** The category weights are exercised through
  `verticalFollow()`, but I did not run the classification over a shipped YSM model's bone list, so
  the split between cloth/hair/tail/unknown on, say, `01_taisho_maid` is unmeasured. The `follow=`
  column added to the `[physics] segments` log line is what answers that in game.
- **Collision with the widened reach.** The `skipFor` reach now includes the pose-to-target gap.
  Reasoning says this makes volumes *more* likely to be skipped as unsatisfiable and so can only
  weaken collision; the volumetric tests (`MaidSkirtCollisionTest`, `YsmPhysicsPartsLimitTest`) are
  green but no test constructs a volume that the old reach would have kept and the new one skips.
- **In-game tuning.** Whether 0.92 / 0.80 / 0.60 are the right *taste* — as opposed to meeting the
  stated ≤10° criterion — needs a client.

## 11. Files

Changed by this task (relative to HEAD):

| file | change |
|---|---|
| `YsmDynamicBoneSolver.java` | new params, `target` field, blend, spring term, cone axis, reach |
| `YsmPhysicsTuning.java` | `DEFAULTS` inlined to literals (the deleted class's constants); `gravityFollowScale()` |
| `YSMCompatConfig.java` | `secondaryMotionGravityFollow` + the three missing cloth keys |
| `YsmPhysicsParts.java` | `Segment#verticalFollow()`, `Category`, weights |
| `YsmMeshSecondaryMotion.java` | `toLocal` (was `YsmPhysicsSimulator.toLocal`), call site, `DOWN_IN_MODEL_SPACE`, `follow=` log |
| `YSMPlayerAnimator.java` | completed the revert: dead spring loop and its fields removed, report kept |
| `YsmClothTuning.java` | (no change — the three keys it needed were added instead) |
| `YsmDynamicBoneSolverTest.java` | 9 new tests + helper section |
| `MaidSkirtCoherenceTest.java` | `NO_FOLLOW`/`DOWN` constants for the widened signature |
| `YsmPhysicsTuningTest.java` | defaults asserted as literals |

Evidence in `tmp_verify/`: `T9_equilibrium.java` + `T9_equilibrium_table.txt`,
`T9_probe.java` + `T9_probe.txt`, `T9_mutant_results.txt`, `T9_mutants/{00-baseline,M1,M2,M3}`,
this file.
