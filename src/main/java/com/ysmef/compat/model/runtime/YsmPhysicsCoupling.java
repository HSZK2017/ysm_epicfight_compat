package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Applies short-range angular coupling to the topology of a simulated garment. */
final class YsmPhysicsCoupling {
    private YsmPhysicsCoupling() {}

    /**
     * Pull a segment's swing toward the average of the panels sewn to it.
     *
     * <p>Applied after the segment's own dynamics, so it shapes the result rather than replacing
     * it: a panel that is genuinely pushed still leads, and its neighbours follow it instead of
     * staying where they were.
     *
     * <p>The partners are fixed at build time from the model's own geometry and its parent links
     * ({@link YsmPhysicsTopology#knitsOf}), so this costs one pass over a short list per segment and allocates
     * nothing. A segment further from its piece's root than its parent is - which is every joint
     * of a chain - is the side that moves; the root is pulled toward its own neighbours instead.
     *
     * <h2>Why the factor is per second and not per frame</h2>
     *
     * <p>The pull was a flat quarter of the disagreement per frame, which is a different coupling
     * at 30 fps than at 240: at 240 the garment converges in half the time, so the same model
     * behaves differently on two machines and neither number is the one that was tuned. It is the
     * fraction of the disagreement that closes in one step of length {@code dt}, so
     * {@code 1 - 2^(-dt/H)}: with {@code H} = 0.03 s a tenth of a second closes 90 per cent of the
     * disagreement at any frame rate, and a long frame cannot overshoot because the factor is
     * bounded by one.
     *
     * <p>This changes the solver's direction but not its angular velocity. The frame coordinator
     * reprojects cloth against the body after a successful pull, then derives the rendered
     * quaternion from the resulting direction. Returning whether a pull happened makes that
     * second collision pass conditional.
     */
    static boolean relaxTowardsNeighbours(YsmMeshSecondaryMotion.State state, int index, float dt) {
        YsmPhysicsTopology.Knits knits = state.knits;
        if (knits == null || index < 0 || index >= knits.count.length
                || knits.count[index] == 0) {
            return false;
        }
        Vector3f ownRest = state.restDirections[index];
        if (ownRest.lengthSquared() < 1.0E-8F) {
            return false;
        }
        Vector3f mean = coherenceMean;
        mean.zero();
        int used = 0;
        int start = knits.start[index];
        for (int slot = 0; slot < knits.count[index]; slot++) {
            int partner = knits.partners[start + slot];
            if (partner < 0 || partner >= state.states.length || !state.integrated[partner]) {
                // A partner with no pose this frame has no direction to be pulled toward; its
                // stale one is what a garment must not follow.
                continue;
            }
            Vector3f partnerRest = state.restDirections[partner];
            if (partnerRest.lengthSquared() < 1.0E-8F) {
                continue;
            }
            if (state.parts.segments()[index].category() == YsmPhysicsParts.Category.CLOTH
                    && partner == state.parts.segments()[index].parent()) {
                // The child's mesh delta is composed under this parent's delta later in the
                // frame. Its own swing is therefore a bend RELATIVE to the parent. Copying the
                // parent's swing into that bend applies it twice and opens the hem like a strip.
                partnerTarget.set(ownRest);
            } else {
                // Neighbouring panels share their SWING, not their absolute direction. Their
                // rest directions differ around the waist and must keep that authored spread.
                YsmDynamicBoneSolver.rotationFromTo(partnerSwing, partnerRest,
                        state.states[partner].direction);
                partnerTarget.set(ownRest).rotate(partnerSwing);
            }
            mean.add(partnerTarget);
            used++;
        }
        if (used == 0 || mean.lengthSquared() < 1.0E-8F) {
            return false;
        }
        mean.normalize();

        Vector3f direction = state.states[index].direction;
        float disagreement = YsmDynamicBoneSolver.angleBetween(direction, mean);
        if (disagreement < COHERENCE_DEADBAND) {
            return false;
        }
        // Rotate the piece a fraction of the way toward where the fabric around it points. A
        // rotation rather than a blend of positions: the segment keeps its lever and its state,
        // it simply aims where its neighbours aim.
        float factor = coherenceFactor(dt);
        if (factor <= 0.0F) {
            return false;
        }
        relaxDirection(direction, mean, factor);
        state.states[index].lastAngle = YsmDynamicBoneSolver.angleBetween(state.restDirections[index], direction);
        return true;
    }

