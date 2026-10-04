package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.YSMGeoModel;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Test
    void aThighStillCollidesWhenTheSkirtsAttachmentIsInsideIt() throws IOException {
        YsmBodyColliders colliders = thighVolume();
        int index = thighIndex(colliders);
        float[] volume = colliders.resolvedVolume(index);
        Vector3f pivot = new Vector3f(volume[0], volume[1], volume[2]);
        Vector3f axis = new Vector3f(volume[4] - volume[0],
                volume[5] - volume[1], volume[6] - volume[2]).normalize();
        Vector3f radial = new Vector3f(1.0F, 0.0F, 0.0F);
        radial.fma(-radial.dot(axis), axis).normalize();
        Vector3f restCentre = new Vector3f(pivot).fma(volume[7] + 0.08F, radial);

        assertTrue(!colliders.skipFor(pivot, restCentre, 0.1F, index),
                "a cloth root inside the thigh must not disable collision for its hem outside it");
        assertTrue(colliders.skipFor(pivot, pivot, 0.1F, index),
                "a panel whose rest centre is already inside the thigh cannot be ejected");
    }

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
     * The rule, exercised on the real model and the real volumes: a volume is skipped for a panel
     * when, and only when, the panel's resting centre of mass is inside it.
     *
     * <h2>What this test used to assert, and why that was wrong</h2>
     *
     * <p>It used to assert that every volume whose centre came within {@code radius + swingReach} of
     * a panel's resting centre of mass was skipped for it, and that no volume overlapping a panel's
     * resting place was ever applied. Both halves encoded the wrong rule.
     *
     * <p>The margin's meaning was "this piece could touch that volume somewhere within its swing",
     * which is true of nearly every volume near nearly every panel - and a volume the piece could
     * touch is precisely the volume that has to be allowed to push it. Measured on this model, FM1's
     * resting centre of mass is 0.171 blocks from a thigh axis whose capsule radius is 0.223: outside
     * the volume, hanging clear of it, and yet the old margin skipped it. The thigh was told to
     * ignore three of these panels that it should have been holding off, and that is one of the ways
     * a leg is seen through a skirt.
     *
     * <p>The case the rule exists for is narrower and the second half of the old assertion named it
     * correctly: a volume that CONTAINS the resting place, because pushing a piece out of a volume it
     * starts inside is ejection, not collision. So the assertion is now containment - and the panels
     * are genuinely outside, which is a measurement rather than a hope
     * ({@code tmp_verify/T12_inside.txt}).
     */
    @Test
    void aVolumeIsSkippedForAPanelOnlyWhenItContainsItsRestingPlace() throws IOException {
        List<Panel> panels = panels();
        int skipped = 0;
        int containedButApplied = 0;
        int outsideButSkipped = 0;
        for (Panel panel : panels) {
            float swingReach = panel.lever() * (float) Math.sin(MAX_ANGLE_ROOT);
            for (float[] volume : LOGGED_VOLUMES) {
                if (!isKept((int) volume[0])) {
                    continue;
                }
                // The logged volumes are spheres, so their two ends are the same point.
                float distance = panel.restCentre().distance(volume[1], volume[2], volume[3]);
                boolean inside = distance < volume[4];
                boolean skippedForIt = YsmBodyColliders.volumeIsInsideWorkspace(
                        panel.pivot(), panel.restCentre(), swingReach,
                        volume[1], volume[2], volume[3],
                        volume[1], volume[2], volume[3], volume[4]);
                if (skippedForIt) {
                    skipped++;
                }
                if (inside && !skippedForIt) {
                    containedButApplied++;
                }
                if (!inside && skippedForIt
                        && panel.pivot().distance(volume[1], volume[2], volume[3]) >= volume[4]) {
                    outsideButSkipped++;
                }
            }
        }

        assertEquals(0, containedButApplied,
                "a volume that CONTAINS a panel's resting place must never be applied to it: pushing "
                        + "a piece out of a volume it starts inside is ejection, not collision; "
                        + containedButApplied + " such pairs are still applied");
        assertEquals(0, outsideButSkipped,
                "and a panel hanging clear of a volume must not have it skipped: a volume the piece "
                        + "could touch is the one that has to push it. " + outsideButSkipped
                        + " clear pairs are skipped, which is the defect that let a leg through a "
                        + "skirt");
        // The rule still has work to do on this model - the pieces that hang from the joints the
        // volumes sit on - or it would have been deleted rather than narrowed.
        assertTrue(skipped > 0,
                "the pivot-inside-volume half of the rule must still be firing somewhere on this "
                        + "model, or the rule is dead code");
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
                            headX, headY, headZ, headX, headY, headZ, headRadius),
                    panel.name() + " is nowhere near the head; that volume must stay live");
        }
    }

    // ------------------------------------------------------------------
    // T12: the leg steps through the skirt
    // ------------------------------------------------------------------

    /** The panel's own collision radius, from the model: {@code SEGMENT_RADIUS}. */
    private static final float PANEL_RADIUS = 0.033F;

    /**
     * <b>The test this task exists for.</b> A panel hanging against the upper thigh is pushed clear
     * of the volume that is meant to represent that thigh, and the sphere the old rule built does not
     * reach it at all.
     *
     * <p>Driven through the production collider and the production solver, from the real model:
     * {@link YsmBodyColliders#fromGeometry} receives the thigh's own vertices from the shipped mesh
     * fixture, so the shape rule under test is the one that ships rather than a copy of it. An earlier
     * version of this test built its own collider, and a mutant that turned every volume back into a
     * sphere left it green - which is exactly the false assurance a test like this must not give.
     *
     * <h2>Why the panel is where it is</h2>
     *
     * <p>Placed from the volume's own measured geometry rather than at hand-picked coordinates: the
     * panel hangs beside the thigh at the height of one of the limb's <i>ends</i>. That is the part of
     * a stepping leg that sweeps through a skirt, and it is precisely the part a sphere at the limb's
     * middle cannot cover. The numbers the two shapes produce are printed by the failure messages, so
     * the difference is visible rather than asserted and forgotten.
     */
    @Test
    void aThighVolumeReachesTheClothTheLegStepsThrough() throws IOException {
        YsmBodyColliders colliders = thighVolume();
        int index = thighIndex(colliders);

        float[] volume = colliders.resolvedVolume(index);
        Vector3f end0 = new Vector3f(volume[0], volume[1], volume[2]);
        Vector3f end1 = new Vector3f(volume[4], volume[5], volume[6]);
        float tubeRadius = volume[7];
        float span = end0.distance(end1);

        assertTrue(span > 0.10F,
                "the thigh's volume must be a CAPSULE along the limb, not a sphere at its middle: its "
                        + "two ends are " + span + " blocks apart (a sphere would be 0.00), tube radius "
                        + tubeRadius + ". This is the regression the whole task is about - the leg "
                        + "stepped through the skirt because the volume covering it was the size of "
                        + "its thin middle, not of the limb the mesh reaches along");

        // The panel's resting centre of mass is placed just INSIDE the volume's surface, measured
        // from the volume's own axis in its own frame - so the numbers mean something without any
        // assumption about which way the model faces. Just inside, because the solver's swing limit
        // is a real constraint: a panel far enough out to need a large turn could not be pushed out
        // however good the volume is, and a test asserting otherwise would be asking the limit to
        // break rather than asking the collision to work.
        float minimum = tubeRadius + PANEL_RADIUS;
        Vector3f axis = new Vector3f(end1).sub(end0);
        if (axis.lengthSquared() < 1.0E-9F) {
            axis.set(0.0F, 1.0F, 0.0F);
        }
        axis.normalize();
        // A perpendicular direction, so the panel sits beside the limb rather than along it.
        Vector3f perpendicular = new Vector3f(1.0F, 0.0F, 0.0F);
        if (Math.abs(perpendicular.dot(axis)) > 0.9F) {
            perpendicular.set(0.0F, 0.0F, 1.0F);
        }
        perpendicular.sub(new Vector3f(axis).mul(perpendicular.dot(axis))).normalize();

        Vector3f rest = new Vector3f(perpendicular).mul(-1.0F);
        // Inside the volume's own THICKNESS - not inside the surface distance the solver uses, which
        // also carries the panel's own radius. Those are different questions and the difference is
        // the whole point: a capsule contains the points within its tube radius of its axis, and a
        // panel resting against it has its centre of mass a further `panelRadius` out. This test needs
        // the first, because it is asking whether the collider sees the panel at all.
        //
        // Placed off the MIDDLE of the segment rather than off an end: a point perpendicular to an
        // endpoint is further from the segment than its offset, because the nearest point on the
        // segment is the endpoint itself, so an offset from an end lands outside instead.
        float startGap = tubeRadius - 0.01F;
        Vector3f anchor = new Vector3f(end0).add(end1).mul(0.5F);
        Vector3f besideEnd = new Vector3f(anchor).add(new Vector3f(perpendicular).mul(startGap));
        Vector3f pivot = new Vector3f(besideEnd).add(new Vector3f(0.0F, 0.14F, 0.0F));
        float lever = 0.14F;

        assertTrue(minimum > 0.0F && span > 0.0F, "the volume must have real dimensions");
        assertTrue(rest.lengthSquared() > 0.0F && pivot.distance(besideEnd) > 0.0F,
                "the panel must hang off its pivot with a real lever");
        float startDistance = YsmBodyColliders.distanceToSegment(besideEnd.x, besideEnd.y, besideEnd.z,
                end0.x, end0.y, end0.z, end1.x, end1.y, end1.z);
        assertTrue(startDistance < tubeRadius,
                "the panel must start INSIDE the volume's own thickness, or there is nothing to push: "
                        + startDistance + " against a tube radius of " + tubeRadius);

        // THE MECHANISM, at the level the report is about: the collider is handed the panel's centre
        // of mass and must put it outside its own surface.
        //
        // Asserted here rather than only through a driven swing, and that is a deliberate choice made
        // after trying the other way: a swing test can only ask "did the solver's own spring, limit
        // and collision agree to move the piece", and on a synthetic skeleton those three can settle
        // anywhere the geometry of the test happens to leave them - the first three versions of this
        // test measured the pivot's placement rather than the volume. The push-out is the production
        // entry the collision response calls, on the production geometry, with the real dimensions.
        Vector3f point = new Vector3f(besideEnd);
        boolean pushed = colliders.resolve(point, null, PANEL_RADIUS, index);
        float pushedGap = YsmBodyColliders.distanceToSegment(point.x, point.y, point.z,
                end0.x, end0.y, end0.z, end1.x, end1.y, end1.z);

        assertTrue(pushed,
                "the volume must report a contact for a panel inside it - silent here means the leg "
                        + "walks through the cloth, which is the report");
        assertTrue(pushedGap >= minimum - 1.0E-3F,
                "and it must put the panel's centre of mass on or outside its surface: gap " + pushedGap
                        + " against a surface at " + minimum);
        assertTrue(pushedGap <= minimum + 0.01F,
                "and not past it - a volume that shoved cloth off its own edge would be the 'flung "
                        + "away' failure instead. Gap was " + pushedGap);

        // Control: the OLD shape - a sphere of the old rule's radius at the limb's middle - never
        // sees this panel, which is exactly why the leg went through the cloth before this fix.
        //
        // The radius is the measured one, quoted rather than recomputed: it is what the 15th
        // percentile of this geometry's distances from its own centre came to, and it is in the
        // before/after table in tmp_verify/T12_volumes.txt. Quoting it is the honest thing here
        // because the rule that produced it is no longer in the code to call - and because a test
        // that re-derived it would be testing its own arithmetic instead of the shipped numbers.
        float oldRadius = OLD_SPHERE_RADIUS;
        float oldSurface = oldRadius + PANEL_RADIUS;
        Vector3f middle = new Vector3f(end0).add(end1).mul(0.5F);
        float reachFromMiddle = middle.distance(besideEnd);
        assertTrue(reachFromMiddle > oldSurface,
                "control: the old shape - a sphere of radius " + oldRadius + " centred at the limb's "
                        + "middle - reaches only " + oldSurface + " blocks from that middle, and this "
                        + "panel is " + reachFromMiddle + " blocks away, so it never asked the panel "
                        + "to move. That is the defect: the sphere covered the limb's thickness but "
                        + "not the part of the limb a skirt panel hangs beside");

        // And the driven path agrees that the piece is not left hanging where it was.
        Vector3f settled = settle(pivot, rest, lever, colliders);
        float gap = YsmBodyColliders.distanceToSegment(settled.x, settled.y, settled.z,
                end0.x, end0.y, end0.z, end1.x, end1.y, end1.z);
        assertTrue(gap <= startGap + 0.05F,
                "and the solver's own loop must not carry the piece deeper into the limb: it started "
                        + startGap + " from the axis and the run left it at " + gap);
    }

    /**
     * The radius the old sphere rule produced for the thigh of this model, blocks.
     *
     * <p>Measured from the same fixture by {@code tmp_verify/T12_volumes.txt}: the 15th percentile
     * of the thigh vertices' distances from their centroid, clamped to {@code [0.03, 0.20]}. The
     * capsule that replaced it has a tube radius of 0.180 over a 0.597-block span, so the two shapes
     * differ by the limb's length rather than by a few centimetres of radius.
     */
    private static final float OLD_SPHERE_RADIUS = 0.087F;

    /**
     * <b>The front panel is pushed by the thigh, and the push removes the velocity that drove it
     * in.</b>
     *
     * <p>Where the other test asks whether the volume <i>reaches</i> the cloth, this one asks whether
     * the cloth is actually moved - the two halves of the report. A front panel is the case that
     * matters: it is what a leg sweeps through on a step, and the measured table says its resting
     * centre of mass hangs clear of the thigh's capsule (0.148-0.310 blocks from the axis against a
     * 0.223 radius), so nothing in the skip rule should be keeping the thigh off it.
     *
     * <p>The panel's centre of mass is placed just inside the thigh's real capsule surface, and the
     * volume is the one production builds from this model's own leg vertices. Asserted: the contact
     * is reported, the centre of mass ends up on the surface, and the component of velocity pointing
     * into the limb is gone while the tangential part survives - a contact that killed the tangential
     * motion too would be the sticky stop this project has already removed once.
     */
    @Test
    void aFrontPanelIsPushedByTheThighAndLosesOnlyItsInwardVelocity() throws IOException {
        YsmBodyColliders colliders = thighVolume();
        int index = thighIndex(colliders);
        float[] volume = colliders.resolvedVolume(index);
        Vector3f end0 = new Vector3f(volume[0], volume[1], volume[2]);
        Vector3f end1 = new Vector3f(volume[4], volume[5], volume[6]);
        float tubeRadius = volume[7];
        float minimum = tubeRadius + PANEL_RADIUS;

        Vector3f axis = new Vector3f(end1).sub(end0);
        if (axis.lengthSquared() < 1.0E-9F) {
            axis.set(0.0F, 1.0F, 0.0F);
        }
        axis.normalize();
        Vector3f perpendicular = new Vector3f(1.0F, 0.0F, 0.0F);
        if (Math.abs(perpendicular.dot(axis)) > 0.9F) {
            perpendicular.set(0.0F, 0.0F, 1.0F);
        }
        perpendicular.sub(new Vector3f(axis).mul(perpendicular.dot(axis))).normalize();

        Vector3f anchor = new Vector3f(end0).add(end1).mul(0.5F);
        Vector3f point = new Vector3f(anchor)
                .add(new Vector3f(perpendicular).mul(tubeRadius - 0.01F));

        // A velocity with one component into the limb and one along it.
        Vector3f velocity = new Vector3f(perpendicular).mul(-2.0F).add(new Vector3f(axis).mul(3.0F));
        float inwardBefore = velocity.dot(perpendicular);

        boolean contacted = colliders.resolve(point, velocity, PANEL_RADIUS, index);
        float after = YsmBodyColliders.distanceToSegment(point.x, point.y, point.z,
                end0.x, end0.y, end0.z, end1.x, end1.y, end1.z);

        assertTrue(contacted,
                "the thigh must report a contact for a front panel inside its surface - a silent "
                        + "volume here is the leg walking through the cloth");
        assertTrue(after >= minimum - 1.0E-3F,
                "and it must put the panel's centre of mass on the surface: " + after
                        + " against a surface at " + minimum);
        assertTrue(after <= minimum + 0.01F,
                "and no further - a volume that threw cloth past its own edge is the 'flung away' "
                        + "failure instead. Gap was " + after);
        assertTrue(inwardBefore < 0.0F, "the test's own velocity must point into the limb");
        assertTrue(velocity.dot(perpendicular) >= -1.0E-4F,
                "the component of velocity driving the panel into the limb must be removed: it was "
                        + inwardBefore + " and is now " + velocity.dot(perpendicular));
        assertEquals(3.0F, velocity.dot(axis), 1.0E-3F,
                "while the tangential part survives - a contact that killed it too would be a sticky "
                        + "stop, which is the 'flung away and snapped home' defect this project has "
                        + "already removed once");
    }

    /**
     * The collider the model itself produces, from the shipped mesh fixture's real vertices.
     *
     * <p>The thigh's vertices come from {@code parts} entries whose bone is directly mapped to joint
     * 1, which is the same rule {@link YsmBodyColliders#build} applies - only the mesh object is
     * bypassed, because building one needs the asset loader and the shape rule does not depend on it.
     */
    private static YsmBodyColliders thighVolume() throws IOException {
        JsonObject mesh = JsonParser.parseString(resource("/cloth/taisho_mesh.json")).getAsJsonObject();
        JsonObject vertices = mesh.getAsJsonObject("vertices");
        JsonArray positions = vertices.getAsJsonObject("positions").getAsJsonArray("array");
        JsonObject parts = vertices.getAsJsonObject("parts");
        JsonObject runtime = JsonParser.parseString(resource("/cloth/taisho_runtime.json")).getAsJsonObject();
        JsonArray bones = runtime.getAsJsonArray("bones");
        Map<String, Integer> indexOf = new java.util.HashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            indexOf.put(bones.get(i).getAsJsonObject().get("name").getAsString(), i);
        }

        Map<Integer, List<Vector3f>> byJoint = new java.util.HashMap<>();
        for (Map.Entry<String, JsonElement> entry : parts.entrySet()) {
            // Only the converted bone parts, which is the filter the production builder applies and
            // the one this test first got wrong: the mesh also carries Epic Fight's own base parts
            // ("head", "leftLeg"), and reading those as bones silently attributed their vertices to
            // whichever bone happened to share the name.
            if (!entry.getKey().startsWith(com.ysmef.compat.model.EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String name = entry.getKey().substring(
                    com.ysmef.compat.model.EFMeshJsonWriter.BONE_PART_PREFIX.length());
            Integer boneIndex = indexOf.get(name);
            if (boneIndex == null) {
                continue;
            }
            JsonObject bone = bones.get(boneIndex).getAsJsonObject();
            if (!bone.get("mapped").getAsBoolean()) {
                continue;
            }
            int joint = bone.get("joint").getAsInt();
            if (joint != 1 && joint != 4 && joint != 2 && joint != 5 && joint != 3 && joint != 6) {
                continue;
            }
            List<Vector3f> list = byJoint.computeIfAbsent(joint, key -> new ArrayList<>());
            JsonElement arrayElement = entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject().get("array") : entry.getValue();
            for (JsonElement ordinal : arrayElement.getAsJsonArray()) {
                int index = ordinal.getAsInt();
                list.add(new Vector3f(positions.get(index * 3).getAsFloat(),
                        positions.get(index * 3 + 1).getAsFloat(),
                        positions.get(index * 3 + 2).getAsFloat()));
            }
        }
        YsmBodyColliders colliders = YsmBodyColliders.fromGeometry(byJoint);
        StringBuilder sizes = new StringBuilder();
        for (Map.Entry<Integer, List<Vector3f>> entry : byJoint.entrySet()) {
            sizes.append(" joint").append(entry.getKey()).append('=').append(entry.getValue().size());
        }
        assertTrue(byJoint.containsKey(1) || byJoint.containsKey(4),
                "the thigh's vertices must be gathered before anything can be built from them;"
                        + " gathered:" + sizes);
        assertNotNull(colliders, "the fixture's leg geometry must produce volumes; gathered:" + sizes);
        return colliders;
    }

    /** Which volume in the set is the thigh, i.e. the first limb joint. */
    private static int thighIndex(YsmBodyColliders colliders) {
        StringBuilder joints = new StringBuilder();
        for (int i = 0; i < colliders.count(); i++) {
            int joint = colliders.jointOf(i);
            joints.append(joints.length() == 0 ? "" : ",").append(joint);
            if (joint == 1 || joint == 4) {
                return i;
            }
        }
        throw new AssertionError("the model produced no thigh volume; the volumes it did produce are "
                + "for joints [" + joints + "]");
    }

    /**
     * The box a sphere is in, stated as the reason the shape had to change.
     *
     * <p>A sphere's radius is one number and it has to do two jobs: reach along the limb, and stay
     * thin across it. The panel here sits at the far end, so a sphere thin enough not to push the
     * skirt out (its radius) would have to be centred {@code span / 2} from that end to reach it, and
     * that distance is several times the radius. This is not a tuning problem - no radius satisfies
     * both, which is why raising the clamp would have inflated the torso and head volumes instead of
     * fixing anything.
     */
    private static float segmentRadius(YsmBodyColliders colliders, int index) {
        float[] volume = colliders.resolvedVolume(index);
        Vector3f end0 = new Vector3f(volume[0], volume[1], volume[2]);
        Vector3f end1 = new Vector3f(volume[4], volume[5], volume[6]);
        float tubeRadius = volume[7];
        float reachNeeded = end0.distance(end1) * 0.5F;
        assertTrue(reachNeeded > tubeRadius + PANEL_RADIUS,
                "a sphere of the limb's own thickness (" + tubeRadius + ") would have to be centred "
                        + reachNeeded + " blocks from the panel to touch it - impossible, which is the "
                        + "defect");
        return tubeRadius;
    }

    /**
     * Drive one panel against one volume for a second and return where its centre of mass ended up.
     *
     * <p>No gravity and no spring driving: the question is only where collision puts the piece. What
     * is real is the panel's lever and radius and the volume's shape.</p>
     *
     * <p>The swing limit is deliberately wide here. This test is about whether the volume reaches the
     * cloth and moves it, and a limit tight enough to stop the move would make the answer depend on
     * the limit rather than on the volume - the failure mode being looked for is "collision never
     * engaged at all", which a wide limit isolates. The limit's own behaviour is covered by
     * {@code YsmDynamicBoneSolverTest} and by this class's other tests.
     */
    private static Vector3f settle(Vector3f pivot, Vector3f rest, float lever,
                                   YsmDynamicBoneSolver.Colliders colliders) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f still = new Vector3f();
        float dt = 1.0F / 60.0F;
        for (int frame = 0; frame <= 60; frame++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, 0.0F, 0.0F, 0.0F,
                    new Vector3f(0.0F, -1.0F, 0.0F), pivot, rest, lever,
                    2.36F, 0.81F, 1.0F, (float) Math.PI * 0.999F, still, colliders, PANEL_RADIUS,
                    null, dt, out);
        }
        return new Vector3f(state.direction).mul(lever).add(pivot);
    }

    private static boolean isKept(int joint) {
        for (int kept : KEPT_JOINTS) {
            if (kept == joint) {
                return true;
            }
        }
        return false;
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
