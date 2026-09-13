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
    /**
     * How far a body bone may move in one frame before the change is treated as not motion.
     *
     * <p>Well above anything a real animation produces between two frames - a sprinting
     * entity moves a few centimetres - and well below the scale of a frame mismatch, which is
     * the failure this guards against. See advancePins.
     */
    private static final float MAX_BONE_STEP = 1.0F;

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
        /** Where each particle sat in bind pose, kept for the displacement measurement. */
        final float[] bindX;
        final float[] bindY;
        final float[] bindZ;
        /** Pinned particles follow the skeleton and are not simulated. */
        final boolean[] pinned;
        /** Where each pinned particle sits at bind, and where the skeleton has carried it. */
        final float[] pinBindX;
        final float[] pinBindY;
        final float[] pinBindZ;
        final float[] pinX;
        final float[] pinY;
        final float[] pinZ;
        /** Each pinned particle's bind offset from the bone it follows, in model space. */
        final float[] pinOffsetX;
        final float[] pinOffsetY;
        final float[] pinOffsetZ;
        /** The body bone each pinned particle follows, or -1. */
        final int[] pinBone;
        /** The body bone each particle must stay outside, or -1. */
        final int[] avoidBone;
        /** How far outside that bone's axis the particle must stay, blocks. */
        final float[] avoidRadius;
        /** Where each body bone sat on the previous step, to measure its motion. */
        final float[] lastOriginX;
        final float[] lastOriginY;
        final float[] lastOriginZ;
        final boolean[] lastOriginValid;
        /** The point a bone's rotation change turns its pinned particles about. */
        final float[] anchorX;
        final float[] anchorY;
        final float[] anchorZ;
        /** The bone's rotation on the previous step, for measuring how it turned. */
        final Quaternionf[] lastRotation;
        final boolean[] lastRotationValid;
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
        /** How many bone steps were too large to be motion, for the one-line report. */
        long rejectedSteps;
        /** The largest bone step accepted as motion, blocks. */
        float largestBoneStep;
        /**
         * How far the pinned bones have moved from where the piece was built.
         *
         * <p>Accumulated here rather than derived from the current position, because the pins
         * are placed at a fixed anchor plus this: a running total cannot drift out of step
         * with the reference position the way two independently updated quantities can.
         */
        float totalDx;
        float totalDy;
        float totalDz;

        Cloth(int particleCount, int linkCount, int boneSlots) {
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
            this.pinBindX = new float[particleCount];
            this.pinBindY = new float[particleCount];
            this.pinBindZ = new float[particleCount];
            this.pinX = new float[particleCount];
            this.pinY = new float[particleCount];
            this.pinZ = new float[particleCount];
            this.pinOffsetX = new float[particleCount];
            this.pinOffsetY = new float[particleCount];
            this.pinOffsetZ = new float[particleCount];
            this.pinBone = new int[particleCount];
            this.avoidBone = new int[particleCount];
            this.avoidRadius = new float[particleCount];
            this.vertexOfParticle = new int[particleCount];
            this.lastOriginX = new float[Math.max(1, boneSlots)];
            this.lastOriginY = new float[Math.max(1, boneSlots)];
            this.lastOriginZ = new float[Math.max(1, boneSlots)];
            this.lastOriginValid = new boolean[Math.max(1, boneSlots)];
            this.anchorX = new float[Math.max(1, boneSlots)];
            this.anchorY = new float[Math.max(1, boneSlots)];
            this.anchorZ = new float[Math.max(1, boneSlots)];
            this.lastRotation = new Quaternionf[Math.max(1, boneSlots)];
            this.lastRotationValid = new boolean[Math.max(1, boneSlots)];
            for (int b = 0; b < this.lastRotation.length; b++) {
                this.lastRotation[b] = new Quaternionf();
            }
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

        /**
         * How much the piece's free particles have stretched away from where they hang.
         *
         * <p>Measured as the change in distance from each free particle to the piece's pinned
         * centroid, against the same distance at bind. That form is deliberate: it is
         * independent of any frame, so it cannot be fooled by the pins and the particles
         * being expressed differently - which is exactly what a plain displacement from the
         * bind position could not tell apart, and what made one of this feature's faults look
         * like "the cloth moved nine blocks" while standing still.
         *
         * <p>Near zero means the cloth is tracking its attachment, which is correct; a value
         * that grows with time means the solve is being pulled apart.
         */
        public float largestStretch() {
            float pinX = 0.0F, pinY = 0.0F, pinZ = 0.0F;
            float bindPinX = 0.0F, bindPinY = 0.0F, bindPinZ = 0.0F;
            int pins = 0;
            for (int i = 0; i < pinned.length; i++) {
                if (pinned[i]) {
                    pinX += x[i];
                    pinY += y[i];
                    pinZ += z[i];
                    bindPinX += bindX[i];
                    bindPinY += bindY[i];
                    bindPinZ += bindZ[i];
                    pins++;
                }
            }
            if (pins == 0) {
                return 0.0F;
            }
            pinX /= pins;
            pinY /= pins;
            pinZ /= pins;
            bindPinX /= pins;
            bindPinY /= pins;
            bindPinZ /= pins;

            float largest = 0.0F;
            for (int i = 0; i < x.length; i++) {
                if (pinned[i]) {
                    continue;
                }
                float now = distance(x[i] - pinX, y[i] - pinY, z[i] - pinZ);
                float atBind = distance(bindX[i] - bindPinX, bindY[i] - bindPinY, bindZ[i] - bindPinZ);
                largest = Math.max(largest, Math.abs(now - atBind));
            }
            return largest;
        }

        private static float distance(float dx, float dy, float dz) {
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** Where the piece hangs from now, model space: the centroid of its pinned
         * particles.
         *
         * <p>The caller measures a part's swing against this rather than against the bind
         * pivot, because only this moves with the body. Zeros when nothing is pinned, which
         * cannot happen for a piece that was built.
         */
        public void pinnedPosition(Vector3f out) {
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
    }

    /** Builds a cloth with the given sizes, for the caller that knows the geometry. */
    public static Cloth allocate(int particleCount, int linkCount, int boneSlots) {
        return new Cloth(particleCount, linkCount, boneSlots);
    }

    /** One solver; state lives entirely in the {@link Cloth}es it is given. */
    public static final YsmClothSolver INSTANCE = new YsmClothSolver();

    private YsmClothSolver() {}

    // Scratch, so a step allocates nothing.
    private final Vector3f axisScratch = new Vector3f();
    private static final Quaternionf scratchChange = new Quaternionf();
    private static final Vector3f scratchOffset = new Vector3f();

    /**
     * Mark a particle as hanging from a bone.
     *
     * <p>The particle is held at its bind position and moved by however much the bone moves
     * from there - not placed at an offset from the bone's origin. The difference matters
     * because the bind side of a converted model cannot be trusted to agree with its pose
     * side: this mod's own test model binds every bone at (0, 0, 0) while posing them at
     * their real heights, so an offset read from the bind side puts the pin most of a block
     * away from the geometry it is supposed to hold, and the links then drag the piece
     * across the model. Holding the particle where it is and adding the bone's own motion
     * needs no bind matrix at all.
     *
     * @param bone the body bone this particle follows
     */
    public static void pin(Cloth cloth, int particle, int bone) {
        if (particle < 0 || particle >= cloth.pinned.length) {
            return;
        }
        if (!cloth.pinned[particle]) {
            cloth.pinnedCount++;
        }
        cloth.pinned[particle] = true;
        cloth.pinBone[particle] = bone;
        cloth.pinBindX[particle] = cloth.x[particle];
        cloth.pinBindY[particle] = cloth.y[particle];
        cloth.pinBindZ[particle] = cloth.z[particle];
        cloth.pinX[particle] = cloth.x[particle];
        cloth.pinY[particle] = cloth.y[particle];
        cloth.pinZ[particle] = cloth.z[particle];
    }

    /**
     * Record where a bone the pinned particles follow was on the previous step.
     *
     * <p>Only the bone's change from one step to the next is used, never the difference from
     * its bind position. The two frames of a converted model do not agree - this mod's test
     * model binds every bone at (0, 0, 0) while posing them at their real heights - so a
     * particle placed from a bind origin inherits that disagreement as a jump of a whole
     * block on the first frame.
     */
    static void recordPreviousOrigin(Cloth cloth, int bone, float x, float y, float z) {
        if (cloth == null || bone < 0 || bone >= cloth.lastOriginX.length) {
            return;
        }
        cloth.lastOriginX[bone] = x;
        cloth.lastOriginY[bone] = y;
        cloth.lastOriginZ[bone] = z;
        cloth.lastOriginValid[bone] = true;
    }

    /** Whether a bone's previous position is known, so its motion can be measured. */
    static boolean hasPreviousOrigin(Cloth cloth, int bone) {
        return cloth != null && bone >= 0 && bone < cloth.lastOriginValid.length
                && cloth.lastOriginValid[bone];
    }

    /**
     * Take the bone's current position as the reference its next motion is measured from,
     * without moving anything.
     *
     * <p>Called once when the piece is built. Without it the solver's first step would
     * measure the bone's motion against the zero it was initialised with, and read the
     * bone's entire height as a step - which is a jump of 1.09 blocks on this mod's test
     * model, dragging the pinned particles clear off their geometry before the cloth has
     * moved at all.
     */
    public static void seedPreviousOrigin(Cloth cloth, int bone, Vector3f origin) {
        if (origin == null || cloth == null || bone < 0 || bone >= cloth.lastOriginX.length) {
            return;
        }
        recordPreviousOrigin(cloth, bone, origin.x, origin.y, origin.z);
    }

    /**
     * Carry the pinned particles of one bone to where that bone has just moved.
     *
     * <p>Three things move a hanging piece, and all three are needed: the bone's translation,
     * its rotation, and - for the first frame - the particle itself being at the right place
     * to begin with. The translation and rotation are both taken as changes between
     * consecutive steps, which is what lets this work on a model whose bind and pose frames
     * disagree; the particle's own starting point is its bind vertex, so the attachment never
     * leaves the geometry it belongs to.
     *
     * @param rotation the bone's rotation in the current pose
     */
    static void advancePins(Cloth cloth, int bone, float x, float y, float z, Quaternionf rotation) {
        if (cloth == null || bone < 0 || bone >= cloth.lastOriginX.length) {
            return;
        }
        float dx = x - cloth.lastOriginX[bone];
        float dy = y - cloth.lastOriginY[bone];
        float dz = z - cloth.lastOriginZ[bone];

        // A body bone cannot move further than this in one frame. A larger change is not
        // motion: it is the pose being read differently - a model swap, a respawn, the first
        // frame after the entity was replaced - and treating it as motion drags the whole
        // piece across the model at the velocity ceiling, every frame, for as long as the
        // difference persists.
        float step = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (step > MAX_BONE_STEP) {
            cloth.rejectedSteps++;
            recordPreviousOrigin(cloth, bone, x, y, z);
            return;
        }
        if (step > cloth.largestBoneStep) {
            cloth.largestBoneStep = step;
        }
        // The bone's accumulated displacement from where the piece was built, advanced before
        // the pins are placed from it.
        cloth.totalDx += dx;
        cloth.totalDy += dy;
        cloth.totalDz += dz;

        // The change in the bone's rotation, applied about the pin's anchor so the piece
        // swings with the bone rather than only following its origin.
        Quaternionf change = null;
        if (rotation != null && cloth.lastRotationValid[bone]) {
            change = scratchChange.set(cloth.lastRotation[bone]).conjugate().mul(rotation);
        }
        for (int i = 0; i < cloth.pinned.length; i++) {
            if (!cloth.pinned[i] || cloth.pinBone[i] != bone) {
                continue;
            }
            float ox = cloth.pinBindX[i] - cloth.anchorX[bone];
            float oy = cloth.pinBindY[i] - cloth.anchorY[bone];
            float oz = cloth.pinBindZ[i] - cloth.anchorZ[bone];
            if (change != null) {
                scratchOffset.set(ox, oy, oz);
                change.transform(scratchOffset);
                ox = scratchOffset.x;
                oy = scratchOffset.y;
                oz = scratchOffset.z;
            }
            // Where the bone has carried this particle: its bind point moved by how far the
            // bone has moved <i>from where it was bound</i>. The anchor does not advance with
            // the bone - a moving anchor is what made the bone's step get applied twice per
            // frame, which is a piece that creeps at the velocity ceiling forever.
            cloth.px[i] = cloth.x[i];
            cloth.py[i] = cloth.y[i];
            cloth.pz[i] = cloth.z[i];
            cloth.x[i] = cloth.anchorX[bone] + ox + cloth.totalDx;
            cloth.y[i] = cloth.anchorY[bone] + oy + cloth.totalDy;
            cloth.z[i] = cloth.anchorZ[bone] + oz + cloth.totalDz;
        }

        recordPreviousOrigin(cloth, bone, x, y, z);
        if (rotation != null) {
            cloth.lastRotation[bone].set(rotation);
            cloth.lastRotationValid[bone] = true;
        }
    }

    /**
     * Anchor the pinned particles of a bone at their bind attachment, about which the bone's
     * rotation change is applied.
     *
     * <p>Called once while building, with the bone's pose for the frame the model was first
     * seen: from then on the piece follows that bone exactly.
     */
    public static void anchorPins(Cloth cloth, int bone, float x, float y, float z, Quaternionf rotation) {
        if (cloth == null || bone < 0 || bone >= cloth.anchorX.length) {
            return;
        }
        cloth.anchorX[bone] = x;
        cloth.anchorY[bone] = y;
        cloth.anchorZ[bone] = z;
        if (rotation != null) {
            cloth.lastRotation[bone].set(rotation);
            cloth.lastRotationValid[bone] = true;
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
        cloth.bindX[particle] = bx;
        cloth.bindY[particle] = by;
        cloth.bindZ[particle] = bz;
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
     * @param cloth      the piece to advance
     * @param jointPos   for each body bone, its origin in the current pose
     * @param poseOfBone for each body bone, its rotation in the current pose, or null
     * @param boneCount  how many entries of those arrays are filled
     * @param dt         seconds since the last step (clamped internally)
     * @param tuning     the solve's shape, or null for the tuned defaults
     * @param maxVelocityPerStep per-particle displacement ceiling, blocks
     */
    public void step(Cloth cloth, Vector3f[] jointPos, Quaternionf[] poseOfBone, int boneCount,
                     float dt, YsmClothTuning tuning) {
        if (cloth == null || jointPos == null
                || boneCount <= 0 || !(dt > 0.0F) || !Float.isFinite(dt)) {
            return;
        }
        YsmClothTuning shape = tuning == null ? YsmClothTuning.DEFAULTS : tuning;
        int iterations = Math.max(1, shape.iterations);
        float gravity = shape.gravity;
        float damping = shape.damping;
        float h = Math.min(dt, MAX_DT);
        float hh = h * h;

        // Pinned particles are not simulated: they are moved by however far their bone has
        // moved since the previous step, and the links carry that motion into the rest of the
        // piece. Measuring the bone's change rather than its offset from a bind position is
        // what keeps the pin on its own geometry - see recordPreviousOrigin.
        for (int bone = 0; bone < boneCount; bone++) {
            Vector3f origin = jointPos[bone];
            if (origin == null) {
                continue;
            }
            advancePins(cloth, bone, origin.x, origin.y, origin.z,
                    poseOfBone == null ? null : poseOfBone[bone]);
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
            // Deliberately no per-particle speed clamp. One was here, at a quarter of a block
            // per step, and it is what left the cloth permanently behind: a body bone on this
            // model moves up to 0.9 blocks in a step, which is more than twice that, so the
            // free particles could never catch up and the piece grew a stretch that only got
            // larger with time. The distance constraints are the real limit and they do not
            // need help - a projection places particles on a legal configuration whatever
            // speed they arrived at, which is the whole reason this is position-based.
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
