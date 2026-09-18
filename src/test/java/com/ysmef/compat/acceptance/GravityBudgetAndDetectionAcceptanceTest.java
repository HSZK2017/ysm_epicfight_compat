package com.ysmef.compat.acceptance;

import com.ysmef.compat.model.runtime.YsmDynamicBoneSolver;
import com.ysmef.compat.model.runtime.YsmPhysicsBinding;
import com.ysmef.compat.ysm.script.ScriptAnim;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four claims the round of work most needs falsified, each checked without reusing the
 * implementation's own expected value.
 *
 * <ol>
 *   <li><b>Gravity is no longer saturated.</b> The analytic rest angle of a pendulum whose rest
 *       direction is {@code phi} from plumb is the root of {@code G sin(phi - theta) =
 *       L omega^2 sin(theta)}. This suite solves that by bisection itself and compares it with
 *       what the solver settles at - and does so at three different masses, because the balance
 *       is mass-independent and an inertia term that failed to cancel mass would show up as an
 *       angle that moves with it. Nothing here is taken from the implementation.</li>
 *   <li><b>A chain's last joint no longer gets zero budget.</b> The shipped log's {@code Tail4}
 *       to {@code Tail7} each had {@code 0.0deg allowed}; the budget rule is checked directly
 *       over a seven-joint chain, and the assertion is that every joint - including the last -
 *       may still move.</li>
 *   <li><b>The detection cross-check refuses a plausible-looking animation.</b> An animation
 *       named {@code Hair_Physics}, carrying real {@code ysm.second_order} calls, that drives
 *       bones this model does not have must be refused rather than accepted on its name.</li>
 *   <li><b>All parts go through the solver.</b> Both directions: the source guard is in
 *       {@code ImplementationPathAcceptanceTest}, and the runtime proxy here is reflection over
 *       the class's declared members, which is as close to a behavioural proxy as a class that
 *       takes an Epic Fight skinned mesh allows. What it cannot prove is stated in the report's
 *       in-game checklist.</li>
 * </ol>
 */
class GravityBudgetAndDetectionAcceptanceTest {

    private static final float DT = 1.0F / 60.0F;
    private static final Vector3f STILL = new Vector3f();
    private static final float GRAVITY = AcceptanceSupport.constant("GRAVITY", 24.0F);

    private static final Vector3f REST = new Vector3f(0.0F, -1.0F, 0.0F);

    // ------------------------------------------------------------------
    // 1. the rest angle, solved here rather than taken from the implementation
    // ------------------------------------------------------------------

    /**
     * The angle the solver settles at is the root of the pendulum balance, at every mass.
     *
     * <h2>The balance, derived here</h2>
     *
     * <p>A piece of length {@code L} rotated {@code theta} away from its rest direction, whose
     * rest direction itself leans {@code phi} from plumb, has two torques about its pivot:
     * gravity {@code m g L sin(phi - theta)} pulling it further out, and the author's spring
     * {@code m L^2 omega^2 sin(theta)} pulling it back. At rest they cancel, and the mass drops
     * out of both sides:
     *
     * <pre>
     *   G sin(phi - theta) = L omega^2 sin(theta)
     * </pre>
     *
     * <p>The root is found here by bisection on that equation - forty iterations on an interval
     * that brackets the root - so the expected value is this test's own arithmetic and not the
     * implementation's. The comparison is then made at three masses spanning a factor of sixteen,
     * which is the part that catches a missing or wrong inertia term: {@code alpha = (d x g)/L}
     * is mass-independent because {@code I = m L^2} and {@code tau = m L (d x g)} divide it out,
     * and an implementation that kept {@code mass} in only one of them would settle at a
     * different angle for a heavy piece than for a light one.
     */
    @Test
    @DisplayName("the settling angle is the analytic pendulum balance, at every mass")
    void theSettlingAngleIsTheAnalyticBalanceAndIsMassIndependent() {
        float lever = 0.14F;
        float frequency = 2.36F;
        float omega = (float) (2.0 * Math.PI * frequency);
        float limit = AcceptanceSupport.radians(80.0);
        float phi = AcceptanceSupport.radians(35.0);

        // The rest direction leans phi from plumb, so gravity can pull the piece away from it.
        Vector3f rest = new Vector3f(0.0F, -(float) Math.cos(phi), (float) Math.sin(phi));
        float expected = bisect(phi, lever, omega);

        assertTrue(AcceptanceSupport.degrees(expected) > 3.0F,
                "the fixture must actually be displaced from its rest by gravity; the analytic"
                        + " answer was " + AcceptanceSupport.degrees(expected) + " degrees");

        for (float mass : new float[]{0.25F, 1.0F, 4.0F}) {
            float settled = settle(mass, lever, frequency, limit, rest);
            assertEquals(AcceptanceSupport.degrees(expected), AcceptanceSupport.degrees(settled), 1.5F,
                    "at mass " + mass + " the piece settled at "
                            + AcceptanceSupport.degrees(settled) + " degrees but the pendulum balance"
                            + " G sin(phi - theta) = L omega^2 sin(theta) puts it at "
                            + AcceptanceSupport.degrees(expected) + " degrees. A difference here is"
                            + " either a saturated gravity term or an inertia that does not cancel"
                            + " the mass - and a piece that settles at a different angle depending"
                            + " on how heavy it is cannot be hanging under gravity.");
        }
    }

