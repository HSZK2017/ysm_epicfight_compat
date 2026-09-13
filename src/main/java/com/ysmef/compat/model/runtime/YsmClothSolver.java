package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A position-based cloth solver for the pieces of a converted model that hang and swing.
 *
 * <h2>Why cloth rather than a spring per bone</h2>
 *
 * <p>The first implementation put a damped spring on one rigid bone per hanging piece and
 * rotated that bone. That can only ever rotate the piece as a whole: every vertex of a
 * hair strand turns by the same angle, so the strand reads as a stiff card being flung
 * rather than as hair. It also had nothing to work with on most models - the bones named
 * for hair are the leaf strands, which have no bones under them, so a lever derived from
 * the bone hierarchy is zero and the piece cannot move at all.
 *
 * <p>Cloth fixes both by construction. Every vertex gets its own particle, only the top
 * row is pinned, and links between neighbouring particles carry the motion down the piece
 * with delay and overshoot. Nothing here needs a lever, and a strand bends along its
 * length instead of turning as one.
 *
 * <h2>The method</h2>
 *
 * <p>Position-based dynamics: integrate each free particle with Verlet, project the
 * distance constraints a fixed number of times, then resolve collisions. Constraints are
 * solved by projection rather than by force, which is what makes it stable at the frame
 * rates a phone actually produces - a force-based solver stiff enough to hold hair shape
 * has to be substepped to stay put, while this one cannot explode, because every
 * projection moves particles back onto a legal configuration.
 *
 * <p>Free of Minecraft types: the caller supplies positions and receives positions, so the
 * solve can be tested without a game.
 */
public final class YsmClothSolver {

    /** Gravity, blocks/s^2. */
    public static final float DEFAULT_GRAVITY = 14.0F;
    /** Velocity kept between frames. Below 1 the cloth loses energy instead of ringing. */
    public static final float DEFAULT_DAMPING = 0.86F;
    /** Constraint iterations per step. More is stiffer and costs linearly. */
    public static final int DEFAULT_ITERATIONS = 8;
    /** Radius of the body spheres the cloth is kept out of, blocks. */
    public static final float DEFAULT_BODY_RADIUS = 0.22F;
    /** How many particles one model may simulate, before the config says otherwise. */
    public static final int DEFAULT_MAX_PARTICLES = 4000;

    /** A step longer than this is clamped, so a lag spike cannot fling the cloth. */
    private static final float MAX_DT = 0.05F;
    /** Speed below which a particle counts as stopped, blocks/s. */
    private static final float SLEEP_SPEED = 0.002F;
    /** Structural links are held at full strength; bending links only shape the fold. */
    private static final float BEND_STIFFNESS = 0.25F;

    /**
     * A piece of cloth: its particles, the links between them, and the body it must stay
     * out of.
     *
     * <p>Allocated once per piece per model. Everything the solver touches lives in these
     * arrays, so a step allocates nothing.
     */
    public static final class Cloth {
        /** Current positions, model space. */
        final float[] x;
        final float[] y;
        final float[] z;
        /** Previous positions, for Verlet. */
        final float[] px;
        final float[] py;
        final float[] pz;
        /** Pinned particles follow the skeleton and are not simulated. */
        final boolean[] pinned;
        /** Per pinned particle: its bind offset in the pin bone's frame. */
        final float[] localX;
        final float[] localY;
        final float[] localZ;
        /** The body bone each pinned particle follows, or -1. */
        final int[] pinBone;
        /** The body bone each particle must stay outside, or -1. */
        final int[] avoidBone;
        /** How far outside that bone's axis the particle must stay, blocks. */
        final float[] avoidRadius;
        /** Distance links, as particle index pairs. */
        final int[] linkA;
        final int[] linkB;
        /** The length each link is held at, from the bind pose. */
        final float[] linkRest;
        /** How strongly each link is held: 1 structural, less for bending links. */
        final float[] linkStiffness;
        /**
         * The mesh vertex each particle belongs to, for writing the result back.
         *
         * <p>A vertex is shared between the triangles around it, so a particle and a vertex
         * are not one to one and the mapping has to be carried rather than assumed.
         */
        final int[] vertexOfParticle;
        /** How many particles are pinned, reported once so a silent failure is visible. */
        int pinnedCount;

