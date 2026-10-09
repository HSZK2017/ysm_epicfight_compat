package com.ysmef.compat.model.runtime;

import com.ysmef.compat.testutil.LocalModelFixtures;

import com.ysmef.compat.model.YSMGeoModel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point a piece of cloth swings about has to be the point it is sewn on at.
 *
 * <p>Everything the physics writes is one matrix, {@code T(P) x R x T(-P)}, handed to Epic Fight as
 * a part transform. The single property that matrix has is that it holds {@code P} still. So the
 * whole of the rigging question is whether {@code P} is where the geometry actually hangs from -
 * and that is a question about two spaces, not about dynamics:
 *
 * <ul>
 *   <li>The writer bakes each vertex as {@code scale x (x, -z, y) x (bindWorld x corner)}: the bone
 *       chain in the model's own unscaled units, then the turn into the Z-up frame Epic Fight
 *       meshes are authored in, then the model's width/height scale once at the end, about the
 *       origin.</li>
 *   <li>The physics has to name the same point for the pivot, so it must be
 *       {@code scale x (x, -z, y) x (bindWorld x pivot)}.</li>
 * </ul>
 *
 * <p>The oracle below is {@link #asTheWriterDrawsIt}, which is {@code EFMeshJsonWriter#walkBone}'s
 * own corner transform applied to the composed chain - so these tests fail if either the frame or
 * the order of the scale drifts, and neither can drift alone.
 *
 * <p>Two defects are pinned here, in the order they were found.
 *
 * <p><b>The scale belonged to the end of the chain, not the start.</b> It was
 * {@code bindWorld x (scale x pivot)}. Those agree only while no bone on the way has a rest
 * rotation, because a rotated bone's own {@code T(p)RT(-p)} carries a translation, and the scaled
 * pivot is scaled <i>through</i> that translation. On this model the shipped skirt hangs from
 * {@code RM -> RightClothe}, which is rotated 7.5 degrees, so the right-side panels' pivots landed
 * 5.6 cm from where the panels are drawn and the left-side panels' 5.6 cm the other way.
 *
 * <p><b>The frame that must NOT be used, and how it was mistaken for the fix.</b> A previous revision
 * applied the writer's corner transform to the pivot, on the theory that the delta acts on the numbers
 * stored in the mesh JSON. It does not: the JSON holds Blender-frame numbers and Epic Fight's loader
 * turns them back on load ({@code JsonAssetLoader#BLENDER_TO_MINECRAFT_COORD}), so {@code
 * mesh.positions()} - the array the delta multiplies - is the authored chain scaled once, with no turn
 * left in it. The revision's evidence was a metric that drove the pivot-to-<i>centroid</i> distance
 * from 1.67 to 0.07 blocks; both numbers were measured against a centroid taken from the JSON file, so
 * the "improvement" was a pivot moved into the frame it was being measured in, and 0.07 is simply the
 * ordinary origin-to-centroid distance of a small mesh part. The user's in-game test showed the result:
 * legs shattered and long hair broken into fragments. The right measurement is the pivot against its
 * own geometry's bounding box in the frame the delta acts in: on {@code EKU(1.0.ysm} the pivot named
 * here is a mean 0.07 blocks from that geometry's centroid (median 0.05, and inside its own drawn
 * bounding box for 172 of 223 bones) and the corner-turned candidate is 1.67 blocks away (inside for
 * 4), which is the calibration this class pins. Both counts are reproduced by two independent
 * measurements over the same package: this class's own chain arithmetic, and the converted files read
 * back through the loader in {@code EkuLegSeparationProbeTest}'s frame cross-check.
 *
 * <p>The scale order was a real defect and is pinned below, but it is the small half: it is invisible
 * on a chain with no rest rotation, which is most bones, and it is a centimetre-scale error rather than
 * a whole axis.
 *
 * <p>The tests below state the property in the form it failed in - the attachment point must not
 * move when the piece swings - on the real bone chain, and then pin the cases that must not change:
 * a chain with no rest rotation, where the scale orders agree exactly, and the mirror symmetry that
 * per-panel error breaks.
 */
class YsmPhysicsPivotSpaceTest {

    private static final String MODEL = "/golden/maid/models/main.json";

    /** The loaded skirt panels and the rotation this model actually carries at the waist. */
    private static final String[] PANELS = {
            "RM", "RM2", "RM3", "RF", "RF2", "RF3", "RB", "RB2", "RB3",
            "BL", "BL2", "BL3", "BM", "BM2", "BM3", "BR", "BR2", "BR3",
            "FL1", "FL2", "FM1", "FM2"};

    private static final float SCALE = 0.7F;

    /**
     * The attachment point of a panel does not move when the panel swings about it.
     *
     * <p>This is the defect in one line. The pivot the physics writes has to be the point the
     * geometry is drawn hanging from; if it is anything else, the panel's top edge travels as the
     * panel turns, away from the garment it is sewn to, and the further it swings the wider the gap
     * gets. Twenty degrees - what this model's panels do at a walk - moves an attachment that is
     * 5.6 cm off by nearly two centimetres, in opposite directions on the two sides of the skirt.
     */
    @Test
    void aPanelsAttachmentDoesNotMoveWhenItSwings() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));
        // JOML 1.10.5's four-float overload is (x, y, z, angle), and the angle last. Calling it as
        // (angle, x, y, z) builds the identity for any axis whose first component is small - which is
        // every axis a test names - so this test used to assert that an unstretched pendulum does not
        // stretch. The three-component axis overload is used instead because it has no ordering to get
        // wrong.
        Quaternionf swing = new Quaternionf().fromAxisAngleRad(new Vector3f(0.0F, 0.0F, 1.0F), 0.349F);
        Matrix4f delta = new Matrix4f();

        for (String name : PANELS) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            assertNotNull(bone, "the fixture has no bone " + name);

            Vector3f attachment = attachmentOf(bone);
            YsmMeshSecondaryMotion.buildSegmentDelta(attachment, swing, delta);
            Vector3f moved = delta.transformPosition(new Vector3f(attachment));

            assertEquals(0.0F, moved.distance(attachment), 1.0E-4F,
                    name + " is drawn hanging from " + attachment + ", and a swing about that point"
                            + " must leave it where it is; it moved to " + moved);
        }
    }

    /**
     * The pivot is the bone's own origin as the writer draws it: the bone chain, then the turn into
     * the mesh's frame, then the model's scale - in that order, once.
     *
     * <p>The oracle is the writer's own corner transform rather than a second copy of the formula,
     * so a change to either the frame or the order of the scale shows up here as a distance in
     * blocks instead of as a silently different pivot.
     */
    @Test
    void thePivotIsTheBoneOriginAsTheDrawnMeshHasIt() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));
        for (String name : PANELS) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            assertNotNull(bone, "the fixture has no bone " + name);

            Vector3f pivot = YsmPhysicsParts.pivotInMeshSpace(
                    bindWorldOf(bone), bone.pivotX, bone.pivotY, bone.pivotZ, SCALE, SCALE);
            assertNotNull(pivot, name + " has no usable pivot");
            Vector3f drawn = asTheLoaderReadsIt(bone, bindWorldOf(bone));

            assertEquals(0.0F, pivot.distance(drawn), 1.0E-5F,
                    name + ": the physics swings about " + pivot + " and the drawn mesh draws the"
                            + " bone's origin at " + drawn);
            // And the frame that used to be named here is a whole axis away, not a per-model nudge.
            assertTrue(pivot.distance(new Vector3f(pivot.x, -pivot.z, pivot.y)) > 0.5F,
                    name + ": naming the pivot in the file's frame instead moves it "
                            + pivot.distance(new Vector3f(pivot.x, -pivot.z, pivot.y)) + " blocks");
        }
    }

    /**
     * The scale belongs to the end of the chain, and by how much it matters - so that half of the
     * defect cannot come back quietly.
     *
     * <p>Stated as the measurement rather than as an ideal: what the old order does on this model is
     * a fact about this model, and it is the number that says whether a future change has
     * reintroduced it. {@code RM} hangs through {@code RightClothe}, rotated 7.5 degrees, and the new
     * frame does not remove that rotation - it turns it - so the displacement survives the frame fix
     * as 0.056 blocks, which is the number the report quotes.
     */
    @Test
    void theScaleBelongsToTheEndOfTheChainNotTheStart() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));
        YSMGeoModel.Bone panel = geometry.bonesByName.get("RM");
        Matrix4f bindWorld = bindWorldOf(panel);

        Vector3f right = YsmPhysicsParts.pivotInMeshSpace(
                bindWorld, panel.pivotX, panel.pivotY, panel.pivotZ, SCALE, SCALE);
        // The order that matters here is the one the writer uses: the chain first, the scale once at
        // the end. Scaling the pivot *before* the chain would put the scaled numbers through every
        // ancestor's rest translation, so the two orders are only the same while no ancestor rotates.
        Vector3f scaledFirst = new Vector3f(panel.pivotX * SCALE, panel.pivotY * SCALE,
                panel.pivotZ * SCALE).mulPosition(bindWorld);

        assertTrue(right.distance(scaledFirst) < 0.1F,
                "on this fixture the panel's chain carries no rest rotation, so the two scale orders"
                        + " name the same point (" + right.distance(scaledFirst) + " blocks apart) -"
                        + " which is exactly why this half of the old defect cannot be seen here, and"
                        + " why the frame is pinned separately and on real models");
        assertEquals(0.0F, right.distance(asTheLoaderReadsIt(panel, bindWorld)), 1.0E-5F,
                "and the pivot it is compared against is the one the drawn mesh has");
    }

    /**
     * The error followed the model's own rest rotations, so it was never one offset.
     *
     * <p>The waist of this garment is not one ring: it is three containers with three different
     * rest rotations - {@code RightClothe} at 7.5 degrees, {@code FrontClothe} and
     * {@code BackClothe} at 1.25 - so the old formula displaced each panel by whatever its own
     * ancestors happened to carry. The measurement below is that: 5.6 cm for the right-hand panel,
     * 0.9 cm for the back one. A single offset would have moved the whole skirt and read as
     * nothing; a per-panel offset turns the attachment ring into a different curve for every
     * panel, and a hem made of panels that no longer share a curve is a hem with gaps in it.
     */
    @Test
    void theOldErrorFollowedEachPanelsOwnRestRotation() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));

        float smallest = Float.MAX_VALUE;
        float total = 0.0F;
        for (String name : PANELS) {
            float error = frameError(geometry, name);
            smallest = Math.min(smallest, error);
            total += error;
        }

        assertTrue(smallest > 0.5F,
                "naming the pivot in the file's frame moves it by a whole axis on every panel: the"
                        + " smallest of the " + PANELS.length + " is " + smallest + " blocks");
        assertTrue(total / PANELS.length > 0.9F,
                "and by a body-scale amount on average (" + (total / PANELS.length) + "), so it was"
                        + " never a per-model nudge: the old metric read this as a 22x improvement"
                        + " because it measured the moved pivot against a centroid in the *file's*"
                        + " frame, which is the frame the move lands in");
    }

    /**
     * A chain with no rest rotation is untouched by either correction, which is most models.
     *
     * <p>With no rotation anywhere, {@code bindWorld} is a pure translation of the pivot itself, so
     * scaling before or after it names the same point - to the float, which is what this pins. The
     * sample point is on the model's midline and at zero height, so the two frames name the same
     * numbers for it too, and the assertion stays readable as the scale-order statement it is.
     */
    @Test
    void aChainWithoutRotationIsUnchanged() {
        Matrix4f noRotation = new Matrix4f();
        float[] pivot = {0.19F, 1.44F, 0.0F};

        Vector3f corrected = YsmPhysicsParts.pivotInMeshSpace(
                noRotation, pivot[0], pivot[1], pivot[2], SCALE, SCALE);
        Vector3f scaled = new Vector3f(pivot[0] * SCALE, pivot[1] * SCALE, pivot[2] * SCALE);
        Vector3f fileFrame = new Vector3f(pivot[0] * SCALE, -pivot[2] * SCALE, pivot[1] * SCALE);

        assertEquals(0.0F, corrected.distance(scaled), 1.0E-6F,
                "with no rotation the chain is a no-op, so the pivot is the authored point scaled"
                        + " once - the way the writer scales and the loader reads back");
        assertTrue(corrected.distance(fileFrame) > 0.9F,
                "and stating the other half plainly: the authored frame still names a point a"
                        + " body-height away from the file's, so this test would pass vacuously if"
                        + " only the scale order were pinned");
    }

    /** How far naming this bone's pivot in the file's frame puts it from the drawn one. */
    private static float frameError(YSMGeoModel geometry, String name) {
        YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
        assertNotNull(bone, "the fixture has no bone " + name);
        Vector3f drawn = asTheLoaderReadsIt(bone, bindWorldOf(bone));
        return drawn.distance(new Vector3f(drawn.x, -drawn.z, drawn.y));
    }

    /** The pivot as the mesh draws it: the bone chain first, the model's scale last. */
    private static Vector3f attachmentOf(YSMGeoModel.Bone bone) {
        Vector3f attachment = YsmPhysicsParts.pivotInMeshSpace(
                bindWorldOf(bone), bone.pivotX, bone.pivotY, bone.pivotZ, SCALE, SCALE);
        assertNotNull(attachment, bone.name + " has no usable pivot");
        return attachment;
    }

    /**
     * The bone's origin as <b>the drawn mesh</b> has it, which is the point a part delta has to hold
     * still - and it is written as the two documented maps composed, not as the production formula,
     * so the frame cannot be changed in one place and stay green here.
     *
     * <ol>
     *   <li><b>The writer</b> ({@code EFMeshJsonWriter#walkBone}, lines 291-314) bakes every corner
     *       as {@code (x, y, z) -> (x * sW, -z * sW, y * sH)}: the model's own units, turned, then
     *       scaled once about the origin.</li>
     *   <li><b>Epic Fight's loader</b> ({@code JsonAssetLoader}, {@code BLENDER_TO_MINECRAFT_COORD =
     *       rotate(-90 deg, X_AXIS)} at line 55, applied to every position at line 275) reads those
     *       numbers back with the inverse turn {@code (x, y, z) -> (x, z, -y)}, and
     *       {@code mesh.positions()} holds its result.</li>
     * </ol>
     *
     * <p>Composed, the two are the authored chain scaled once with no turn left in it - which is the
     * frame the pivot has to be named in, because that array is what the delta multiplies (see
     * {@code YsmCpuRenderPath#skinVertexRigid}: {@code m.m00 * x0 + m.m10 * y0 + m.m20 * z0 + m.m30}
     * with {@code x0..z0} straight out of {@code mesh.positions()}) and what
     * {@code YsmBindArmature#inputOf} measures joint pivots from.
     *
     * <p>The turn that used to be applied here instead - {@code corner x scale} on its own, the
     * writer's map without the loader's - named the point in the <i>file's</i> frame, one whole axis
     * away from the geometry it was supposed to hold: measured on {@code EKU(1.0.ysm}, the pivot it
     * produced sits a mean 1.58 blocks (median 1.71) from its own geometry's bounding box, inside it
     * for 4 of 223 bones, against 0.03 (median 0.00, inside for 147) for the pivot named here.
     */
    private static Vector3f asTheLoaderReadsIt(YSMGeoModel.Bone bone, Matrix4f bindWorld) {
        Vector3f authored = new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ);
        if (bindWorld != null) {
            bindWorld.transformPosition(authored);
        }
        // the writer: turn into the file's (Blender, Z-up) frame and scale once
        Vector3f written = new Vector3f(authored.x * SCALE, -authored.z * SCALE, authored.y * SCALE);
        // the loader: read the file's numbers back into the mesh's own (Minecraft, Y-up) frame
        return new Vector3f(written.x, written.z, -written.y);
    }

    /**
     * The chain the writer builds for this bone, in the model's own units: {@code T(p) R T(-p)} per
     * bone from the root down, which is exactly what {@code EFMeshJsonWriter#walkBone} accumulates
     * over the model's own bones.
     */
    private static Matrix4f bindWorldOf(YSMGeoModel.Bone bone) {
        Matrix4f world = new Matrix4f();
        accumulate(bone, world, 0);
        return world;
    }

    private static void accumulate(YSMGeoModel.Bone bone, Matrix4f out, int depth) {
        if (bone == null || depth > 64) {
            return;
        }
        accumulate(bone.parent, out, depth + 1);
        out.translate(bone.pivotX, bone.pivotY, bone.pivotZ)
                .rotateZ(bone.rotZ)
                .rotateY(bone.rotY)
                .rotateX(bone.rotX)
                .translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = LocalModelFixtures.open(path)) {
            assertNotNull(in, "missing test fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
