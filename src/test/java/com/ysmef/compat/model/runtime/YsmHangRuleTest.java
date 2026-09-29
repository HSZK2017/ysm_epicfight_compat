package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vertical twin of {@code wrapsPivot}: a piece whose own geometry sits above its pivot cannot be
 * hanging from that pivot, so it must not be simulated.
 *
 * <h2>What these tests are anchored to</h2>
 *
 * <p>{@code EKU(1.0.ysm}'s {@code RightLegclothes2} is the reported defect, and its numbers are known
 * from the stored artefacts: authored pivot {@code (0.022, 0.068, 0.004)} - at the ankle - own
 * geometry spanning y 0.456..0.934 (the thigh), lever 0.622 blocks. Its sibling
 * {@code RightLegclothes1} shares the joint and hangs correctly: pivot {@code (0.092, 0.897, 0.014)}
 * with geometry y 0.456..0.967, lever 0.139. The pair is the calibration here: the rule must drop the
 * first and keep the second, and both are built below from the same geometry the mesh carries.
 *
 * <p>Each test is written so it can fail: the direction cases are opposites, the share/inches pair
 * distinguishes a direction test from a distance test, and the long-axis helper is asked for a shape
 * with no long axis as well as one with.
 */
class YsmHangRuleTest {

    /** The reported thigh piece: an ankle pivot under thigh geometry. */
    @Test
    void theThighDrawnByAnAnklePivotIsDropped() {
        Vector3f pivot = new Vector3f(0.022F, 0.068F, 0.004F);
        List<Vector3f> thigh = box(0.10F, 0.456F, 0.02F, 0.22F, 0.934F, 0.20F);
        Vector3f rest = centroid(thigh).sub(pivot);
        assertTrue(YsmPhysicsParts.risesFromPivot(rest, rest.length()),
                "the geometry is above the pivot by most of the lever, so the piece does not hang from it");
    }

    /** The same thigh's other half: a pivot at the top of the piece. */
    @Test
    void theSiblingHangingFromAPivotOnItIsKept() {
        Vector3f pivot = new Vector3f(0.092F, 0.897F, 0.014F);
        List<Vector3f> upper = box(0.02F, 0.456F, 0.02F, 0.18F, 0.967F, 0.20F);
        Vector3f rest = centroid(upper).sub(pivot);
        assertFalse(YsmPhysicsParts.risesFromPivot(rest, rest.length()),
                "a piece whose mass is below its pivot must keep its simulation");
    }

    /** The sign is the test: down is kept, up is dropped, and the two answer differently. */
    @Test
    void straightDownIsKeptAndStraightUpIsDropped() {
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, -1.0F, 0.0F), 1.0F));
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.3F, -0.9F, 0.2F), 0.7F));
        assertTrue(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, 1.0F, 0.0F), 1.0F));
        assertTrue(YsmPhysicsParts.risesFromPivot(new Vector3f(0.3F, 0.9F, 0.2F), 0.7F));
    }

    /**
     * Level is not "above": the margin exists because the sign of a level piece's up-component is
     * float noise, and the two answers below differ by nothing but that margin.
     */
    @Test
    void aPieceLevelWithItsPivotIsNotDropped() {
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.5F, 0.0F, 0.0F), 0.5F),
                "a piece whose centre of mass sits level with its pivot is not hanging above it");
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.5F, 0.02F, 0.0F), 0.5F),
                "four per cent of the lever up is inside the margin");
        assertTrue(YsmPhysicsParts.risesFromPivot(new Vector3f(0.5F, 0.25F, 0.0F), 0.5F),
                "half the lever up is a piece standing above its pivot");
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.5F, 0.02F, 0.0F), 0.5F,
                YsmPhysicsParts.risesFromPivotMargin()),
                "the shipped margin must be the one the two-argument form uses");
    }

    /**
     * The quantity is a <b>direction</b>, not a distance: the same absolute rise above the pivot is
     * dropped on a short lever and kept on a long one, because on the short lever it is most of the
     * piece and on the long one it is a corner of it.
     */
    @Test
    void theRuleReadsAShareOfTheLeverAndNotABlockCount() {
        float rise = 0.05F;
        assertTrue(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, rise, 0.0F), rise),
                "a piece whose whole lever points up stands above its pivot");
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, rise, 0.0F), 1.0F),
                "the same five centimetres on a one-block lever is not 'geometry above the pivot'; a "
                        + "rule that dropped this would be a distance threshold wearing a sign test's name");
    }

    /** Nothing unusable is ever "above the pivot": the safe answer is to keep the simulation. */
    @Test
    void degenerateInputsAreNotDropped() {
        assertFalse(YsmPhysicsParts.risesFromPivot(null, 1.0F));
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, 1.0F, 0.0F), 0.0F));
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, 1.0F, 0.0F), -1.0F));
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, 1.0F, 0.0F), Float.NaN));
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(Float.NaN, 1.0F, 0.0F), 1.0F));
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, Float.POSITIVE_INFINITY, 0.0F), 1.0F));
    }

    /** The margin the reports quote is the margin production applies. */
    @Test
    void theReportedMarginIsTheOneTheRuleUses() {
        assertEquals(0.10F, YsmPhysicsParts.risesFromPivotMargin(), 1.0E-6F);
        for (float share : new float[]{-1.0F, -0.01F, 0.0F, 0.05F, 0.099F, 0.101F, 0.5F, 1.0F}) {
            Vector3f rest = new Vector3f(0.0F, share, 0.0F);
            assertEquals(YsmPhysicsParts.risesFromPivot(rest, 1.0F),
                    YsmPhysicsParts.risesFromPivot(rest, 1.0F, YsmPhysicsParts.risesFromPivotMargin()),
                    "the two-argument form must be the shipped margin, at share " + share);
        }
    }

    // ------------------------------------------------------------------
    // the pair, which is what ships
    // ------------------------------------------------------------------

    /**
     * The shape that made the rule narrow: the known-good maid's tail tip. Its geometry stands above
     * its pivot - the direction test fires - but the pivot is <b>on</b> the piece, so the piece is a
     * hinge drawn upward and keeps its simulation. Before the containment condition was added, this is
     * one of the four pieces the rule took off that model.
     */
    @Test
    void aPieceStandingUpFromAPivotOnItselfIsKept() {
        Vector3f pivot = new Vector3f(0.0F, 0.10F, 0.0F);
        List<Vector3f> tailTip = box(-0.02F, 0.10F, -0.02F, 0.02F, 0.15F, 0.02F);
        Vector3f rest = centroid(tailTip).sub(pivot);
        assertTrue(YsmPhysicsParts.risesFromPivot(rest, rest.length()),
                "the tail tip's centre of mass is above its pivot, so the direction test fires");
        assertTrue(YsmPhysicsParts.pivotOnGeometry(tailTip, pivot),
                "and its pivot is still on the piece");
        assertFalse(YsmPhysicsParts.risesOffPivot(tailTip, pivot, rest, rest.length()),
                "so the rule that ships keeps it: a hinge on the piece turns it correctly whatever way it is drawn");
    }

    /** The reported defect: an ankle pivot under thigh geometry. */
    @Test
    void aThighDrawnByAnAnklePivotIsDropped() {
        Vector3f pivot = new Vector3f(0.022F, 0.068F, 0.004F);
        List<Vector3f> thigh = box(0.10F, 0.456F, 0.02F, 0.22F, 0.934F, 0.20F);
        Vector3f rest = centroid(thigh).sub(pivot);
        assertFalse(YsmPhysicsParts.pivotOnGeometry(thigh, pivot));
        assertTrue(YsmPhysicsParts.risesOffPivot(thigh, pivot, rest, rest.length()),
                "the geometry stands above a pivot that is 0.388 blocks below it, which is the defect");
        double gap = YsmPhysicsParts.pivotGapFromGeometry(thigh, pivot);
        assertTrue(gap > 0.38 && gap < 0.40, String.format(java.util.Locale.ROOT,
                "and the gap the log prints is the ankle-to-thigh distance, about 0.39 blocks on the real "
                        + "piece (measured %.3f here; the test box is offset in x and z as well as y)", gap));
    }

    /** Containment is a boolean at zero: on a face is on the piece, a hair outside is not. */
    @Test
    void thePivotIsOnThePieceUpToAndIncludingItsBox() {
        List<Vector3f> block = box(-0.1F, 0.0F, -0.1F, 0.1F, 0.4F, 0.1F);
        assertTrue(YsmPhysicsParts.pivotOnGeometry(block, new Vector3f(0.0F, 0.0F, 0.0F)),
                "the bottom face of the box is on the piece");
        assertTrue(YsmPhysicsParts.pivotOnGeometry(block, new Vector3f(0.1F, 0.2F, 0.1F)),
                "so is a corner");
        assertTrue(YsmPhysicsParts.pivotOnGeometry(block, new Vector3f(0.0F, 0.2F, 0.0F)));
        assertFalse(YsmPhysicsParts.pivotOnGeometry(block, new Vector3f(0.0F, -0.01F, 0.0F)),
                "a centimetre below the piece is off it");
        assertEquals(0.0, YsmPhysicsParts.pivotGapFromGeometry(block, new Vector3f(0.0F, 0.2F, 0.0F)),
                1.0E-6);
        assertEquals(0.01, YsmPhysicsParts.pivotGapFromGeometry(block, new Vector3f(0.0F, -0.01F, 0.0F)),
                1.0E-5);
    }

    /** Nothing measurable is ever dropped: the failure mode of a wrong drop is silent. */
    @Test
    void aPieceWithNoUsableGeometryIsKept() {
        Vector3f rest = new Vector3f(0.0F, 1.0F, 0.0F);
        assertFalse(YsmPhysicsParts.risesOffPivot(null, new Vector3f(), rest, 1.0F));
        assertFalse(YsmPhysicsParts.risesOffPivot(List.of(), new Vector3f(), rest, 1.0F));
        assertFalse(YsmPhysicsParts.risesOffPivot(List.of(new Vector3f(), new Vector3f()),
                new Vector3f(), rest, 1.0F), "below the minimum vertex count");
        assertFalse(YsmPhysicsParts.pivotOnGeometry(List.of(), new Vector3f()));
        assertFalse(YsmPhysicsParts.risesOffPivot(box(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F),
                new Vector3f(Float.NaN, 0.0F, 0.0F), rest, 1.0F));
        assertTrue(Double.isNaN(YsmPhysicsParts.pivotGapFromGeometry(List.of(), new Vector3f())));
    }

    // ------------------------------------------------------------------
    // the pure helpers the leg diagnostic is built from
    // ------------------------------------------------------------------

    /** The angle from vertical is a line's angle: 0 along the model's y, 90 across it. */
    @Test
    void theAngleFromVerticalIsMeasuredAgainstTheModelsUp() {
        assertEquals(0.0, YsmPhysicsParts.angleFromVertical(new Vector3f(0.0F, 1.0F, 0.0F)), 1.0E-6);
        assertEquals(0.0, YsmPhysicsParts.angleFromVertical(new Vector3f(0.0F, -3.0F, 0.0F)), 1.0E-6);
        assertEquals(90.0, YsmPhysicsParts.angleFromVertical(new Vector3f(1.0F, 0.0F, 0.0F)), 1.0E-6);
        assertEquals(45.0, YsmPhysicsParts.angleFromVertical(new Vector3f(1.0F, 1.0F, 0.0F)), 1.0E-4);
        assertTrue(Double.isNaN(YsmPhysicsParts.angleFromVertical(new Vector3f(0.0F, 0.0F, 0.0F))));
        assertTrue(Double.isNaN(YsmPhysicsParts.angleFromVertical(null)));
        assertTrue(Double.isNaN(YsmPhysicsParts.angleFromVertical(new Vector3f(Float.NaN, 1.0F, 0.0F))));
    }

    /** The long axis is the shape's own: a strand's is its length, a cube has none. */
    @Test
    void theLongAxisIsTheDirectionThePieceIsDrawnAlong() {
        List<Vector3f> upright = bar(0.0F, 0.0F, 0.0F, 0.0F, 0.5F, 0.0F, 0.02F);
        Vector3f axis = YsmPhysicsParts.longAxisOf(upright);
        assertNotNull(axis, "a strand has a long axis");
        assertEquals(0.0, YsmPhysicsParts.angleFromVertical(axis), 0.05,
                "a bar drawn up the model's y reads 0 degrees from vertical");
        assertTrue(Math.abs(axis.y) > 0.99F, "the axis must be the bar's own direction: " + axis);

        List<Vector3f> sideways = bar(0.0F, 0.0F, 0.0F, 0.5F, 0.0F, 0.0F, 0.02F);
        Vector3f flat = YsmPhysicsParts.longAxisOf(sideways);
        assertNotNull(flat, "a bar lying across the model has a long axis too");
        assertEquals(90.0, YsmPhysicsParts.angleFromVertical(flat), 1.0E-3,
                "and it reads 90 degrees from vertical - the number the leg diagnostic reports");

        assertNull(YsmPhysicsParts.longAxisOf(cube(0.1F)), "a cube has no long axis to report");
        assertNull(YsmPhysicsParts.longAxisOf(null));
        assertNull(YsmPhysicsParts.longAxisOf(List.of()), "nothing has no axis");
        assertNull(YsmPhysicsParts.longAxisOf(List.of(new Vector3f(), new Vector3f())),
                "two vertices are below the minimum the helpers work with");
    }

    /** The rotation angle a matrix applies, from the quaternion form the diagnostic reads it with. */
    @Test
    void theRotationAngleOfAQuaternionIsTheTurnItApplies() {
        assertEquals(0.0, YsmMeshSecondaryMotion.degreesOf(new Quaternionf()), 1.0E-4);
        Quaternionf quarter = new Quaternionf().rotateY((float) (Math.PI * 0.5));
        assertEquals(90.0, YsmMeshSecondaryMotion.degreesOf(quarter), 1.0E-3);
        Quaternionf half = new Quaternionf().rotateZ((float) Math.PI);
        assertEquals(180.0, YsmMeshSecondaryMotion.degreesOf(half), 1.0E-3);
        Quaternionf negated = new Quaternionf(-quarter.x, -quarter.y, -quarter.z, -quarter.w);
        assertEquals(90.0, YsmMeshSecondaryMotion.degreesOf(negated), 1.0E-3,
                "a quaternion and its negation are the same rotation");
        assertTrue(Double.isNaN(YsmMeshSecondaryMotion.degreesOf((Quaternionf) null)));
        assertTrue(Double.isNaN(YsmMeshSecondaryMotion.degreesOf(
                new Quaternionf(Float.NaN, 1.0F, 0.0F, 0.0F))));
        assertTrue(Double.isNaN(YsmMeshSecondaryMotion.degreesOf(new Quaternionf(0.0F, 0.0F, 0.0F, 0.0F))),
                "a quaternion with no length is not the identity; it is unreadable");
        assertNotNull(new Quaternionf());
    }

    // ------------------------------------------------------------------
    // the geometry the two calibration pieces are built from
    // ------------------------------------------------------------------

    /** A box of vertices between two corners, four per face. */
    private static List<Vector3f> box(float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        List<Vector3f> out = new ArrayList<>();
        for (float x : new float[]{minX, maxX}) {
            for (float y : new float[]{minY, maxY}) {
                for (float z : new float[]{minZ, maxZ}) {
                    out.add(new Vector3f(x, y, z));
                }
            }
        }
        return out;
    }

    /** A cube of the given size: every extent equal, so no principal axis. */
    private static List<Vector3f> cube(float size) {
        return box(0.0F, 0.0F, 0.0F, size, size, size);
    }

    /** A bar from one point to another, `radius` across, eight vertices at each end. */
    private static List<Vector3f> bar(float x0, float y0, float z0, float x1, float y1, float z1,
                                      float radius) {
        List<Vector3f> out = new ArrayList<>();
        for (int end = 0; end < 2; end++) {
            float cx = end == 0 ? x0 : x1;
            float cy = end == 0 ? y0 : y1;
            float cz = end == 0 ? z0 : z1;
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0;
                out.add(new Vector3f(cx + (float) Math.cos(angle) * radius,
                        cy + (float) Math.sin(angle) * radius * 0.1F,
                        cz + (float) Math.sin(angle) * radius));
            }
        }
        return out;
    }

    private static Vector3f centroid(List<Vector3f> vertices) {
        Vector3f acc = new Vector3f();
        for (Vector3f v : vertices) {
            acc.add(v);
        }
        return acc.div(vertices.size());
    }
}