    /** Bisection on {@code G sin(phi - theta) - L omega^2 sin(theta)} over {@code (0, phi)}. */
    private static float bisect(float phi, float lever, float omega) {
        float low = 0.0F;
        float high = phi;
        for (int i = 0; i < 60; i++) {
            float mid = 0.5F * (low + high);
            float f = GRAVITY * (float) Math.sin(phi - mid)
                    - lever * omega * omega * (float) Math.sin(mid);
            if (f > 0.0F) {
                low = mid;      // gravity still wins: the piece has to come further out
            } else {
                high = mid;
            }
        }
        return 0.5F * (low + high);
    }

    /** Runs the solver with a motionless pivot until the swing stops changing. */
    private static float settle(float mass, float lever, float frequency, float limit,
                                Vector3f rest) {
        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, rest, lever, frequency, 0.35F, mass, limit,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
        Quaternionf out = new Quaternionf();
        float previous = Float.NaN;
        float angle = 0.0F;
        for (int frame = 0; frame < 1200; frame++) {
            AcceptanceSupport.step(state, pivot, rest, lever, frequency, 0.35F, mass, limit,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, out);
            angle = AcceptanceSupport.angleBetween(rest, state.direction);
            if (frame > 600 && Float.isFinite(previous)
                    && Math.abs(angle - previous) < 1.0E-7F) {
                break;
            }
            previous = angle;
        }
        assertTrue(Float.isFinite(angle), "the settling angle is not finite");
        return angle;
    }

    // ------------------------------------------------------------------
    // 2. the chain budget no longer runs out before the last joint
    // ------------------------------------------------------------------

    /**
     * A seven-joint chain still leaves its last joint a non-zero budget.
     *
     * <p>The shipped log is the spec: {@code Tail4} through {@code Tail7} each reported
     * {@code 0.0deg allowed} and {@code 0.0deg own} - they never moved - while the three joints
     * above them used the whole chain limit. Whatever the budget rule is, exhausting it at joint
     * four of seven is the defect, so the assertion is simply that a joint at the end of a long
     * chain is still allowed to move.
     *
     * <p>The number the fix is expected to produce (a seventh of the limit, 8.57 degrees for a
     * 60 degree chain) is <b>not</b> asserted: pinning it would test the formula rather than the
     * behaviour, and any rule that leaves the last joint a usable share is an answer to the
     * reported problem.
     */
    @Test
    @DisplayName("the last joint of a seven-joint chain still has a budget")
    void theLastJointOfALongChainIsNotStarved() {
        float strandLimit = AcceptanceSupport.radians(60.0);
        float rootLimit = AcceptanceSupport.radians(20.0);

        float[] allowances = AcceptanceSupport.pieceAllowances(7, rootLimit, strandLimit);
        float last = allowances[allowances.length - 1];
        float total = 0.0F;
        for (float allowance : allowances) {
            total += allowance;
        }
        float pieceTotal = AcceptanceSupport.chainLimitFor(7, strandLimit);

        StringBuilder table = new StringBuilder("[T5] seven-joint piece, per-joint allowance:");
        for (float allowance : allowances) {
            table.append(' ').append(Math.round(AcceptanceSupport.degrees(allowance) * 10.0F) / 10.0F);
        }
        table.append(" deg (total ").append(Math.round(AcceptanceSupport.degrees(total) * 10.0F) / 10.0F)
                .append(" deg, piece total ").append(Math.round(AcceptanceSupport.degrees(pieceTotal) * 10.0F) / 10.0F)
                .append(" deg)");
        System.out.println(table);

        assertTrue(AcceptanceSupport.degrees(last) > 0.5F,
                "the seventh joint of a seven-joint chain was allowed only "
                        + AcceptanceSupport.degrees(last) + " degrees, which is the shipped log's"
                        + " defect: the joints above it used the whole budget and the hem was bolted"
                        + " to the pose while the waistband swung");
        assertTrue(total <= pieceTotal + 1.0E-3F,
                "the chain as a whole must still be bounded by its piece total, and it used "
                        + AcceptanceSupport.degrees(total) + " degrees of "
                        + AcceptanceSupport.degrees(pieceTotal));
    }

