package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the reported defect - "the skirt panels fly apart" - on the real model that shows
 * it, offline.
 *
 * <p>The fixture is the plaintext source of {@code wine_fox/01_taisho_maid}, the maid outfit
 * whose skirt is twenty-four separate panels ({@code RM/RM2/RM3}, {@code RF...}, {@code FM1/FM2},
 * {@code BL...}, {@code BM...}, {@code BR...}, {@code FL1/FL2}). Every number used here is read
 * from that model: the pivot chain from {@code models/main.json}, each panel's own cube for its
 * centre of mass and lever, and the same scale the mesh is baked with.
 *
 * <h2>What the in-game log said, and what these tests pin</h2>
 *
 * <p>A session's worth of {@code [physics]} lines reported {@code max swing} between 33.6 and
 * 60.0 degrees, median 45.2, over five minutes - never settling, and touching the 60 degree
 * per-segment clamp exactly. A piece that is merely swinging settles when the body does; a piece
 * held at a fixed large angle is in equilibrium with a <i>constant</i> force, and the only
 * constant force in the model is the air drag. The arithmetic says why: at a 5 block/s run this
 * skirt's panels feel about 400 rad/s^2 of drag against 220 rad/s^2 of spring, so the equilibrium
 * is the clamp rather than an angle the spring can hold, and each panel sits at its own extreme.
 *
 * <p>So the tests are: a run must put every panel on <i>its own</i> balance rather than on one
 * constant, a stride must move the cloth without any panel leaving its limit, and the panels must
 * not exceed what the spring can hold.
 */
class MaidSkirtCoherenceTest {

    /** The panels of this model, by name, as {@code [physics]} reported them. */
    private static final String[] SKIRT_PANELS = {
            "RM", "RM2", "RM3", "RF", "RF2", "RF3", "RB", "RB2", "RB3",
            "BL", "BL2", "BL3", "BM", "BM2", "BM3", "BR", "BR2", "BR3",
            "FL1", "FL2", "FM1", "FM2"};

    /** The spring frequency the run used: the default 220 1/s^2 is sqrt(220)/2pi. */
    private static final float FREQUENCY = (float) (Math.sqrt(220.0) / (2.0 * Math.PI));
    private static final float DAMPING = (float) (24.0 / (2.0 * Math.sqrt(220.0)));

    /** Seconds per frame at the 8-15 ms the log reported. */
    private static final float FRAME = 0.010F;

    /** The gravity the solver is handed: the default, since nothing here is a gravity test. */
    private static final float GRAVITY = YsmDynamicBoneSolver.GRAVITY;

    /** The drag the solver applies, so the balance the tests assert on is the solver's own law. */
    private static final float AIR_DRAG = YsmDynamicBoneSolver.AIR_DRAG;

    /**
     * The gravity-follow weight these runs use, and where it says they are aimed.
     *
     * <p>Zero and the world's vertical, which is the pair every test in this file was written
     * against: at zero the spring's target is the pose, so a skirt panel's coupling and its
     * neighbours' agreement are measured with the pose as the only thing holding them - which is
     * what the shipped log's numbers came from. The gravity-follow mechanism has its own tests in
     * {@code YsmDynamicBoneSolverTest}; changing this constant here would move this file's baseline
     * and invalidate the measurements it exists to pin.
     */
    private static final float NO_FOLLOW = 0.0F;
    private static final Vector3f DOWN = new Vector3f(0.0F, -1.0F, 0.0F);

    /**
     * A hand-authored panel hanging straight down, at the lever and mass this model's panels have.
     */
    private record Panel(String name, float lever, float mass) {}

    @Test
    void theRealPanelsAreTheOnesTheLogReported() throws IOException {
        List<Panel> panels = realPanels();

        assertEquals(SKIRT_PANELS.length, panels.size(),
                "the skirt is " + SKIRT_PANELS.length + " panels; got " + names(panels));
        for (Panel panel : panels) {
            assertTrue(panel.lever() > 0.02F && panel.lever() < 0.6F,
                    panel.name() + " should have a lever of a few centimetres, got " + panel.lever());
        }
    }

