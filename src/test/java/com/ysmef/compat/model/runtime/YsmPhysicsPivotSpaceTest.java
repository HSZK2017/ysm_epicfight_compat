package com.ysmef.compat.model.runtime;

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
 *   <li>The writer bakes each vertex as {@code scale x (bindWorld x corner)} - the bone chain in
 *       the model's own unscaled units, then the model's width/height scale applied once at the
 *       end, about the origin.</li>
 *   <li>The physics has to name the same point for the pivot, so it must be
 *       {@code scale x (bindWorld x pivot)}.</li>
 * </ul>
 *
 * <p>It was {@code bindWorld x (scale x pivot)}. Those agree only while no bone on the way has a
 * rest rotation, because a rotated bone's own {@code T(p)RT(-p)} carries a translation, and the
 * scaled pivot is scaled <i>through</i> that translation. On this model the shipped skirt hangs
 * from {@code RM -> RightClothe}, which is rotated 7.5 degrees: every right-side panel's pivot
 * landed 5.6 cm from where the panel is drawn, and every left-side panel's 5.6 cm the other way.
 *
 * <p>The tests below state the property in the form it failed in - the attachment point must not
 * move when the piece swings - on the real bone chain, and then pin the two cases that must not
 * change: a chain with no rotation, where both formulas agree, and the mirror symmetry, which the
 * old formula broke.
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
        Quaternionf swing = new Quaternionf().fromAxisAngleRad(0.0F, 0.0F, 1.0F, 0.349F);
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
     * The wrong order is wrong, and by how much - so a regression cannot come back quietly.
     *
     * <p>Stated as the measurement rather than as an ideal: what the old formula did on this model
     * is a fact about this model, and it is the number that says whether a future change has
     * reintroduced it.
     */
    @Test
    void theScaleBelongsToTheEndOfTheChainNotTheStart() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource(MODEL));
        YSMGeoModel.Bone panel = geometry.bonesByName.get("RM");
        Matrix4f bindWorld = bindWorldOf(panel);

        Vector3f right = YsmPhysicsParts.pivotInMeshSpace(
                bindWorld, panel.pivotX, panel.pivotY, panel.pivotZ, SCALE, SCALE);
        Vector3f wrong = new Vector3f(panel.pivotX * SCALE, panel.pivotY * SCALE, panel.pivotZ * SCALE)
                .mulPosition(bindWorld);

        assertEquals(0.0557F, right.distance(wrong), 5.0E-3F,
                "scaling the pivot before the bone chain moves it by five and a half centimetres on"
                        + " this model's right-hand skirt");
        assertTrue(right.y > 0.9F && right.y < 1.1F,
                "and the corrected pivot is at the waist where the panel is drawn, not "
                        + right.y + " blocks up");
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

        float rightPanel = pivotError(geometry, "RM");
        float backPanel = pivotError(geometry, "BM");

        assertEquals(0.056F, rightPanel, 4.0E-3F,
                "the right-hand panel hangs through a container rotated 7.5 degrees");
        assertEquals(0.009F, backPanel, 4.0E-3F,
                "the back panel hangs through one rotated 1.25, so it was displaced six times less");
        assertTrue(rightPanel > backPanel * 4.0F,
                "the displacement was per-panel, not per-garment - which is what makes it a tear"
                        + " rather than a shift");
    }

    /** A chain with no rest rotation is untouched by the correction, which is most models. */
    @Test
    void aChainWithoutRotationIsUnchanged() {
        Matrix4f noRotation = new Matrix4f();
        float[] pivot = {0.19F, 1.44F, 0.0F};

        Vector3f corrected = YsmPhysicsParts.pivotInMeshSpace(
                noRotation, pivot[0], pivot[1], pivot[2], SCALE, SCALE);
        Vector3f scaled = new Vector3f(pivot[0] * SCALE, pivot[1] * SCALE, pivot[2] * SCALE);

        assertEquals(0.0F, corrected.distance(scaled), 1.0E-6F,
                "with no rotation anywhere the two orders name the same point, so a model that"
                        + " bakes its rotations into the cubes cannot be changed by this");
    }

    /** How far the old order put this bone's pivot from where the geometry hangs. */
    private static float pivotError(YSMGeoModel geometry, String name) {
        YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
        assertNotNull(bone, "the fixture has no bone " + name);
        Matrix4f bindWorld = bindWorldOf(bone);
        Vector3f right = YsmPhysicsParts.pivotInMeshSpace(
                bindWorld, bone.pivotX, bone.pivotY, bone.pivotZ, SCALE, SCALE);
        Vector3f wrong = new Vector3f(bone.pivotX * SCALE, bone.pivotY * SCALE, bone.pivotZ * SCALE)
                .mulPosition(bindWorld);
        return right.distance(wrong);
    }

    /** The pivot as the mesh draws it: the bone chain first, the model's scale last. */
    private static Vector3f attachmentOf(YSMGeoModel.Bone bone) {
        Vector3f attachment = YsmPhysicsParts.pivotInMeshSpace(
                bindWorldOf(bone), bone.pivotX, bone.pivotY, bone.pivotZ, SCALE, SCALE);
        assertNotNull(attachment, bone.name + " has no usable pivot");
        return attachment;
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
        try (InputStream in = YsmPhysicsPivotSpaceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
