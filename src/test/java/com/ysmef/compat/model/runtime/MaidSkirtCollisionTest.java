package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
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
 * Guards the geometry side of the collision set against the model and the volumes that were
 * actually shipped.
 *
 * <p>The volumes below are copied verbatim from the live log's {@code collision volumes of
 * 'wine_fox/01_taisho_maid'} line, in posed model space and blocks, and the panels' pivots,
 * levers and rest directions are computed from the model's own {@code models/main.json}. So this
 * is not a synthetic case: it is the state that produced the report.
 *
 * <h2>What went wrong, as the numbers had it</h2>
 *
 * <p>Two of the ten volumes were in the wrong place for a garment, and the log said which:
 *
 * <ul>
 *   <li>The right forearm's volume (joint 12) sat <b>inside</b> the right-back panel's resting
 *       position - a sphere centred on a forearm, at hip height, inside a skirt. A volume inside
 *       cloth does not collide with it, it fires it away.</li>
 *   <li>The thigh volumes reached the front and right panels' resting places. A volume that merely
 *       <i>reaches</i> a panel is worse than one that misses it: the panel is clamped to a swing
 *       limit that stops it short of the surface, so it is pushed one frame and clamped the next,
 *       and sits pinned at its limit for as long as the pose holds. The shipped run showed exactly
 *       that - {@code FM1(root, 20.0deg/20.0chain, hit=0.282)}.</li>
 * </ul>
 *
 * <p>Hence two rules, and these tests are the two rules: arms are not part of what a garment
 * collides with, and no volume may sit inside a panel's working space.
 */
class MaidSkirtCollisionTest {

    /** joint, x, y, z, radius - straight from the shipped log. */
    private static final float[][] LOGGED_VOLUMES = {
            {1, 0.13F, 0.57F, 0.0F, 0.14F},
            {4, -0.08F, 0.57F, -0.12F, 0.14F},
            {2, 0.17F, 0.11F, -0.03F, 0.10F},
            {5, -0.09F, 0.12F, -0.21F, 0.10F},
            {8, 0.0F, 1.11F, -0.01F, 0.19F},
            {9, -0.02F, 1.45F, 0.0F, 0.15F},
            {11, 0.16F, 1.23F, 0.09F, 0.14F},
            {16, -0.18F, 1.22F, -0.12F, 0.14F},
            {12, 0.22F, 0.85F, 0.12F, 0.10F},
            {17, -0.23F, 0.83F, -0.15F, 0.10F}};

    /** The joints a garment is allowed to rest against; the arms are not among them. */
    private static final int[] KEPT_JOINTS = {0, 1, 4, 2, 5, 3, 6, 7, 8, 9};

    private static final String[] PANELS = {
            "RM", "RM2", "RM3", "RF", "RF2", "RF3", "RB", "RB2", "RB3",
            "BL", "BL2", "BL3", "BM", "BM2", "BM3", "BR", "BR2", "BR3",
            "FL1", "FL2", "FM1", "FM2"};

    /** The segment radius the solver uses for a panel: the geometry's spread, clamped. */
    private static final float SEGMENT_RADIUS = 0.033F;
    private static final float MAX_ANGLE_ROOT = 0.349F;