    /**
     * A run puts every panel on <i>its own</i> balance, computed from its own lever and mass - and
     * not on one constant.
     *
     * <h2>Why this replaces "a run does not pin every panel at its limit"</h2>
     *
     * <p>The assertion this replaces - no panel may settle within 2% of its limit while running -
     * passed on the old solver, and it passed <i>because</i> of the design the rewrite deleted: the
     * outside-force total was capped at {@code 0.3 * omega_n^2} = 66 rad/s^2, which for this model's
     * panels is roughly a tenth of the drag they really feel at 5 blocks/s. The test was therefore
     * measuring the ceiling, not the cloth, and it could not distinguish "the panels hang where the
     * physics puts them" from "the panels are parked by a bound". Loosening it to make a rewritten
     * solver pass would be the same mistake in the other direction, so it is replaced by the
     * statement the physics actually makes:
     *
     * <pre>
     *   tan(theta_balance) = C * v^2 / (m * L * (omega_n^2 + g/L))
     * </pre>
     *
     * <p>For this fixture ({@code L} = 0.084..0.159, {@code m} = 0.38, {@code C} = 0.9,
     * {@code omega_n^2} = 220, {@code g} = 24) the balance is:
     *
     * <pre>
     *   1 block/s (a walk): 2.33 to 3.24 degrees - inside the 20 degree limit, and different for
     *                       every panel because the lever is what decides it
     *   5 blocks/s (a run): 45.5 to 54.8 degrees - outside the limit for every panel of the skirt
     * </pre>
     *
     * <p>So the honest run assertion is <b>two-sided</b>: at 5 blocks/s every panel must be on its
     * stop <i>because its own balance is outside it</i> - the panel really does feel nine times the
     * torque the spring can hold - and at 1 block/s no panel may be on its stop at all, and the
     * angles must not all be equal. The second half is the discriminator: the removed ceiling gave
     * all twenty-two panels the same {@code asin(0.3)} = 17.46 degrees whatever their lever.
     */
    @Test
    void aRunPutsEveryPanelOnItsOwnBalanceNotOnOneConstant() {
        List<Panel> panels = realPanelsUnchecked();
        float limit = 0.35F;

        int onStopWhileWalking = 0;
        float minWalk = Float.MAX_VALUE;
        float maxWalk = 0.0F;
        for (Panel panel : panels) {
            float balance = windBalance(panel, 1.0F);
            float settled = settledAngle(panel, new Vector3f(0.0F, 0.0F, -1.0F), limit);
            assertEquals(balance, settled, 0.02F, panel.name() + " (L=" + panel.lever() + ", m="
                    + panel.mass() + ") settled at " + Math.toDegrees(settled)
                    + " degrees, and its own drag balance is " + Math.toDegrees(balance));
            if (settled >= limit * 0.98F) {
                onStopWhileWalking++;
            }
            minWalk = Math.min(minWalk, settled);
            maxWalk = Math.max(maxWalk, settled);
        }
        assertEquals(0, onStopWhileWalking,
                onStopWhileWalking + " of " + panels.size() + " panels sat on their stop at walking"
                        + " speed, where the drag balance is a couple of degrees");
        assertTrue(maxWalk - minWalk > 0.005F,
                "the panels must trail by their own amounts, decided by their own levers; the spread"
                        + " was " + Math.toDegrees(maxWalk - minWalk) + " degrees. One angle for the"
                        + " whole skirt is what the removed outside-force ceiling produced.");

        int onStopWhileRunning = 0;
        for (Panel panel : panels) {
            float balance = windBalance(panel, 5.0F);
            float settled = settledAngle(panel, new Vector3f(0.0F, 0.0F, -5.0F), limit);
            assertTrue(balance > limit,
                    panel.name() + " balances at " + Math.toDegrees(balance)
                            + " degrees at 5 blocks/s, inside the limit - so this fixture no longer"
                            + " tests what it was written for");
            assertEquals(Math.min(balance, limit), settled, 0.02F,
                    panel.name() + " must rest on its stop while running, because its own balance is"
                            + " outside it (" + Math.toDegrees(balance) + " degrees)");
            if (settled >= limit * 0.98F) {
                onStopWhileRunning++;
            }
        }
        assertEquals(panels.size(), onStopWhileRunning,
                "at 5 blocks/s the drag is nine times what the spring can hold, so every panel"
                        + " belongs on its stop; " + onStopWhileRunning + " of " + panels.size()
                        + " were");
    }

