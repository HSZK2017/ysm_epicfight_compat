package com.ysmef.compat.model.runtime;

/** Limits chosen for a whole physics chain before the frame solver runs. */
final class YsmPhysicsMotionLimits {
    private static final float CLOTH_DRAG_SCALE = 0.2F;
    private static final float REFERENCE_RUN_SPEED = 5.0F;
    private static final float FREE_RUN_TRAIL_TANGENT = (float) Math.tan(Math.toRadians(18.0D));

    private YsmPhysicsMotionLimits() {}

    /**
     * A skirt panel is a connected sheet. Giving each of its three bones the full joint
     * allowance lets the hem fold back over the waist, even though each joint is legal on
     * its own. Tails need the longer chain allowance, so only garments use a single
     * configured angle for their complete chain.
     */
    static float pieceLimit(YsmPhysicsParts.Segment segment, int joints, float maxAnglePerJoint) {
        float general = YsmPhysicsParts.chainLimitFor(joints, maxAnglePerJoint);
        return segment.category() == YsmPhysicsParts.Category.CLOTH
                ? Math.min(general, Math.max(0.0F, maxAnglePerJoint)) : general;
    }

    /**
     * The generic strand drag drives every short panel of the maid skirt into its stop at
     * running speed. Cloth retains the user's drag scaling up to the coefficient whose
     * free equilibrium is 18 degrees at five blocks per second. The threshold follows
     * this segment's mass, lever and spring, so a broad panel is not treated like a hair.
     */
    static float airDrag(YsmPhysicsParts.Segment segment, float configuredDrag) {
        if (segment.category() != YsmPhysicsParts.Category.CLOTH
                || !Float.isFinite(configuredDrag) || configuredDrag < 0.0F) {
            return configuredDrag;
        }
        float scaled = configuredDrag * CLOTH_DRAG_SCALE;
        if (!(segment.lever() > 0.0F) || !(segment.mass() > 0.0F)) {
            return scaled;
        }
        float frequency = (float) (2.0D * Math.PI * segment.frequency());
        float restoring = frequency * frequency
                + (float) YsmPhysicsTuning.gravityAcceleration() / segment.lever();
        float balanceCap = FREE_RUN_TRAIL_TANGENT * segment.mass()
                * segment.lever() * restoring / (REFERENCE_RUN_SPEED * REFERENCE_RUN_SPEED);
        return Float.isFinite(balanceCap) && balanceCap > 0.0F
                ? Math.min(scaled, balanceCap) : scaled;
    }
}