    /**
     * The fraction of the neighbours' disagreement that closes in one frame of {@code dt} seconds.
     *
     * <p>{@code 1 - 2^(-dt/H)}: the coupling's time constant is {@link #COHERENCE_HALF_LIFE_SECONDS},
     * expressed as a half-life because that is the form whose meaning does not depend on the frame
     * rate. Zero for a frame with no time in it, which leaves the garment exactly as it is rather
     * than pulling it by an amount nobody asked for.
     */
    static float coherenceFactor(float dt) {
        if (!(dt > 0.0F) || !Float.isFinite(dt)) {
            return 0.0F;
        }
        double fraction = 1.0 - Math.pow(2.0, -dt / COHERENCE_HALF_LIFE_SECONDS);
        return (float) Math.max(0.0, Math.min(1.0, fraction));
    }

    /**
     * Turn {@code direction} a fraction of the way toward {@code target}.
     *
     * <p>The coupling's whole mechanism, extracted so its two properties can be tested: it is a
     * <b>pull</b>, so one application closes exactly {@code factor} of the disagreement and never
     * overshoots, and repeated application converges rather than oscillating. A constraint
     * (snapping to the target) would make a panel that is genuinely pushed unable to lead, and
     * cloth does lead where it is pushed - the rest of the garment follows it.
     *
     * <p>Written as an explicit axis-and-angle rotation rather than a quaternion slerp. The
     * fraction is the whole point of the method, and getting it wrong is invisible - the piece
     * simply snaps to its neighbours instead of being pulled toward them, which reads as the
     * garment having no give at all - so it is done in the one form whose meaning is unambiguous
     * from the line itself.
     */
    static void relaxDirection(Vector3f direction, Vector3f target, float factor) {
        if (direction == null || target == null || factor <= 0.0F) {
            return;
        }
        float angle = YsmDynamicBoneSolver.angleBetween(direction, target);
        if (angle < 1.0E-4F) {
            return;
        }
        coherenceAxis.set(direction).cross(target);
        if (coherenceAxis.lengthSquared() < 1.0E-10F) {
            // Exactly opposed: there is no shortest way round, and a pull has no business
            // inventing one. The piece keeps the direction it is in.
            return;
        }
        coherenceAxis.normalize();
        direction.rotateAxis(angle * Math.min(1.0F, factor),
                coherenceAxis.x, coherenceAxis.y, coherenceAxis.z);
        direction.normalize();
    }

    /**
     * The time constant of the panel coupling, as the time in which half the disagreement between
     * two neighbours is closed.
     *
     * <p>Thirty milliseconds: a tenth of a second closes 90 per cent of it, which is well inside
     * the time a viewer reads a garment as one object, while a panel that is genuinely being pushed
     * is still free to lead for several frames. This replaces a flat quarter-per-frame, which was
     * 0.25 at 60 fps and 0.5 at 120 - two different garments on two machines, and neither one the
     * number anybody tuned. See {@link #coherenceFactor}.
     */
    private static final float COHERENCE_HALF_LIFE_SECONDS = 0.03F;

    /** Disagreement below this is left alone, so a settled garment is exactly still. */
    private static final float COHERENCE_DEADBAND = 0.01F;

    private static final Vector3f coherenceMean = new Vector3f();
    private static final Vector3f coherenceAxis = new Vector3f();
    private static final Quaternionf partnerSwing = new Quaternionf();
    private static final Vector3f partnerTarget = new Vector3f();

}