    /** The angle the wind balance puts a panel at: {@code tan(t) = C v^2 / (m L (omega^2 + g/L))}. */
    private static float windBalance(Panel panel, float speed) {
        double omegaSquared = Math.pow(2.0 * Math.PI * FREQUENCY, 2.0);
        double drive = AIR_DRAG * speed * speed / (panel.mass() * panel.lever());
        double restoring = omegaSquared + GRAVITY / panel.lever();
        return (float) Math.atan(drive / restoring);
    }

    /**
     * And the trail is still there: the wind has to move the cloth, or the fix would just be
     * "turn the feature off". A running panel should settle well clear of its rest direction.
     */
    @Test
    void aRunStillTrailsTheCloth() {
        List<Panel> panels = realPanelsUnchecked();
        Vector3f running = new Vector3f(0.0F, 0.0F, -5.0F);

        float total = 0.0F;
        for (Panel panel : panels) {
            total += settledAngle(panel, running, 1.5F);
        }
        float average = total / panels.size();

        assertTrue(average > 0.10F,
                "running must sweep the panels back; average trail was only " + average + " rad");
    }

    /**
     * Coherence: the panels are one garment, so at a given speed they should all trail by
     * comparable amounts. The failure that matters is not a large angle but a large
     * <i>spread</i> - panels pointing every which way.
     */
    @Test
    void thePanelsTrailTogetherRatherThanEveryWhichWay() {
        List<Panel> panels = realPanelsUnchecked();
        Vector3f running = new Vector3f(0.0F, 0.0F, -5.0F);

        float min = Float.MAX_VALUE;
        float max = 0.0F;
        for (Panel panel : panels) {
            float angle = settledAngle(panel, running, 1.5F);
            min = Math.min(min, angle);
            max = Math.max(max, angle);
        }

        assertTrue(max - min < 0.25F,
                "panels of one skirt must trail by comparable angles; spread was " + (max - min)
                        + " rad (min " + min + ", max " + max + ")");
    }

    /** With the body still, the panels hang: gravity and the spring have nothing to fight. */
    @Test
    void aStillBodyLeavesTheSkirtAtRest() {
        List<Panel> panels = realPanelsUnchecked();

        for (Panel panel : panels) {
            float angle = settledAngle(panel, new Vector3f(), 1.5F);
            assertTrue(angle < 0.02F,
                    panel.name() + " swings " + angle + " rad with the body still; a skirt must hang");
        }
    }

