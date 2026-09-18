package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pass that evaluates an authored physics animation.
 *
 * <p>The three bones below are the shape of a real one, taken from the shipped {@code Hair_Physics}:
 * a root driven by how the body is moving, and two segments under it, each driven by the change in
 * the bone above. What is tested is not the arithmetic of any particular expression - the authors
 * own that - but the three properties that decide whether their arithmetic gets a fair hearing:
 *
 * <ul>
 *   <li>each bone sees the bones before it and not itself,</li>
 *   <li>a bone the animation does not mention does not move,</li>
 *   <li>and nothing moves at all when the body is still.</li>
 * </ul>
 *
 * <p>That last one is the property that makes this safe to attach to a model. An authored physics
 * animation is a set of expressions whose inputs are all zero on a character standing still, so a
 * correct evaluation leaves the pose exactly as Epic Fight drew it - and a mistake anywhere in this
 * pass shows up as a model that twitches while standing.
 */
class YsmPhysicsLayerTest {

    /** The filter chain of the shipped hair animation, with its own names and tuning. */
    private static List<YsmPhysicsLayer.Bone> hair() {
        return List.of(
                // The root: how hard the body is moving, filtered.
                new YsmPhysicsLayer.Bone("BackHairA1",
                        "ysm.second_order('头发垂直', ysm.head_pitch, 1.5, 0.6, 0)", null, null),
                // The first segment: driven by the change in the root's own x.
                new YsmPhysicsLayer.Bone("BackHairB1",
                        "10*ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0)", null, null),
                // The second segment: driven by the change in the segment above it, which is the
                // idiom that only works if the bones are evaluated in order.
                new YsmPhysicsLayer.Bone("BackHairC1",
                        "ysm.second_order('B1x增量', ysm.bone_rot('BackHairB1').x, 2, 0.5, 0.2)", null, null));
    }

    /** One frame of the animation's timeline: the delta of the bone above, kept in variables. */
    private static void timeline(YsmPhysicsLayer layer) {
        // The timeline is part of the animation, so it is part of what the caller runs; it is
        // reproduced here for the two variables the expressions above read.
        layer.evaluate(null, 0.0, 0.0);
    }

    /**
    /**
     * A bone is driven by the bone above it, which requires the evaluation to be in order.
     *
     * <p>Asserted by making the root move and watching the segment below it follow. If the pass ran
     * out of order - or read last frame's table, or its own - the segment would see a bone at rest
     * and stay at rest, which on screen is a lock of hair whose top swings and whose lower half does
     * not.
     */
    @Test
    void aSegmentFollowsTheBoneAboveIt() {
        YsmPhysicsLayer layer = new YsmPhysicsLayer(List.of(
                new YsmPhysicsLayer.Bone("Parent", "ysm.head_pitch", null, null),
                new YsmPhysicsLayer.Bone("Child",
                        "2*ysm.second_order('child', ysm.bone_rot('Parent').x, 1.0, 0.5, 0)", null, null)));

        // A still head first: nothing may move, which is also what the child would look like if the
        // order were wrong.
        for (int frame = 0; frame < 30; frame++) {
            layer.advance(0.05F);
            layer.evaluate(null, 0.0, 0.0);
        }
        assertEquals(0.0F, layer.rotationOf("Child")[0], 1.0E-6F, "a still head moves nothing");

        // Now the head pitches over, and the child is the parent's own rotation, gaining two.
        for (int frame = 0; frame < 60; frame++) {
            layer.advance(0.05F);
            layer.evaluate(null, 0.0, -30.0);
        }
        float parent = layer.rotationOf("Parent")[0];
        float child = layer.rotationOf("Child")[0];
        assertEquals(-30.0F, parent, 1.0E-4F, "an unfiltered channel is the expression itself");
        assertTrue(child < -20.0F,
                "the child is driven by the parent's rotation and must have followed it; it is at "
                        + child);
        assertTrue(Math.abs(child - 2.0F * parent) < Math.abs(parent),
                "and it must lag the value it is driven toward rather than equal it: " + child);
        assertNull(layer.rotationOf("Nobody"), "a bone the animation never mentions has no rotation");
    }