    /**
     * A long chain's per-joint budget must not shrink towards nothing as the chain gets longer.
     *
     * <h2>Why this is a separate test, and what it measures that the one above does not</h2>
     *
     * <p>{@link #theLastJointOfALongChainIsNotStarved()} only asks that the last joint gets
     * <i>something</i>, and a rule that divides a fixed chain limit by the number of joints
     * satisfies it while being wrong on screen: seven joints sharing sixty degrees get 8.57 degrees
     * each, and a ponytail whose every bone turns by eight degrees does not hang or swing, it stands
     * still. That is the defect this test exists for, and on the revision that first introduced the
     * per-joint split it <b>fails</b>.
     *
     * <h2>The frame path, in the order the frame path takes it</h2>
     *
     * <p>An earlier version of this test called {@code chainAllowance} with the per-joint limit where
     * the piece total belongs, which is not what the render path does. It passed, and only because
     * the per-joint floor happened to land exactly on the assertion's boundary - a measurement error
     * that the assertion could not see. The two steps are now taken in order:
     * {@code chainLimitFor(joints, limit)} for the piece's total, then
     * {@code chainAllowance(own, total, used, jointsLeft)} at each joint.
     *
     * <h2>Why fifteen degrees, in blocks rather than in degrees</h2>
     *
     * <p>An angle is a poor threshold because the same angle is a different distance on a different
     * lever, and distance is what a viewer sees. A joint allowed {@code theta} lets its segment's
     * centre of mass travel {@code 2 L sin(theta/2)} from its rest position, and the shipped log says
     * what "not moving" looks like: {@code Tail4} to {@code Tail7} each reported
     * {@code moved 0.0 blocks}. Real strands have levers of 0.06 to 0.15 blocks, so the floor is
     * checked at the smallest of those: 15 degrees on {@code L} = 0.06 is 1.6 cm of travel, while the
     * degenerate tail bones' levers of 0.02 give 5 mm.
     *
     * <p>What that makes the assertion is a <b>necessary</b> condition, and it is stated as one: a
     * joint at the floor must still be able to move a centimetre at a lever the model actually has.
     * It is not sufficient - whether a piece <i>uses</i> its allowance is the dynamics, and that is
     * the part only a game can show.
     */
    @Test
    @DisplayName("a long chain keeps a usable per-joint budget, and the whole chain stays bounded")
    void thePerJointBudgetDoesNotVanishAsTheChainGrows() {
        float strandLimit = AcceptanceSupport.radians(60.0);
        float rootLimit = AcceptanceSupport.radians(20.0);
        float smallestLever = 0.06F;             // the low end of the levers a real strand has

        StringBuilder table = new StringBuilder("[T5] smallest per-joint allowance by chain length:");
        for (int joints = 1; joints <= 12; joints++) {
            float[] allowances = AcceptanceSupport.pieceAllowances(joints, rootLimit, strandLimit);
            float smallest = Float.MAX_VALUE;
            float total = 0.0F;
            for (float allowance : allowances) {
                smallest = Math.min(smallest, allowance);
                total += allowance;
            }
            float smallestDegrees = AcceptanceSupport.degrees(smallest);
            float pieceTotal = AcceptanceSupport.chainLimitFor(joints, strandLimit);
            table.append(" N=").append(joints).append(':')
                    .append(Math.round(smallestDegrees * 10.0F) / 10.0F);
            if (joints == 7) {
                table.append("(flat-sixty gave 8.6)");
            }

            assertTrue(smallestDegrees >= 14.5F,
                    "a chain of " + joints + " joints leaves its smallest joint only "
                            + smallestDegrees + " degrees. A per-joint share that falls as the chain"
                            + " grows is a tail that stops hanging: the shipped log is full of pieces"
                            + " at 8.6 degrees that never moved.");
            float travel = 2.0F * smallestLever * (float) Math.sin(0.5F * smallest);
            assertTrue(travel >= 0.01F,
                    "a joint allowed " + smallestDegrees + " degrees on a " + smallestLever
                            + " block lever can move its centre of mass only "
                            + Math.round(travel * 1000.0F) + " mm, below the centimetre that reads as"
                            + " motion; the shipped log's phantom pieces reported 'moved 0.0 blocks'");
            assertTrue(total <= pieceTotal + 1.0E-3F,
                    "a chain of " + joints + " joints was granted "
                            + AcceptanceSupport.degrees(total) + " degrees in total, more than the "
                            + AcceptanceSupport.degrees(pieceTotal) + " its piece total allows");
        }
        System.out.println(table);
        System.out.println("[T5] piece totals: 2 joints "
                + AcceptanceSupport.degrees(AcceptanceSupport.chainLimitFor(2, strandLimit))
                + " deg, 7 joints "
                + AcceptanceSupport.degrees(AcceptanceSupport.chainLimitFor(7, strandLimit))
                + " deg, 12 joints "
                + AcceptanceSupport.degrees(AcceptanceSupport.chainLimitFor(12, strandLimit))
                + " deg");

        // The two-joint case is the regression guard: a two-bone panel is the common shape, and
        // widening the budget for long chains must not change what a short one does. The base of a
        // piece is the number that decides how a skirt hangs.
        float root = AcceptanceSupport.chainAllowance(rootLimit,
                AcceptanceSupport.chainLimitFor(2, strandLimit), 0.0F, 2);
        assertEquals(AcceptanceSupport.degrees(rootLimit), AcceptanceSupport.degrees(root), 0.05F,
                "a two-joint chain's base was allowed " + AcceptanceSupport.degrees(root)
                        + " degrees instead of its own " + AcceptanceSupport.degrees(rootLimit)
                        + "; the shipped panel's base must not move");
    }

