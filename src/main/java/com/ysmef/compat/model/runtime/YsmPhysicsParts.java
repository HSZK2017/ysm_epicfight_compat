package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMJointMapper;
import com.ysmef.compat.model.YSMMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Turns a model plus its converted mesh into the list of <b>segments</b> the dynamics run
 * on: one per physics-driven bone, each with its own pivot, moment arm, mass and
 * collision radius.
 *
 * <h2>Where the segment list comes from</h2>
 *
 * <p>Two sources, in this order, and the order is the point:
 *
 * <ol>
 *   <li><b>The author's own declaration</b> ({@link YsmPhysicsBinding}): the bones named
 *       by the animation the model's controller plays as its physics animation
 *       ({@code Hair_Physics}, or whatever the model calls it). This is exact - it is the
 *       same list YSM itself would simulate - and it is what makes the feature work on
 *       models whose bone names say nothing ("MaWei_LeftA2", "BackHairRightC2").</li>
 *   <li><b>The bone-name classifier</b> ({@link YsmPhysicsChains}) as a fallback, for
 *       models that predate the convention or were exported without controllers.</li>
 * </ol>
 *
 * <h2>Every bone is its own segment</h2>
 *
 * <p>The previous classifier kept only the <i>top</i> of each hanging piece, on the
 * grounds that a ponytail is one swinging object - which was true of a simulation that
 * could only apply one rotation per piece. It is not true of a pendulum chain, and it is
 * exactly what made a ten-bone ponytail swing like a plank: the whole piece rotated about
 * one pivot. Here every bone the author listed (and, on the fallback path, every bone of
 * the piece that carries geometry) gets its own pivot and its own dynamics, so the piece
 * curls. Tearing is prevented by composing each segment's delta under its nearest
 * simulated ancestor's - see {@link Segment#parent()}.
 *
 * <h2>What may not be a segment</h2>
 *
 * <p>A segment moves the geometry drawn by <i>its own mesh part</i>, so its lever, rest
 * direction and collision radius have to be measured from that geometry and from nothing
 * else. {@link #ownsItsGeometry} is that rule, and {@link #poseBelongsToEpicFight} is its
 * other half: a bone whose name is a body part is animated by Epic Fight, and a second
 * rotation on top of that pose turns a limb rather than the cloth on it. Both are checked
 * in {@code buildSegment}, so a part list that names such a bone - an author's physics
 * animation naming a mapped bone, or a bone that carries no quads at all - yields no
 * segment for it rather than a segment with a lever pointing at someone else's geometry.
 * Two further tests ask whether the pivot is a point the piece could hang from at all:
 * {@link #wrapsPivot} (its geometry closes <i>around</i> the pivot) and
 * {@link #risesOffPivot} (its geometry stands <i>above</i> a pivot that is not on it). Both
 * are the same defect - a rotation about a point the piece is not attached to - and both keep
 * the piece rigid on its joint instead.
 *
 * <h2>The moment arm</h2>
 *
 * <p>{@code bindPivot} is the bone's pivot expressed in the mesh's own bind space. It is
 * the point the part transform must rotate about, and getting it wrong is what produced
 * the "flung away and snapped home" behaviour: a rotation applied about the model origin
 * instead of about the bone's pivot gives every piece a lever the length of the model.
 * The pivot is {@code bindWorld x (pivot x scale)}: {@code bindWorld} is the accumulated
 * bind deformation the mesh was written with, so this is the same point the geometry was
 * placed at.
 *
 * <p>Non-uniform model scales ({@code width_scale != height_scale}) make that product an
 * approximation, because an accumulated rotation about each pivot does not commute with an
 * anisotropic scale. Every model seen on the test instance uses a uniform scale, and the
 * alternative - deriving the pivot from geometry - collapses for the outright common case
 * of a strand bone with no geometry of its own.
 */
public final class YsmPhysicsParts {

    /**
     * Segment collision spheres are this fraction of the piece's own half-thickness.
     *
     * <p>Deliberately smaller than the geometry, so cloth comes to rest on a surface rather than
     * being flung off it by a sphere that reaches past the thing it is meant to touch. What the
     * fraction multiplies is the <i>thickness</i>, not the distance to the centre of mass: see
     * {@link #radiusFor}.
     */
    private static final float COLLISION_RADIUS_FRACTION = 0.65F;

    private static final float MIN_RADIUS = 0.02F;
    private static final float MAX_RADIUS = 0.22F;

    /** Fewer vertices than this and the geometry cannot size a lever or a sphere. */
    private static final int MIN_VERTICES = 4;

    /** Relative mass proxy: vertex count over this, clamped. See {@link Segment#mass()}. */
    private static final float VERTICES_PER_MASS_UNIT = 64.0F;

    /** A lever shorter than this is a bone sitting on its own pivot; it cannot swing. */
    private static final float MIN_LEVER = 0.01F;

    /**
     * The same number, for the leg diagnostic: a piece that was dropped for having no lever has to be
     * able to say so with the threshold the rule used, rather than with a copy of it that can drift.
     */
    static float minimumLever() {
        return MIN_LEVER;
    }

    /**
     * The spread, largest eigenvalue over smallest, below which a bone's own geometry is read as
     * surrounding its pivot rather than hanging from it. See {@link #directionSpread}.
     *
     * <p>2.6, and it is a <b>gate</b>, not the rule: the measurement that chose it is in
     * {@link #wrapsPivot}, and it is on the corpus report
     * {@code build/reports/ysm-physics-wrap-corpus.md} (test {@code DefectCalibrationCorpusSweepTest}).
     * On the shipped skirt band {@code X_yiqun1} the spread is 2.313, on the panel below it
     * ({@code X_qunzi1}) 2.786, and on that model's hair, fringe and ear bones 2.16 - 85.
     */
    private static final float WRAPS_PIVOT_MAX_SPREAD = 2.6F;

    /**
     * How far from the body's left-right axis a bone's pivot must sit, as a share of the half-width of
     * its own geometry, before the geometry is read as a band <b>worn around</b> that axis.
     *
     * <p>One half: the pivot is at or beyond the middle of the piece's half-width, i.e. out at its
     * rim. {@code X_yiqun1} measures 0.70, the panel below it 0.15, and every other physics bone of
     * that model 0.00.
     */
    private static final float WRAPS_PIVOT_MIN_AXIS_OFFSET = 0.5F;

    /**
     * How far a piece's own x span may be off-centre from the body's left-right axis, as a share of
     * its half-width, for it to count as worn around the body rather than as a limb, a held item or
     * a decoration that merely crosses the axis.
     *
     * <p>Thirty per cent. This is the condition that keeps the rule on garments: a trouser leg or a
     * hand that partly crosses the mirror plane is lopsided by far more than this about it, while a
     * skirt, a belt or an armour ring is modelled symmetrically.
     */
    private static final float WRAPS_PIVOT_MAX_AXIS_ASYMMETRY = 0.30F;

    /**
     * The least upward tilt of a piece's own geometry away from its pivot, as the up-component of the
     * unit direction from the pivot to that geometry's centroid, before the piece is read as one the
     * pivot <b>cannot be hanging from</b>. See {@link #risesFromPivot}.
     *
     * <p>Ten per cent, i.e. about 5.7 degrees above horizontal, and it is a <b>direction</b> margin
     * rather than a distance: what the rule reads is the sign of the up-component of a unit vector,
     * so the number is dimensionless and a two-block thigh and a two-centimetre charm are judged by
     * the same constant. It is not zero for two reasons, both measured rather than assumed:
     *
     * <ul>
     *   <li><b>A sign is not a direction.</b> The up-component of a piece whose centroid sits level
     *       with its pivot is float noise, and the sign of that noise is a coin flip: a piece would
     *       then be simulated or not depending on which way its last vertex rounded. The margin has
     *       to be comfortably above the noise of a sum of a few thousand floats, and 0.10 of the
     *       lever is some ten orders of magnitude above it.</li>
     *   <li><b>It is not where the two populations separate, and the measurement says so.</b> Over the
     *       906-package corpus the up-components do <i>not</i> form two clusters with an empty band
     *       between them: 18,377 of 44,576 candidate bones (41 per cent) have a centroid above their
     *       pivot, the band -0.05..+0.30 holds 4,174 of them, and the affected-model count falls only
     *       from 731 at margin 0 to 723 at margin 0.50. The margin's job is therefore only to keep the
     *       sign of a level piece out of the decision - 0.10 is the smallest round number that is
     *       comfortably above float noise and below every piece the numbers in {@link #risesOffPivot}
     *       are about (the reported thigh measures +0.996, the maid's tail tip +0.949). The rule's
     *       precision comes from the second condition, not from this number.</li>
     * </ul>
     *
     * <p>What it deliberately is <b>not</b>: a distance in blocks. The neighbouring candidate - "the
     * pivot is more than N blocks outside its own geometry" - was measured at 0.048 blocks on the
     * reported piece against 0.037 on the worst piece of the known-good model
     * ({@code wine_fox/01_taisho_maid}), a factor of 1.3, and a rule whose margin is 1.3x on the
     * models it was calibrated against is a rule fitted to one file. The direction separates the same
     * two pieces by a wide margin on both models.
     */
    private static final float RISES_FROM_PIVOT_MIN_UP_SHARE = 0.10F;

    /**
     * Whether a piece's own geometry <b>rises from</b> its pivot: the first half of
     * {@link #risesOffPivot}, which is the rule that ships.
     *
     * <p>This is the direction test on its own, and it is kept as its own method because it is the
     * half that has a threshold: the corpus calibration sweeps it, the leg diagnostic prints the
     * number it reads, and the second half - containment - is a boolean with nothing to tune. Read
     * {@link #risesOffPivot} for why the pair is the rule and this alone is not.
     *
     * <h2>The defect this exists for</h2>
     *
     * <p>{@code RightLegclothes2} of the reported {@code EKU(1.0.ysm}: its authored pivot is carried
     * through the bone's {@code T(p) R T(-p)} chain to {@code (0.022, 0.068, 0.004)} - <b>at the
     * ankle</b> - while the geometry that part draws spans y 0.456..0.934, i.e. the thigh. The pivot
     * is 0.388 blocks below the piece it is supposed to hinge, on a piece 0.478 blocks long, and the
     * direction from that pivot to the piece's own centre of mass therefore points <b>up</b>: 174.8
     * degrees from straight down on the deployed build, where every correctly hinged piece of the same
     * model points down ({@code RightLegclothes1}, the other half of the same thigh and the same
     * joint, measures 5.9). The solver has one spring per piece and no notion of "this pivot is not
     * on this geometry": it swings each half of that thigh toward the world's vertical from its own
     * rest, in opposite senses, each pinned at its 30 degree allowance - which is the thigh lying
     * nearly horizontal and the pair splaying like 八.
     *
     * <h2>What the rule reads, and why it is the up-component and not the height</h2>
     *
     * <p>The piece's own {@code rest} is {@code centroid - pivot}, already measured in
     * {@link #buildSegment} from that piece's own geometry and nothing else. A piece that hangs from
     * its pivot has its mass below that pivot, so the direction points down and the up-component is
     * negative. A piece whose geometry is entirely above its pivot cannot be hanging from it, and the
     * length of the lever says nothing about that: it is the <i>direction</i> that is wrong, which is
     * why this is a share of the lever rather than a distance.
     *
     * @param rest  the direction from the piece's pivot to its own geometry's centroid, bind space
     * @param lever {@code |rest|}, blocks
     */
    static boolean risesFromPivot(Vector3f rest, float lever) {
        return risesFromPivot(rest, lever, RISES_FROM_PIVOT_MIN_UP_SHARE);
    }

    /**
     * The margin the rule above ships, so a report or a test can name the number production uses
     * instead of repeating it - a second copy of a threshold is how a calibration report ends up
     * describing a rule nobody runs.
     */
    static float risesFromPivotMargin() {
        return RISES_FROM_PIVOT_MIN_UP_SHARE;
    }

    /**
     * The same test at an explicit margin, so the corpus calibration can sweep the threshold and a
     * test can pin both sides of it without a second copy of the arithmetic.
     */
    static boolean risesFromPivot(Vector3f rest, float lever, float minUpShare) {
        if (rest == null || !YsmDynamicBoneSolver.isFinite(rest)
                || !Float.isFinite(lever) || lever <= 0.0F) {
            return false;
        }
        return rest.y > lever * minUpShare;
    }

    /**
     * How far outside a piece's own geometry its pivot has to sit before the pivot is read as
     * <b>not a point on the piece</b>. See {@link #pivotOnGeometry}.
     *
     * <p>One tenth of a millimetre, and it is float noise on a box's faces rather than a margin: the
     * question is containment - is this pivot on the piece at all - and the answer is allowed to be
     * "yes" only when the pivot is inside the box the piece occupies, to within rounding. It is
     * deliberately <b>not</b> a tuned distance. An earlier round measured "the pivot is more than N
     * blocks outside its own geometry" and could not place N: the reported model's shin piece measures
     * 0.048 blocks against 0.037 on the worst piece of the known-good model, a factor of 1.3, which is
     * a rule fitted to one file. Containment has no N: a pivot inside the piece is on it, one outside
     * is not, and the distance does not enter.
     */
    private static final float PIVOT_ON_GEOMETRY_SLACK = 1.0E-4F;

    /**
     * Whether a pivot is still <b>on</b> the piece it belongs to: inside the bounding box of that
     * piece's own geometry (to within {@link #PIVOT_ON_GEOMETRY_SLACK}).
     *
     * <p>This is the second half of {@link #risesOffPivot}, and the reason the first half alone is not
     * the rule. Measured over the corpus, a piece whose centre of mass sits above its pivot is common
     * and usually harmless: ears, a hat, hair ornaments and a fox's tail tip all stand up from a pivot
     * that is still <i>on</i> them, and a hinge on the piece turns it correctly however it is
     * oriented. What cannot be hinged is a pivot that is not on the piece at all - the reported thigh
     * piece's pivot is 0.388 blocks below its own geometry, at the ankle - and containment is the
     * boolean that separates the two.
     *
     * <p>The bounding box is deliberately the <i>loose</i> version of "on the piece": a pivot inside
     * the box of a concave piece, or in the empty corner of an L, reads as on the piece and the piece
     * keeps its simulation. The error is in the safe direction - a piece that should have been left
     * rigid merely keeps swinging - and it is why this test is paired with the direction one rather
     * than used alone.
     */
    static boolean pivotOnGeometry(List<Vector3f> vertices, Vector3f pivot) {
        if (vertices == null || pivot == null || !YsmDynamicBoneSolver.isFinite(pivot)) {
            return false;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
            used++;
        }
        if (used < MIN_VERTICES) {
            // Too little geometry to say the pivot is on it: answered "no", so the caller keeps the
            // piece simulated rather than dropping it on a measurement that could not be made.
            return false;
        }
        return pivot.x >= minX - PIVOT_ON_GEOMETRY_SLACK && pivot.x <= maxX + PIVOT_ON_GEOMETRY_SLACK
                && pivot.y >= minY - PIVOT_ON_GEOMETRY_SLACK && pivot.y <= maxY + PIVOT_ON_GEOMETRY_SLACK
                && pivot.z >= minZ - PIVOT_ON_GEOMETRY_SLACK && pivot.z <= maxZ + PIVOT_ON_GEOMETRY_SLACK;
    }

    /**
     * How far outside its own geometry a pivot sits, in blocks: the distance from the pivot to the
     * bounding box of that geometry, 0 when the pivot is inside it. Reported by the rule's log line,
     * because "the pivot is off the piece" and "the pivot is a whole limb off the piece" are the same
     * decision and very different pictures.
     */
    static double pivotGapFromGeometry(List<Vector3f> vertices, Vector3f pivot) {
        if (vertices == null || pivot == null || !YsmDynamicBoneSolver.isFinite(pivot)) {
            return Double.NaN;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
            used++;
        }
        if (used < MIN_VERTICES) {
            return Double.NaN;
        }
        double dx = Math.max(0.0D, Math.max(minX - pivot.x, pivot.x - maxX));
        double dy = Math.max(0.0D, Math.max(minY - pivot.y, pivot.y - maxY));
        double dz = Math.max(0.0D, Math.max(minZ - pivot.z, pivot.z - maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The rule as it ships: <b>the piece's geometry rises off a pivot that is not on it, so it cannot
     * be hanging from that pivot</b> - the vertical twin of {@link #wrapsPivot}, and the same defect
     * class, a rotation about a point the piece is not attached to.
     *
     * <p>Two conditions, and each is there because the other alone is wrong on a measured model:
     *
     * <ol>
     *   <li><b>The direction</b> ({@link #risesFromPivot}): the piece's own {@code rest} points upward
     *       by more than {@link #RISES_FROM_PIVOT_MIN_UP_SHARE} of its lever. A piece that hangs from
     *       its pivot has its mass below it.</li>
     *   <li><b>The containment</b> ({@link #pivotOnGeometry}): the pivot is <i>not</i> inside the piece
     *       it draws. A pivot on the piece is a hinge; the piece turns correctly about it whatever
     *       direction it is drawn in.</li>
     * </ol>
     *
     * <h2>Why the direction alone is not enough, and what it costs</h2>
     *
     * <p>Measured on {@code wine_fox/01_taisho_maid} - a model the user has accepted - the direction
     * test alone drops four of her fifty-nine simulated pieces: {@code Tail6}, {@code Tail7} (up-shares
     * 0.999 and 0.913) and {@code Tail5} (0.949), the tip of her tail, and {@code BaseHair} (0.101).
     * All four have their pivot <b>inside</b> their own geometry - gaps of exactly 0.000 blocks - so
     * all four are hinged correctly and merely drawn upward, and freezing them would take the tip off a
     * tail this mod is meant to swing. On the reported {@code EKU(1.0.ysm} the direction test drops
     * thirteen of eighty-nine, of which nine are the same kind of thing: both ears, a hat, hairpins and
     * ornaments, every one of them pivoted on itself. The four pieces it drops whose pivot is off them
     * are exactly the four the defect is made of - {@code RightLegclothes2} (gap 0.388 blocks,
     * displacement 0.322), {@code LeftLowerclothes1} (0.053, 0.199), {@code LeftLowerclothes2}
     * (0.048, 0.115), {@code RightLowerclothes2} (0.048, 0.119) - and those are the two models'
     * numbers that matter: what the rule drops on the reported model is the defect, and what it drops
     * on the accepted one is nothing.
     *
     * <p>Over the corpus's 906 packages (736 parseable) the second condition halves the blast radius:
     * of the 21,607 bones the production classifier would simulate, the direction test alone drops
     * 2,267 in 536 models (10.5 per cent), and the shipped pair drops <b>1,098</b> in <b>349</b> models
     * (5.1 per cent) - every one of them a piece whose pivot is off the geometry it moves, and none of
     * them already dropped by {@link #wrapsPivot}. Of the 1,098, 779 have pivots five centimetres or
     * more outside their own geometry and 78 sit under five millimetres outside it (the residual cost
     * of a containment test at zero, recorded in {@code build/reports/ysm-hang-rule-corpus.md}).
     *
     * <p>A piece this test rejects yields no segment, so its geometry stays rigid on its joint and is
     * drawn exactly where the pose puts it - the same answer the reference implementation gives for a
     * piece whose pivot is not on it. A child's parent link resolves to the nearest <i>surviving</i>
     * segment (see {@code resolveParent}), so a strand whose top piece is dropped hangs off the joint
     * instead of tearing.
     *
     * @param vertices the piece's own drawn geometry, in mesh bind space
     * @param pivot    its authored pivot, in the same space
     * @param rest     {@code centroid - pivot}, the same space
     * @param lever    {@code |rest|}, blocks
     */
    static boolean risesOffPivot(List<Vector3f> vertices, Vector3f pivot, Vector3f rest, float lever) {
        if (vertices == null || vertices.size() < MIN_VERTICES || pivot == null
                || !YsmDynamicBoneSolver.isFinite(pivot)) {
            // No geometry to say the pivot is off: the piece keeps its simulation. "Cannot tell" must
            // never be read as "drop it" - the failure mode of a wrong drop is a piece of a garment
            // that stops moving with nothing in the log to explain it.
            return false;
        }
        return risesFromPivot(rest, lever) && !pivotOnGeometry(vertices, pivot);
    }

    /**
     * Whether a bone's own geometry <b>sits about</b> its pivot instead of hanging from it: a skirt
     * band round the hips, a belt, a scalp - a piece whose pivot is a point on its rim, so that any
     * rotation about that pivot slides the piece off the body it is worn on.
     *
     * <h2>The defect this exists for</h2>
     *
     * <p>{@code X_yiqun1} of the shipped {@code 兽耳酱x1} / {@code NagaU_Kemomimi} is an outer skirt
     * band: 1512 vertex slots spanning x -0.214..0.214, y 0.582..0.972, z -0.196..0.193, with its
     * authored pivot carried through the same {@code T(p) R T(-p)} chain the mesh was baked with to
     * <b>(+0.150, 0.727, -0.025)</b> - on the band's own edge, 0.150 blocks off the centre of
     * (0.000, 0.752, -0.010). {@link #buildSegment} therefore measures {@code rest = centroid -
     * pivot = (-0.150, +0.026, +0.014)}, a direction that is 0.98 horizontal, and the solver's spring
     * - whose target direction is a blend of that rest and the world's downward - holds the band at
     * a permanent ~22 degrees of tilt. The game's own log says so: {@code X_yiqun1 ...
     * axis=(0.59,0.0,0.81), rest=(-0.79,0.17,0.58), moved 0.079 blocks}, stable across frames
     * 240/480/720/960, and it moves the hem about 0.2 blocks.
     *
     * <h2>The rule, and the measurement that shaped it</h2>
     *
     * <p>The isotropy of the directions from the pivot to the vertices - large for a strand, near 1
     * for a shell - was measured first, as the natural reading of "closes around its pivot", and it
     * <b>does not separate this band from the pieces that must keep swinging</b>: {@code X_yiqun1}
     * scores 2.313, the panel below it ({@code X_qunzi1}, which hangs correctly from its own top and
     * must stay simulated) 2.786, the model's hair 7.34, its fringe 5.35 and its ears 2.16. Any
     * threshold that drops the band at 2.313 also drops the ears at 2.160, and the margin to the
     * panel is 0.47. Eight further readings of the same idea (octant coverage, the pivot's place
     * inside the geometry's bounding box, the reference implementation's "reaches back past the
     * pivot", the local and global centre offsets, the offset-covariance eigenvalue ratio) were
     * measured against the same fifteen bones and all of them either missed the band or hit a piece
     * that must swing; the numbers are in the corpus report. What does separate it is the property
     * the reader can see: <b>the piece is a band round the body's own left-right axis, and the pivot
     * is out at its rim</b>. So the rule is:
     *
     * <ol>
     *   <li>the piece's own x span is centred on the model's mirror plane
     *       (x = 0) within {@link #WRAPS_PIVOT_MAX_AXIS_ASYMMETRY} of its half-width, so it is worn
     *       around the trunk rather than being a limb or a decoration that crosses the axis;</li>
     *   <li>the pivot sits at least {@link #WRAPS_PIVOT_MIN_AXIS_OFFSET} of that half-width away from
     *       the axis - it is on the rim, not on the axis; and</li>
     *   <li>the geometry's unit directions from the pivot have a spread below
     *       {@link #WRAPS_PIVOT_MAX_SPREAD}, so the piece surrounds the pivot rather than hanging off
     *       one side of it. This gate is what keeps the rule off the strand-like bones that satisfy
     *       the first two by accident.</li>
     * </ol>
     *
     * <p>Calibrated over the 906-model corpus (730 packages parseable): it drops <b>78 of the 42933
     * bones</b> that carry geometry, tier 0, a joint and no direct mapping (0.18 per cent, in 64 of
     * 724 models), of which <b>28 of the 21121 bones the production classifier would actually
     * simulate</b> (0.13 per cent), in 21 models. On the skirt model it selects {@code X_yiqun1} and
     * its three duplicates and nothing else: {@code X_qunzi1}, {@code X_Hair1}, the fringe, the
     * braid and both ears keep their segments.
     *
     * <p>A bone that fails this test is dropped from the simulation - the same answer the reference
     * implementation gives for a scalp or a band - so its geometry stays rigid on its joint. Its
     * children are unaffected in structure: the parent link resolves to the nearest <i>surviving</i>
     * segment (see {@code resolveParent}), and on the shipped skirt the bones below the band are
     * siblings of it rather than its children, so nothing about them changes.
     *
     * @param vertices the bone's own drawn geometry, in mesh bind space
     * @param pivot    its authored pivot, in the same space
     */
    static boolean wrapsPivot(List<Vector3f> vertices, Vector3f pivot) {
        return bandAxisOffset(vertices, pivot) >= WRAPS_PIVOT_MIN_AXIS_OFFSET
                && directionSpread(vertices, pivot) < WRAPS_PIVOT_MAX_SPREAD;
    }

    /**
     * How far the pivot sits from the body's left-right axis (x = 0), as a share of the half-width of
     * the bone's own geometry, for a piece that is <i>centred</i> on that axis. Zero for a piece that
     * is not centred on the axis at all, or that has no usable width - the safe answer, because it
     * means "this is not a band round the body".
     *
     * <p>Measured in the mesh's bind space, whose x is the model's own x (the writer bakes the model
     * space and scales it, x by {@code width_scale}), so x = 0 is the model's mirror plane.
     */
    static float bandAxisOffset(List<Vector3f> vertices, Vector3f pivot) {
        if (vertices == null || pivot == null || vertices.size() < MIN_VERTICES) {
            return 0.0F;
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            minX = Math.min(minX, vertex.x);
            maxX = Math.max(maxX, vertex.x);
            used++;
        }
        float halfWidth = (maxX - minX) * 0.5F;
        if (used < MIN_VERTICES || !(halfWidth > 1.0E-5F)) {
            return 0.0F;
        }
        if (Math.abs(minX + maxX) > halfWidth * WRAPS_PIVOT_MAX_AXIS_ASYMMETRY) {
            return 0.0F;
        }
        return Math.abs(pivot.x) / halfWidth;
    }

    /**
     * How concentrated the directions from a pivot to a set of vertices are, as the ratio of the
     * largest to the smallest eigenvalue of their unit-direction covariance: <b>1</b> when the
     * geometry surrounds the pivot evenly (a sphere of directions), large when every vertex lies the
     * same way from it (a strand).
     *
     * <p>The unit directions are used, not the offsets, and that is the whole of the measure's
     * independence from size: a two-block braid and a ten-centimetre tuft of the same shape have the
     * same spread, so one threshold serves both. Vertices sitting exactly on the pivot carry no
     * direction and are skipped; a set too small to have a shape at all answers
     * {@link Float#MAX_VALUE}, so "cannot tell" is never read as "wraps".
     *
     * <p>The covariance is symmetric and 3x3, so its eigenvalues come from a cyclic Jacobi rotation
     * - a dozen lines, no library, no iteration to a tolerance the render thread has to pay for -
     * and the ratio is finite unless the directions are exactly coplanar (then the smallest
     * eigenvalue is 0 and the answer is capped at {@link #MAX_SPREAD}).
     */
    static float directionSpread(List<Vector3f> vertices, Vector3f pivot) {
        if (vertices == null || pivot == null || vertices.size() < MIN_VERTICES) {
            return Float.MAX_VALUE;
        }
        double xx = 0.0D;
        double xy = 0.0D;
        double xz = 0.0D;
        double yy = 0.0D;
        double yz = 0.0D;
        double zz = 0.0D;
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            float dx = vertex.x - pivot.x;
            float dy = vertex.y - pivot.y;
            float dz = vertex.z - pivot.z;
            double length = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            if (length < 1.0E-6D) {
                continue;
            }
            double ux = dx / length;
            double uy = dy / length;
            double uz = dz / length;
            xx += ux * ux;
            xy += ux * uy;
            xz += ux * uz;
            yy += uy * uy;
            yz += uy * uz;
            zz += uz * uz;
            used++;
        }
        if (used < MIN_VERTICES) {
            return Float.MAX_VALUE;
        }
        double[][] matrix = {
                {xx / used, xy / used, xz / used},
                {xy / used, yy / used, yz / used},
                {xz / used, yz / used, zz / used}};
        double[] eigenvalues = jacobiEigenvalues(matrix);
        double largest = Math.max(eigenvalues[0], Math.max(eigenvalues[1], eigenvalues[2]));
        double smallest = Math.min(eigenvalues[0], Math.min(eigenvalues[1], eigenvalues[2]));
        if (!(largest > 0.0D)) {
            return Float.MAX_VALUE;
        }
        if (smallest <= 1.0E-9D) {
            return MAX_SPREAD;
        }
        return (float) Math.min(MAX_SPREAD, largest / smallest);
    }

    /** The spread of a set of unit directions that is exactly coplanar, or one that is a single point. */
    private static final float MAX_SPREAD = 1.0E6F;

    /**
     * The eigenvalues of a symmetric 3x3 matrix, by cyclic Jacobi rotations.
     *
     * <p>Eigenvalues only, and returned in place: the axes are not wanted, only the spread's
     * largest-over-smallest ratio, and a decomposition that also had to keep the rotation matrix
     * would be more code and more to get wrong for nothing. Package-private so the calibration sweep
     * can read the same eigenvalues the rule is built from instead of re-deriving them.
     */
    static double[] jacobiEigenvalues(double[][] matrix) {
        return jacobiEigen(matrix, null);
    }

    /**
     * The same rotation with the axes kept: {@code vectors}, when given, receives the columns of the
     * accumulated rotation, so column {@code i} is the eigenvector of the returned eigenvalue
     * {@code i} - the pair {@link #longAxisOf} needs to name the direction a piece is drawn along.
     *
     * <p>The accumulation is the same rotation applied to the identity that the sweep applies to the
     * matrix, and it is written as one more column sweep inside the same loop rather than as a second
     * pass, so the two cannot drift apart.
     */
    static double[] jacobiEigen(double[][] matrix, double[][] vectors) {
        if (vectors != null) {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    vectors[i][j] = i == j ? 1.0D : 0.0D;
                }
            }
        }
        for (int sweep = 0; sweep < 12; sweep++) {
            double off = Math.abs(matrix[0][1]) + Math.abs(matrix[0][2]) + Math.abs(matrix[1][2]);
            if (off < 1.0E-12D) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    double apq = matrix[p][q];
                    if (Math.abs(apq) < 1.0E-15D) {
                        continue;
                    }
                    double theta = (matrix[q][q] - matrix[p][p]) / (2.0D * apq);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0D));
                    if (theta == 0.0D) {
                        t = 1.0D;
                    }
                    double c = 1.0D / Math.sqrt(t * t + 1.0D);
                    double s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = matrix[k][p];
                        double akq = matrix[k][q];
                        matrix[k][p] = c * akp - s * akq;
                        matrix[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = matrix[p][k];
                        double aqk = matrix[q][k];
                        matrix[p][k] = c * apk - s * aqk;
                        matrix[q][k] = s * apk + c * aqk;
                    }
                    if (vectors != null) {
                        for (int k = 0; k < 3; k++) {
                            double vkp = vectors[k][p];
                            double vkq = vectors[k][q];
                            vectors[k][p] = c * vkp - s * vkq;
                            vectors[k][q] = s * vkp + c * vkq;
                        }
                    }
                }
            }
        }
        return new double[]{matrix[0][0], matrix[1][1], matrix[2][2]};
    }

    /** The torso joint id, whose pieces hang where the legs are. */
    private static final int JOINT_TORSO = 7;

    /** Where the segment list came from, for the log. */
    public enum Source {
        /**
         * The model's controller plays a physics animation, and it named these bones.
         *
         * <p>What that buys is the <b>list</b> and the author's <b>spring parameters</b>
         * ({@link Segment#frequency()}, {@link Segment#coefficient()}) - not the motion. Every
         * segment of every model is integrated by the pendulum solver; this source never means
         * "write the author's keyframe angles onto the parts". See
         * {@code YsmMeshSecondaryMotion#apply}.
         */
        AUTHORED,
        /** No such animation: the bones were classified by name. */
        BONE_NAMES,
        /** Nothing to simulate. */
        NONE
    }

    /**
     * One simulated bone.
     *
     * @param boneIndex   index into {@link YSMRuntimeModel#bones}
     * @param boneName    the bone's name, for the log and for user overrides
     * @param joint       the Epic Fight joint whose pose carries this part
     * @param bindPivot   the pivot in model bind space
     * @param bindAnchor  the point the piece is <b>held</b> by, model bind space, and the point its
     *                    delta actually rotates about - the centre of its contact patch with the
     *                    geometry it rests on, see {@link #contactAnchor}. Equal to
     *                    {@code bindPivot} for a piece whose pivot is already where it is held.
     * @param bindRest    pivot to centre of mass, model bind space; its length is the lever
     * @param lever       {@code |bindRest|}, blocks. The moment arm.
     * @param radius      collision radius, blocks
     * @param mass        relative mass. A proxy from the geometry's vertex count (the model
     *                    package carries no density), used only by the drag term, where it
     *                    decides how readily a piece is blown about - which is the one
     *                    place the difference between a thin braid and a broad skirt panel
     *                    has to show up.
     * @param frequency   spring frequency, Hz: the author's {@code ysm.second_order}
     *                    argument when there is one, the config's stiffness otherwise
     * @param coefficient damping ratio 0..1, likewise the author's or the config's
     * @param maxAngle    how far this segment may bend from its rest direction, radians
     * @param parent      the nearest simulated ancestor bone's segment index, or -1; the
     *                    segment's delta is composed under its parent's so a piece cannot
     *                    tear apart at a joint
     * @param parts       the mesh part ordinals carrying this bone's geometry
     * @param authored    true when the model itself declared this bone as physics-driven
     * @param neighbours  the segments sewn to this one - pivots near each other, hanging the same
     *                    way - which the simulation pulls this one toward so a garment moves as
     *                    one garment rather than as its separate panels. See
     *                    {@code YsmMeshSecondaryMotion#relaxTowardsNeighbours}.
     */
    public record Segment(int boneIndex, String boneName, int joint, Vector3f bindPivot,
                          Vector3f bindAnchor, Vector3f bindRest, float lever, float radius, float mass,
                          float frequency, float coefficient, float maxAngle, int parent,
                          int[] parts, boolean authored, int[] neighbours,
                          Category category) {

        /**
         * The same segment without a family: the name is asked instead.
         *
         * <p>For callers that build a segment by hand - a test that wants one piece with a given lever
         * should not have to invent a skeleton for it. The frame path always goes through
         * {@link YsmPhysicsParts#build}, which fills the family from the container rule, so nothing a
         * player sees depends on this convenience.
         *
         * <p>The hinge defaults to the bind pivot, which is the identity these callers are written
         * against: a segment built by hand turns about the point it was given. The frame path uses the
         * canonical constructor and passes the {@link #bindAnchor()} it measured.
         */
        public Segment(int boneIndex, String boneName, int joint, Vector3f bindPivot,
                       Vector3f bindRest, float lever, float radius, float mass,
                       float frequency, float coefficient, float maxAngle, int parent,
                       int[] parts, boolean authored, int[] neighbours) {
            this(boneIndex, boneName, joint, bindPivot, bindPivot, bindRest, lever, radius, mass,
                    frequency, coefficient, maxAngle, parent, parts, authored, neighbours,
                    categoryOf(boneName));
        }

        /**
         * How much this piece's spring follows the world's downward direction rather than the posed
         * rest direction, 0..1 - the solver's {@code verticalFollow}. Read live, so the config's own
         * scaling applies without rebuilding the classification.
         */
        public float verticalFollow() {
            return category.weight * (float) YsmPhysicsTuning.gravityFollowScale();
        }
    }

    /**
     * How far a piece's spring leaves the pose for the world's vertical, per kind of piece.
     *
     * <p>These are the whole of the classification's answer to "hair should droop but a skirt
     * should hang". The mechanism is one: the spring's target direction is a blend of the posed rest
     * direction and the world's downward direction (see {@code YsmDynamicBoneSolver#update}), and the
     * weight is how much of the second it contains. What the weight buys is stated exactly by the
     * solver's balance: a piece settles at the target as the spring gets stiff, and the target is at
     * {@code atan2(b sin a, (1-b) + b cos a)} from the rest direction for a body leaning {@code a},
     * so the angle the piece keeps from the world's vertical settles to roughly {@code (1 - b) a} -
     * the fraction of the body's lean the piece declines to follow.
     *
     * <ul>
     *   <li><b>{@link Category#CLOTH} (0.92).</b> Cloth is expected to hang toward the ground: at
     *       sixty degrees of lean the piece then sits 8 per cent of that - 4.8 degrees - off
     *       vertical, inside the ten degrees the report asks for, with margin for the per-lever
     *       gravity share (the measurement is in {@code tmp_verify/T9_findings.md}). Raising it to 1
     *       would be 0 degrees with no margin at all and would make the pieces indifferent to the
     *       pose, so the last 8 per cent is kept deliberately: a garment that answers the animation
     *       slightly is what makes it read as worn rather than as a second, independent object.</li>
     *   <li><b>{@link Category#HAIR} (0.60).</b> A lock of hair grows out of a skull and has its own
     *       volume, so every strand pointing at the world's vertical is <i>wrong</i> - that is what
     *       wet hair looks like, not hair. At 0.60 a piece keeps about 40 per cent of the body's
     *       lean: on a sixty degree sprint the strands sit some 24 degrees off vertical, which is
     *       what a ponytail actually does behind a runner, while a standing pose is untouched
     *       because a blend of two directions that already agree is the same direction.</li>
     *   <li><b>{@link Category#TAIL} (0.80).</b> A tail is neither: it is a heavy appendage with a
     *       shape of its own, hung from the base of the spine. It reads as gravity-driven - more
     *       than hair, whose root has to follow the head, and less than a skirt panel, which is
     *       attached along a whole waistband and has no shape to keep. At 0.80 it holds about a
     *       fifth of the lean.</li>
     * </ul>
     *
     * <p><b>Hair is matched before cloth and cloth before tail</b>, because the families' names
     * overlap: "ponytail" and "twintail" contain "tail", and a "hairband" contains no hint that
     * would not also catch the hair around it. Ordering is what keeps a ponytail from being treated
     * as a tail.
     */
    /**
     * The families a hanging piece can belong to, and how far each follows the world's vertical.
     *
     * <p>Which family a bone lands in is decided by {@link #classifyBone}, and the record carries the
     * answer rather than the bone's name, so the decision is made once per model at classification
     * time and cannot drift between frames.
     */
    enum Category {
        /** Skirts, dresses, capes and anything else worn: hangs toward the ground. */
        CLOTH(0.92F),
        /** Tails, braids and tufts that are part of the body. */
        TAIL(0.80F),
        /** Hair, which grows out of the skull and keeps its volume. */
        HAIR(0.60F),
        /** Nothing recognisable: fall back to the piece following its own pose. */
        UNKNOWN(0.0F);

        final float weight;

        Category(float weight) {
            this.weight = weight;
        }
    }

    /**
     * The name fragments of each family, matched against the same normalized name the rest of the
     * classifier uses, in the order {@link Category}'s own comment explains.
     */
    private static final String[] HAIR_HINTS = {
            "hair", "bangs", "fringe", "ahoge", "mop"
    };

    private static final String[] CLOTH_HINTS = {
            "skirt", "dress", "qun", "cloth", "hem", "coat", "robe", "kimono",
            "cape", "cloak", "mantle", "scarf", "sash", "belt", "apron", "sleeve", "ribbon"
    };

    private static final String[] TAIL_HINTS = {
            "tail", "braid", "tassel", "pendant", "plume", "feather"
    };

    /**
     * Which family a bone belongs to, read from its own name <i>or from the container it hangs in</i>.
     *
     * <h2>Why the container, and not more names</h2>
     *
     * <p>The name route alone failed on the first model a user ran it against, and it failed in the
     * worst way: the hairdo and the tail were classified correctly while every one of the skirt's
     * twenty-four panels came out {@code follow=0.00}, so "cloth hangs toward the ground" was never
     * applied to any cloth on that model. The panels are called {@code FM}, {@code FL1},
     * {@code RB3} - short labels for front-middle, front-left, right-back and so on - and no
     * vocabulary of English cloth words will ever cover the next model's labels either. What those
     * panels <i>do</i> say is where they hang: {@code FM <- FrontClothe <- clothe <- UpBody},
     * {@code RB3 <- RB2 <- RB <- RightClothe}. The author has already stated, structurally and in a
     * form that survives any naming scheme, "this bone is part of the clothing".
     *
     * <p>So the rule is: a bone's own name first, and then the name of the container it hangs inside.
     * The chain is walked to a fixed depth rather than to the root, and the depth is the whole
     * precision of the rule. A container delimits a <b>region</b> - everything under
     * {@code FrontClothe} is that panel - so the two links a real rig uses
     * ({@code FM}, {@code FM1}, {@code FM2} under {@code FrontClothe}) are exactly what is wanted,
     * while a hair bone twelve links up from {@code UpBody} shares no ancestor with a garment that
     * means anything. Taking the nearest named ancestor instead of a fixed depth would be worse than
     * either: a skirt's panels hang off the same trunk the legs and the tail do, so "nearest named
     * ancestor" eventually reaches something that reads as cloth and would turn a leg into a skirt.
     *
     * <p>The failure direction is unchanged and still deliberate: unrecognised means the pose decides
     * (weight 0), never a guess, because a body part handed to gravity is visible while a piece that
     * does not droop is merely unimproved.
     *
     * @param model    the model whose bone table gives the parent links
     * @param boneIndex the bone to classify
     */
    static Category classifyBone(YSMRuntimeModel model, int boneIndex) {
        return model == null ? Category.UNKNOWN : classifyBone(model.bones, boneIndex);
    }

    /**
     * The same, over a bone table rather than a whole model.
     *
     * <p>Both entry points exist because both callers have exactly one of the two: a rebuilt model
     * when the segments are built, and a bare table in the tests that classify a model's bones
     * without a mesh - which is the shape the acceptance fixtures already use.
     */
    static Category classifyBone(YSMRuntimeModel.BoneRt[] bones, int boneIndex) {
        if (bones == null || boneIndex < 0 || boneIndex >= bones.length) {
            return Category.UNKNOWN;
        }
        String name = bones[boneIndex] == null ? null : bones[boneIndex].name;
        Category own = categoryOf(name);
        if (own != Category.UNKNOWN) {
            return own;
        }
        // Walk up to the first ancestor that reads as ANY family, which is the region this bone hangs
        // inside. The stop is the rule's whole precision: a named region ends the question, so a
        // garment two or three links up is reached while the trunk that every region shares - the
        // body, the head - never is. Reading every ancestor instead of stopping at the first named
        // one would make "cloth" a property of the whole skeleton, because eventually every bone's
        // chain reaches UpBody.
        int ancestor = parentOfBone(bones, boneIndex);
        for (int link = 0; link < CONTAINER_LOOKUP_LINKS && ancestor >= 0; link++) {
            Category container = categoryOf(bones[ancestor].name);
            if (container != Category.UNKNOWN) {
                return container;
            }
            ancestor = parentOfBone(bones, ancestor);
        }
        return Category.UNKNOWN;
    }

    /**
     * How many links up the container rule reads before giving up.
     *
     * <p>Eight, and it is a guard rather than the rule's precision. Reaching the end of a chain costs
     * nothing - what a bone hangs <i>inside</i> is a fact about the model - so a deep panel is
     * classified by its container however many layers the author drew. A limit of three would
     * classify the first three layers of a four-layer panel and leave the deepest bone on the pose,
     * which is a panel that moves from the waist down to the knee and is rigid below it; the model
     * this was fixed against draws three layers, but nothing in the format stops the next one drawing
     * five, and a rule that has to be re-tuned per model is the name table again in another costume.
     *
     * <p>The guard itself is against a cyclic or absurdly deep parent table, which is model data read
     * from a file. It is not a modelling decision and should not be read as one.
     */
    private static final int CONTAINER_LOOKUP_LINKS = 8;

    /** The parent index of a bone, or -1 when it has none or the link is not usable. */
    private static int parentOfBone(YSMRuntimeModel.BoneRt[] bones, int boneIndex) {
        int parent = bones[boneIndex] == null ? -1 : bones[boneIndex].parent;
        return parent >= 0 && parent < bones.length && parent != boneIndex ? parent : -1;
    }

    /**
     * Which family a bone's <i>own name</i> reads as.
     *
     * <p>Name-driven, and only ever a fallback for the structural rule in {@link #classifyBone} -
     * which is the order that matters: the panels this was written for carry names no vocabulary
     * covers, and a name table is exactly what the model authors keep outrunning. Its role is the
     * bones whose name is the whole statement, a bone called {@code LongHair} or {@code Skirt} on a
     * rig with no containers at all.
     *
     * <p>A name it does not recognise is {@link Category#UNKNOWN}, never a guess: a body part handed
     * to gravity is visible, while a piece that does not droop is merely unimproved.
     */
    static Category categoryOf(String boneName) {
        if (boneName == null || boneName.isEmpty()) {
            return Category.UNKNOWN;
        }
        String normalized = YSMJointMapper.normalize(boneName);
        if (normalized.isEmpty()) {
            return Category.UNKNOWN;
        }
        if (containsAny(normalized, HAIR_HINTS)) {
            return Category.HAIR;
        }
        if (containsAny(normalized, CLOTH_HINTS)) {
            return Category.CLOTH;
        }
        if (containsAny(normalized, TAIL_HINTS)) {
            return Category.TAIL;
        }
        return Category.UNKNOWN;
    }

    private static boolean containsAny(String normalized, String[] hints) {
        for (String hint : hints) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** The segment list of one model. */
    public record Model(Segment[] segments, Source source, int droppedPieces) {

        public boolean isEmpty() {
            return segments.length == 0;
        }

        /** The names, for the log. */
        public String names() {
            StringBuilder builder = new StringBuilder();
            for (Segment segment : segments) {
                builder.append(builder.length() == 0 ? "" : ", ").append(segment.boneName());
            }
            return builder.toString();
        }
    }

    /** A segment before its parent link is known in segment indices. */
    private record Draft(int boneIndex, String boneName, int joint, Vector3f bindPivot,
                         Vector3f bindAnchor, Vector3f bindRest, float lever, float radius, float mass,
                         float frequency, float coefficient, float maxAngle, int parentBone,
                         int[] parts, boolean authored) {}

    private static final Model EMPTY = new Model(new Segment[0], Source.NONE, 0);

    private YsmPhysicsParts() {}

    /**
     * Build the segment list.
     *
     * @param model             the runtime model (bone table + the author's physics parts)
     * @param mesh              the converted mesh being drawn, used for levers and radii
     * @param fallbackFrequency spring frequency when the model authored none
     * @param fallbackDamping   damping ratio when the model authored none
     * @param maxAngle          swing limit for a strand, radians
     * @param maxAngleRoot      swing limit for the top of a piece, radians
     */
    public static Model build(YSMRuntimeModel model, YSMMesh mesh,
                              float fallbackFrequency, float fallbackDamping,
                              float maxAngle, float maxAngleRoot) {
        if (model == null || model.bones == null || model.bones.length == 0) {
            return EMPTY;
        }
        Map<Integer, List<Vector3f>> vertices = verticesByBone(mesh, model);
        Map<Integer, float[]> geometry = geometryByBone(vertices);
        Map<Integer, int[]> partOrdinals = partOrdinalsByBone(mesh, model);
        // What a segment may be measured from is its own geometry, and nothing else - see
        // ownsItsGeometry. The same test is handed to the classifier so that "is this bone a piece
        // of cloth or the container above one" is answered by one rule everywhere.
        IntPredicate ownsGeometry = bone -> ownsItsGeometry(bone, geometry, partOrdinals);

        List<Integer> selected = new ArrayList<>();
        boolean authored = false;
        if (model.physicsParts != null && !model.physicsParts.isEmpty()) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (YsmPhysicsBinding.Part part : model.physicsParts) {
                Integer index = model.boneIndex.get(part.bone());
                if (index != null && index >= 0 && index < model.bones.length && seen.add(index)
                        && ownsGeometry.test(index) && !poseBelongsToEpicFight(model.bones[index])) {
                    selected.add(index);
                }
            }
            // The flag follows the bones that survived the two ownership tests, not the declaration:
            // an author's list of which no bone owns its own geometry says nothing usable about the
            // model, and the honest answer then is the classifier's - which prefers the bone that
            // actually carries the mesh - rather than an empty simulation and a source label
            // claiming the author's own rig. Reported as BONE_NAMES, because that is what it is.
            authored = !selected.isEmpty();
        }
        // The cap is a per-model backstop for a model that declares hundreds of bones, and it is a
        // cap on bones because every bone swings on its own pivot now - counting pieces would bound
        // nothing. It is applied a piece at a time, though, and never inside one: cutting the list
        // at a bone count splits whatever piece straddles the cut, leaving half a panel simulated
        // and half of it rigid. Those two halves answer differently to the same body motion, which
        // is a panel tearing apart down the middle - and it is silent, because the count that was
        // capped is not a number anyone sees. Whole pieces are dropped, and the number is reported.
        //
        // Both sources are capped the same way, and that is a correction: an authored part list is
        // not structureless just because it is flat. On the shipped maid it is twenty-four panels
        // and on the wine fox a tail of seven bones, and a flat cut at the cap puts the cut inside a
        // piece exactly as often as it does on the classified path - which is what "some panels move
        // and the ones beside them do not" looks like. Whole pieces, both ways, one counter.
        int cap = YsmPhysicsTuning.maxChains();
        if (cap <= 0) {
            return new Model(new Segment[0], Source.NONE, 0);
        }
        int[] dropped = {0};
        if (selected.isEmpty()) {
            selected = selectBones(model.bones, ownsGeometry, cap, dropped);
        } else {
            selected = capPieces(model.bones, selected, cap, dropped);
        }
        if (selected.isEmpty()) {
            return new Model(new Segment[0], Source.NONE, 0);
        }

        Map<String, YsmPhysicsBinding.Part> bindings = YsmPhysicsBinding.byName(
                model.physicsParts == null ? List.of() : model.physicsParts);

        List<Draft> drafts = new ArrayList<>(selected.size());
        for (int boneIndex : selected) {
            Draft draft = buildSegment(model, boneIndex, vertices, geometry, partOrdinals, bindings,
                    ownsGeometry, fallbackFrequency, fallbackDamping);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        if (drafts.isEmpty()) {
            return new Model(new Segment[0], Source.NONE, dropped[0]);
        }

        // Parent links are resolved here, once every draft exists: a bone that produced no
        // segment must not be referenced, and the answer differs depending on which of its
        // ancestors survived.
        Map<Integer, Integer> segmentOfBone = new HashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            segmentOfBone.put(drafts.get(i).boneIndex(), i);
        }
        Segment[] segments = new Segment[drafts.size()];
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            int parent = resolveParent(draft.boneIndex(), draft.parentBone(), model.bones,
                    segmentOfBone);
            // The swing limit is decided HERE, from the parent link that actually survived, and
            // that timing is the whole point. It used to be decided while the draft was built, from
            // the nearest ancestor that *could* be a segment - which is a different bone whenever
            // the parent is later dropped for having no lever of its own. Every panel top of a
            // panel skirt is exactly that case: it hangs off a container bone ("FM", "FrontClothe")
            // that carries no usable geometry, so the draft was given the loose segment limit and
            // then reported itself as a root. The shipped log says so in as many words -
            // "FM1(name,root,... 41.2deg/60.0chain)" - a root running at the segment limit, which
            // is three times the firm limit a base is supposed to have.
            float limit = swingLimit(parent >= 0, draft.joint() == JOINT_TORSO, maxAngle, maxAngleRoot);
            segments[i] = new Segment(draft.boneIndex(), draft.boneName(), draft.joint(),
                    draft.bindPivot(), draft.bindAnchor(), draft.bindRest(), draft.lever(),
                    draft.radius(), draft.mass(),
                    draft.frequency(), draft.coefficient(), limit, parent,
                    draft.parts(), draft.authored(), NO_NEIGHBOURS,
                    classifyBone(model, draft.boneIndex()));
        }
        wireNeighbours(segments);
        return new Model(segments, authored ? Source.AUTHORED : Source.BONE_NAMES, dropped[0]);
    }

    /** No neighbours, for a segment whose piece is the only thing it hangs with. */
    private static final int[] NO_NEIGHBOURS = new int[0];

    /**
     * Model/bone keys already reported as wrapping their pivot, so a model reload does not repeat the
     * line. Cleared with the rest of the per-mesh state by {@link #clear()}.
     */
    private static final java.util.Set<String> WRAPPED_PIVOT_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Model/bone keys already reported as rising from their pivot (see {@link #risesFromPivot}), so a
     * model reload does not repeat the line. Cleared with the rest of the per-mesh state by
     * {@link #clear()}.
     */
    private static final java.util.Set<String> RISES_FROM_PIVOT_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Apply the cap to a declared part list, a whole piece at a time.
     *
     * <p>An authored part list is flat - a list of bone names - but the bones in it are not
     * structureless: they hang off one another in the skeleton, so the same subtree rule that
     * {@link #selectBones} applies can be applied here. What it buys is that a piece is never cut:
     * the alternative, taking the first {@code cap} names, puts the cut inside whatever piece
     * straddles it, and the bone below the cut then hangs off a bone that is not simulated any more -
     * half a panel swinging, half of it rigid, with nothing in the log to say which half or why.
     *
     * <p>The first piece is always kept, even when it alone exceeds the cap. That is the same rule
     * {@link #selectBones} already had, and it is there so a small cap on a model with one long
     * piece simulates something rather than nothing - an empty selection looks exactly like a model
     * with no physics at all.
     *
     * @param bones      the model's bone table, for the parent links
     * @param declared   the bones the author declared, in the model's own order
     * @param cap        the most bones that may be simulated
     * @param droppedOut receives the number of whole pieces left out, for the log
     */
    static List<Integer> capPieces(YSMRuntimeModel.BoneRt[] bones, List<Integer> declared,
                                   int cap, int[] droppedOut) {
        List<Integer> kept = new ArrayList<>(declared.size());
        boolean first = true;
        for (List<Integer> piece : piecesOf(bones, declared)) {
            if (first || kept.size() + piece.size() <= cap) {
                kept.addAll(piece);
                first = false;
            } else if (droppedOut != null && droppedOut.length > 0) {
                // Counted as pieces, not bones: "the cap left out four panels" is a sentence about
                // a garment, and "the cap left out twelve bones" is a number nobody can act on.
                droppedOut[0]++;
            }
        }
        return kept;
    }

    /**
     * Group a declared bone list into the pieces it is made of: the connected groups of declared
     * bones under the skeleton, in the order the list names them.
     *
     * <p>Connectivity is by ancestry within the list: walking up from a bone to the first declared
     * ancestor it has. Bones that share such an ancestor are one piece, which is what a strand in
     * three bones ({@code RB -> RB2 -> RB3}) is, and what a fan of panels under one bracket is not -
     * each panel reaches the bracket only through a bone the author did not declare, so each panel
     * is its own group and the cap can drop one without touching its neighbour.
     *
     * <p>The walk is bounded, and it stops at a parent the table does not contain, because a damaged
     * or truncated table can name one and this runs on the render thread's path to a model's first
     * frame.
     */
    static List<List<Integer>> piecesOf(YSMRuntimeModel.BoneRt[] bones, List<Integer> declared) {
        List<List<Integer>> pieces = new ArrayList<>();
        if (bones == null || declared == null || declared.isEmpty()) {
            return pieces;
        }
        Map<Integer, Integer> slotOfBone = new HashMap<>();
        for (int slot = 0; slot < declared.size(); slot++) {
            slotOfBone.putIfAbsent(declared.get(slot), slot);
        }
        int[] group = new int[declared.size()];
        for (int slot = 0; slot < group.length; slot++) {
            group[slot] = slot;
        }
        for (int slot = 0; slot < declared.size(); slot++) {
            int bone = declared.get(slot);
            if (bone < 0 || bone >= bones.length || bones[bone] == null) {
                continue;
            }
            int guard = 0;
            for (int parent = bones[bone].parent;
                 parent >= 0 && parent < bones.length && bones[parent] != null
                         && guard++ <= bones.length;
                 parent = bones[parent].parent) {
                Integer parentSlot = slotOfBone.get(parent);
                if (parentSlot != null) {
                    join(group, slot, parentSlot);
                    break;
                }
            }
        }
        Map<Integer, List<Integer>> byRoot = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < group.length; slot++) {
            byRoot.computeIfAbsent(find(group, slot), key -> new ArrayList<>()).add(declared.get(slot));
        }
        pieces.addAll(byRoot.values());
        return pieces;
    }

    /** Union by root: {@code find} chases to the representative, {@code join} points one at the other. */
    private static void join(int[] group, int a, int b) {
        int rootA = find(group, a);
        int rootB = find(group, b);
        if (rootA != rootB) {
            group[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }

    private static int find(int[] group, int slot) {
        int current = slot;
        int guard = 0;
        while (group[current] != current && guard++ <= group.length) {
            current = group[current];
        }
        return current;
    }

    /**
     * Whether the geometry a segment would be measured from is the bone's <b>own</b>.
     *
     * <p>Two answers have to agree, and they come from two walks of the mesh:
     *
     * <ul>
     *   <li>The mesh carries a part named after this bone ({@code y/<name>}), so the geometry on
     *       screen is drawn by this bone's own transform and by no other. A bone with no part of its
     *       own has nothing for a segment to write, and the mesh writer emits parts only for bones
     *       that carry quads - so a bone can be named by the author's physics animation and still
     *       have no geometry at all.</li>
     *   <li>That part holds vertices that survive the default-form visibility pass, so the bone's
     *       centre of mass and thickness are measured on the geometry that is actually drawn rather
     *       than on a variant body or a hidden accessory.</li>
     * </ul>
     *
     * <p>A bone that fails either test is not a piece of anything: the lever, the rest direction and
     * the collision radius have to be measured from the piece the segment moves. A bone given a
     * <i>descendant's</i> centroid - which is what this used to do - swings on a lever that points
     * at someone else's geometry, writes its delta to no part at all, and still spends the piece's
     * chain allowance, so the strands below it are curtailed by a bone that cannot move anything.
     */
    static boolean ownsItsGeometry(int boneIndex, Map<Integer, float[]> geometry,
                                   Map<Integer, int[]> partOrdinals) {
        int[] parts = partOrdinals == null ? null : partOrdinals.get(boneIndex);
        if (parts == null || parts.length == 0) {
            return false;
        }
        float[] entry = geometry == null ? null : geometry.get(boneIndex);
        return entry != null && entry[3] >= MIN_VERTICES;
    }

    /**
     * Whether Epic Fight owns this bone's pose, which leaves nothing of it for us to swing.
     *
     * <p>A directly mapped bone is one whose own name is a body part - {@code UpBody},
     * {@code RightArm}, {@code Head} - and Epic Fight animates it directly. Writing a second
     * rotation on top of a pose someone else is driving is not secondary motion, it is a body part
     * being turned twice: the coat sleeve is drawn by the arm joint, and rotating it again about its
     * own pivot moves the arm. The name classifier has always excluded mapped bones
     * ({@code YsmPhysicsChains#build}); this is the same rule stated where a <i>declared</i> part
     * list is filtered, so an author's animation naming a mapped bone cannot smuggle one in.
     */
    static boolean poseBelongsToEpicFight(YSMRuntimeModel.BoneRt bone) {
        return bone == null || bone.mapped;
    }

    /**
     * How near two panels' pivots must be, in bind space blocks, to count as sewn together.
     *
     * <p>A few centimetres: the panels of a skirt are modelled side by side around the waist, so
     * their tops are close together while their hems spread apart. Coupling on the pivot rather
     * than on the geometry is deliberate - what has to move together is where the fabric is
     * attached, not where it happens to hang.
     */
    private static final float NEIGHBOUR_RADIUS = 0.18F;

    /**
     * How alike two panels' rest directions must be to count as sewn together.
     *
     * <p>Roughly forty degrees. A skirt's panels fan out around the waist, so neighbours differ by
     * a modest angle; the front and back panels differ by much more and should not be coupled, or
     * the whole skirt would average itself into a single rigid cone.
     */
    private static final float NEIGHBOUR_MAX_ANGLE = 0.7F;

    /** At most this many neighbours each, so the relaxation stays a short fixed loop. */
    private static final int NEIGHBOUR_COUNT = 4;

    /**
     * Records which segments are sewn to which, from the model's own bind geometry.
     *
     * <p>This is what makes a panel skirt behave like a skirt: without it every panel is an
     * independent pendulum, and the shipped numbers show what that produces - a 20.0 degree front
     * panel beside a 5.1 degree one, which on screen is not a curve but a gap. See
     * {@code YsmMeshSecondaryMotion#relaxTowardsNeighbours} for what is done with the links.
     */
    private static void wireNeighbours(Segment[] segments) {
        for (int i = 0; i < segments.length; i++) {
            // Only the nearest few, chosen by distance, so the graph is local and bounded.
            int[] best = new int[NEIGHBOUR_COUNT];
            float[] bestDistance = new float[NEIGHBOUR_COUNT];
            java.util.Arrays.fill(best, -1);
            java.util.Arrays.fill(bestDistance, Float.MAX_VALUE);
            for (int j = 0; j < segments.length; j++) {
                if (i == j || segments[j].parent() == i || segments[i].parent() == j) {
                    // A parent and child are already tied by the chain composition; coupling them
                    // again would fight the budget that keeps a chain inside its limit.
                    continue;
                }
                float distance = segments[i].bindPivot().distance(segments[j].bindPivot());
                if (distance > NEIGHBOUR_RADIUS) {
                    continue;
                }
                float angle = YsmDynamicBoneSolver.angleBetween(
                        segments[i].bindRest(), segments[j].bindRest());
                if (angle > NEIGHBOUR_MAX_ANGLE) {
                    continue;
                }
                for (int slot = 0; slot < NEIGHBOUR_COUNT; slot++) {
                    if (distance < bestDistance[slot]) {
                        for (int shift = NEIGHBOUR_COUNT - 1; shift > slot; shift--) {
                            best[shift] = best[shift - 1];
                            bestDistance[shift] = bestDistance[shift - 1];
                        }
                        best[slot] = j;
                        bestDistance[slot] = distance;
                        break;
                    }
                }
            }
            int count = 0;
            for (int slot = 0; slot < NEIGHBOUR_COUNT; slot++) {
                if (best[slot] >= 0) {
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }
            int[] neighbours = new int[count];
            System.arraycopy(best, 0, neighbours, 0, count);
            segments[i] = new Segment(segments[i].boneIndex(), segments[i].boneName(),
                    segments[i].joint(), segments[i].bindPivot(), segments[i].bindAnchor(),
                    segments[i].bindRest(),
                    segments[i].lever(), segments[i].radius(), segments[i].mass(),
                    segments[i].frequency(), segments[i].coefficient(), segments[i].maxAngle(),
                    segments[i].parent(), segments[i].parts(), segments[i].authored(), neighbours,
                    segments[i].category());
        }
    }

    /**
     * Resolve a draft's parent to a surviving segment index.
     *
     * <p>Falls back along the skeleton when the preferred bone produced no segment, so a
     * strand whose author-declared driver was dropped for having no lever still hangs off
     * whatever is above it rather than becoming a second chain root.
     */
    static int resolveParent(int boneIndex, int preferredParentBone,
                             YSMRuntimeModel.BoneRt[] bones,
                             Map<Integer, Integer> segmentOfBone) {
        Integer self = segmentOfBone.get(boneIndex);
        Integer preferred = segmentOfBone.get(preferredParentBone);
        if (preferred != null && !preferred.equals(self)) {
            // An authored follows link may cross skeleton branches. Keep it when its driver
            // survived the geometric checks; otherwise use the actual bone hierarchy below.
            return preferred;
        }
        if (bones == null || boneIndex < 0 || boneIndex >= bones.length
                || bones[boneIndex] == null) {
            return -1;
        }
        int parent = bones[boneIndex].parent;
        for (int guard = 0; parent >= 0 && parent < bones.length && guard < bones.length;
             guard++) {
            if (parent == boneIndex || bones[parent] == null) {
                break;
            }
            Integer surviving = segmentOfBone.get(parent);
            if (surviving != null && !surviving.equals(self)) {
                return surviving;
            }
            int next = bones[parent].parent;
            if (next == parent) {
                break;
            }
            parent = next;
        }
        return -1;
    }

    /**
     * How far a segment may swing from its rest direction, radians.
     *
     * <p>Extracted and package-private because the decision is easy to get subtly wrong and its
     * failure is invisible: a base held at three times its intended limit does not crash or log
     * anything, it just lets a whole skirt splay. Both of this method's rules were wrong at some
     * point - the firmer root limit was overridden for anything resolving to the torso, and the
     * rule was applied before the parent link was final - so it is pinned by tests.
     *
     * @param hasParent true when the segment hangs off another <b>surviving</b> segment; false
     *                  makes it the base of its piece, which is held to the firm root limit
     * @param torso     whether the segment resolves to the torso, where a panel a knee pushes may
     *                  come further up - a relaxation for strands only, never for a base
     */
    static float swingLimit(boolean hasParent, boolean torso, float maxAngle, float maxAngleRoot) {
        float limit = hasParent ? maxAngle : maxAngleRoot;
        if (hasParent && torso) {
            limit = Math.max(limit, maxAngle);
        }
        return limit;
    }

    /**
     * The least bend a chain's total allowance must give each of its joints, radians.
     *
     * <p>Fifteen degrees. A joint held below this is a joint that reads as still: on the wine fox's
     * tail, sharing one flat sixty degrees over seven bones gave 8.6 degrees each, and a tail that
     * moves 8.6 degrees per bone is a tail nobody sees move - which is the opposite of the defect
     * this whole feature exists for. The floor is what makes the allowance scale with the chain
     * instead of being eaten by it; the total cap below is what keeps a very long chain from
     * splaying.
     */
    static final float MIN_CHAIN_ANGLE_PER_JOINT = (float) Math.toRadians(15.0D);

    /**
     * The most a piece may bend as a whole, radians, however many joints it has.
     *
     * <p>One hundred and twenty degrees. A single piece of cloth or a ponytail is allowed sixty by
     * the per-joint configuration; a chain of bones is not one piece of cloth - its joints add up,
     * and the shape a viewer reads is the <i>sum</i> of them - so a chain is allowed more in total
     * than any one of its joints. This is the ceiling on that: past it the total stops growing, and
     * a piece long enough to reach it does so by giving each joint the floor above rather than by
     * bending further and further at the end.
     */
    static final float MAX_CHAIN_ANGLE_TOTAL = (float) Math.toRadians(120.0D);

    /**
     * How far a piece made of {@code joints} bones may bend as a whole, radians.
     *
     * <pre>
     *   limit = min(maxAngle x joints, max(MIN_PER_JOINT x joints, MAX_CHAIN_TOTAL))
     * </pre>
     *
     * <h2>Why the piece's total is not the per-joint limit</h2>
     *
     * <p>The configuration's {@code secondaryMotionMaxAngleDegrees} (sixty) is what <b>one</b> piece
     * of cloth may swing. Treating it as the whole chain's total - which is what this did first -
     * makes a chain stiffer the longer it is: seven bones sharing sixty degrees get 8.6 each, so the
     * tail barely moves, and the physical intuition is the other way round (a real tail bends more,
     * not less, the further from the root you look).
     *
     * <p>So the total grows with the joint count, with two bounds on it:
     *
     * <ul>
     *   <li><b>{@link #MIN_CHAIN_ANGLE_PER_JOINT} per joint</b>, so no chain is ever shared out into
     *       invisibility. This is what binds once a chain is long enough that the cap below would
     *       give it less than fifteen degrees a joint.</li>
     *   <li><b>{@link #MAX_CHAIN_ANGLE_TOTAL} in all</b>, so a very long chain does not simply
     *       multiply: without it, twelve joints of sixty would be a seven-hundred-degree allowance,
     *       which is not a piece of cloth bending, it is a piece of cloth folding through itself.</li>
     * </ul>
     *
     * <p>The outer {@code min} is the configuration's own word on the matter: a piece may never be
     * allowed more in total than its joints would each be allowed separately. That is what keeps a
     * model configured with a small {@code secondaryMotionMaxAngleDegrees} - ten degrees, say -
     * from having its long chains handed a large allowance that its per-joint limits would never
     * grant.
     *
     * <p>Numbers under the shipped configuration (per-joint 60, root 20, cap 120, floor 15):
     *
     * <pre>
     *   1 joint  min(60,  max(15, 120)) = 60
     *   2 joints min(120, max(30, 120)) = 120    - root 20, hem 60: the shipped panel, unchanged
     *   3 joints min(180, max(45, 120)) = 120    - root 20, then 50 and 50
     *   7 joints min(420, max(105, 120)) = 120   - 60/7 plus the floor: 17.1 each, not 8.6
     *   9 joints min(540, max(135, 120)) = 135   - the floor has taken over from the cap
     * </pre>
     *
     * @param joints           the number of <b>resolved segments</b> in the piece - the bones that
     *                         are actually simulated, not the bones in the skeleton's subtree. A
     *                         decoration hanging off the garment that is not a segment must not
     *                         raise the allowance of the chain it hangs near.
     * @param maxAnglePerJoint the configuration's per-joint limit, radians
     */
    static float chainLimitFor(int joints, float maxAnglePerJoint) {
        if (joints <= 0 || !Float.isFinite(maxAnglePerJoint) || maxAnglePerJoint <= 0.0F) {
            return 0.0F;
        }
        float ownTotal = maxAnglePerJoint * joints;
        float floorTotal = MIN_CHAIN_ANGLE_PER_JOINT * joints;
        return Math.min(ownTotal, Math.max(floorTotal, MAX_CHAIN_ANGLE_TOTAL));
    }

    /**
     * How far one joint of a chain may turn, given how much of the piece's total bend is already
     * used up by the joints above it and how many joints are left to share the rest.
     *
     * <h2>Two ceilings, and the mistake was letting one of them starve the chain</h2>
     *
     * <p>{@code ownLimit} is this joint's own allowance - firm at the base of a piece
     * ({@code maxAngleRoot}, 20 degrees in the shipped config) and loose for the strands below it
     * ({@code maxAngle}, 60). {@code chainLimit} is how far the piece <i>as a whole</i> may bend,
     * and it is <b>not</b> the same number: it is the piece's total, scaled by how many joints the
     * piece has ({@link #chainLimitFor}), and it is the number the per-joint allowance is shared out
     * of. Using the per-joint sixty as the piece's total - which is what this did first - made a
     * chain stiffer the longer it was: seven bones sharing sixty degrees get 8.6 each.
     *
     * <p>What it also did was hand each joint everything that was left: {@code min(ownLimit,
     * chainLimit - used)}. On a short piece that is right, and it is why the shipped skirt works. On
     * a long one it is a first-come-first-served queue, and the queue is ordered root first - so the
     * joints near the root eat the whole allowance and every joint below runs out. The wine fox's
     * seven-bone tail is the measured case: at 18.4 degrees a bone the chain reaches 73.6 degrees by
     * its third joint, and from the fourth on {@code max(0, 60 - 73.6)} is <b>zero</b> - the bone is
     * slerped back to no swing at all, on every frame, forever. Four of the tail's seven bones could
     * not move, and no log said why: {@code own=0.0deg, axis=(0,0,0)} looks exactly like a bone that
     * was never classified.
     *
     * <h2>The rule: the piece's bend is shared out, not queued for</h2>
     *
     * <p>What is left of the piece's allowance is divided by the number of joints that still have to
     * bend inside it - this one and every joint below it - and that share is this joint's ceiling,
     * under its own limit:
     *
     * <pre>
     *   allowed = min(ownLimit, max(0, chainLimit - usedByAncestors) / jointsLeft)
     * </pre>
     *
     * <p>Chosen over "bound only this joint's angle relative to its parent" because that rule has no
     * ceiling on the piece at all - seven joints of sixty degrees each is a fan, not a tail - and the
     * requirement is that the <i>shape</i> of the piece stays bounded. Chosen over "scale the whole
     * chain down after the fact" because a second pass over a chain whose deltas are already composed
     * root-first would have to rewrite them, and the composition is what keeps a nested piece from
     * tearing.
     *
     * <p><b>Why a joint can never be frozen by its ancestors.</b> Each joint takes at most
     * {@code remaining / jointsLeft}, so what is left for the joints below is at least
     * {@code remaining x (jointsLeft - 1) / jointsLeft}: a positive quantity, strictly decreasing
     * but never zero, until the last joint of the chain. A joint with anything left below it
     * therefore always has an allowance above zero. On top of that the share is floored at
     * {@link #MIN_CHAIN_ANGLE_PER_JOINT}, so the promise does not depend on the caller having
     * handed in a total worthy of the chain: a joint is never allowed less than the floor (unless
     * its own limit is smaller, which the {@code min} keeps), however small the total it is sharing
     * or however much of it the joints above have spent. The sum of the joints' angles is bounded by
     * {@code chainLimit} whenever the total was scaled to the chain (see {@link #chainLimitFor}, and
     * that is the only caller the frame path has); the floor is what stops a caller that passes a
     * flat per-joint limit as the total - which is what this seam's earlier version meant by that
     * argument - from starving the far end of a long chain.
     *
     * <h2>What it does to the shipped configuration (base 20, strand 60, piece limit from
     * {@link #chainLimitFor})</h2>
     *
     * <ul>
     *   <li><b>A one-joint piece:</b> limit 60, so {@code min(20, 60/1)} = 20 - the firm base itself,
     *       and the scaling never hands a lone joint more than its own limit.</li>
     *   <li><b>A two-joint piece</b> - a panel top with one strand, and most of the maid's garment:
     *       limit 120, so the base takes {@code min(20, 60)} = 20 exactly as it always did, and the
     *       strand is capped by its own 60 rather than by the chain. The strand's allowance grew from
     *       40 to 60, and that is invisible on this model: its panels were measured at 20.0, 12.1,
     *       5.1 and 4.6 degrees, all far under 40, so nothing that used to be clamped is not clamped
     *       now. The base - the number that decides how the skirt hangs - is bit for bit what it
     *       was.</li>
     *   <li><b>A three-joint piece:</b> limit 120, so 20 then 50 then 50, and the hem leads the waist
     *       by design rather than by accident.</li>
     *   <li><b>A seven-joint tail:</b> limit 120, so 17.1 degrees a bone - above the fifteen-degree
     *       floor, and where the flat sixty-degree version gave 8.6, which is a tail that does not
     *       visibly move. This is the number the change was for.</li>
     * </ul>
     *
     * @param ownLimit         this joint's own allowance, radians
     * @param chainLimit       how far the whole piece may bend from its rest, radians; the piece's
     *                         scaled total in the frame path ({@link #chainLimitFor})
     * @param usedByAncestors  what this joint's ancestors have already taken, radians: the sum of
     *                         their own granted angles, which is what the allowance above is spent
     *                         out of. Deliberately not the composed chain angle - rotations about
     *                         different axes compose to something larger than their sum, so a budget
     *                         measured that way is spent faster than the joints spend it.
     * @param jointsLeft       how many joints of this piece are at or below this one, this joint
     *                         included; at least 1
     */
    static float chainAllowance(float ownLimit, float chainLimit, float usedByAncestors, int jointsLeft) {
        float remaining = Math.max(0.0F, chainLimit - Math.max(0.0F, usedByAncestors));
        float share = Math.max(remaining / Math.max(1, jointsLeft), MIN_CHAIN_ANGLE_PER_JOINT);
        return Math.min(ownLimit, share);
    }

    /**
     * The bones to simulate, out of the model's hanging pieces, under a cap on how many bones may
     * be simulated at once.
     *
     * <p>Each accepted piece is the top of the piece its name identified plus every bone under it
     * that carries geometry - the subtree rather than the top alone, because the dynamics are per
     * bone now and dragging the whole piece around one pivot is precisely the rigid-plank behaviour
     * being replaced.
     *
     * <p>Two rules, and both of them were learned from a garment that came apart:
     *
     * <ol>
     *   <li><b>Every candidate starts a piece, not just the ones the limit rule calls a root.</b>
     *       {@link YsmPhysicsChains#isChainRoot} answers "is the bone above me itself a
     *       hangs-kind of bone", which decides how firmly a piece is held - it is not a statement
     *       about where pieces begin. Gating on it drops every piece whose parent happens to be
     *       named {@code RightClothe}, {@code BackClothe}, {@code Hair} ..., and keeps the ones
     *       whose parent is named {@code RB} or {@code LM2}. On the shipped maid that is exactly
     *       the right-hand and back panels left rigid while the left and front ones swing: half a
     *       skirt moving and half of it bolted down, which reads as the other half having been
     *       dragged off to one side.</li>
     *   <li><b>A bone is simulated once.</b> Nested candidates - {@code RB} and {@code RB2} and
     *       {@code RB3} can all be candidates, since each is asked about its own parent - each
     *       collect the piece they hang in, and the outer one's piece contains the inner one's
     *       bones. Collecting them into separate lists and concatenating them puts the same bone
     *       in the segment list twice; both copies then write their delta to the same mesh part,
     *       and the second write is the one that shows. Deduplicating against what has already
     *       been taken is what keeps the count honest, and the count is what the cap spends.</li>
     * </ol>
     *
     * @param bones           the model's bone table
     * @param carriesGeometry which bones carry mesh, so a container can be told from a panel
     * @param cap             the most bones that may be simulated
     * @param droppedOut      receives the number of pieces left out by the cap, for the log
     */
    static List<Integer> selectBones(YSMRuntimeModel.BoneRt[] bones, IntPredicate carriesGeometry,
                                     int cap, int[] droppedOut) {
        List<Integer> selected = new ArrayList<>();
        List<Integer> piece = new ArrayList<>();
        for (YsmPhysicsChains.Chain chain : YsmPhysicsChains.build(bones, carriesGeometry)) {
            piece.clear();
            collectPiece(bones, chain.boneIndex(), carriesGeometry, piece);
            piece.removeIf(selected::contains);
            if (piece.isEmpty()) {
                continue;
            }
            if (selected.isEmpty() || selected.size() + piece.size() <= cap) {
                selected.addAll(piece);
            } else if (droppedOut != null && droppedOut.length > 0) {
                droppedOut[0]++;
            }
        }
        return withoutBrackets(bones, selected);
    }

    /**
     * Drop the bones that only hold other swinging bones apart.
     *
     * <p>A garment is modelled as a bracket per group - {@code FM} over {@code FM1} and {@code FM2},
     * {@code LB} over {@code LB2} and {@code LB3} - and the bracket is what the author rotates when
     * they want the whole group to move. It is not a piece of cloth with a hang of its own: swinging
     * it as well as the panels under it turns the group twice, once at the bracket and once at the
     * panel, and the two rotations compose into a position nothing was ever meant to be in. On the
     * shipped maid that is exactly what the log showed once every candidate was taken -
     * {@code FL FM FR LB LF LM} listed beside the panels they hold - and the same double rotation is
     * what "the parts are in the wrong place" is.
     *
     * <p>The test is a <i>branch</i>: two or more of this bone's own children are simulated. It is
     * deliberately not "has descendants", which would throw away the top of every chain -
     * {@code RB -> RB2 -> RB3} is one panel in three bones and all three must swing, while
     * {@code FM} over two panels is a bracket and must not. Dropping a bracket is safe for the
     * panels beneath it: each one's parent link already resolves to the nearest simulated ancestor,
     * and a bracket was never simulated.
     */
    private static List<Integer> withoutBrackets(YSMRuntimeModel.BoneRt[] bones, List<Integer> selected) {
        Map<Integer, Integer> childrenOf = new HashMap<>();
        for (int index : selected) {
            int parent = bones[index] == null ? -1 : bones[index].parent;
            if (parent >= 0) {
                childrenOf.merge(parent, 1, Integer::sum);
            }
        }
        List<Integer> kept = new ArrayList<>(selected.size());
        for (int index : selected) {
            Integer children = childrenOf.get(index);
            if (children == null || children < 2) {
                kept.add(index);
            }
        }
        return kept;
    }

    private static void collectPiece(YSMRuntimeModel.BoneRt[] bones, int top,
                                     IntPredicate carriesGeometry, List<Integer> out) {
        // The veto holds for every bone of a piece, not only for the bone that starts it. A
        // placeholder such as `bone5` is swept in as a *descendant* of a garment container, so
        // testing the candidates alone never sees it - which is exactly how the shipped maid ended
        // up with a one-block-long unnamed bone among its skirt's pieces.
        if (!out.contains(top) && bones[top] != null && !YsmPhysicsChains.isVetoed(bones[top].name)) {
            out.add(top);
        }
        for (int i = 0; i < bones.length; i++) {
            if (i == top || bones[i] == null || out.contains(i) || !carriesGeometry.test(i)
                    || YsmPhysicsChains.isVetoed(bones[i].name)) {
                continue;
            }
            int guard = 0;
            for (int parent = bones[i].parent; parent >= 0 && guard++ <= bones.length; parent = bones[parent].parent) {
                if (parent == top) {
                    out.add(i);
                    break;
                }
            }
        }
    }

    /**
     * One segment, or null when this bone cannot be one.
     *
     * <p>Three tests stand between a bone and a segment, and each of them is a way a piece of cloth
     * ends up being moved by something other than itself:
     *
     * <ul>
     *   <li><b>It has a joint</b> ({@code bone.joint >= 0}), or the pose carries no geometry of it
     *       and the simulation would have nothing to read a pivot from.</li>
     *   <li><b>Epic Fight does not own its pose</b> ({@link #poseBelongsToEpicFight}). A mapped
     *       bone is a body part, and turning a body part a second time about its own pivot moves the
     *       limb rather than the cloth on it.</li>
     *   <li><b>Its geometry is its own</b> ({@link #ownsItsGeometry}). The lever, the rest direction
     *       and the collision radius are measured from it, and a bone with no geometry of its own
     *       used to be given a descendant's centroid instead - which is a lever pointing at another
     *       bone's geometry, a delta written to no part at all, and a share of the piece's chain
     *       allowance spent on a bone that cannot move anything.</li>
     * </ul>
     */
    private static Draft buildSegment(YSMRuntimeModel model, int boneIndex,
                                      Map<Integer, List<Vector3f>> vertices, Map<Integer, float[]> geometry,
                                      Map<Integer, int[]> partOrdinals,
                                      Map<String, YsmPhysicsBinding.Part> bindings,
                                      IntPredicate ownsGeometry,
                                      float fallbackFrequency, float fallbackDamping) {
        YSMRuntimeModel.BoneRt bone = model.bones[boneIndex];
        if (bone == null || bone.joint < 0 || poseBelongsToEpicFight(bone)
                || !ownsItsGeometry(boneIndex, geometry, partOrdinals)) {
            return null;
        }
        Vector3f pivot = bindPivot(model, bone);
        if (pivot == null || !YsmDynamicBoneSolver.isFinite(pivot)) {
            return null;
        }
        Vector3f centroid = centroid(geometry.get(boneIndex));
        if (centroid == null) {
            return null;
        }
        Vector3f rest = new Vector3f(centroid).sub(pivot);
        float lever = rest.length();
        if (!Float.isFinite(lever) || lever < MIN_LEVER) {
            // The geometry sits on the pivot: this bone cannot swing, and pretending it can
            // would hand the solver a zero-length lever to divide by.
            return null;
        }
        if (wrapsPivot(vertices.get(boneIndex), pivot)) {
            // The geometry closes around the pivot rather than hanging from it, so every rotation
            // about that pivot slides the piece off the body: see wrapsPivot. Reported once per
            // model and bone, because the only other trace of it is the piece not moving.
            if (WRAPPED_PIVOT_LOGGED.add(model.modelId + '/' + bone.name)) {
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] model '{}': bone '{}' sits about its pivot rather than hanging from it "
                                + "(geometry spread {}), so it stays rigid on its joint and only what hangs below it swings",
                        model.modelId, bone.name,
                        Math.round(directionSpread(vertices.get(boneIndex), pivot) * 100.0F) / 100.0F);
            }
            return null;
        }
        if (risesOffPivot(vertices.get(boneIndex), pivot, rest, lever)) {
            // The geometry stands above a pivot that is not on it, so the direction this piece would be
            // swung about is not the direction it is attached in: see risesOffPivot. Its pivot is a
            // whole limb away from the geometry it draws (on the reported model, an ankle pivot under a
            // thigh), and a spring pulling that geometry toward the world's vertical lays it out beside
            // the limb. Reported once per model and bone, because the only other trace of it is a piece
            // drawn where the pose never put it.
            if (RISES_FROM_PIVOT_LOGGED.add(model.modelId + '/' + bone.name)) {
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] model '{}': bone '{}' has its own geometry above its pivot rather "
                                + "than hanging from it (rest {} blocks up of a {} block lever, {} deg above horizontal; "
                                + "pivot ({},{},{}) is {} blocks outside that geometry, whose y spans {}..{}), so it "
                                + "stays rigid on its joint",
                        model.modelId, bone.name,
                        Math.round(rest.y * 1000.0F) / 1000.0F, Math.round(lever * 1000.0F) / 1000.0F,
                        Math.round(Math.toDegrees(Math.asin(Math.min(1.0F, rest.y / lever))) * 10.0F) / 10.0F,
                        Math.round(pivot.x * 1000.0F) / 1000.0F, Math.round(pivot.y * 1000.0F) / 1000.0F,
                        Math.round(pivot.z * 1000.0F) / 1000.0F,
                        Math.round(pivotGapFromGeometry(vertices.get(boneIndex), pivot) * 1000.0F) / 1000.0F,
                        Math.round(minY(vertices.get(boneIndex)) * 1000.0F) / 1000.0F,
                        Math.round(maxY(vertices.get(boneIndex)) * 1000.0F) / 1000.0F);
            }
            return null;
        }

        YsmPhysicsBinding.Part binding = bindings.get(bone.name);
        float frequency = binding != null ? (float) binding.frequency() : fallbackFrequency;
        float coefficient = binding != null ? (float) binding.coefficient() : fallbackDamping;
        if (!Float.isFinite(frequency) || frequency <= 0.0F) {
            frequency = fallbackFrequency;
        }
        if (!Float.isFinite(coefficient) || coefficient < 0.0F) {
            coefficient = fallbackDamping;
        }

        int parentBone = nearestSimulatedAncestor(model, boneIndex, bindings, ownsGeometry);

        int[] parts = partOrdinals.getOrDefault(boneIndex, new int[0]);
        // Where this piece is *held*, which is where its delta turns it - see contactAnchor. Measured
        // here, once per model rather than once per frame, from the two geometries the model already
        // carries: the piece's own, and the first ancestor's above it that has any.
        Vector3f anchor = contactAnchor(vertices.get(boneIndex),
                restsOnGeometry(model.bones, boneIndex, vertices), pivot, lever);
        if (anchor != pivot && anchor.distance(pivot) > ANCHOR_REPORT_DISTANCE
                && ANCHOR_MOVED_LOGGED.add(model.modelId + '/' + bone.name)) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [physics] model '{}': bone '{}' turns about the point it is held "
                            + "by, {} blocks from its own pivot (pivot ({},{},{}), hinge ({},{},{})), so its "
                            + "own swing no longer slides the end it hangs by",
                    model.modelId, bone.name,
                    Math.round(anchor.distance(pivot) * 1000.0F) / 1000.0F,
                    Math.round(pivot.x * 1000.0F) / 1000.0F, Math.round(pivot.y * 1000.0F) / 1000.0F,
                    Math.round(pivot.z * 1000.0F) / 1000.0F,
                    Math.round(anchor.x * 1000.0F) / 1000.0F, Math.round(anchor.y * 1000.0F) / 1000.0F,
                    Math.round(anchor.z * 1000.0F) / 1000.0F);
        }
        // The swing limit is not decided here: the parent link is only provisional until the
        // surviving segments are known, and the limit has to follow the link that survives. See
        // the second pass in build().
        return new Draft(boneIndex, bone.name, bone.joint, pivot, anchor, rest, lever,
                radiusFor(vertices.get(boneIndex), pivot, rest), mass(geometry.get(boneIndex)),
                frequency, coefficient, 0.0F, parentBone, parts, binding != null);
    }

    /**
     * How far the hinge has to move off the pivot before it is worth a log line, in blocks - one
     * millimetre.
     *
     * <p>Below this the two points are the same point at the resolution the report reads them at, and a
     * line per piece per model would be noise; above it the piece is one whose own swing used to slide.
     */
    static final float ANCHOR_REPORT_DISTANCE = 0.001F;

    /** Model/bone keys already reported as hinged off their pivot, so a reload does not repeat the line. */
    private static final java.util.Set<String> ANCHOR_MOVED_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The bone's pivot in the mesh's model bind space.
     *
     * <p>{@code bindWorld} is the accumulated {@code T(p) R T(-p)} chain the mesh writer
     * used, so its image of the bone's own pivot is the point the geometry was placed at -
     * which is what a part delta has to rotate about. The pivot is scaled the way the
     * vertices were ({@code width_scale} on x and z, {@code height_scale} on y), because
     * the runtime JSON stores the raw authored pivot while the mesh carries the scaled one.
     * That scale is the whole of the difference: {@code mesh.positions()} is this same
     * composed chain with the writer's turn and the loader's inverse turn already cancelled,
     * so no frame turn belongs here either - see {@link #pivotInMeshSpace}.
     *
     * <p>Package-private because the leg diagnostic needs it for pieces this class <i>dropped</i>:
     * those have no {@code Segment}, and a diagnostic that rebuilt their pivot its own way would be
     * explaining a decision nobody made.
     */
    static Vector3f bindPivot(YSMRuntimeModel model, YSMRuntimeModel.BoneRt bone) {
        float scaleX = model.widthScale;
        float scaleY = model.heightScale;
        if (!Float.isFinite(scaleX) || scaleX <= 0.0F) {
            scaleX = 1.0F;
        }
        if (!Float.isFinite(scaleY) || scaleY <= 0.0F) {
            scaleY = 1.0F;
        }
        return pivotInMeshSpace(bone.bindWorld, bone.px, bone.py, bone.pz, scaleX, scaleY);
    }

    /**
     * How close to its own closest approach to what it rests on a vertex has to be to count as part of
     * the contact patch the hinge is placed at, in blocks - one centimetre in the mesh's own space.
     *
     * <p>This is a <b>resolution, not a threshold on pieces</b>: it says which of a piece's own vertices
     * are touching, and it is applied to every piece of every model by the same rule. It was fixed by
     * measurement on the reported model rather than by taste ({@code build/reports/ysm-reanchor-round.md}):
     * at 0.005 and 0.010 blocks the cap's patch is the same 27 vertices and its hinge the same point; at
     * 0.030 the patch swallows the whole inner shell (378 vertices), the hinge drifts back to the middle
     * of the piece and less of the defect is removed, not more.
     */
    static final float CONTACT_PATCH_TOLERANCE = 0.01F;

    /**
     * The most distance tests the contact search may spend on one piece.
     *
     * <p>The patch walks the piece's own vertices against the cloud it rests on. On this project's models
     * that product is small for a strand and large for a cap wrapped round a skull, and a converted mesh
     * can carry a hundred thousand vertices, so a cloud bigger than this budget is strided. The budget
     * bounds the work at model load rather than changing what is measured: the stride is derived from the
     * two cloud sizes, and the check that it does not move the hinge on the reported model is in the
     * round's report.
     */
    static final int CONTACT_SEARCH_WORK = 262144;

    /**
     * The point of a piece that is <b>held</b>: the centre of the patch of its own geometry that touches
     * the geometry it rests on.
     *
     * <p><b>Why the hinge is not the pivot.</b> The delta the frame path hands the mesh is a rotation
     * about a point, and rotating a body about a point that is not where it is held <i>translates</i> the
     * end it is held by. For a strand whose pivot sits at its root the two coincide and nothing is wrong;
     * for a piece whose pivot is inside its own volume - the reported top-of-head cap, whose pivot is
     * 0.107 blocks from the nearest vertex of its own geometry - the whole piece slides, which is what
     * "the hair lifts off the skull when she looks down" is. The point it is held by is where it touches
     * what it hangs from, and that is measurable from the model's own two geometries without a name, a
     * shape statistic or a threshold on pieces.
     *
     * <p>The patch is every one of the piece's own vertices within {@link #CONTACT_PATCH_TOLERANCE} of its
     * own closest approach to {@code restsOn}, averaged, because a single nearest vertex is a quantisation
     * of the contact and on a box part it can land on a far corner - on the reported model the strand
     * {@code LongHair}'s nearest corner to the skull is 0.31 blocks from its own pivot while the top face
     * it actually hangs by is 0.055 away, and hinging the strand at its bottom corner is a worse defect
     * than the one being fixed. A contact inferred from a remote ancestor is refused when even its
     * broad bounding box remains more than {@link #CONTACT_SUPPORT_MAX_GAP} from the bounded hinge.
     *
     * @param own      the piece's own geometry, mesh space
     * @param restsOn  the geometry it rests on, mesh space, or null when nothing above it has any
     * @param fallback the bind pivot, which is what a piece that rests on nothing measurable keeps
     * @param lever    {@code |centroid - pivot|}, blocks: how far the hinge may travel from the pivot
     * @return the hinge point, never null when {@code fallback} is not
     */
    static Vector3f contactAnchor(List<Vector3f> own, List<Vector3f> restsOn, Vector3f fallback,
                                  float lever) {
        if (own == null || own.isEmpty() || restsOn == null || restsOn.isEmpty() || fallback == null) {
            return fallback;
        }
        int stride = contactStride(own.size(), restsOn.size());
        float nearest = Float.MAX_VALUE;
        for (Vector3f vertex : own) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            nearest = Math.min(nearest, distanceToCloud(vertex, restsOn, stride));
        }
        if (!Float.isFinite(nearest)) {
            return fallback;
        }
        Vector3f sum = new Vector3f();
        int used = 0;
        for (Vector3f vertex : own) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            if (distanceToCloud(vertex, restsOn, stride) <= nearest + CONTACT_PATCH_TOLERANCE) {
                sum.add(vertex);
                used++;
            }
        }
        if (used == 0) {
            return fallback;
        }
        Vector3f candidate = withinLever(fallback, sum.div(used), lever);
        double supportGap = pivotGapFromGeometry(restsOn, candidate);
        if (!Double.isFinite(supportGap) || supportGap > CONTACT_SUPPORT_MAX_GAP) {
            // The first ancestor with geometry need not be in physical contact with this part.
            // If the bounded hinge still sits outside even that support's broad bounding box,
            // the nearest-vertex patch is a remote shape rather than an attachment. Keep the
            // authored pivot instead of rotating the part around a point in empty space.
            return fallback;
        }
        return candidate;
    }

    /**
     * A generous upper bound on a contact hinge's gap from its support, in blocks.
     *
     * <p>This is checked against the support's bounding box, which is a lower bound on the distance
     * to its surface. A gap beyond this bound proves the chosen ancestor is not a local support;
     * overlap of the boxes is only permission to use the contact patch, not proof of contact.
     */
    static final float CONTACT_SUPPORT_MAX_GAP = 0.1F;

    /**
     * The furthest the hinge may travel from the piece's bind pivot, in blocks - a quarter of a block.
     *
     * <p>The correction the reported defect needs is 0.162 blocks after the lever bound below, so this
     * ceiling does not bind on the case that motivated the rule; it binds on the pieces whose contact is
     * found far from their pivot, where the walk up to the first ancestor with geometry has landed on
     * geometry a limb away. A quarter of a block is also below the size at which the resulting translation
     * of the whole piece is visible at ordinary swings: {@code 0.25 * 2 sin(10 deg) = 0.043} blocks, four
     * centimetres. Measured over the corpus, 90 per cent of simulated pieces move their hinge by under
     * 0.082 blocks on average and every one of them by at most this.
     */
    static final float CONTACT_HINGE_LIMIT = 0.25F;

    /**
     * The contact centre, pulled back to within the piece's own lever of its pivot and within
     * {@link #CONTACT_HINGE_LIMIT} blocks of it.
     *
     * <p><b>Why a bound is needed.</b> The walk that finds what a piece rests on climbs past ancestors
     * that carry no geometry, and on a model whose nearest bone is a container the geometry it lands on
     * can be a limb away: measured over the corpus the unbounded rule moves the hinge by up to
     * <b>3.869 blocks</b> on one hairpin, which would fling it that far at any swing. The bound is not a
     * classifier - every piece is measured by the same rule, and the rule is a distance in blocks - and
     * the piece's own lever is the natural half of it, because that is the radius the piece's own swing
     * is already drawn on: the hinge stays inside the piece's own scale. Measured on the reported model
     * the lever binds on the cap ({@code 0.187 -> 0.162}, 87 per cent of the correction kept) and on
     * nothing else in its head region.
     */
    private static Vector3f withinLever(Vector3f pivot, Vector3f contact, float lever) {
        float distance = pivot.distance(contact);
        if (!Float.isFinite(distance) || distance <= 0.0F) {
            return pivot;
        }
        float limit = CONTACT_HINGE_LIMIT;
        if (Float.isFinite(lever) && lever > 0.0F) {
            limit = Math.min(limit, lever);
        }
        if (distance <= limit) {
            return contact;
        }
        return new Vector3f(pivot).lerp(contact, limit / distance);
    }

    /**
     * The own geometry of the nearest ancestor bone that has any - what this piece rests on.
     *
     * <p>Walked up the model's own bone chain rather than taken from the parent alone, because the bone a
     * piece hangs from is often a container with no geometry of its own: on the reported model the cap's
     * parent is {@code Hair}, which carries none, and the first ancestor that does is the skull. For a
     * strand of a chain the first ancestor with geometry is the strand above it, which is where a chain
     * link is held.
     */
    static List<Vector3f> restsOnGeometry(YSMRuntimeModel.BoneRt[] bones, int boneIndex,
                                          Map<Integer, List<Vector3f>> vertices) {
        if (bones == null || vertices == null) {
            return null;
        }
        int at = parentOf(bones, boneIndex);
        for (int guard = 0; at >= 0 && guard <= bones.length; guard++) {
            List<Vector3f> own = vertices.get(at);
            if (own != null && !own.isEmpty()) {
                return own;
            }
            at = parentOf(bones, at);
        }
        return null;
    }

    /** The bone's parent index, or -1 when it has none or the table does not carry it. */
    private static int parentOf(YSMRuntimeModel.BoneRt[] bones, int boneIndex) {
        if (boneIndex < 0 || boneIndex >= bones.length || bones[boneIndex] == null) {
            return -1;
        }
        return bones[boneIndex].parent;
    }

    /**
     * How many of a cloud's vertices the contact search reads: one when the product fits the budget, and
     * otherwise the smallest stride that brings it inside - derived from the data, so the search cost per
     * piece is bounded while the measurement stays as close to the whole cloud as the budget allows.
     */
    private static int contactStride(int ownSize, int cloudSize) {
        long product = (long) ownSize * (long) cloudSize;
        if (product <= CONTACT_SEARCH_WORK || ownSize <= 0) {
            return 1;
        }
        return (int) Math.max(1L, Math.min(cloudSize, (product + CONTACT_SEARCH_WORK - 1) / CONTACT_SEARCH_WORK));
    }

    /** The distance from a point to the nearest point of a cloud, reading every {@code stride}-th one. */
    private static float distanceToCloud(Vector3f point, List<Vector3f> cloud, int stride) {
        float best = Float.MAX_VALUE;
        for (int at = 0; at < cloud.size(); at += stride) {
            Vector3f other = cloud.get(at);
            if (other != null && YsmDynamicBoneSolver.isFinite(other)) {
                best = Math.min(best, point.distance(other));
            }
        }
        return best;
    }

    /**
     * A bone's pivot in the mesh's own space - the space the writer bakes the vertices in, and the
     * space a part transform acts in.
     *
     * <p><b>The frame, and why this method is two steps and not three.</b> The bone chain acts in the
     * model's own authored units, so {@code bindWorld} composes there. That authored frame is
     * <i>not</i> the frame the delta acts in, and the difference is not a turn that is missing here:
     * it is a turn that has already been undone before the physics ever sees the vertices. The writer
     * stores every corner of {@link com.ysmef.compat.model.EFMeshJsonWriter} as
     * {@code (x, y, z) -> (x * widthScale, -z * widthScale, y * heightScale)} - the Blender, Z-up
     * frame Epic Fight's meshes are authored in, scaled once about the origin - and Epic Fight's
     * loader ({@code JsonAssetLoader}, {@code BLENDER_TO_MINECRAFT_COORD}, applied to every position
     * as the mesh is read) applies that map's inverse to every one of them. Composed, the two are the
     * authored chain scaled once with <b>no turn left in it</b>, and {@code mesh.positions()} - the
     * array a part delta multiplies - is that composition's output. So the pivot is the composed
     * chain, then the scale, and nothing else. The scale goes on last because the writer scales once,
     * at the end, about the origin.
     *
     * <p><b>The turn must not be re-added, and a distance metric is not grounds for adding it.</b>
     * An earlier revision applied the stored map to the pivot as well, {@code (x, y, z) -> (x, -z, y)},
     * because doing so drove the mean distance from a bone's pivot to the centroid of its own geometry
     * from 1.672 to 0.074 blocks over 223 bones on {@code EKU(1.0.ysm} and read as a 22x improvement.
     * Both the metric and the change were wrong. The "before" number was measured with the pivot in
     * the mesh's frame and the geometry's centroid taken from the numbers as they are stored in the
     * JSON - i.e. in the file's frame - so the two sides were never in one frame; a rigid turn
     * preserves all distances, so comparing a mesh-frame pivot with a file-frame centroid, or a
     * file-frame pivot with a mesh-frame centroid, is the same measurement wearing two labels, and the
     * one that "improves" is the one whose pivot has been moved into the frame the centroid was
     * already in. A pivot also does not belong at a geometry's centroid: it belongs at the bone's own
     * origin, normally one end of the geometry, so a long limb's ~1.7-block origin-to-centroid distance
     * is the expected value and driving it toward zero means the point moved off the joint. Grounded in
     * the turned revision's own in-game test, models came apart - legs shattered and long hair broke
     * into separated fragments - and the turn was reverted.
     *
     * <p>The calibration that replaced it reads the pivot against its own geometry's bounding box in
     * the drawn frame, where both sides are in one frame and the pivot is expected to land: on
     * {@code EKU(1.0.ysm} the pivot named here is inside that box for 172 of 223 bones, and the
     * corner-turned candidate is inside it for 4. Any future frame decision has to be grounded in the
     * stored converted artifacts and calibrated against a known-good model, not against a metric.
     *
     * <p>What stays in the authored frame: everything the animation pipeline owns.
     * {@code YSMRuntimeModel.bindWorld}, {@code YSMPlayerAnimator}'s deltas and
     * {@code YsmMeshCloth.nearestMappedBoneTo} compare authored pivots with authored pivots, which is
     * this class's only caller that must NOT be turned, and is not.
     */
    static Vector3f pivotInMeshSpace(Matrix4f bindWorld, float px, float py, float pz,
                                     float scaleX, float scaleY) {
        Vector3f pivot = new Vector3f(px, py, pz);
        if (!YsmDynamicBoneSolver.isFinite(pivot)) {
            return null;
        }
        if (bindWorld != null) {
            bindWorld.transformPosition(pivot);
        }
        // No frame turn here - deliberately, and this must not be re-added on the strength of a
        // metric alone. An earlier revision applied the writer's corner transform
        // ((x, y, z) -> (x, -z, y)) to this pivot, justified by a measurement that drove the
        // distance from a bone's pivot to the centroid of its own geometry from 1.672 to 0.074
        // blocks over 223 bones and looked like a 22x improvement. Both the metric and the change
        // were wrong: a pivot belongs at the bone's OWN ORIGIN - its rotation centre, normally one
        // end of the geometry - not at the geometry's centroid, so a long limb's ~1.7-block
        // origin-to-centroid distance is the expected value and driving it to zero means the
        // transform moved the origin onto the centroid. The user's in-game test then showed models
        // torn apart: legs shattered and long hair broken into separated fragments. Reverted.
        // Any future frame decision must be grounded in the stored converted artifacts (authored
        // package vs the mesh JSON actually written) and calibrated against a known-good model.
        pivot.mul(scaleX, scaleY, scaleX);
        return YsmDynamicBoneSolver.isFinite(pivot) ? pivot : null;
    }

    /** The centroid of a geometry entry, or null. */
    private static Vector3f centroid(float[] entry) {
        if (entry == null || entry[3] < MIN_VERTICES) {
            return null;
        }
        return new Vector3f(entry[0], entry[1], entry[2]);
    }

    /** The lowest y of a set of vertices, or {@link Float#NaN} when there is nothing to measure. */
    static float minY(List<Vector3f> vertices) {
        return extentY(vertices, true);
    }

    /** The highest y of a set of vertices, or {@link Float#NaN} when there is nothing to measure. */
    static float maxY(List<Vector3f> vertices) {
        return extentY(vertices, false);
    }

    private static float extentY(List<Vector3f> vertices, boolean lowest) {
        if (vertices == null || vertices.isEmpty()) {
            return Float.NaN;
        }
        float best = lowest ? Float.MAX_VALUE : -Float.MAX_VALUE;
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            best = lowest ? Math.min(best, vertex.y) : Math.max(best, vertex.y);
            used++;
        }
        return used == 0 ? Float.NaN : best;
    }

    /**
     * The direction a piece's own geometry is <b>longest</b> along, in bind space: the principal axis
     * of the vertices about their centroid, or null when the shape has no single long axis.
     *
     * <h2>Why the diagnostic needs it and the rule does not</h2>
     *
     * <p>{@code rest} says which way the piece's mass sits from its pivot; it does not say which way
     * the piece is <i>drawn</i>, and on the reported thigh those are two different statements: the
     * piece is drawn along the leg while its own delta turns it about the ankle. "The thigh lies
     * nearly horizontal" is a claim about the drawn long axis, so the diagnostic measures that axis
     * and carries it through the joint's pose ({@code pose x toOrigin}) and then through the piece's
     * own delta - the three numbers that separate "the delta laid it out" from "the pose already
     * had it at 48 degrees".
     *
     * <p>The covariance of the offsets from the centroid is symmetric and 3x3, so its principal axis
     * comes from the same cyclic Jacobi rotation as {@link #directionSpread}'s eigenvalues, with the
     * rotation accumulated this time. The axis of a well-conditioned shape is stable; the axis of a
     * shape whose two largest extents are within {@link #MIN_AXIS_SEPARATION} of each other is not,
     * and this answers null there rather than a direction that a float's last bit chose. A cube, a
     * flat square panel and a piece whose vertices are all on one point all answer null, and that is
     * the intended reading: they have no long axis to be drawn along.
     *
     * @return a unit axis, sign-arbitrary (a line has no direction), or null
     */
    static Vector3f longAxisOf(List<Vector3f> vertices) {
        if (vertices == null) {
            return null;
        }
        int used = 0;
        double cx = 0.0D;
        double cy = 0.0D;
        double cz = 0.0D;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            cx += vertex.x;
            cy += vertex.y;
            cz += vertex.z;
            used++;
        }
        if (used < MIN_VERTICES) {
            return null;
        }
        cx /= used;
        cy /= used;
        cz /= used;
        double xx = 0.0D;
        double xy = 0.0D;
        double xz = 0.0D;
        double yy = 0.0D;
        double yz = 0.0D;
        double zz = 0.0D;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            double dx = vertex.x - cx;
            double dy = vertex.y - cy;
            double dz = vertex.z - cz;
            xx += dx * dx;
            xy += dx * dy;
            xz += dx * dz;
            yy += dy * dy;
            yz += dy * dz;
            zz += dz * dz;
        }
        double[][] matrix = {
                {xx / used, xy / used, xz / used},
                {xy / used, yy / used, yz / used},
                {xz / used, yz / used, zz / used}};
        double[][] vectors = new double[3][3];
        double[] eigenvalues = jacobiEigen(matrix, vectors);
        int longest = 0;
        for (int i = 1; i < 3; i++) {
            if (eigenvalues[i] > eigenvalues[longest]) {
                longest = i;
            }
        }
        double largest = eigenvalues[longest];
        double secondLargest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (i != longest) {
                secondLargest = Math.max(secondLargest, eigenvalues[i]);
            }
        }
        if (!(largest > 0.0D) || !(secondLargest > 0.0D)
                || largest < secondLargest * MIN_AXIS_SEPARATION) {
            return null;
        }
        Vector3f axis = new Vector3f((float) vectors[0][longest], (float) vectors[1][longest],
                (float) vectors[2][longest]);
        if (!YsmDynamicBoneSolver.isFinite(axis) || axis.lengthSquared() < 1.0E-8F) {
            return null;
        }
        return axis.normalize();
    }

    /**
     * How much longer the piece must be along its principal axis than across it, as the ratio of the
     * largest eigenvalue of the offsets' covariance to the next, before {@link #longAxisOf} answers a
     * direction at all. 1.05, i.e. the axis has to be at least five per cent better conditioned than
     * the alternative - just enough that the answer is a property of the shape rather than of the
     * last bit of a float sum. It is not a claim about how elongated a piece must be to be simulated:
     * nothing about the simulation reads this number.
     */
    private static final double MIN_AXIS_SEPARATION = 1.05D;

    /**
     * The angle between a direction and the model's vertical, in degrees, 0 (straight up or straight
     * down) to 90 (horizontal) - or {@link Double#NaN} when the direction has no length.
     *
     * <p>Measured as a <b>line</b> against the vertical, because the axis of a piece's geometry has no
     * direction of its own and its sign is an artefact of the eigenvector's sign convention. A piece
     * that hangs straight down and one that stands straight up both read 0.
     */
    static double angleFromVertical(Vector3f direction) {
        if (direction == null || !YsmDynamicBoneSolver.isFinite(direction)) {
            return Double.NaN;
        }
        double length = Math.sqrt(direction.lengthSquared());
        if (length < 1.0E-9D) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.acos(Math.max(0.0D, Math.min(1.0D, Math.abs(direction.y) / length))));
    }

    /**
     * The nearest ancestor bone that can itself be a segment, or -1.
     *
     * <p>The author's {@code follows} link is preferred when it names such a bone, because
     * it is what the model author actually wrote: the wine fox drives {@code BackHairB2}
     * from {@code BackHairA1} through a timeline variable, and those two are not always
     * parent and child in the skeleton. The skeleton is the fallback, and it is all the
     * name-classifier path has.
     */
    private static int nearestSimulatedAncestor(YSMRuntimeModel model, int boneIndex,
                                                Map<String, YsmPhysicsBinding.Part> bindings,
                                                IntPredicate ownsGeometry) {
        YsmPhysicsBinding.Part binding = bindings.get(model.bones[boneIndex].name);
        if (binding != null && !binding.follows().isEmpty()) {
            Integer followed = model.boneIndex.get(binding.follows());
            if (followed != null && followed != boneIndex && ownsGeometry.test(followed)
                    && !isUnder(model.bones, followed, boneIndex)) {
                return followed;
            }
        }
        int guard = 0;
        for (int parent = model.bones[boneIndex].parent; parent >= 0 && guard++ <= model.bones.length;
             parent = model.bones[parent].parent) {
            if (ownsGeometry.test(parent)) {
                return parent;
            }
        }
        return -1;
    }

    /** Geometry per bone: [cx, cy, cz, vertexCount]. Thickness is measured in {@link #radiusFor}. */
    static Map<Integer, float[]> geometryByBone(Map<Integer, List<Vector3f>> vertices) {
        Map<Integer, float[]> out = new HashMap<>();
        if (vertices == null) {
            return out;
        }
        for (Map.Entry<Integer, List<Vector3f>> entry : vertices.entrySet()) {
            List<Vector3f> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            Vector3f centre = new Vector3f();
            for (Vector3f vertex : list) {
                centre.add(vertex);
            }
            centre.div(list.size());
            out.put(entry.getKey(), new float[]{centre.x, centre.y, centre.z, list.size()});
        }
        return out;
    }

    /**
     * The mesh part ordinals carrying each bone's geometry.
     *
     * <p>Ordinals rather than names: this runs per drawn frame, and resolving a name
     * through a hash map every time is the kind of per-frame cost this feature has to be
     * cheap enough for a phone to pay. Computed once per mesh and cached against the mesh.
     */
    static Map<Integer, int[]> partOrdinalsByBone(YSMMesh mesh, YSMRuntimeModel model) {
        Map<Integer, int[]> cached = PART_ORDINALS.get(mesh);
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<Integer>> collected = new HashMap<>();
        String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
        int ordinal = 0;
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (partName.startsWith(prefix)) {
                Integer boneIndex = model.boneIndex.get(partName.substring(prefix.length()));
                if (boneIndex != null && boneIndex >= 0 && boneIndex < model.bones.length) {
                    collected.computeIfAbsent(boneIndex, key -> new ArrayList<>()).add(ordinal);
                }
            }
            ordinal++;
        }
        Map<Integer, int[]> out = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : collected.entrySet()) {
            List<Integer> list = entry.getValue();
            int[] array = new int[list.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = list.get(i);
            }
            out.put(entry.getKey(), array);
        }
        PART_ORDINALS.put(mesh, out);
        return out;
    }

    /** mesh -&gt; bone -&gt; part ordinals. Weak keys: a mesh is dropped when its model is unloaded. */
    private static final Map<YSMMesh, Map<Integer, int[]>> PART_ORDINALS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** mesh -&gt; bone -&gt; vertices. Weak keys, same reason. */
    private static final Map<YSMMesh, Map<Integer, List<Vector3f>>> VERTICES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * mesh -&gt; bone -&gt; vertices, the same set every lever and radius here is measured from.
     *
     * <p>Package-private rather than private because the leg diagnostic
     * ({@code YsmMeshSecondaryMotion#logLegRegion}) reads a piece's own geometry to name the axis it
     * is drawn along, and it must read the same vertices - hidden default-variant bones excluded -
     * that the segment's pivot and rest were measured from, or the number it prints would be about a
     * different piece.
     */
    static Map<Integer, List<Vector3f>> verticesByBone(YSMMesh mesh, YSMRuntimeModel model) {
        Map<Integer, List<Vector3f>> cached = VERTICES.get(mesh);
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<Vector3f>> out = new HashMap<>();
        float[] positions = mesh == null ? null : mesh.positions();
        // Bones the model's own scripts collapse to nothing in its default form - variant
        // bodies, weapons, expressions, accessories - are still in the mesh, still carry
        // vertices, and are only marked hidden at draw time. Their vertices sit wherever that
        // variant was authored, which for a different form of the same character can be most of
        // a block away, so a centre of mass averaged over them points somewhere the visible
        // piece is not: the lever is wrong, the rest direction is wrong, and the piece is then
        // swung about a pivot it does not have. YsmBindArmature excludes exactly these bones
        // from its own pivot computation for the same reason; this is the same rule.
        java.util.Set<String> hidden = model.defaultHiddenBoneNames();
        if (positions != null) {
            String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
            for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
                String partName = entry.getKey();
                if (!partName.startsWith(prefix)) {
                    continue;
                }
                Integer boneIndex = model.boneIndex.get(partName.substring(prefix.length()));
                if (boneIndex == null || boneIndex < 0 || boneIndex >= model.bones.length) {
                    continue;
                }
                if (hidden.contains(model.bones[boneIndex].name)) {
                    continue;
                }
                MeshPart part = entry.getValue();
                if (part == null || part.getVertices() == null) {
                    continue;
                }
                List<Vector3f> list = out.computeIfAbsent(boneIndex, key -> new ArrayList<>());
                for (VertexBuilder builder : part.getVertices()) {
                    int position = builder.position * 3;
                    if (position + 2 < positions.length) {
                        list.add(new Vector3f(positions[position], positions[position + 1], positions[position + 2]));
                    }
                }
            }
        }
        VERTICES.put(mesh, out);
        return out;
    }

    /** Forget every per-mesh index (model reload, world leave). */
    public static void clear() {
        PART_ORDINALS.clear();
        VERTICES.clear();
        WRAPPED_PIVOT_LOGGED.clear();
        RISES_FROM_PIVOT_LOGGED.clear();
    }

    /**
     * A segment's collision radius, in blocks: the piece's own thickness, across the direction it
     * hangs.
     *
     * <p>Half the geometry's <i>width</i>, and the correction is which width. This used to be the
     * median distance from the vertices to their centroid, which measures the piece's <b>length</b>
     * as much as its thickness: on a strand - a tail, a braid, a long lock of hair - most vertices
     * are far from the centre along the strand, so the median is roughly half the strand's length.
     * The numbers that produces are wrong in both directions, and both were shipped:
     *
     * <ul>
     *   <li>a long thin piece gets a radius of the length, clamped at {@link #MAX_RADIUS} (0.22
     *       blocks) - a two-block tail with a 22 cm collision sphere, which pushes the whole body
     *       away from it;</li>
     *   <li>a short piece (a few centimetres of strand, or a small accessory strand) gets a few
     *       millimetres, clamped up to {@link #MIN_RADIUS} - a sphere that reaches nothing, so the
     *       piece passes through the body it is supposed to rest on and collision is, in the only
     *       sense a viewer has, not there at all.</li>
     * </ul>
     *
     * <p>The thickness does not have that ambiguity: distance from the line the piece hangs along,
     * taken as a median so a stray vertex (a spike, a corner of the neighbouring face) cannot size
     * the sphere. A garment panel measured this way gets its half-width, which is what a sphere
     * standing in for a sheet of cloth should be.
     *
     * <p>Package-private so the two regimes can be pinned by a test: the numbers above are what a
     * user sees as "hair goes through the shoulder" and "the tail shoves the body", and neither is
     * visible in any log.
     */
    static float radiusFor(List<Vector3f> vertices, Vector3f pivot, Vector3f restDir) {
        if (vertices == null || vertices.size() < MIN_VERTICES
                || pivot == null || restDir == null || !YsmDynamicBoneSolver.isFinite(restDir)) {
            return MIN_RADIUS;
        }
        float length = restDir.length();
        if (!Float.isFinite(length) || length < 1.0E-6F) {
            return MIN_RADIUS;
        }
        float ux = restDir.x / length;
        float uy = restDir.y / length;
        float uz = restDir.z / length;
        float[] distances = new float[vertices.size()];
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            float wx = vertex.x - pivot.x;
            float wy = vertex.y - pivot.y;
            float wz = vertex.z - pivot.z;
            float along = wx * ux + wy * uy + wz * uz;
            float ex = wx - along * ux;
            float ey = wy - along * uy;
            float ez = wz - along * uz;
            distances[used++] = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        }
        if (used < MIN_VERTICES) {
            return MIN_RADIUS;
        }
        java.util.Arrays.sort(distances, 0, used);
        float median = distances[used / 2];
        if (!Float.isFinite(median)) {
            return MIN_RADIUS;
        }
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, median * COLLISION_RADIUS_FRACTION));
    }

    /**
     * Relative mass: the geometry's vertex count over {@link #VERTICES_PER_MASS_UNIT},
     * clamped to a sane range.
     *
     * <p>A proxy, and only ever used to decide how easily the air moves the piece, which is
     * the ratio of a cloth panel's area to its mass. Vertex count tracks that ratio well
     * enough for the difference that matters (a broad skirt panel against a thin braid),
     * and the model package carries nothing better.
     */
    private static float mass(float[] entry) {
        if (entry == null || entry[3] <= 0.0F) {
            return 1.0F;
        }
        return Math.max(0.25F, Math.min(4.0F, entry[3] / VERTICES_PER_MASS_UNIT));
    }

    /** Whether {@code bone} hangs anywhere under {@code ancestor}, bounded against cycles. */
    private static boolean isUnder(YSMRuntimeModel.BoneRt[] bones, int bone, int ancestor) {
        int guard = 0;
        for (int i = bones[bone].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            if (i == ancestor) {
                return true;
            }
        }
        return false;
    }
}
