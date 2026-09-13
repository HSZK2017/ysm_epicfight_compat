package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

/**
 * A position-based cloth solver for the pieces of a converted model that hang and swing.
 *
 * <h2>Why cloth rather than a spring per bone</h2>
 *
 * <p>The first implementation put a damped spring on one rigid bone per hanging piece and
 * rotated that bone. That can only ever rotate the piece as a whole: every vertex of a strand
 * turns by the same angle, so the strand reads as a stiff card being flung rather than as hair.
 *
 * <p>Cloth fixes that by construction. Every vertex gets a particle, only the attachment is
 * pinned, and links between neighbours carry the motion down the piece with delay and
 * overshoot. A strand bends along its length instead of turning as one.
 *
 * <h2>The method</h2>
 *
 * <p>Position-based dynamics: integrate each free particle with Verlet, project the distance
 * constraints a fixed number of times, then resolve collisions. Constraints are solved by
 * projection rather than by force, which is what makes it stable at the frame rates a phone
 * actually produces - and why there is no speed limit anywhere in here: a projection puts
 * particles on a legal configuration whatever speed they arrived at, so a clamp can only hold
 * the cloth back. One was tried at a quarter of a block per step, against a model whose bones
 * move three times that, and the cloth spent the session unable to catch up.
 *
 * <h2>Where a piece hangs from</h2>
 *
 * <p>A pinned particle is placed where the skinning path places the vertex it belongs to:
 * {@code pose(joint) x toOrigin(joint) x bindVertex}. Both matrices are passed in for that one
 * joint, because together they <i>are</i> the transform. Deriving the same position from the
 * bone's motion instead means crossing between the mesh's bind frame and the pose's own, and a
 * converted model's two frames do not have to agree - this one's {@code UpBody} binds at
 * (0, 0, 0) and poses at y = 1.09, which is what broke three attempts at reconstructing it.
 *
 * <p>Free of Minecraft types: the caller supplies matrices and positions, so the solve can be
 * tested without a game.
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
    /** Structural links hold the cloth's shape; bending links only resist folding. */
    private static final float BEND_STIFFNESS = 0.25F;

    /**
     * A piece of cloth: its particles, the links between them, and the body it must stay out of.
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
        /** Where each particle sat in bind pose, which is also where the solve starts. */
        final float[] bindX;
        final float[] bindY;
        final float[] bindZ;
        /** Pinned particles are placed from the joint matrices instead of being simulated. */
        final boolean[] pinned;
        /** The joint each pinned particle follows, or -1. */
        final int[] pinJoint;
        /** The body joint each particle must stay outside, or -1. */
        final int[] avoidJoint;
        /** How far outside that joint the particle must stay, blocks. */
        final float[] avoidRadius;
        /** Distance links, as particle index pairs. */
        final int[] linkA;
        final int[] linkB;
        /** The length each link is held at, from the bind pose. */
        final float[] linkRest;
        /** How strongly each link is held: 1 structural, less for bending links. */
        final float[] linkStiffness;
        /**
         * The mesh vertex each particle belongs to.
         *
         * <p>A vertex is shared between the triangles around it, so particles and vertices are
         * not one to one and the mapping has to be carried rather than assumed.
         */
        final int[] vertexOfParticle;
        /** How many particles are pinned, reported once so a silent failure is visible. */
        int pinnedCount;
        /** How far the free particles have stretched from their attachment, blocks. */
        float largestStretch;
        /** How far the attachment itself moved on the last step, blocks. */
        float pinJump;

        Cloth(int particleCount, int linkCount) {
            this.x = new float[particleCount];
            this.y = new float[particleCount];
            this.z = new float[particleCount];
            this.px = new float[particleCount];
            this.py = new float[particleCount];
            this.pz = new float[particleCount];
            this.bindX = new float[particleCount];
            this.bindY = new float[particleCount];
            this.bindZ = new float[particleCount];
            this.pinned = new boolean[particleCount];
            this.pinJoint = new int[particleCount];
            this.avoidJoint = new int[particleCount];
            this.avoidRadius = new float[particleCount];
            this.vertexOfParticle = new int[particleCount];
            this.linkA = new int[linkCount];
            this.linkB = new int[linkCount];
            this.linkRest = new float[linkCount];
            this.linkStiffness = new float[linkCount];
            java.util.Arrays.fill(this.pinJoint, -1);
            java.util.Arrays.fill(this.avoidJoint, -1);
        }

        /** Number of particles, for the caller that writes the result back. */
        public int particleCount() {
            return x.length;
        }

        /** The mesh vertex a particle belongs to. */
        public int vertexOf(int particle) {
            return vertexOfParticle[particle];
        }

        /** A particle's current position, model space. */
        public void position(int particle, Vector3f out) {
            out.set(x[particle], y[particle], z[particle]);
        }

        /**
         * Where the piece hangs from now, model space: the centroid of its pinned particles.
         *
         * <p>What a swing has to be measured against. Measuring from where the piece was
         * <i>bound</i> instead reports the body's own travel as if it were the cloth's motion -
         * a player walking across the room reads as a piece displaced seven blocks, which says
         * nothing about whether the cloth is behaving.
         */
        public void attachment(Vector3f out) {
            out.set(0.0F, 0.0F, 0.0F);
            int found = 0;
            for (int i = 0; i < pinned.length; i++) {
                if (pinned[i]) {
                    out.add(x[i], y[i], z[i]);
                    found++;
                }
            }
            if (found > 0) {
                out.div(found);
            }
        }

        /** Whether a particle is pinned to the skeleton. */
        public boolean isPinned(int particle) {
            return pinned[particle];
        }

        /** How many particles hang from the skeleton rather than being simulated. */
        public int pinnedCount() {
            return pinnedCount;
        }

        /** How far the free particles have stretched away from their attachment, blocks. */
        public float largestStretch() {
            return largestStretch;
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
    private final Vector3f jointSpace = new Vector3f();
    private final yesman.epicfight.api.utils.math.Vec4f posed4 = new yesman.epicfight.api.utils.math.Vec4f();

    /** Set a particle's bind position, which is also where it starts. */
    public static void initParticle(Cloth cloth, int particle, float bx, float by, float bz) {
        cloth.x[particle] = bx;
        cloth.y[particle] = by;
        cloth.z[particle] = bz;
        cloth.px[particle] = bx;
        cloth.py[particle] = by;
        cloth.pz[particle] = bz;
        cloth.bindX[particle] = bx;
        cloth.bindY[particle] = by;
        cloth.bindZ[particle] = bz;
    }

    /** Add a distance link between two particles, held at the length the bind pose gives it. */
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
     * Mark a particle as hanging from a joint.
     *
     * <p>The particle is not held at a position derived from the bone: it is placed from that
     * joint's own matrices on every step - see {@link #step}.
     */
    public static void pin(Cloth cloth, int particle, int joint) {
        if (particle < 0 || particle >= cloth.pinned.length) {
            return;
        }
        if (!cloth.pinned[particle]) {
            cloth.pinnedCount++;
        }
        cloth.pinned[particle] = true;
        cloth.pinJoint[particle] = joint;
    }

    /**
     * Advance one piece by one step.
     *
     * @param cloth     the piece to advance
     * @param poses     the live pose matrix of each joint
     * @param toOrigin  the inverse bind matrix of each joint
     * @param boneCount how many entries of those arrays are filled
     * @param dt        seconds since the last step (clamped internally)
     * @param tuning    the solve's shape, or null for the tuned defaults
     */
    public void step(Cloth cloth, OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin, int boneCount,
                     float dt, YsmClothTuning tuning) {
        if (cloth == null || poses == null || toOrigin == null
                || boneCount <= 0 || !(dt > 0.0F) || !Float.isFinite(dt)) {
            return;
        }
        YsmClothTuning shape = tuning == null ? YsmClothTuning.DEFAULTS : tuning;
        int substeps = Math.max(1, shape.substeps);
        float frame = Math.min(dt, MAX_DT);
        // The lattice is advanced in substeps because the drag it has to follow is not the
        // frame's motion but the substep's: a body moving 0.1 blocks in a frame against links
        // 0.05 long hands each constraint sweep about one link of propagation to work with, and
        // the piece is left permanently behind by however much the sweep cannot carry. Dividing
        // the same motion into four keeps every sweep's job inside the lattice's own scale.
        //
        // Gravity and damping are per <i>frame</i> quantities, so they are applied on the last
        // substep at the full frame's step rather than a fraction of it. Applying them per
        // substep would set gravity to four times its configured value - which is exactly what
        // the first version of this did, and it left the cloth creeping after the body stopped
        // instead of settling.
        for (int sub = 0; sub < substeps - 1; sub++) {
            substep(cloth, poses, toOrigin, boneCount, frame / substeps, shape, false);
        }
        substep(cloth, poses, toOrigin, boneCount, frame / substeps, shape, true);
        measureStretch(cloth);
    }

    /** One substep: carry the pins, integrate the free particles, then project. */
    private void substep(Cloth cloth, OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin, int boneCount,
                         float dt, YsmClothTuning shape, boolean applyForces) {
        int iterations = Math.max(1, shape.iterations);
        float gravity = applyForces ? shape.gravity : 0.0F;
        float damping = applyForces ? shape.damping : 1.0F;
        float h = Math.min(dt, MAX_DT);
        float hh = h * h;

        // Pinned particles are placed exactly where the skinning path places their vertex:
        // pose x toOrigin x bindVertex. This is the whole attachment - no reconstruction, and
        // therefore nothing that can disagree with the geometry it is holding.
        float jump = 0.0F;
        for (int i = 0; i < cloth.pinned.length; i++) {
            if (!cloth.pinned[i]) {
                continue;
            }
            int joint = cloth.pinJoint[i];
            if (joint < 0 || joint >= boneCount || joint >= poses.length || joint >= toOrigin.length
                    || poses[joint] == null || toOrigin[joint] == null) {
                continue;
            }
            jointSpace.set(cloth.bindX[i], cloth.bindY[i], cloth.bindZ[i]);
            // pose x toOrigin is the joint's skinning matrix, so this is exactly what the
            // renderer does to the vertex the particle stands for.
            OpenMatrix4f skin = OpenMatrix4f.mul(poses[joint], toOrigin[joint], null);
            posed4.set(jointSpace.x, jointSpace.y, jointSpace.z, 1.0F);
            OpenMatrix4f.transform(skin, posed4, posed4);
            float dx = posed4.x - cloth.x[i];
            float dy = posed4.y - cloth.y[i];
            float dz = posed4.z - cloth.z[i];
            jump = Math.max(jump, distance(dx, dy, dz));
            cloth.px[i] = cloth.x[i];
            cloth.py[i] = cloth.y[i];
            cloth.pz[i] = cloth.z[i];
            cloth.x[i] = posed4.x;
            cloth.y[i] = posed4.y;
            cloth.z[i] = posed4.z;
        }
        cloth.pinJump = jump;

        // Verlet: the previous position is the velocity, so a particle keeps moving unless a
        // constraint or the damping takes it away.
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
            cloth.px[i] = oldX;
            cloth.py[i] = oldY;
            cloth.pz[i] = oldZ;
            cloth.x[i] = oldX + vx;
            cloth.y[i] = oldY + vy - gravity * hh;
            cloth.z[i] = oldZ + vz;
        }

        for (int iteration = 0; iteration < iterations; iteration++) {
            solveLinks(cloth);
            solveCollisions(cloth, poses, boneCount);
        }
    }

    /**
     * The largest distance any pinned particle moved on the last step, blocks.
     *
     * <p>The pins are placed from the joint matrices, so this is how far the body moved its
     * attachment in one frame - and it is the number that decides whether the solve can keep
     * up. A piece whose attachment jumps further than its own links are long leaves the free
     * particles behind by construction: the constraint pass can only move them a fraction of
     * that distance per iteration, so a large enough jump is a stretch no iteration count can
     * remove, and the fix belongs upstream of the solver rather than in it.
     */
    public static float pinJump(Cloth cloth) {
        return cloth.pinJump;
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
     * Push particles out of the body they are next to.
     *
     * <p>One sphere per particle, centred on the body joint it was assigned and sized by the
     * caller. The centre comes from that joint's pose translation - the same matrices the pins
     * use - so the cloth is pushed out of where the body actually is. This is what stops a
     * skirt passing through a thigh, which a spring on a bone can never do because it has no
     * idea where the body is.
     */
    private void solveCollisions(Cloth cloth, OpenMatrix4f[] poses, int boneCount) {
        for (int i = 0; i < cloth.x.length; i++) {
            int joint = cloth.avoidJoint[i];
            float radius = cloth.avoidRadius[i];
            if (joint < 0 || joint >= boneCount || joint >= poses.length || !(radius > 0.0F)
                    || poses[joint] == null) {
                continue;
            }
            OpenMatrix4f pose = poses[joint];
            float dx = cloth.x[i] - pose.m30;
            float dy = cloth.y[i] - pose.m31;
            float dz = cloth.z[i] - pose.m32;
            float distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq >= radius * radius) {
                continue;
            }
            float distance = (float) Math.sqrt(distanceSq);
            if (distance < 1.0E-5F) {
                // Dead centre: no shortest path exists. Push up rather than divide by zero.
                cloth.y[i] = pose.m31 + radius;
                continue;
            }
            float scale = radius / distance;
            cloth.x[i] = pose.m30 + dx * scale;
            cloth.y[i] = pose.m31 + dy * scale;
            cloth.z[i] = pose.m32 + dz * scale;
        }
    }

    /**
     * How far the free particles have been pulled from where they were bound, in blocks.
     *
     * <p>Measured against the pinned particles rather than against the bind pose, so it is
     * independent of where the body has moved to and reports only the cloth's own deformation.
     * Near zero means the solve is holding the piece together; a value that grows with time
     * means something is pulling it apart.
     */
    private static void measureStretch(Cloth cloth) {
        float pinX = 0.0F, pinY = 0.0F, pinZ = 0.0F;
        float bindPinX = 0.0F, bindPinY = 0.0F, bindPinZ = 0.0F;
        int pins = 0;
        for (int i = 0; i < cloth.pinned.length; i++) {
            if (cloth.pinned[i]) {
                pinX += cloth.x[i];
                pinY += cloth.y[i];
                pinZ += cloth.z[i];
                bindPinX += cloth.bindX[i];
                bindPinY += cloth.bindY[i];
                bindPinZ += cloth.bindZ[i];
                pins++;
            }
        }
        if (pins == 0) {
            cloth.largestStretch = 0.0F;
            return;
        }
        pinX /= pins;
        pinY /= pins;
        pinZ /= pins;
        bindPinX /= pins;
        bindPinY /= pins;
        bindPinZ /= pins;
        float largest = 0.0F;
        for (int i = 0; i < cloth.x.length; i++) {
            if (cloth.pinned[i]) {
                continue;
            }
            float now = distance(cloth.x[i] - pinX, cloth.y[i] - pinY, cloth.z[i] - pinZ);
            float atBind = distance(cloth.bindX[i] - bindPinX, cloth.bindY[i] - bindPinY,
                    cloth.bindZ[i] - bindPinZ);
            largest = Math.max(largest, Math.abs(now - atBind));
        }
        cloth.largestStretch = largest;
    }

    private static float distance(float dx, float dy, float dz) {
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The worst a single link has been stretched past its rest length, as a fraction.
     *
     * <p>The direct check on whether the constraints are doing their job, and the one that
     * separates a solve that is falling apart from a measurement that is misreading it: if the
     * links are holding, the piece is holding together whatever the aggregate numbers say.
     *
     * <p>Read it together with {@link #worstLinkRestLength}: a fraction is only meaningful
     * against the length it is a fraction of. A single short link - the two vertices of a
     * degenerate triangle, say - reports a large percentage for an absolute error too small to
     * see, which is how a lattice that is actually held can keep reporting half its length.
     */
    public static float worstLinkStretch(Cloth cloth) {
        return worstLink(cloth)[0];
    }

    /** The rest length of the link {@link #worstLinkStretch} is reporting, in blocks. */
    public static float worstLinkRestLength(Cloth cloth) {
        return worstLink(cloth)[1];
    }

    private static final float[] worstLinkScratch = new float[2];

    /** {worst relative stretch, that link's rest length}. */
    private static float[] worstLink(Cloth cloth) {
        float worst = 0.0F;
        float worstRest = 0.0F;
        for (int l = 0; l < cloth.linkA.length; l++) {
            if (cloth.linkRest[l] < 1.0E-5F) {
                continue;
            }
            float dx = cloth.x[cloth.linkB[l]] - cloth.x[cloth.linkA[l]];
            float dy = cloth.y[cloth.linkB[l]] - cloth.y[cloth.linkA[l]];
            float dz = cloth.z[cloth.linkB[l]] - cloth.z[cloth.linkA[l]];
            float ratio = distance(dx, dy, dz) / cloth.linkRest[l];
            float stretch = Math.abs(ratio - 1.0F);
            if (stretch > worst) {
                worst = stretch;
                worstRest = cloth.linkRest[l];
            }
        }
        worstLinkScratch[0] = worst;
        worstLinkScratch[1] = worstRest;
        return worstLinkScratch;
    }

    /** The rotation part of a matrix, as a quaternion; identity when it is not a rotation. */
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
}