    // ------------------------------------------------------------------
    // 5. the air-drag knob: what the default is actually worth
    // ------------------------------------------------------------------

    /**
     * The air-drag knob decides whether a garment trails or is pinned at its stop, and this
     * measures where the boundary is - without reusing anyone's expected value.
     *
     * <h2>What is measured</h2>
     *
     * <p>One representative panel ({@code L} = 0.11 blocks, the author's 2.36 Hz, mass 1, the
     * 20 degree root limit the real garment gets) with a stationary pivot and the body moving at
     * 5 blocks/s, which is a run. The <b>body's velocity, not its acceleration</b>, is the driver
     * here: in the model's frame the air moves backwards at the body's speed, so a piece standing
     * still relative to the body feels a real wind, and the drag term turns it into a trail angle.
     * The settled angle is read after 20 s, and the two crossings that matter - the air drag and
     * the body speed at which the piece reaches its 20 degree limit - are found by bisection on
     * this test's own measurements.
     *
     * <p>The numbers this produces are the answer to "should the default 0.9 be lowered", and the
     * decision belongs to the report's §8 rather than to an assertion, so the assertions here are
     * only the properties: more air means a bigger trail angle, the range must actually bracket the
     * limit, and the walk-speed crossing must be finite and inside the range a player can reach.
     */
    @Test
    @DisplayName("the air-drag knob is what decides whether a panel trails or is pinned at its stop")
    void theAirDragKnobDecidesTrailVersusPinning() {
        float lever = 0.11F;
        float frequency = 2.36F;
        float mass = 1.0F;
        float limit = AcceptanceSupport.radians(20.0);
        float runSpeed = 5.0F;

        float[] drags = {0.9F, 0.6F, 0.45F, 0.3F, 0.235F, 0.2F, 0.1F};
        float[] angles = new float[drags.length];
        StringBuilder table = new StringBuilder("[T5] air drag vs trail angle at " + runSpeed
                + " blocks/s, L=" + lever + ", 20 deg limit:");
        for (int i = 0; i < drags.length; i++) {
            angles[i] = AcceptanceSupport.degrees(trailAngle(lever, frequency, mass, limit,
                    runSpeed, drags[i]));
            table.append(' ').append(drags[i]).append("->").append(Math.round(angles[i] * 10.0F) / 10.0F);
        }
        System.out.println(table);

        for (int i = 1; i < drags.length; i++) {
            assertTrue(angles[i] <= angles[i - 1] + 0.5F,
                    "less air (" + drags[i] + ") must not trail further than more air ("
                            + drags[i - 1] + "): " + angles[i] + " vs " + angles[i - 1]
                            + " degrees. The knob has to be monotone or it cannot be tuned.");
        }
        assertTrue(angles[0] > angles[drags.length - 1] + 2.0F,
                "the air-drag knob barely moves the trail angle (" + angles[0] + " to "
                        + angles[drags.length - 1] + " degrees), so it is not really wired up");

        float crossingDrag = bisectAirDrag(lever, frequency, mass, limit, runSpeed);
        float crossingSpeed = bisectSpeed(lever, frequency, mass, limit, AcceptanceSupport
                .constant("AIR_DRAG", 0.9F));
        System.out.println("[T5] air drag at which the 20 deg limit is first reached at "
                + runSpeed + " blocks/s: " + crossingDrag
                + "; body speed at which it is first reached at the default drag: " + crossingSpeed
                + " blocks/s");

        assertTrue(crossingDrag > 0.05F && crossingDrag < 1.0F,
                "the crossing air drag (" + crossingDrag + ") is outside the range a config can set,"
                        + " which means the limit is reached at every setting or at none");
        assertTrue(crossingSpeed > 0.5F && crossingSpeed < 8.0F,
                "at the default air drag the 20 degree root limit is first reached at "
                        + crossingSpeed + " blocks/s. If that is inside walking speed the garment is"
                        + " pinned at its stop for all ordinary movement, which is the symptom the"
                        + " round of work set out to remove - see the report's §8");
    }