        Cloth(int particleCount, int linkCount) {
            this.x = new float[particleCount];
            this.y = new float[particleCount];
            this.z = new float[particleCount];
            this.px = new float[particleCount];
            this.py = new float[particleCount];
            this.pz = new float[particleCount];
            this.pinned = new boolean[particleCount];
            this.localX = new float[particleCount];
            this.localY = new float[particleCount];
            this.localZ = new float[particleCount];
            this.pinBone = new int[particleCount];
            this.avoidBone = new int[particleCount];
            this.avoidRadius = new float[particleCount];
            this.vertexOfParticle = new int[particleCount];
            this.linkA = new int[linkCount];
            this.linkB = new int[linkCount];
            this.linkRest = new float[linkCount];
            this.linkStiffness = new float[linkCount];
            java.util.Arrays.fill(this.pinBone, -1);
            java.util.Arrays.fill(this.avoidBone, -1);
        }

        /** Number of particles, for the caller that writes the result back. */
        public int particleCount() {
            return x.length;
        }

        /** A particle's current position, model space. */
        public void position(int particle, Vector3f out) {
            out.set(x[particle], y[particle], z[particle]);
        }

        /** Whether a particle is pinned to the skeleton. */
        public boolean isPinned(int particle) {
            return pinned[particle];
        }

        /** How many particles hang from the skeleton rather than being simulated. */
        public int pinnedCount() {
            return pinnedCount;
        }
    }

    /** Builds a cloth with the given sizes, for the caller that knows the geometry. */
    public static Cloth allocate(int particleCount, int linkCount) {
        return new Cloth(particleCount, linkCount);
    }

    /** One solver; state lives entirely in the {@link Cloth}es it is given. */
    public static final YsmClothSolver INSTANCE = new YsmClothSolver();

    private YsmClothSolver() {}

    // Scratch, so a step allocates nothing.
    private final Vector3f carried = new Vector3f();
    private final Vector3f axisScratch = new Vector3f();

    /**
     * Mark a particle as hanging from a bone.
     *
     * <p>{@code localX/Y/Z} is the particle's bind offset already expressed in the bone's
     * bind frame; {@link #toLocal} converts one, and a piece with several pinned particles
     * on the same bone converts once and reuses the rotation.
     */
    public static void pin(Cloth cloth, int particle, int bone,
                           float localX, float localY, float localZ) {
        if (particle < 0 || particle >= cloth.pinned.length) {
            return;
        }
        if (!cloth.pinned[particle]) {
            cloth.pinnedCount++;
        }
        cloth.pinned[particle] = true;
        cloth.pinBone[particle] = bone;
        cloth.localX[particle] = localX;
        cloth.localY[particle] = localY;
        cloth.localZ[particle] = localZ;
    }

    /**
     * Express a model-space offset in a bone's bind frame.
     *
     * @param bindRotation the bone's bind rotation; {@code inverseBind} is its conjugate
     */
    public static void toLocal(Vector3f out, float modelX, float modelY, float modelZ,
                               Quaternionf inverseBind) {
        out.set(modelX, modelY, modelZ);
        if (inverseBind != null) {
            inverseBind.transform(out);
        }
    }

    /**
     * The rotation part of a bind matrix, as a quaternion.
     *
     * <p>A matrix that is not a rotation - a degenerate bind pose, a zero scale - yields
     * identity rather than a NaN that would propagate into every pinned particle.
     */
    public static void rotationOf(org.joml.Matrix4f matrix, Quaternionf out) {
        if (matrix == null) {
            out.identity();
            return;
        }
        out.setFromUnnormalized(matrix);
        if (!Float.isFinite(out.w()) || out.lengthSquared() < 1.0E-6F) {
            out.identity();
        } else {
            out.normalize();
        }
    }

