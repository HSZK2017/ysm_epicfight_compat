package com.ysmef.compat.model.runtime;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the in-game reports are made of, stated as the two invariants the physics has to keep.
 *
 * <h2>1. A collision may rotate a chain; it may never translate it</h2>
 *
 * <p>The report: <i>"the hair comes away from the head, most obviously when it is long; when the tip
 * touches the model the tip should be pushed up and slide along the body, but what happens is that
 * the hair is pushed off the head and separated."</i> The invariant that rules that out is structural
 * rather than a matter of tuning, and it has three parts, one per joint of a chain:
 *
 * <pre>
 *   the segment's delta is T(bindPivot) R T(-bindPivot)      - it holds bindPivot still
 *   the segment's swing is a rotation about its own pivot     - it moves nothing else
 *   a child's delta is composed under its parent's            - so the joint between them stays put
 * </pre>
 *
 * <p>Together they say: <b>the point where a piece is attached to the bone above it does not move,
 * whatever the piece below it does.</b> A collision is the only thing in the solver that writes a
 * direction directly (see {@code YsmDynamicBoneSolver#resolveCollisions}, where the correction is
 * applied as a rotation of the swing and never as a translation), so if this test passes with a
 * collider pressing the tip, the collision response cannot be what pushes a piece off the body.
 *
 * <p>The first test below pins that on a three-segment chain, with a collider under the tip. The
 * second pins the other half: the attachment is only that point if the pivot is named in the frame
 * the vertices are in, and the frame the shipped code names it in <i>is</i> that frame -
 * {@code mesh.positions()} is the authored chain scaled once, because the writer's turn is undone by
 * Epic Fight's loader as the mesh is read (see {@link YsmPhysicsParts#pivotInMeshSpace}). An earlier
 * revision additionally applied the writer's corner turn to the pivot, on the theory that the frame
 * was missing; that put the root's own pivot 1.95 blocks from the scalp on this strand, and the
 * in-game result was separation - legs shattered and long hair broken into fragments. It was
 * reverted, and the measurement below is the direction it failed in.
 *
 * <h2>2. The moment arm is the distance to the piece's own geometry</h2>
 *
 * <p>A pivot that is right rotates the piece about its root; a pivot that is wrong turns the pivot
 * error into a lever. Measured on {@code EKU(1.0.ysm}'s {@code LeftBackHair1_1}, a head-bound strand:
 * the shipped pivot is 0.12 blocks from the strand's centroid and sits inside the strand's own drawn
 * bounding box, while the turned candidate is 2.05 blocks away and outside it - a factor of 17 on the
 * lever, which is the difference between a tip that swings and a piece that leaves the body.
 */
class YsmPhysicsChainAttachmentTest {

    /** Epic Fight's joint ids the chain hangs from; only the head's is needed here. */
    private static final int JOINT_HEAD = 9;

    private static final float GRAVITY = 24.0F;
    private static final Vector3f DOWN = new Vector3f(0.0F, -1.0F, 0.0F);
    private static final Vector3f STILL = new Vector3f();
    private static final float DT = 1.0F / 60.0F;
    private static final int FRAMES = 90;

    /**
     * A plunger that pushes the piece's centre of mass out of a sphere, exactly as the body's own
     * collision volumes do - {@link YsmBodyColliders#pushOutOfCapsule} is the production push-out.
     */
    private static final class Sphere implements YsmDynamicBoneSolver.Colliders {
        final Vector3f centre = new Vector3f();
        float radius = 0.25F;

        @Override
        public int count() {
            return 1;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float pointRadius, int index) {
            return YsmBodyColliders.pushOutOfCapsule(point, velocity, pointRadius,
                    centre.x, centre.y, centre.z, centre.x, centre.y, centre.z, radius);
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            return false;
        }
    }

    /** One link of the fixture chain: where it hangs from, how far its geometry reaches, which way. */
    private record Link(Vector3f pivot, Vector3f restDir, float lever, float maxAngle) {}

    /**
     * A long hair chain rooted under the head joint, its tip pressed into a collider: the joint
     * between every link, and the root's own attachment, must not move by more than a millimetre
     * over ninety frames.
     *
     * <p>This is the report's expected behaviour stated as a number. The tip is allowed - and
     * expected - to move: it is pushed out of the volume and slides along it. What may not move is
     * the root, because the root is drawn attached to the scalp.
     */
    @Test
    @DisplayName("a collision at the tip rotates the chain and never moves its root")
    void aCollisionAtTheTipCannotMoveTheChainRoot() {
        // A ponytail of four links hanging from a scalp at 1.60 blocks, each link 0.16 long, so the
        // tip reaches 0.96 - chest height, which is where a long strand meets the body - and drifts
        // backwards as a real one does.
        List<Link> links = new ArrayList<>();
        float y = 1.60F;
        float z = -0.10F;
        for (int i = 0; i < 4; i++) {
            Vector3f pivot = new Vector3f(0.0F, y, z);
            Vector3f rest = new Vector3f(0.06F * i, -0.985F, -0.16F).normalize();
            links.add(new Link(pivot, rest, 0.16F, (float) Math.toRadians(30.0)));
            y += rest.y * 0.16F;
            z += rest.z * 0.16F;
        }

        // The collider sits where the last link's centre of mass rests, so every frame starts with
        // the tip inside it: the worst case for a response that translates instead of rotating.
        Sphere collider = new Sphere();
        Link tip = links.get(links.size() - 1);
        collider.centre.set(tip.pivot).fma(tip.lever, tip.restDir);
        collider.radius = 0.30F;

        Chain chain = new Chain(links);
        Vector3f rootBefore = chain.attachment(0);
        Vector3f tipBefore = chain.attachment(links.size() - 1);
        float rootDrift = 0.0F;
        float rootDriftOfTheChain = 0.0F;
        float tipTravel = 0.0F;
        Vector3f rootPrevious = new Vector3f(rootBefore);
        Vector3f tipPrevious = new Vector3f(tipBefore);

        for (int frame = 0; frame < FRAMES; frame++) {
            chain.step(collider);
            Vector3f rootNow = chain.attachment(0);
            Vector3f tipNow = chain.attachment(links.size() - 1);
            rootDrift = Math.max(rootDrift, rootNow.distance(chain.rootBind()));
            rootDriftOfTheChain = Math.max(rootDriftOfTheChain, rootNow.distance(rootPrevious));
            tipTravel = Math.max(tipTravel, tipNow.distance(tipPrevious));
            rootPrevious.set(rootNow);
            tipPrevious.set(tipNow);
        }

        // The root is the point the chain is drawn from: the first link's own pivot, in the frame
        // the delta is applied in. It may not have moved at all.
        assertEquals(0.0F, rootDrift, 1.0E-4F,
                "the chain's root left the bone that draws it by " + rootDrift + " blocks: a collision"
                        + " response that translates the chain is the reported defect");
        assertEquals(0.0F, rootDriftOfTheChain, 1.0E-4F,
                "and it must not creep frame by frame either (worst per-frame step "
                        + rootDriftOfTheChain + " blocks)");
        // And the response happened: the tip really was pushed, so this is not a vacuous pass.
        assertTrue(tipTravel > 0.02F,
                "the tip was pressed into a collider for " + FRAMES + " frames and moved only "
                        + tipTravel + " blocks, so this test did not exercise the response");
    }

    /**
     * The pivot belongs in the frame the vertices are in, and the size of the error when it is not.
     *
     * <p>A hair chain at head height, on {@code EKU(1.0.ysm}'s own numbers: the strand's geometry
     * centroid and its pivot, both in the frame the delta acts in - the pivot is the authored number
     * taken through the model's scale, which is what {@code mesh.positions()} holds, and the centroid
     * is read out of the strand's own bounding box there. The strand hangs from that pivot; the corner
     * turn an earlier revision applied instead put it 1.95 blocks off the scalp, and because a
     * segment's delta holds {@code bindPivot} still, that 1.95 blocks is how far the strand's root
     * travels on the first degree of swing - which is the reported separation.
     */
    @Test
    @DisplayName("the strand hangs from the pivot the production code names, in the frame the delta acts in")
    void theRootStaysOnTheScalp() {
        // EKU(1.0.ysm / LeftBackHair1_1. The pivot is the model's own authored number through the
        // model's own scale - which is what mesh.positions() carries, because the writer's turn and
        // Epic Fight's loader inverse-turn cancel (see YsmPhysicsParts#pivotInMeshSpace). The
        // centroid is the strand's geometry centroid in that same frame: (-0.086, 1.381, 0.240),
        // i.e. the stored (-0.086, -0.240, 1.381) read back the way the loader reads it.
        Vector3f authoredPivot = new Vector3f(-0.058F, 2.127F, 0.342F);
        float scale = 0.7F;
        Vector3f storedCentroid = new Vector3f(-0.086F, -0.240F, 1.381F);
        Vector3f geometryCentroid = new Vector3f(storedCentroid.x, storedCentroid.z, -storedCentroid.y);

        Vector3f meshPivot = YsmPhysicsParts.pivotInMeshSpace(new Matrix4f(),
                authoredPivot.x, authoredPivot.y, authoredPivot.z, scale, scale);
        // The candidate the reverted revision named: the writer's stored corner map applied to the
        // pivot as well. It is one whole axis away from the point the strand is drawn hanging from.
        Vector3f turnedPivot = new Vector3f(meshPivot.x, -meshPivot.z, meshPivot.y);

        float correctLever = meshPivot.distance(geometryCentroid);
        float wrongLever = turnedPivot.distance(geometryCentroid);

        assertTrue(correctLever < 0.20F,
                "the strand's pivot must sit on the strand: it is " + correctLever + " blocks from its"
                        + " own geometry's centroid");
        assertTrue(wrongLever > 1.5F,
                "and turning it the way the reverted revision did puts it " + wrongLever + " blocks"
                        + " away - which is the distance the strand's root travels when it swings,"
                        + " because T(P)RT(-P) holds P still");
        assertTrue(wrongLever / correctLever > 5.0F,
                "the lever is wrong by a factor of " + (wrongLever / correctLever));

        // The strand's root - the point it is sewn to the skull at - is the bone's own origin, which
        // in the frame the delta acts in is exactly meshPivot. A swing about that pivot holds it
        // there; a swing about the corner-turned point carries it off by the lever error, and that
        // displacement is the separation the report describes.
        assertEquals(0.0F, swingMovesPivotBy(meshPivot, meshPivot, 20.0F), 1.0E-4F,
                "a swing about the correct pivot leaves the strand's root exactly where it is");
        assertTrue(swingMovesPivotBy(turnedPivot, meshPivot, 20.0F) > 0.5F,
                "and a swing about the corner-turned one moves it: the root is not held at all"
                        + " (turned pivot=" + turnedPivot + " root=" + meshPivot
                        + " moved=" + swingMovesPivotBy(turnedPivot, meshPivot, 20.0F)
                        + "; the two candidates are " + wrongLever + " and " + correctLever
                        + " blocks from the strand's own geometry)");
    }

    @Test
    @DisplayName("the fixture's own swing arithmetic, so a vacuous measurement cannot pass for a fix")
    void theFixtureSwingIsARealRotation() {
        Quaternionf swing = new Quaternionf().fromAxisAngleRad(new Vector3f(1.0F, 0.0F, 0.0F),
                (float) Math.toRadians(20.0F));
        assertTrue(Math.abs(swing.w() - 1.0F) > 0.01F,
                "fromAxisAngleRad(20 deg about X) must not be the identity: " + swing);
        Vector3f pivot = new Vector3f(0.0F, 0.0F, 0.0F);
        Matrix4f delta = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(pivot, swing, delta);
        float moved = delta.transformPosition(new Vector3f(1.0F, 0.0F, 0.0F))
                .distance(new Vector3f(1.0F, 0.0F, 0.0F));
        assertEquals(0.0F, moved, 1.0E-5F, "a swing about the origin still holds the origin");
        float movedOffAxis = delta.transformPosition(new Vector3f(0.0F, 0.0F, 1.0F))
                .distance(new Vector3f(0.0F, 0.0F, 1.0F));
        assertTrue(movedOffAxis > 0.3F,
                "a point a block off the axis must move by 2*sin(10 deg) = 0.347 blocks, moved "
                        + movedOffAxis);
    }

    /**
     * How far a twenty-degree swing about {@code pivot} moves the geometry that hangs from the correct
     * attachment point.
     *
     * <p>The delta the physics writes is {@code T(P) R T(-P)}: it holds P still, and nothing else.
     * So a point that is really at {@code attachment} moves by
     * {@code |R(attachment - P) + P - attachment|}, which for a small angle is
     * {@code sin(angle) * |attachment - P|} - the lever error, in blocks. The angle is twenty degrees
     * rather than one because {@code buildSegmentDelta} treats a swing below a tenth of a degree as
     * neutral and writes the identity for it, which would make this measurement vacuously zero.
     */
    private static float swingMovesPivotBy(Vector3f pivot, Vector3f attachment, float degrees) {
        Quaternionf swing = new Quaternionf().fromAxisAngleRad(new Vector3f(1.0F, 0.0F, 0.0F),
                (float) Math.toRadians(degrees));
        Matrix4f delta = new Matrix4f();
        YsmMeshSecondaryMotion.buildSegmentDelta(pivot, swing, delta);
        Vector3f moved = new Vector3f(attachment);
        delta.transformPosition(moved);
        float distance = moved.distance(attachment);
        if (System.getenv("YSMEF_CHAIN_PROBE") != null) {
            System.out.println("[swing] pivot=" + pivot + " attach=" + attachment + " deg=" + degrees
                    + " swing=" + swing + " delta.translation="
                    + delta.m30() + "," + delta.m31() + "," + delta.m32()
                    + " moved=" + moved + " distance=" + distance);
        }
        return distance;
    }

    /**
     * The chain fixture, driven through the production solver and the production delta builder.
     *
     * <p>Each link is handed to {@link YsmDynamicBoneSolver#update} with its own pivot and its own
     * lever, and the delta it returns is composed under its parent's - which is exactly the two calls
     * {@code YsmMeshSecondaryMotion#resolveSegment} makes. The joint positions this test reads are
     * then taken from the composed matrices, so they are the positions the mesh would be drawn at.
     */
    private static final class Chain {
        private final List<Link> links;
        private final YsmDynamicBoneSolver.SegmentState[] states;
        private final Matrix4f[] deltas;
        private final Quaternionf[] scratch;
        private final Quaternionf[] bindSwing;
        private final Matrix4f[] composed;
        private final Vector3f[] attachments;

        Chain(List<Link> links) {
            this.links = links;
            int count = links.size();
            this.states = new YsmDynamicBoneSolver.SegmentState[count];
            this.deltas = new Matrix4f[count];
            this.scratch = new Quaternionf[count];
            this.bindSwing = new Quaternionf[count];
            this.composed = new Matrix4f[count];
            this.attachments = new Vector3f[count];
            for (int i = 0; i < count; i++) {
                this.states[i] = new YsmDynamicBoneSolver.SegmentState();
                this.deltas[i] = new Matrix4f();
                this.scratch[i] = new Quaternionf();
                this.bindSwing[i] = new Quaternionf();
                this.composed[i] = new Matrix4f();
                this.attachments[i] = new Vector3f(links.get(i).pivot());
            }
        }

        /** Where the chain is bolted to the bone above it: the first link's own pivot. */
        Vector3f rootBind() {
            return links.get(0).pivot();
        }

        void step(YsmDynamicBoneSolver.Colliders colliders) {
            Quaternionf out = new Quaternionf();
            for (int i = 0; i < links.size(); i++) {
                Link link = links.get(i);
                YsmDynamicBoneSolver.INSTANCE.update(states[i], GRAVITY, 0.0F, 0.0F, DOWN,
                        link.pivot(), link.restDir(), link.lever(), 2.36F, 0.4F, 1.0F,
                        link.maxAngle(), STILL, colliders, 0.03F, null, DT, out);
                // A swing expressed in model space has to be carried into the space the part delta
                // acts in; the fixture's deformation is the identity, so this is the swing itself.
                scratch[i].set(out);
                YsmMeshSecondaryMotion.buildSegmentDelta(link.pivot(), scratch[i], deltas[i]);
                // The delta holds its own pivot still - asserted here rather than assumed, because
                // every joint below is composed out of these.
                assertTrue(deltas[i].transformPosition(new Vector3f(link.pivot()))
                                .distance(link.pivot()) < 1.0E-4F,
                        "link " + i + " swings about " + link.pivot() + " and its delta moved that"
                                + " point - the fixture's own premise is broken. swing=" + scratch[i]);
            }
            // Composed under the parent, one link at a time, exactly as resolveSegment does it.
            for (int i = 0; i < links.size(); i++) {
                if (i == 0) {
                    composed[i].set(deltas[i]);
                } else {
                    composed[i].set(composed[i - 1]).mul(deltas[i]);
                }
                attachments[i].set(links.get(i).pivot());
                composed[i].transformPosition(attachments[i]);
            }
        }

        /**
         * Where link {@code index}'s attachment point ends up. Link 0's is the chain's root - the
         * point it is drawn hanging from, which no swing anywhere may move.
         */
        Vector3f attachment(int index) {
            return attachments[index];
        }
    }
}