    /** The angle a panel settles at, with the body moving at {@code bodySpeed} and no pivot motion. */
    private static float trailAngle(float lever, float frequency, float mass, float limit,
                                    float bodySpeed, float airDrag) {
        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        Vector3f body = new Vector3f(bodySpeed, 0.0F, 0.0F);
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, REST, lever, frequency, 0.35F, mass, limit,
                body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, airDrag, DT, new Quaternionf());
        Quaternionf out = new Quaternionf();
        float angle = 0.0F;
        for (int frame = 0; frame < 1200; frame++) {
            AcceptanceSupport.step(state, pivot, REST, lever, frequency, 0.35F, mass, limit,
                    body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, airDrag, DT, out);
            angle = AcceptanceSupport.angleBetween(REST, state.direction);
        }
        return angle;
    }

    /** The air drag at which the panel's settled angle first reaches the limit. */
    private static float bisectAirDrag(float lever, float frequency, float mass, float limit,
                                       float bodySpeed) {
        float noAir = 0.0F;          // the piece should hang near its rest
        float muchAir = 3.0F;        // the piece should be at its stop
        if (!below(trailAngle(lever, frequency, mass, limit, bodySpeed, noAir), limit)
                || below(trailAngle(lever, frequency, mass, limit, bodySpeed, muchAir), limit)) {
            // The range does not bracket the limit at all: either it is pinned with no air, or no
            // amount of air pins it. Reporting NaN lets the assertion say which.
            return Float.NaN;
        }
        for (int i = 0; i < 24; i++) {
            float mid = 0.5F * (noAir + muchAir);
            if (below(trailAngle(lever, frequency, mass, limit, bodySpeed, mid), limit)) {
                noAir = mid;
            } else {
                muchAir = mid;
            }
        }
        return 0.5F * (noAir + muchAir);
    }

    /** The body speed at which the panel's settled angle first reaches the limit. */
    private static float bisectSpeed(float lever, float frequency, float mass, float limit,
                                     float airDrag) {
        float slow = 0.05F;
        float fast = 20.0F;
        if (!below(trailAngle(lever, frequency, mass, limit, slow, airDrag), limit)
                || below(trailAngle(lever, frequency, mass, limit, fast, airDrag), limit)) {
            return Float.NaN;
        }
        for (int i = 0; i < 30; i++) {
            float mid = 0.5F * (slow + fast);
            if (below(trailAngle(lever, frequency, mass, limit, mid, airDrag), limit)) {
                slow = mid;
            } else {
                fast = mid;
            }
        }
        return 0.5F * (slow + fast);
    }

    /** Whether the piece stays strictly inside its limit, with a little room for the limit's own margin. */
    private static boolean below(float angle, float limit) {
        return angle < limit - AcceptanceSupport.radians(0.05);
    }

    // ------------------------------------------------------------------
    // 3. the detection cross-check
    // ------------------------------------------------------------------

    /**
     * An animation that looks exactly like a physics rig but drives none of this model's bones
     * is refused, and the refusal is reported.
     *
     * <p>This is the failure the cross-check was added for and the one that is easy to get wrong
     * in the flattering direction: {@code Hair_Physics} is YSM's own built-in name, it carries
     * real {@code ysm.second_order} calls, and on this machine it drives 58 bones - none of which
     * exist in the models that inherit it. Accepting it on its name would put a physics part list
     * on every model while moving none of them, which reads in the log exactly like a working
     * feature and on screen exactly like nothing happening.
     *
     * <p>The second half is the control: the identical animation with one bone the model does
     * have must be accepted, or the refusal proves nothing.
     */
    @Test
    @DisplayName("a physics-looking animation that drives none of the model's bones is refused")
    void anAnimationThatDrivesNothingOfThisModelIsRefused() {
        Map<String, List<String>> controllers = new LinkedHashMap<>();
        controllers.put("player.pre_parallel_0", List.of("Hair_Physics"));

        Map<String, ScriptAnim> animations = new LinkedHashMap<>();
        animations.put("Hair_Physics", physicsRig("SomeOtherModelsBone"));
        Map<String, ScriptAnim> ours = new LinkedHashMap<>();
        ours.put("Hair_Physics", physicsRig("BackHair"));

        YsmPhysicsBinding.Selection refused = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(controllers, animations, Map.of(), Map.of(),
                        List.of("BackHair", "BackHair2")));
        assertTrue(refused.parts().isEmpty(),
                "the animation drives no bone this model has, so it must not contribute parts; it"
                        + " contributed " + refused.parts().size());
        assertFalse(refused.rejected().isEmpty(),
                "a refusal has to be reported: 'bound but drives nothing' and 'authored nothing'"
                        + " look identical in every other number, and only this line tells them apart");

        YsmPhysicsBinding.Selection accepted = YsmPhysicsBinding.select(
                YsmPhysicsBinding.Sources.of(controllers, ours, Map.of(), Map.of(),
                        List.of("BackHair", "BackHair2")));
        assertEquals(1, accepted.parts().size(),
                "the control case must be accepted, otherwise the refusal above proves nothing");
        assertEquals("BackHair", accepted.parts().get(0).bone());
        assertTrue(accepted.rejected().isEmpty(),
                "an accepted candidate must not leave a refusal message behind");
    }

    /** A minimal animation whose one bone is driven by a real second-order spring call. */
    private static ScriptAnim physicsRig(String bone) {
        ScriptAnim anim = new ScriptAnim();
        anim.name = "Hair_Physics";
        ScriptAnim.BoneChannels channels = new ScriptAnim.BoneChannels();
        ScriptAnim.Channel rotation = new ScriptAnim.Channel();
        ScriptAnim.Key key = new ScriptAnim.Key();
        key.time = 0.0F;
        // The form the real models use: the bone's rotation is a spring over the value above it.
        key.post = ScriptAnim.Value.ofExpr(
                "ysm.second_order('x', v.above_x, 1.2, 0.7, 0)", null, null);
        rotation.keys.add(key);
        channels.rotation = rotation;
        anim.bones.put(bone, channels);
        return anim;
    }

    // ------------------------------------------------------------------
    // 4. every part goes through the solver - the runtime side of the pair
    // ------------------------------------------------------------------

    /**
     * There is exactly one path that writes a mesh transform, and everything it writes comes
     * out of the solver.
     *
     * <h2>Why the criterion is not "no method mentions authored"</h2>
     *
     * <p>The first version of this check scanned for the substring {@code authored} and went red
     * on {@code reportDeclaredRig} - a <b>logging method</b> that reports how many bones the
     * author's animation declares and how many of them the model actually has geometry for. It
     * writes no transform at all. A guard that fails on a log message's name is a guard nobody
     * will keep, so the criterion is narrowed to the property that matters:
     *
     * <ul>
     *   <li>no method named exactly {@code applyAuthored} - the take-over that was deleted;</li>
     *   <li>no {@code LAYERS} field - the per-model cache of evaluated author layers;</li>
     *   <li>{@code setRuntimeTransformAt} appears exactly once in code, it is inside
     *       {@code apply}, and what it writes is {@code state.deltas[i]} - a segment's delta,
     *       not an authored angle;</li>
     *   <li>{@code apply} calls {@code simulate(} and never branches on
     *       {@code segment.authored()};</li>
     *   <li>every write to {@code state.deltas} happens inside {@code simulate}.</li>
     * </ul>
     *
     * <p>Scope, stated honestly: a fully behavioural proof would run {@code apply} with a model and
     * a mesh, and an Epic Fight {@code YSMMesh} owns a vertex buffer and cannot be built outside a
     * game. What this establishes is that there is nothing left to take over <i>with</i>, and that
     * the single write site is fed by the simulation.
     */
    @Test
    @DisplayName("exactly one mesh-transform write path, fed by the solver")
    void thereIsNoSecondTransformPathLeft() throws Exception {
        Class<?> owner = Class.forName("com.ysmef.compat.model.runtime.YsmMeshSecondaryMotion");

        for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
            assertFalse(method.getName().equals("applyAuthored"),
                    "applyAuthored is declared again: that is the method that wrote the author's"
                            + " expression angles straight into the mesh and skipped the solver");
        }
        for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
            assertFalse(field.getName().equals("LAYERS"),
                    "the per-model cache of evaluated author layers is back, which is what let the"
                            + " two paths diverge");
        }

        String source = AcceptanceSupport.read(
                "com/ysmef/compat/model/runtime/YsmMeshSecondaryMotion.java");
        String code = AcceptanceSupport.stripComments(source);
        String apply = AcceptanceSupport.stripComments(AcceptanceSupport.methodBody(source, "void apply("));

        int writes = 0;
        int at = code.indexOf("setRuntimeTransformAt");
        while (at >= 0) {
            writes++;
            at = code.indexOf("setRuntimeTransformAt", at + 1);
        }
        assertEquals(1, writes,
                "there are " + writes + " places that write a mesh transform; one of them is the"
                        + " second path the fix deleted");

        assertTrue(apply.contains("setRuntimeTransformAt"),
                "apply() no longer writes anything into the mesh, so nothing it computes is drawn");
        assertTrue(apply.contains("simulate("),
                "apply() no longer runs the simulation, so whatever it writes was not integrated");

        // `authored()` is allowed exactly where it is a label rather than a decision: the
        // diagnostics line prints "(authored," or "(name," per segment. What must not exist is a
        // branch - `if (segment.authored())` - so each occurrence has to sit in a statement that
        // builds a string. An earlier version of this check rejected the bare word and went red on
        // the log line, which is the same false positive this whole criterion was narrowed for.
        for (String line : AcceptanceSupport.linesContaining(apply, "authored()")) {
            assertTrue(line.contains("append("),
                    "apply() uses segment.authored() somewhere that is not a log label: '"
                            + line.trim() + "'. The two sources must be treated identically here,"
                            + " and a branch on the flag is exactly how they diverged before.");
        }

        assertFalse(java.util.regex.Pattern.compile("deltas\\[[^\\]]*\\]\\s*=[^=]").matcher(apply).find(),
                "apply() computes a delta itself; deltas must come out of simulate()");
        String simulate = AcceptanceSupport.stripComments(
                AcceptanceSupport.methodBodyContaining(source, "simulate(", "deltas["));
        assertTrue(java.util.regex.Pattern.compile("deltas\\[[^\\]]*\\]\\s*=[^=]").matcher(simulate).find(),
                "simulate() no longer produces the deltas, so nothing in the file does");
    }
}