    /** Set a particle's bind position, which is also where it starts. */
    public static void initParticle(Cloth cloth, int particle, float bx, float by, float bz) {
        cloth.x[particle] = bx;
        cloth.y[particle] = by;
        cloth.z[particle] = bz;
        cloth.px[particle] = bx;
        cloth.py[particle] = by;
        cloth.pz[particle] = bz;
    }

    /** Add a distance link between two particles. */
    public static void addLink(Cloth cloth, int link, int a, int b, float stiffness) {
        if (link < 0 || link >= cloth.linkA.length || a < 0 || b < 0
                || a >= cloth.x.length || b >= cloth.x.length) {
            return;
        }
        float dx = cloth.x[b] - cloth.x[a];
        float dy = cloth.y[b] - cloth.y[a];
        float dz = cloth.z[b] - cloth.z[a];
        cloth.linkA[link] = a;
        cloth.linkB[link] = b;
        cloth.linkRest[link] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        cloth.linkStiffness[link] = stiffness;
    }

    /** Structural links hold the cloth's shape; bending links only resist folding. */
    public static float structuralStiffness() {
        return 1.0F;
    }

    public static float bendingStiffness() {
        return BEND_STIFFNESS;
    }

    /**
     * Move the pinned particles to where the skeleton has carried them, then advance the
     * free ones by one step.
     *
     * @param cloth      the piece to advance
     * @param poseOfBone for each body bone, its rotation in the current pose, or null
     * @param jointPos   for each body bone, its origin in the current pose
     * @param boneCount  how many entries of those arrays are filled
     * @param dt         seconds since the last step (clamped internally)
     * @param tuning     the solve's shape, or null for the tuned defaults
     * @param maxVelocityPerStep per-particle displacement ceiling, blocks
     */
    public void step(Cloth cloth, Quaternionf[] poseOfBone, Vector3f[] jointPos, int boneCount,
                     float dt, float maxVelocityPerStep, YsmClothTuning tuning) {
        if (cloth == null || poseOfBone == null || jointPos == null
                || boneCount <= 0 || !(dt > 0.0F) || !Float.isFinite(dt)) {
            return;
        }
        YsmClothTuning shape = tuning == null ? YsmClothTuning.DEFAULTS : tuning;
        int iterations = Math.max(1, shape.iterations);
        float gravity = shape.gravity;
        float damping = shape.damping;
        float h = Math.min(dt, MAX_DT);
        float hh = h * h;

        // Pinned particles are not simulated: they follow the skeleton exactly, which is
        // what transmits the body's motion into the cloth and makes the piece hang from the
        // body rather than from a fixed point in space.
        for (int i = 0; i < cloth.pinned.length; i++) {
            if (!cloth.pinned[i]) {
                continue;
            }
            int bone = cloth.pinBone[i];
            if (bone < 0 || bone >= boneCount) {
                continue;
            }
            Quaternionf pose = poseOfBone[bone];
            Vector3f origin = jointPos[bone];
            if (pose == null || origin == null) {
                continue;
            }
            carried.set(cloth.localX[i], cloth.localY[i], cloth.localZ[i]);
            pose.transform(carried);
            cloth.px[i] = cloth.x[i];
            cloth.py[i] = cloth.y[i];
            cloth.pz[i] = cloth.z[i];
            cloth.x[i] = origin.x + carried.x;
            cloth.y[i] = origin.y + carried.y;
            cloth.z[i] = origin.z + carried.z;
        }

        // Verlet: the previous position is the velocity, so a particle keeps moving unless
        // a constraint or the damping takes it away.
        for (int i = 0; i < cloth.x.length; i++) {
            if (cloth.pinned[i]) {
                continue;
            }
            float oldX = cloth.x[i];
            float oldY = cloth.y[i];
            float oldZ = cloth.z[i];
            float vx = (oldX - cloth.px[i]) * damping;
            float vy = (oldY - cloth.py[i]) * damping;
            float vz = (oldZ - cloth.pz[i]) * damping;
            if (Math.abs(vx) < SLEEP_SPEED && Math.abs(vy) < SLEEP_SPEED && Math.abs(vz) < SLEEP_SPEED) {
                vx = 0.0F;
                vy = 0.0F;
                vz = 0.0F;
            }
            // A per-step velocity ceiling. Without it one bad frame - a model swap, a lag
            // spike, a teleport - hands the solver a displacement it will happily keep, and
            // the piece flies. The pinned particles are exempt: their motion is the body's.
            vx = clamp(vx, maxVelocityPerStep);
            vy = clamp(vy, maxVelocityPerStep);
            vz = clamp(vz, maxVelocityPerStep);
            cloth.px[i] = oldX;
            cloth.py[i] = oldY;
            cloth.pz[i] = oldZ;
            cloth.x[i] = oldX + vx;
            cloth.y[i] = oldY + vy - gravity * hh;
            cloth.z[i] = oldZ + vz;
        }

        for (int iteration = 0; iteration < iterations; iteration++) {
            solveLinks(cloth);
            solveCollisions(cloth, jointPos, boneCount);
        }
    }