    /** A bone the animation does not mention keeps its pose; it is not left with a stale rotation. */
    @Test
    void aBoneWithNoExpressionStaysAtRest() {
        YsmPhysicsLayer layer = new YsmPhysicsLayer(hair());

        for (int frame = 0; frame < 40; frame++) {
            layer.advance(0.05F);
            layer.evaluate(null, 0.0, 45.0);
        }

        assertNotNull(layer.rotationOf("BackHairA1"), "the root is driven");
        assertEquals(0.0F, layer.rotationOf("BackHairA1")[1], 1.0E-6F,
                "a channel with no expression is zero, not a stale value");
        assertEquals(0.0F, layer.rotationOf("BackHairA1")[2], 1.0E-6F);
        assertEquals(0.0F, layer.rotationOf("BackHairB1")[0], 1.0E-6F,
                "and the segment driven by a variable nobody set stays where the pose put it");
    }

    /** Every bone the animation names is evaluated, in the order it was given. */
    @Test
    void everyNamedBoneIsEvaluatedInOrder() {
        YsmPhysicsLayer layer = new YsmPhysicsLayer(hair());
        assertEquals(3, layer.size());
        assertEquals(List.of("BackHairA1", "BackHairB1", "BackHairC1"), layer.boneNames());

        for (int frame = 0; frame < 10; frame++) {
            layer.advance(0.05F);
            layer.evaluate(null, 0.0, 10.0);
        }
        for (String name : layer.boneNames()) {
            assertNotNull(layer.rotationOf(name), name + " must have been evaluated");
            assertEquals(3, layer.rotations().size(), "one entry per bone, no more");
        }
    }

    /** An empty animation is legal and moves nothing - the common case for a model without one. */
    @Test
    void anEmptyLayerIsHarmless() {
        YsmPhysicsLayer layer = new YsmPhysicsLayer(List.of());
        assertEquals(0, layer.size());
        layer.advance(0.05F);
        layer.evaluate(null, 0.0, 0.0);
        assertNull(layer.rotationOf("anything"));
        assertTrue(new YsmPhysicsLayer(null).boneNames().isEmpty(), "a null list is an empty list");
    }

    // ------------------------------------------------------------------
    // Reading the animation the converter stored
    // ------------------------------------------------------------------