    /**
     * The rule, exercised on the real model and the real volumes: every kept volume that reaches a
     * panel's working space is skipped for it.
     *
     * <p>Written as a measurement rather than as an ideal. The measured fact is that the thigh
     * volumes sit inside this skirt - ten of the twenty-two panels have one inside their own
     * resting place - so the honest assertion is not "no volume reaches a panel", which is false
     * and cannot be made true by tuning a threshold, but "a volume that does reach one does not get
     * to push it". That distinction is the whole lesson of this defect: a sphere inside the cloth
     * cannot police the cloth, and trying to makes it fly.
     */
    @Test
    void everyKeptVolumeInsideAPanelsWorkspaceIsSkippedForIt() throws IOException {
        List<Panel> panels = panels();
        int skipped = 0;
        int conflictingAndMisapplied = 0;
        for (Panel panel : panels) {
            float swingReach = panel.lever() * (float) Math.sin(MAX_ANGLE_ROOT);
            for (float[] volume : LOGGED_VOLUMES) {
                if (!isKept((int) volume[0])) {
                    continue;
                }
                float distance = panel.restCentre().distance(volume[1], volume[2], volume[3]);
                boolean skippedForIt = YsmBodyColliders.volumeIsInsideWorkspace(
                        panel.pivot(), panel.restCentre(), swingReach,
                        volume[1], volume[2], volume[3], volume[4]);
                if (skippedForIt) {
                    skipped++;
                }
                if (distance < volume[4] + SEGMENT_RADIUS && !skippedForIt) {
                    conflictingAndMisapplied++;
                }
            }
        }

        assertTrue(skipped > 0,
                "this model's skirt needs the rule: its thigh volumes sit inside its panels");
        assertEquals(0, conflictingAndMisapplied,
                "a volume overlapping a panel's resting place must never be applied to it; "
                        + conflictingAndMisapplied + " pairs still are");
    }

    /**
     * And collision still works where it should: a volume the piece is clearly clear of is not
     * skipped, or the rule would have quietly turned collision off for the whole model.
     */
    @Test
    void aVolumeThePanelIsClearOfIsStillApplied() throws IOException {
        List<Panel> panels = panels();
        // The head, well above every panel of this skirt.
        float headX = -0.02F;
        float headY = 1.45F;
        float headZ = 0.0F;
        float headRadius = 0.15F;

        for (Panel panel : panels) {
            float swingReach = panel.lever() * (float) Math.sin(MAX_ANGLE_ROOT);
            assertTrue(!YsmBodyColliders.volumeIsInsideWorkspace(
                            panel.pivot(), panel.restCentre(), swingReach,
                            headX, headY, headZ, headRadius),
                    panel.name() + " is nowhere near the head; that volume must stay live");
        }
    }

    /** The arms are what a garment must not be collided with, and the log shows why. */
    @Test
    void theArmVolumesAreNotPartOfWhatAGarmentCollidesWith() {
        for (int joint : new int[]{10, 11, 14, 15, 16, 19, 12, 17}) {
            assertTrue(!isKept(joint),
                    "joint " + joint + " is an arm or hand volume; a crude sphere on an arm sweeps"
                            + " through a skirt and throws panels rather than supporting them");
        }
        for (int joint : new int[]{1, 4, 2, 5, 7, 8}) {
            assertTrue(isKept(joint), "joint " + joint + " is body core or leg and must be kept");
        }
    }

    private static boolean isKept(int joint) {
        for (int kept : KEPT_JOINTS) {
            if (kept == joint) {
                return true;
            }
        }
        return false;
    }

    private record Panel(String name, float lever, Vector3f restCentre, Vector3f pivot) {}

    /** Each panel's lever and rest centre of mass, in posed model space, blocks. */
    private static List<Panel> panels() throws IOException {
        YSMGeoModel geometry = YSMGeoModel.parse(resource("/golden/maid/models/main.json"));
        float scale = 0.7F;
        List<Panel> panels = new ArrayList<>();
        for (String name : PANELS) {
            YSMGeoModel.Bone bone = geometry.bonesByName.get(name);
            assertNotNull(bone, "missing " + name);
            Vector3f pivot = new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ).mul(scale);
            Vector3f centre = new Vector3f();
            int count = 0;
            for (YSMGeoModel.Quad quad : bone.quads) {
                for (Vector3f corner : quad.positions) {
                    if (corner != null) {
                        centre.add(corner.x * scale, corner.y * scale, corner.z * scale);
                        count++;
                    }
                }
            }
            if (count == 0) {
                continue;
            }
            centre.div(count);
            Vector3f rest = new Vector3f(centre).sub(pivot);
            float lever = rest.length();
            if (lever < 1.0E-5F) {
                continue;
            }
            panels.add(new Panel(name, lever, centre, pivot));
        }
        return panels;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = MaidSkirtCollisionTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