    private static float clamp(float value, float limit) {
        if (!(limit > 0.0F)) {
            return value;
        }
        if (value > limit) {
            return limit;
        }
        return value < -limit ? -limit : value;
    }

    /**
     * Project every distance link back toward its rest length.
     *
     * <p>A pinned end takes the whole correction: that asymmetry is what keeps the piece
     * attached to the body while the rest of it swings.
     */
    private void solveLinks(Cloth cloth) {
        for (int l = 0; l < cloth.linkA.length; l++) {
            int a = cloth.linkA[l];
            int b = cloth.linkB[l];
            boolean pinA = cloth.pinned[a];
            boolean pinB = cloth.pinned[b];
            if (pinA && pinB) {
                continue;
            }
            float dx = cloth.x[b] - cloth.x[a];
            float dy = cloth.y[b] - cloth.y[a];
            float dz = cloth.z[b] - cloth.z[a];
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0E-5F) {
                continue;
            }
            float correction = (length - cloth.linkRest[l]) / length * cloth.linkStiffness[l];
            float cx = dx * correction;
            float cy = dy * correction;
            float cz = dz * correction;
            if (pinA) {
                cloth.x[b] -= cx;
                cloth.y[b] -= cy;
                cloth.z[b] -= cz;
            } else if (pinB) {
                cloth.x[a] += cx;
                cloth.y[a] += cy;
                cloth.z[a] += cz;
            } else {
                cloth.x[a] += cx * 0.5F;
                cloth.y[a] += cy * 0.5F;
                cloth.z[a] += cz * 0.5F;
                cloth.x[b] -= cx * 0.5F;
                cloth.y[b] -= cy * 0.5F;
                cloth.z[b] -= cz * 0.5F;
            }
        }
    }

    /**
     * Push particles out of the body bones they are tied to.
     *
     * <p>One sphere per particle, centred on the bone it was assigned and sized by the
     * caller from the model's own extent. This is what stops a skirt passing through a
     * thigh - something a spring on a bone can never do, because it has no idea where the
     * body is.
     */
    private void solveCollisions(Cloth cloth, Vector3f[] jointPos, int boneCount) {
        for (int i = 0; i < cloth.x.length; i++) {
            int bone = cloth.avoidBone[i];
            float radius = cloth.avoidRadius[i];
            if (bone < 0 || bone >= boneCount || !(radius > 0.0F)) {
                continue;
            }
            Vector3f centre = jointPos[bone];
            if (centre == null) {
                continue;
            }
            float dx = cloth.x[i] - centre.x;
            float dy = cloth.y[i] - centre.y;
            float dz = cloth.z[i] - centre.z;
            float distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq >= radius * radius) {
                continue;
            }
            float distance = (float) Math.sqrt(distanceSq);
            if (distance < 1.0E-5F) {
                // Dead centre: no shortest path exists. Push up rather than divide by zero.
                cloth.y[i] = centre.y + radius;
                continue;
            }
            float scale = radius / distance;
            cloth.x[i] = centre.x + dx * scale;
            cloth.y[i] = centre.y + dy * scale;
            cloth.z[i] = centre.z + dz * scale;
        }
    }
}