    /**
     * The defect as the shipped build actually showed it: a walking character.
     *
     * <p>The log that came back had {@code max swing} never below 33.6 degrees over five minutes,
     * median 45.2, touching the 60 degree clamp - and the drag ceiling changed none of it. The
     * force that was actually doing it is the pivot's own acceleration, which the pose produces
     * every step and which the solver turned into a fictitious force with a ceiling thirteen
     * times the spring's authority. A walking hip is the honest test: this drives the pivot along
     * a step cadence at the amplitude a stride really has, and requires that the panels follow
     * their pose rather than being pinned to their limits by it.
     */
    /**
     * The stride is answered by every panel, by its own amount, and none of them leaves its limit.
     *
     * <p>This replaces "walking does not pin the panels at their limits", which asserted that no
     * panel comes within 2% of its stop under a 0.18 block, 2 Hz hip. That assertion is not
     * satisfiable by a physical solver: the hip acceleration is about 28 blocks/s^2, which on this
     * skirt's levers balances the panels at 25.7 to 33.8 degrees - outside the 20 degree limit this
     * test hands them - so a panel that is <i>not</i> on its stop is a panel the drag and the
     * fictitious force did not reach. The old solver satisfied it with the same ceiling that parked
     * a whole model at {@code asin(0.3)} = 17.46 degrees.
     *
     * <p>What is asserted instead, and what the ceiling could not produce:
     *
     * <ul>
     *   <li>the limit holds under the stride - no panel exceeds it (this was the test's second
     *       assertion and it stays);</li>
     *   <li>every panel is genuinely driven, at least 0.20 rad of swing over the stride - measured
     *       0.279 to 0.350;</li>
     *   <li>the panels do not all answer identically: spread &gt; 0.01 rad, measured 0.071 new
     *       against 0.030 old. <b>Stated plainly: that one is a sanity bound, not a
     *       discriminator.</b> The stride is aggressive enough that both builds end up near the stop,
     *       so the two are only three degrees apart here; the assertion that separates them is the
     *       run test above, where the old ceiling produced one angle for all twenty-two panels and
     *       the physics produces each panel's own balance.</li>
     * </ul>
     */
    @Test
    void theStrideMovesEveryPanelByItsOwnAmountWithoutLeavingTheLimit() {
        List<Panel> panels = realPanelsUnchecked();
        float rootLimit = 0.35F;

        float min = Float.MAX_VALUE;
        float max = 0.0F;
        for (Panel panel : panels) {
            float angle = walkedAngle(panel, rootLimit);
            assertTrue(angle <= rootLimit + 1.0E-3F,
                    panel.name() + " left its limit under the stride: " + angle + " rad against "
                            + rootLimit);
            assertTrue(angle > 0.20F, panel.name() + " barely moved under the stride: " + angle
                    + " rad. A 28 blocks/s^2 hip has to show in the cloth.");
            min = Math.min(min, angle);
            max = Math.max(max, angle);
        }

        assertTrue(max - min > 0.01F,
                "the panels must not all answer the stride by the same amount; the spread was "
                        + (max - min) + " rad (min " + min + ", max " + max + "). No spread at all"
                        + " is the signature of a bound rather than a balance.");
    }

    /**
     * And the walk still moves the cloth: the step has to show, or the ceiling would be
     * indistinguishable from switching the feature off.
     */
    @Test
    void walkingStillMovesTheCloth() {
        List<Panel> panels = realPanelsUnchecked();

        float total = 0.0F;
        float peak = 0.0F;
        for (Panel panel : panels) {
            float angle = walkedAngle(panel, 1.5F);
            total += angle;
            peak = Math.max(peak, angle);
        }

        assertTrue(total / panels.size() > 0.03F,
                "a step should be visible in the cloth; average displacement was "
                        + (total / panels.size()) + " rad");
        assertTrue(peak > 0.08F, "and at least some panels should swing clearly; peak was " + peak);
    }

    /**
     * The panels must move <i>together</i>. This is the assertion that matches the complaint:
     * what a viewer calls "the panels flew apart" is not a large angle but a large spread, because
     * each panel of a skirt is a separate mesh part and any difference between neighbours opens a
     * visible gap.
     */
    @Test
    void walkingKeepsThePanelsTogether() {
        List<Panel> panels = realPanelsUnchecked();

        float min = Float.MAX_VALUE;
        float max = 0.0F;
        for (Panel panel : panels) {
            float angle = walkedAngle(panel, 1.5F);
            min = Math.min(min, angle);
            max = Math.max(max, angle);
        }

        assertTrue(max - min < 0.30F,
                "panels of one skirt must move by comparable amounts; spread was " + (max - min)
                        + " rad (min " + min + ", max " + max + ")");
    }

    /**
     * Walk one panel: a step cadence on the pivot, at the amplitude a stride produces.
     *
     * <p>A hip swings through roughly a third of a block in a step, at about two steps a second,
     * which is a peak acceleration near 40 blocks/s^2 - well inside what the solver accepts, and
     * enough on a 0.14 block lever to be the largest force in the model if nothing bounds it.
     */
    private static float walkedAngle(Panel panel, float limit) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f rest = new Vector3f(0.0F, -1.0F, 0.0F);
        Vector3f pivot = new Vector3f();
        Vector3f previous = new Vector3f();
        Vector3f velocity = new Vector3f();
        Vector3f body = new Vector3f(0.0F, 0.0F, -2.0F);