    /**
     * The adapter from our stored animation to the layer, on the shape the authors actually ship.
     *
     * <p>The animation is built here the way the converter builds it: a bone whose rotation is one
     * expression per axis, and a timeline whose two entries difference a bone's rotation across the
     * frame. That timeline is the thing being tested as much as the adapter: its {@code 0.0} entry
     * computes {@code v.HP_x = v.HP_x_0 - v.HP_x_1} and its {@code 0.0101} entry stores
     * {@code v.HP_x_1 = v.HP_x_0}, so running both once per frame is what turns a bone's rotation
     * into the per-frame delta the segment below it is driven by.
     */
    @Test
    void theAdapterReadsTheStoredAnimationInOrder() {
        com.ysmef.compat.ysm.script.ScriptAnim animation =
                new com.ysmef.compat.ysm.script.ScriptAnim();
        animation.name = "Hair_Physics";
        animation.length = 0.0101F;

        com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels root =
                new com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels();
        root.rotation = channel("ysm.head_pitch", "0", "0");
        animation.bones.put("BackHairA1", root);

        com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels segment =
                new com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels();
        segment.rotation = channel("10*ysm.second_order('A1x增量', v.HP_x, 2, 0.5, 0)", "0", "0");
        animation.bones.put("BackHairB1", segment);

        // A bone with no channels at all is not part of the physics and must be left out rather
        // than driven to zero, or it would be written over in the pose.
        animation.bones.put("NotPhysics", new com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels());

        animation.timelines.add(new com.ysmef.compat.ysm.script.ScriptAnim.Timeline(0.0F,
                new String[]{"v.HP_x_0=ysm.bone_rot('BackHairA1').x;", "v.HP_x=v.HP_x_0-v.HP_x_1;"}));
        animation.timelines.add(new com.ysmef.compat.ysm.script.ScriptAnim.Timeline(0.0101F,
                new String[]{"v.HP_x_1=v.HP_x_0;"}));

        YsmPhysicsLayer layer = YsmPhysicsLayer.of(animation);
        assertEquals(List.of("BackHairA1", "BackHairB1"), layer.boneNames(),
                "the animation's own order, and only the bones it actually drives");

        // Frame one: the head is at rest, so there is no movement to difference yet. The timeline
        // reads the bone table as it stands, which on the first frame is empty.
        layer.advance(0.05F);
        layer.evaluate(null, 0.0, 0.0);
        assertEquals(0.0F, layer.rotationOf("BackHairB1")[0], 1.0E-6F,
                "a head at rest drives the segment with nothing");

        // Frames two onwards: the head moves, steadily. What the timeline differences is the
        // previous frame's rotation, so the segment is driven by how fast the head is moving - not
        // by where it is - and it therefore answers a frame behind, which is the property that
        // makes a chain trail rather than track.
        for (int frame = 2; frame <= 10; frame++) {
            layer.advance(0.05F);
            layer.evaluate(null, 0.0, -2.0 * frame);
        }
        float moving = layer.rotationOf("BackHairB1")[0];
        assertTrue(moving < -0.5F,
                "the segment is driven by the head's movement and must have moved; it is at " + moving);

        // And when the head stops moving the delta goes to zero, so the segment settles back to
        // rest even though the head is still twenty degrees over - a velocity driver, not a
        // position one, which is what the shipped animation's timeline computes.
        float held = -20.0F;
        for (int frame = 0; frame < 80; frame++) {
            layer.advance(0.05F);
            layer.evaluate(null, 0.0, held);
        }
        float settled = layer.rotationOf("BackHairB1")[0];
        assertEquals(0.0F, settled, 5.0E-2F,
                "with the head no longer moving the segment must settle back to rest; it is at "
                        + settled);
        assertEquals(-20.0F, layer.rotationOf("BackHairA1")[0], 1.0E-4F,
                "and the head's own bone is the value the animation gave it");
    }

    private static com.ysmef.compat.ysm.script.ScriptAnim.Channel channel(String x, String y, String z) {
        com.ysmef.compat.ysm.script.ScriptAnim.Channel channel =
                new com.ysmef.compat.ysm.script.ScriptAnim.Channel();
        com.ysmef.compat.ysm.script.ScriptAnim.Key key = new com.ysmef.compat.ysm.script.ScriptAnim.Key();
        key.time = 0.0F;
        key.post = com.ysmef.compat.ysm.script.ScriptAnim.Value.ofExpr(x, y, z);
        channel.keys.add(key);
        return channel;
    }

    /** A nonsense expression must leave the bone at rest rather than move it to a NaN. */
    @Test
    void aBrokenExpressionLeavesTheBoneAtRest() {
        YsmPhysicsLayer layer = new YsmPhysicsLayer(List.of(
                new YsmPhysicsLayer.Bone("Broken", "ysm.invented_by_nobody(1, 2) + (", null, null),
                new YsmPhysicsLayer.Bone("Empty", "", "  ", null)));

        for (int frame = 0; frame < 10; frame++) {
            layer.advance(0.05F);
            layer.evaluate(null, 0.0, 0.0);
        }
        assertEquals(0.0F, layer.rotationOf("Broken")[0], 1.0E-6F);
        assertEquals(0.0F, layer.rotationOf("Empty")[0], 1.0E-6F);
    }
}