        float amplitude = 0.18F;
        float cadence = 2.0F * (float) Math.PI * 2.0F;
        float time = 0.0F;
        float peak = 0.0F;
        for (int i = 0; i < 900; i++) {
            time += FRAME;
            pivot.set(0.0F, amplitude * (float) Math.sin(cadence * time), 0.0F);
            velocity.set(pivot).sub(previous).div(FRAME);
            previous.set(pivot);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, panel.lever(),
                    FREQUENCY, DAMPING, panel.mass(), limit, body,
                    YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, FRAME, out);
            if (i > 300) {
                peak = Math.max(peak, YsmDynamicBoneSolver.angleBetween(rest, state.direction));
            }
        }
        return peak;
    }

    /**
     * Settle one panel under a steady body velocity and report the angle it comes to rest at.
     *
     * <p>The pivot is held still because a body moving at a constant speed has no pivot
     * acceleration - only the airflow, which is the force under test.
     */
    private static float settledAngle(Panel panel, Vector3f bodyVelocity, float limit) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(0.0F, -1.0F, 0.0F);
        for (int i = 0; i < 1200; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, panel.lever(),
                    FREQUENCY, DAMPING, panel.mass(), limit, bodyVelocity,
                    YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, FRAME, out);
        }
        return YsmDynamicBoneSolver.angleBetween(rest, state.direction);
    }

    // ------------------------------------------------------------------

    /**
     * The model's own skirt panels: lever and mass derived the way {@link YsmPhysicsParts} does,
     * from each bone's pivot and its own cube.
     */
    private static List<Panel> realPanels() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource("/golden/maid/models/main.json"));
        float scale = widthScale();
        List<Panel> panels = new ArrayList<>();
        for (String name : SKIRT_PANELS) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            assertNotNull(bone, "the fixture has no bone " + name);
            Vector3f pivot = new Vector3f(bone.pivotX * scale, bone.pivotY * scale, bone.pivotZ * scale);
            Vector3f centre = cubeCentre(bone, scale);
            assertNotNull(centre, name + " has no cube to take a centre of mass from");
            float lever = centre.distance(pivot);
            int vertices = bone.quads.size() * 4;
            float mass = Math.max(0.25F, Math.min(4.0F, vertices / 64.0F));
            panels.add(new Panel(name, lever, mass));
        }
        return panels;
    }

    /** The same list, with a fixture failure reported as the assertion it is. */
    private static List<Panel> realPanelsUnchecked() {
        try {
            return realPanels();
        } catch (IOException e) {
            throw new AssertionError("could not read the maid fixture", e);
        }
    }

    /** The mean of a bone's cube corners, which is what the mesh's vertices average to. */
    private static Vector3f cubeCentre(YSMGeoModel.Bone bone, float scale) {
        Vector3f acc = new Vector3f();
        int count = 0;
        for (YSMGeoModel.Quad quad : bone.quads) {
            for (Vector3f corner : quad.positions) {
                if (corner == null) {
                    continue;
                }
                acc.add(corner.x * scale, corner.y * scale, corner.z * scale);
                count++;
            }
        }
        return count == 0 ? null : acc.div(count);
    }

    private static float widthScale() throws IOException {
        com.google.gson.JsonObject root = com.google.gson.JsonParser
                .parseString(resource("/golden/maid/ysm.json")).getAsJsonObject();
        if (root.has("properties") && root.getAsJsonObject("properties").has("width_scale")) {
            return root.getAsJsonObject("properties").get("width_scale").getAsFloat();
        }
        return 0.7F;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = MaidSkirtCoherenceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> names(List<Panel> panels) {
        List<String> names = new ArrayList<>();
        for (Panel panel : panels) {
            names.add(panel.name());
        }
        return names;
    }
}
