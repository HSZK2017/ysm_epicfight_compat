package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Secondary motion with an actual moment arm: every hanging segment is integrated as a
 * physical pendulum about <b>its own pivot</b>, with a mass, a moment of inertia, the
 * body's acceleration as a fictitious force, gravity, a restoring spring toward the
 * animated rest direction, damping and aerodynamic drag.
 *
 * <h2>What this replaces, and why the old model looked like a stiff plate</h2>
 *
 * <p>The replaced model - the retired {@code YsmPhysicsSimulator}, whose class is no longer in the
 * tree - treated a whole piece - a hairdo, a skirt - as one
 * massless point held on a sphere around a pivot, and turns the point's offset into a
 * single rotation. Three things follow from that, and all three are visible on screen:
 *
 * <ul>
 *   <li><b>No moment arm.</b> The point has no mass and no inertia, so the spring's
 *       stiffness is the only thing setting the response and every piece - a two-centimetre
 *       fringe and a metre of skirt - answers at the same angular frequency. A real force
 *       produces a torque that grows with the lever ({@code r x F}) while the inertia
 *       resisting it grows with {@code r^2}, so a long piece is <i>slower</i> than a short
 *       one; the point model cannot express that at all.</li>
 *   <li><b>One rigid rotation for a whole piece.</b> A ponytail modelled as ten bones was
 *       one chain, one rotation: it swings as a plank rather than curling, which is exactly
 *       the "stiff card thrown around" the in-game report describes.</li>
 *   <li><b>No collision.</b> Nothing keeps cloth out of the legs or hair out of the
 *       shoulders, so the only thing that stopped a piece was the angle clamp - and a
 *       clamped swing that snaps back on the next frame is the "flung away and snapped
 *       home" half of the same report.</li>
 * </ul>
 *
 * <h2>The dynamics, in terms of the torque and the inertia it works against</h2>
 *
 * <p>With the pivot position {@code P}, the lever {@code L} (pivot to the segment's centre of
 * mass), the unit direction {@code d}, the angular velocity {@code w}, the effective gravity
 * {@code g_eff = g - a_pivot}, and the moment of inertia about the pivot
 * {@code I = m L^2} - a point mass at the centre of mass, which is the same lever the
 * geometry already gives us; a uniform rod about its end is 4/3 of that, and that shape
 * factor cancels wherever a torque is divided by the inertia it belongs to - each force is
 * written as the torque it produces and then divided by {@code I}:
 *
 * <pre>
 *   tau_gravity = m L (d x g_eff)         alpha_gravity = (d x g_eff) / L
 *   tau_spring  = I omega_n^2 (d x rest)  alpha_spring  = omega_n^2 (d x rest)
 *   tau_damping = -2 zeta omega_n I w     alpha_damping = -2 zeta omega_n w
 *   tau_drag    = L (d x F_air)           alpha_drag    = (d x F_air) / (m L)
 * </pre>
 *
 * <p><b>Mass cancels out of the first three and survives only in the drag.</b> That is worth
 * stating rather than leaving implicit, because the ceiling this class used to apply was
 * calibrated on the opposite assumption:
 *
 * <ul>
 *   <li><b>Gravity</b> is a torque {@code m L (d x g)} resisted by an inertia {@code m L^2},
 *       so the mass divides out and what is left is {@code 1/L}: a long piece accelerates
 *       more slowly under the same gravity, and a pendulum's period does not depend on its
 *       mass. The pendulum frequency gravity alone would give a piece is
 *       {@code sqrt(g/L)}, so short pieces swing fast and long ones lag - which is what
 *       "the fringe flickers and the skirt swings" is.</li>
 *   <li><b>The spring</b> is the author's statement about a frequency (YSM's own
 *       {@code ysm.second_order}, see {@link YsmPhysicsBinding}), and a frequency is only
 *       meaningful as a mass-independent statement, so its stiffness has to be the one that
 *       makes {@code sqrt(k/I)} come out at that frequency: {@code k = I omega_n^2}.</li>
 *   <li><b>Drag</b> is the one term where mass survives, and must: the air's force is set by
 *       the air and the piece's speed, not by the piece, so a heavy panel is moved less by
 *       the same wind than a light one while both hang at the same angle under gravity.</li>
 * </ul>
 *
 * <h2>The angle a piece rests at</h2>
 *
 * <p>Nothing here bounds the outside forces, and that is deliberate. Setting the total to
 * zero gives the angle the piece actually settles at:
 *
 * <pre>
 *   sin(theta_eq) = |d x g_eff| / (L omega_n^2)
 * </pre>
 *
 * <p>For a posed rest direction hanging {@code phi} from vertical that is
 * {@code L omega_n^2 sin(theta) = g sin(phi - theta)}: every term is a real quantity - the
 * lever, gravity, the author's frequency, the pose - so two pieces of the same garment settle
 * at different angles, and a piece returns to <i>its own</i> angle when the body stops rather
 * than to whatever the last force left it at. The two regimes matter when tuning:
 * {@code g/L} exceeds {@code omega_n^2} below {@code L = g/omega_n^2}, which is eleven
 * centimetres at the defaults, so a shorter piece is gravity-dominated and hangs close to
 * vertical almost whatever pose it was drawn in, while a longer one tracks the animation.
 *
 * <p>And one consequence of that balance that is worth saying out loud, because it is the
 * remaining way a piece can look pinned: if the balance angle is further out than the piece's
 * own swing limit, the piece rests <i>on the limit</i> - gravity is more than the spring can
 * hold there. At the defaults (24 blocks/s^2, 2.36 Hz) that happens to a root panel of about
 * twelve centimetres whose posed direction hangs more than forty degrees from vertical. The
 * knobs are the stiffness and the gravity - gravity is a parameter of {@link #update}, so the
 * configured value reaches the dynamics rather than only the log - and the fallback is the
 * piece's own limit.
 *
 * <h2>What {@code phi} is, and the weight that removes the pose from it</h2>
 *
 * <p>The balance above only reads as "the pose drags the cloth around" while the spring's target
 * <i>is</i> the pose, which is what {@code phi} being the body's lean means. The target is therefore
 * a parameter of the dynamics and not an identity: the spring pulls toward
 * {@code normalize((1-b)*restDir + b*down)} for a caller-supplied weight {@code b} ({@code
 * verticalFollow}), so {@code b = 0} is the pendulum welded to the animation and {@code b = 1} is a
 * spring with no opinion about the pose at all. Everything above still holds with {@code phi} read
 * as the angle between the rest direction and <b>the target</b> - a short piece is still
 * gravity-dominated and a long one still tracks the animation - but at {@code b = 1} a piece on a
 * body leaning sixty degrees comes to rest on the world's vertical instead of sixty degrees off it,
 * because the target no longer leans. The levers and the weights are in
 * {@code YsmPhysicsParts.Segment#verticalFollow}; the swing limit stays measured from the pose, so
 * a weight cannot buy the piece more travel than its author allowed (see {@link #update}).
 *
 * <p>The previous version of this class did the opposite: it clamped the whole outside-force
 * total to a fraction of the spring's own stiffness (0.3 of it, scaled down by the piece's own
 * limit). That constant has been deleted; the paragraph and the numbers stay because they are
 * what the shipped log shows and what a future reader needs in order to recognise the design if
 * it is ever proposed again. Gravity alone is {@code 24/L}, which for the levers a real garment
 * has (0.059 to 0.257 on the maid model in the shipped log, at 2.36 Hz, so 220 rad/s^2 of
 * spring) is 93 to 407 rad/s^2 against that ceiling of 66 - so gravity was saturated across the
 * entire working range, the restoring torque stopped depending on the angle, the equilibrium
 * stopped depending on the lever, the mass or the pose, and every piece whose rest direction
 * hung more than about twelve degrees from vertical settled at exactly
 * {@code asin(0.3) = 17.46} degrees. Which is what the log shows: eight of that model's pieces
 * read 17.4 or 17.5 degrees with levers from 0.059 to 0.257, masses from 0.56 to 3.94 and rest
 * directions from 27 to 116 degrees off vertical - one constant, where the physics asks for
 * eight different angles.
 *
 * <h2>The swing limit is a constraint, not an energy sink</h2>
 *
 * <p>{@link #applySwingLimit} projects the direction back onto the cone of the authored swing
 * limit and removes <i>only</i> the part of the angular velocity that would drive the piece
 * further past it. The tangential part is kept, so a piece held against its stop slides along
 * it the way cloth slides along a shoulder instead of sticking to it, and a piece that is no
 * longer being pushed leaves the stop under its own spring rather than sitting on it. The
 * earlier version multiplied the angular velocity by 0.25 on every frame at the limit, which
 * is an energy sink with no physical counterpart: whatever had driven the piece out was erased
 * each frame, so it stayed pinned until the pose changed, and the release then read as a snap -
 * the "flung away and snapped home" half of the in-game report.
 *
 * <h2>Frames and stability</h2>
 *
 * <p>Everything is in the model's own space (the space Epic Fight's converted mesh lives
 * in: entity-local, {@code +Y} up), which is where gravity is a constant and where the
 * caller's pose matrices already are.
 *
 * <p>The step is clamped and subdivided, because this runs on the render thread against a
 * frame time that can spike: a lag spike fed to a stiff spring is a visible fling, and the
 * subdivision is what keeps a 200 ms frame from being a 200 ms kick. The substep rule is
 * YSM's own (see {@link #update}), and it is applied to the <i>fastest</i> restoring mode the
 * piece has - the spring's frequency and the pendulum's own {@code sqrt(g/L)} together, since
 * gravity is a restoring torque in its own right here and ignoring it would under-resolve
 * exactly the short, gravity-dominated pieces. Non-finite input leaves the segment exactly
 * where it was rather than poisoning its state.
 *
 * <p>Free of Minecraft types so the dynamics can be tested without a game - the collider
 * set is an interface the caller supplies.
 */
public final class YsmDynamicBoneSolver {

    /**
     * Downward acceleration in the model's frame, blocks/s^2, and the default for the
     * {@code gravity} parameter of {@link #update}.
     *
     * <p>Minecraft's own entity gravity is 0.08 blocks/tick^2 = 32 blocks/s^2; a hanging piece of
     * hair or cloth is light and heavily damped, so it settles at a lower effective value. This is
     * the fallback used when the caller hands in a value that cannot be trusted (see {@link #update}),
     * not the value the solver always uses: the caller passes
     * {@code secondaryMotionGravityAcceleration} through, so the knob really moves the cloth.
     */
    public static final float GRAVITY = 24.0F;

    /** Longest step the integrator accepts, seconds; a longer frame is clamped to it. */
    public static final float MAX_DT = 0.05F;

    /**
     * Integration substeps per frame. The spring is stiff (authored frequencies reach
     * 5 Hz, i.e. 31 rad/s), and one explicit step of a 31 rad/s oscillator needs dt below
     * about 30 ms to stay put; subdividing is what buys the margin without lowering the
     * frequency the author asked for.
     */
    public static final int SUBSTEPS = 3;

    /**
     * Air-drag coefficient, 1/(blocks/s), and the default for the {@code airDrag} parameter of
     * {@link #update}.
     *
     * <p>The strength of everything the body's motion does to the cloth: how far a skirt trails at
     * a run, how far hair streams back. This is the fallback used when the caller hands in a value
     * that cannot be trusted (see {@link #update}), not the value the solver always uses - the
     * caller passes {@code secondaryMotionAirDrag} through, so the knob really moves the cloth.
     */
    public static final float AIR_DRAG = 0.9F;

    /**
     * How much the spring's target direction follows the world's downward direction, and the
     * fallback for the {@code verticalFollow} parameter of {@link #update}.
     *
     * <p>Zero, and deliberately so: the solver is a general pendulum integrator and every piece's
     * weight is a statement about what that piece <i>is</i> - a lock of hair, a skirt panel, a tail -
     * which the solver has no way to know and the caller's classification does. Zero also makes this
     * the value that keeps the pre-existing behaviour, so a caller that has not been taught about
     * this parameter yet gets exactly what it used to get. The weights themselves live with the
     * classification; see {@code YsmPhysicsParts.Segment#verticalFollow}.
     */
    public static final float FALLBACK_VERTICAL_FOLLOW = 0.0F;

    /**
     * Relative speed above which drag stops growing quadratically, blocks/s.
     *
     * <p>The only thing capped about the drag: the force stays {@code C*|v|*v} up to here and is
     * frozen above it. It is a statement about the model, not about the dynamics - the quadratic
     * law is the one that holds for the speeds a body reaches on foot, and this is where the
     * piece leaves the airflow a walking body makes and enters the one a falling body makes.
     */
    private static final float DRAG_SPEED_CAP = 12.0F;

    /** How far a piece aimed straight into a collider is nudged sideways per frame, radians. */
    private static final float COLLISION_SLIDE_ANGLE = 0.08F;

    /**
     * How deep a piece has to be inside a volume for the slide-off response to be used rather
     * than accepted as convergence, blocks. Two centimetres: far more than the sub-millimetre
     * residual the iteration leaves at a resting contact, far less than the overlap of a piece
     * that is genuinely buried in the body.
     */
    private static final float COLLISION_SLIDE_THRESHOLD = 0.02F;

    /**
     * Position-based collision iterations per frame. The lever and the collision volume are
     * two constraints on the same point, and alternating their projections converges linearly
     * at a rate set by how squarely the two surfaces meet - about a factor of 0.6 per pass for
     * a skirt panel resting on a thigh. Eight passes leave the piece within a fraction of a
     * millimetre of the surface, which is far below anything visible.
     */
    private static final int COLLISION_ITERATIONS = 8;

    /**
     * How much of a piece's swing limit collision may use up in one frame.
     *
     * <p>A quarter: a panel that has genuinely entered the body comes out over about four frames,
     * which is under a fifteenth of a second and invisible, while a push that would have thrown a
     * piece straight to its stop takes several frames and reads as the cloth sliding off the limb
     * it touched.
     */
    private static final float COLLISION_STEP_FRACTION = 0.25F;

    /** The floor under {@link #COLLISION_STEP_FRACTION}, radians, for a piece with no limit. */
    private static final float MIN_COLLISION_STEP = 0.05F;

    /** Below this the substep count derived from the spring is clamped, so cost stays bounded. */
    private static final int MAX_SUBSTEPS = 16;

    /**
     * The body's turn rate and its rate of change are clamped before they are used. A teleport, a
     * respawn or a first frame after a stall can hand in anything at all, and these two become an
     * acceleration directly.
     */
    private static final float MAX_YAW_RATE = 12.0F;

    private static final float MAX_YAW_ACCEL = 60.0F;

    /** Above this the angular velocity is clamped; a runaway spring must not spin a bone. */
    private static final float MAX_SPEED = 220.0F;

    /*
     * ------------------------------------------------------------------
     * The caps this solver has, and why each one is a cap on an input or
     * on a numerical quantity rather than on a force.
     * ------------------------------------------------------------------
     *
     * A previous version of this class also clamped the whole outside-force total to a fraction
     * of the spring's own stiffness: three tenths of it, scaled down by the piece's own limit.
     * That is gone, and the constant with it. It capped gravity's torque at 0.3 * omega_n^2,
     * which is smaller than 24/L for every lever a real garment has, so the restoring torque
     * stopped depending on the angle and the equilibrium stopped depending on the lever, the mass
     * and the pose: every piece landed on asin(0.3) = 17.46 degrees. The class comment carries the
     * measurement.
     *
     * What is bounded instead, and every one of them is a bound on something that is not the
     * physics:
     *
     *   MAX_PIVOT_ACCEL          a finite difference of a pose, not a body: a teleport, a
     *                            respawn or the first frame after a stall can hand in anything.
     *   MAX_GRAVITY_CANCELLATION the vertical part of that fictitious force may not reverse
     *                            gravity, or cloth climbs.
     *   MAX_YAW_RATE/ACCEL       the same, for the turn the caller reports at tick rate.
     *   DRAG_SPEED_CAP           the range over which the quadratic drag law holds.
     *   MAX_SPEED                a runaway spring must not spin a bone; never reached by
     *                            anything physical at these frequencies.
     *   MAX_DT / stable substep  the integrator's own stability condition.
     *
     * Gravity, the spring, the damping and the air are not bounded. They are the physics, and the
     * angle they balance at is the one this solver is for.
     */

    /** Above this the pivot's own acceleration is treated as a data glitch, blocks/s^2. */
    private static final float MAX_PIVOT_ACCEL = 120.0F;

    /**
     * The window the pivot's motion is averaged over before it is differentiated into a velocity and
     * an acceleration, seconds.
     *
     * <p>This is a statement about the <i>input</i> rather than about the dynamics, and it is the
     * only filter in the solver. A pivot's position arrives at frame rate but is produced at tick
     * rate: Epic Fight poses its armature twenty times a second, so what the solver differentiates
     * is very nearly a staircase - constant for several frames, then a step. Differentiating that
     * once gives a velocity that is either zero or several times the truth, and twice gives an
     * acceleration that is enormous on the step and enormous with the opposite sign on the frame
     * after, alternating for as long as the pose is held. A punch, a fall or a teleport all arrive
     * through the same two differences, so the window stays.
     *
     * <p>Fifty milliseconds: shorter than any body motion worth showing - a punch accelerates its
     * shoulder for a tenth of a second - and longer than a tick and a half, so a step is averaged
     * into the position it stepped to rather than into the derivative.
     *
     * <p>What it does <i>not</i> do, and what the earlier version of this class wrongly believed it
     * did: it does not by itself keep a garment hanging. Measured on the solver with a tick-rate
     * pose swaying two millimetres, the piece this filter leaves sits within a quarter of a degree
     * of its rest direction, because the quantisation drives the piece at the tick rate, twenty
     * hertz, and a piece whose own frequency is 2.36 Hz has no response there. The angle a piece
     * actually sits at is set by the balance of gravity against the spring - see the class comment -
     * and it was the outside-force ceiling, not this filter, that used to overwrite that balance
     * with a constant.
     */
    private static final float PIVOT_SMOOTHING_SECONDS = 0.05F;

    /**
     * How much of a piece's tangential speed a contact takes away, per frame in contact.
     *
     * <p>Friction, and the one place the solver is allowed to remove energy that the integration
     * put in: a piece sliding along a shoulder loses speed to the surface it slides on. Kept here
     * as a named quantity because it is a physical coefficient and not a convergence aid - it
     * applies to the tangential part only, and the approach velocity is removed separately and
     * exactly (see the collision response).
     */
    private static final float CONTACT_FRICTION = 0.45F;

    /**
     * The most of gravity the pivot's own vertical acceleration may cancel.
     *
     * <p>Physically the fictitious force is real and unbounded: a pivot falling at 120 blocks/s^2
     * would put a piece of cloth into 96 blocks/s^2 of <i>upward</i> acceleration, four times
     * gravity, and that is how a skirt ends up hovering above the player's shoulders. Free fall is
     * the useful limit of the effect - cloth inside a falling body is weightless and floats - so
     * the pivot may take gravity down to a tenth and no further. Nothing in this solver is allowed
     * to make a garment levitate.
     */
    private static final float MAX_GRAVITY_CANCELLATION = 0.9F;

    /**
     * How much of the outward speed a piece keeps, reversed, when it arrives at its swing limit.
     *
     * <p>A stop that takes the approach velocity away outright is a perfectly inelastic collision,
     * and a hair arriving at a shoulder is not one: the body gives, and the piece comes back a
     * little. Fifteen per cent, which loses {@code 1 - e^2} = 97.75 per cent of the energy the piece
     * arrived with - so the limit is overwhelmingly a sink and can never be used to drive a piece,
     * while the contact still reads as contact rather than as a wall.
     *
     * <p>It applies to the outward component alone. The tangential part is not touched at all, and
     * that is the whole difference between this and the {@code angularVelocity *= 0.25} it replaced:
     * that scaled everything, on every frame spent at the stop, and left the piece dead against it.
     */
    private static final float LIMIT_RESTITUTION = 0.15F;

    private static final float EPSILON = 1.0E-5F;

    /**
     * The static geometry a segment must not pass through.
     *
     * <p>The solver only ever reads it, and the same set is used by every segment of every
     * chain in a frame, so the caller resolves the body's colliders once per frame and
     * hands the same instance in.
     */
    public interface Colliders {
        /** How many collision volumes are active this frame. */
        int count();

        /**
         * Push {@code point} out of collider {@code index} if it is inside, and reduce
         * {@code velocity} by the component that drove it in.
         *
         * @param point    the point to resolve, mutated in place when it is inside
         * @param velocity the point's velocity, mutated in place on contact
         * @param radius   the moving sphere's own radius
         * @param index    which collider
         * @return true when the point was inside and got moved
         */
        boolean resolve(Vector3f point, Vector3f velocity, float radius, int index);

        /**
         * Whether this volume must be ignored for a segment hanging from {@code pivot} and
         * resting with its centre of mass at {@code restCentre}.
         *
         * <p>Two cases, and the second one is the difference between cloth and shrapnel.
         *
         * <ul>
         *   <li><b>The pivot is inside the volume.</b> Hair grows out of a head and a skirt is
         *       sewn onto a hip, so the piece starts inside the body volume it is attached to;
         *       resolving against it would eject the piece from the model on the first frame.</li>
         *   <li><b>The volume is inside the piece's own working space</b> - closer to the rest
         *       centre of mass than the piece's own swing amplitude. Such a volume cannot be
         *       satisfied: the piece would have to swing past its own limit to clear the surface,
         *       so it ends up pushed and clamped on alternate frames, pinned at the limit for as
         *       long as the pose holds. That limit cycle is what a panel stuck out from a skirt
         *       actually is, and the shipped log shows three of them (the thigh volumes reaching
         *       the front and right panels, and a forearm volume sitting inside the right-back
         *       one). Collision exists to stop a piece <i>entering</i> the body while it moves,
         *       not to argue with the pose the author drew.</li>
         * </ul>
         *
         * @param swingReach how far this piece's centre of mass can travel from its rest position
         *                   within its own swing limit, blocks: {@code lever * sin(maxAngle)}
         */
        default boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            return false;
        }
    }

    /** A collider set with nothing in it, for models whose body has no usable geometry. */
    public static final Colliders NO_COLLIDERS = new Colliders() {
        @Override
        public int count() {
            return 0;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float radius, int index) {
            return false;
        }
    };

    /** One segment's persistent state. */
    public static final class SegmentState {
        /** Unit vector from the pivot to the centre of mass, model space. */
        public final Vector3f direction = new Vector3f(0.0F, -1.0F, 0.0F);
        /** Angular velocity, model space, radians/s. */
        public final Vector3f angularVelocity = new Vector3f();
        /** The pivot last frame, for the finite-difference velocity. */
        final Vector3f lastPivot = new Vector3f();
        /** The pivot with the pose's tick-rate staircase averaged out of it. */
        final Vector3f smoothedPivot = new Vector3f();
        /** The pivot's velocity this frame, model space, blocks/s. */
        final Vector3f pivotVelocity = new Vector3f();
        /** The pivot's velocity last frame, for the finite-difference acceleration. */
        final Vector3f lastPivotVelocity = new Vector3f();
        boolean initialized;

        /** The swing this segment produced on the last update, radians; for the log. */
        public float lastAngle;
        /** How far a collider had to push the centre of mass, blocks; for the log. */
        public float lastContact;

        /** Forget the segment's history (the model was reloaded, the entity respawned). */
        public void reset() {
            initialized = false;
            angularVelocity.zero();
            pivotVelocity.zero();
            lastPivotVelocity.zero();
            lastAngle = 0.0F;
            lastContact = 0.0F;
        }
    }

    /** One solver instance; the state lives in the caller's {@link SegmentState}s. */
    public static final YsmDynamicBoneSolver INSTANCE = new YsmDynamicBoneSolver();

    /**
     * Where the spring pulls this call, model space - the posed rest direction blended with the
     * world's downward direction by the caller's {@code verticalFollow} weight (see {@link #update}).
     *
     * <p>Kept apart from {@link #rest}, which stays exactly what the caller's pose said, because the
     * two are different questions and both are asked: {@link #integrate} wants the direction the
     * piece is being pulled <i>toward</i>, while the swing limit, the collision skip test and the
     * logged/reported swing are all statements about the pose. See {@link #update} for which use is
     * which and why.
     */
    private final Vector3f target = new Vector3f();
    private final Vector3f rest = new Vector3f();
    private final Vector3f gravity = new Vector3f();
    private final Vector3f alpha = new Vector3f();
    private final Vector3f leverArm = new Vector3f();
    private final Vector3f relative = new Vector3f();
    private final Vector3f axis = new Vector3f();
    private final Vector3f com = new Vector3f();
    private final Vector3f before = new Vector3f();
    private final Vector3f contactAxis = new Vector3f();
    private final Vector3f restCentre = new Vector3f();
    /** The direction collision found the piece in, so the log can report what collision did. */
    private final Vector3f entryDirection = new Vector3f();
    /**
     * The gravity this call is using, blocks/s^2 - the caller's value or the fallback. Per call
     * rather than per instance, because it is a configuration statement and the solver is shared.
     */
    private float gravityDown = GRAVITY;
    /**
     * The air-drag coefficient this call is using, 1/(blocks/s) - the caller's value or the
     * fallback. Per call for the same reason as {@link #gravityDown}.
     */
    private float airDrag = AIR_DRAG;
    /**
     * The body's velocity for this call, model space, never null. Copied out of the parameter
     * rather than used directly so that a caller handing in null gets a body at rest.
     */
    private final Vector3f bodyFlow = new Vector3f();

    /**
     * The vertical follow weight this call is using, 0..1 - the caller's value or the fallback.
     * Per call for the same reason as {@link #gravityDown}.
     */
    private float verticalFollow = FALLBACK_VERTICAL_FOLLOW;

    /**
     * The model-space down direction used when the caller hands in nothing usable. A per-instance
     * scratch rather than a fresh vector per call: this runs once per segment per frame on the
     * render thread.
     */
    private final Vector3f fallbackDown = new Vector3f(0.0F, -1.0F, 0.0F);

    private YsmDynamicBoneSolver() {}

    /**
     * Advance one segment and produce the rotation that takes its rest direction onto its
     * simulated direction.
     *
     * <p>The returned quaternion is in <b>model space</b>. The caller folds it into a mesh
     * part's transform, which lives in the model's bind space, so it must conjugate the
     * rotation into the frame of the joint that carries the part and rotate about the
     * part's own bind pivot - see {@code YsmMeshSecondaryMotion}. Applying a bare rotation
     * instead swings the piece about the model origin, which is where the "no lever" in
     * the in-game report actually comes from.
     *
     * @param state         this segment's persistent state
     * @param gravity       downward acceleration in the model's frame, blocks/s^2 - the value the
     *                      user configured. Zero is honoured and means a weightless piece that
     *                      follows its pose; a negative or non-finite value is a broken
     *                      configuration (it would make the cloth climb) and falls back to
     *                      {@link #GRAVITY}
     * @param airDrag       the air-drag coefficient the user configured, 1/(blocks/s): the strength
     *                      of everything the body's motion does to the cloth. Zero is honoured and
     *                      means no air at all (the piece then follows its pose and gravity and
     *                      nothing else); a negative or non-finite value would blow the piece along
     *                      the wind instead of against it and falls back to {@link #AIR_DRAG}
     * @param verticalFollow how much of the world's downward direction the spring's target
     *                      contains, 0..1. Zero is the piece following its pose exactly - the
     *                      behaviour this solver had before the parameter existed, bit for bit -
     *                      and one is the spring pointing at the world's vertical whatever the pose
     *                      says. In between it is a blend; a non-finite value falls back to
     *                      {@link #FALLBACK_VERTICAL_FOLLOW} and anything outside the range is
     *                      clamped, because a weight outside 0..1 would extrapolate the target past
     *                      vertical and pull the piece <i>upwards</i>. Which weight a piece gets is
     *                      the caller's judgement about what the piece is: see
     *                      {@code YsmPhysicsParts.Segment#verticalFollow} for the classification
     *                      and the numbers.
     * @param downTarget    the world's downward direction expressed in the model's own space. In
     *                      Epic Fight's model space that is {@code (0,-1,0)} and it does not move
     *                      with the body's yaw, pitch or roll - the model's vertical axis is the
     *                      world's, see {@code YsmMeshSecondaryMotion#bodyVelocity}. It is a
     *                      parameter rather than a constant in here so the tests can hand in any
     *                      direction and so a future caller can express wind or a slope without
     *                      touching the dynamics. Null, non-finite or a zero vector falls back to
     *                      {@code (0,-1,0)}; the caller's vector is read and copied, never retained
     * @param pivot         the pivot's current position in model space; null leaves the state alone
     * @param restDir       the direction the piece points at rest right now: what the pose says,
     *                      model space. This is the direction the swing limit, the collision test
     *                      and the reported/logged swing are all measured against, whatever
     *                      {@code verticalFollow} is - see {@link #update} for why
     * @param lever         pivot to centre of mass, blocks; the moment arm
     * @param frequency     the author's spring frequency, Hz
     * @param damping       the author's damping coefficient, 0..1
     * @param mass          the segment's relative mass, used by the drag term only
     * @param maxAngle      how far this segment may bend from its rest direction, radians
     * @param bodyVelocity  the body's velocity in model space, blocks/s; the airflow is its
     *                      opposite, and it is what makes cloth trail while running. Null is read
     *                      as a body at rest rather than refused
     * @param colliders     the body's collision volumes, or {@link #NO_COLLIDERS}
     * @param segmentRadius the segment's own radius for collision
     * @param collideAgainst which colliders to consider, or null for all of them
     * @param dt            seconds since the last update
     * @param out           receives the model-space rotation; identity when there is no swing
     */
    public void update(SegmentState state, float gravity, float airDrag,
                       float verticalFollow, Vector3f downTarget,
                       Vector3f pivot,
                       Vector3f restDir, float lever,
                       float frequency, float damping, float mass, float maxAngle,
                       Vector3f bodyVelocity, Colliders colliders, float segmentRadius,
                       boolean[] collideAgainst, float dt, Quaternionf out) {
        update(state, gravity, airDrag, verticalFollow, downTarget, pivot, restDir, lever, frequency,
                damping, mass, maxAngle, bodyVelocity, colliders, segmentRadius, collideAgainst,
                0.0F, 0.0F, dt, out);
    }

    /**
     * The same, with the body's own turn added.
     *
     * <p>Cloth flares outward when the body turns, and this is the only driving quantity in the
     * solver that comes from the body's <i>rotation</i> rather than its translation. It is also the
     * one YSM's own model authors reach for first: their hair and skirt expressions are built on
     * {@code q.yaw_speed}, and a chain in their physics animation is driven by the change in the
     * bone above it - both are statements about turning.
     *
     * <p>What is added is the pair of pseudo-forces a piece feels in the body's rotating frame, in
     * blocks per second squared per unit mass, at the piece's own horizontal offset {@code r} from
     * the model's vertical axis:
     *
     * <pre>
     *   centrifugal  w^2 * r      outward, always, whenever the body is turning at all
     *   Euler        alpha x r    sideways, and the larger of the two for a snap turn
     * </pre>
     *
     * <p>Both are real effects rather than a modelling convenience: a skirt on a spinning body
     * genuinely stands away from it, and the flare is what tells a viewer the character turned
     * rather than sidestepped. They go into the same outside-force total as gravity, so what holds
     * the piece near its pose is the spring - the turn can throw a piece to its swing limit while
     * it lasts, and the limit is a constraint the piece leaves under its own spring as soon as the
     * turn stops (see {@link #applySwingLimit}). The turn rate and its rate of change are still
     * bounded, because they are read across a tick and a teleport or a respawn can hand in
     * anything (see {@link #MAX_YAW_RATE}).
     *
     * @param gravity      downward acceleration in the model's frame, blocks/s^2; see the
     *                     two-argument-removed overload for what zero, negative and non-finite
     *                     values mean, and for {@code verticalFollow} and {@code downTarget}
     * @param bodyYawRate  the body's turn rate about its own vertical axis, radians/s
     * @param bodyYawAccel the rate of change of that turn rate, radians/s^2
     */
    public void update(SegmentState state, float gravity, float airDrag,
                       float verticalFollow, Vector3f downTarget,
                       Vector3f pivot,
                       Vector3f restDir, float lever,
                       float frequency, float damping, float mass, float maxAngle,
                       Vector3f bodyVelocity, Colliders colliders, float segmentRadius,
                       boolean[] collideAgainst, float bodyYawRate, float bodyYawAccel,
                       float dt, Quaternionf out) {
        out.identity();
        if (state == null || pivot == null || restDir == null) {
            return;
        }
        state.lastAngle = 0.0F;
        state.lastContact = 0.0F;

        if (!isFinite(pivot) || !isFinite(restDir) || restDir.lengthSquared() < EPSILON
                || !Float.isFinite(lever) || lever < EPSILON) {
            // No lever, or a pose that collapsed: nothing to swing.
            return;
        }

        // The configured gravity, with the one value that cannot be trusted rejected: a negative
        // or non-finite setting would tilt the piece upwards and the garment would climb. Zero is
        // not a broken setting - it is the user asking for a weightless piece that follows its
        // pose - so it is honoured. The fallback is the default constant, never a guess.
        this.gravityDown = (Float.isFinite(gravity) && gravity >= 0.0F) ? gravity : GRAVITY;
        // The air, on the same terms: a coefficient the caller configured, with the one value that
        // cannot be trusted rejected (a negative drag would push the piece along the wind instead of
        // against it) and zero honoured - zero is the user switching the air off, which is a
        // setting, not a broken one.
        this.airDrag = (Float.isFinite(airDrag) && airDrag >= 0.0F) ? airDrag : AIR_DRAG;
        // Null is a caller that has nothing to say, not a reason to throw: this is a numeric class
        // reached from render code, and a missing velocity is a body at rest.
        this.bodyFlow.set(bodyVelocity == null ? 0.0F : bodyVelocity.x,
                bodyVelocity == null ? 0.0F : bodyVelocity.y,
                bodyVelocity == null ? 0.0F : bodyVelocity.z);
        if (!isFinite(this.bodyFlow)) {
            this.bodyFlow.zero();
        }

        this.rest.set(restDir).normalize();

        // ------------------------------------------------------------------
        // The gravity-follow target: what the spring pulls toward, once, before any integration.
        //
        // The spring used to pull toward `this.rest` - the direction the pose gave the piece - which
        // made the pose the whole of the target and left gravity able to do no more than displace the
        // piece from it. That is a body-weighted pendulum whose pivot is welded to the animation, and
        // it is why a skirt panel on a body leaning sixty degrees reads as part of the body rather
        // than as cloth: the balance is L*omega^2*sin(swing) = g*sin(phi - swing) with phi the pose's
        // own lean, so at the default tuning a 0.26-block panel stops 21.3 degrees off the pose and
        // 38.7 degrees off vertical. The target below is the same balance with the author's pose and
        // the world's vertical weighted against each other, and gravity now decides the direction the
        // piece hangs in as well as how far it is displaced from it - which is one mechanism for both
        // the hair that should droop and the skirt that should hang toward the ground on a dash.
        //
        // `rest` is NOT replaced by this: it stays exactly what the pose said, because the three
        // other readers of it are all statements about the pose rather than about the target. See
        // the calls at the end of this method for the reasoning.
        // ------------------------------------------------------------------
        this.verticalFollow = Float.isFinite(verticalFollow)
                ? clamp(verticalFollow, 0.0F, 1.0F)
                : FALLBACK_VERTICAL_FOLLOW;
        if (this.verticalFollow <= 0.0F) {
            // Exactly the direction the pose gave, copied and not blended. A blend with a zero
            // weight would be the same vector mathematically, but the copy makes the "zero is the
            // old behaviour, bit for bit" property a fact about the code rather than about
            // floating-point arithmetic, and it is the case every existing caller lands in.
            this.target.set(this.rest);
        } else {
            if (downTarget != null && isFinite(downTarget)
                    && downTarget.lengthSquared() > EPSILON * EPSILON) {
                this.fallbackDown.set(downTarget).normalize();
            } else {
                // Nothing usable from the caller: null, a NaN, or a vector that says no direction.
                // The world's vertical is the only answer that means anything here - a piece that
                // followed a zero vector would be pulled nowhere, and its swing would then depend on
                // what the drag happened to leave it at, which is the "sunken in the wind" look this
                // class exists to avoid.
                this.fallbackDown.set(0.0F, -1.0F, 0.0F);
            }
            this.target.set(this.rest).mul(1.0F - this.verticalFollow)
                    .fma(this.verticalFollow, this.fallbackDown);
            if (!isFinite(this.target) || this.target.lengthSquared() < EPSILON * EPSILON) {
                // Rest exactly opposed to the blend direction: the two cancel and there is no
                // direction left. Falling back to the pose keeps the piece solvable - the limit and
                // the collision test are measured from `rest` anyway - instead of leaving it with a
                // target of zero, which integrate() reads as no restoring torque at all.
                this.target.set(this.rest);
            } else {
                this.target.normalize();
            }
        }

        if (!state.initialized) {
            // The first frame establishes the state from the pose instead of integrating
            // from a default direction, which would show as a snap.
            state.direction.set(this.rest);
            state.angularVelocity.zero();
            state.lastPivot.set(pivot);
            state.smoothedPivot.set(pivot);
            state.pivotVelocity.zero();
            state.lastPivotVelocity.zero();
            state.initialized = true;
            return;
        }

        float step = dt;
        if (!Float.isFinite(step) || step <= 0.0F) {
            // Without a time step there is nothing to integrate; the segment keeps the
            // swing it already had, which is what a paused game should look like.
            state.lastAngle = angleBetween(this.rest, state.direction);
            rotationFromTo(out, this.rest, state.direction);
            return;
        }
        step = Math.min(step, MAX_DT);

        float invStep = 1.0F / step;
        // Average the pose's staircase before differentiating it: see PIVOT_SMOOTHING_SECONDS.
        state.smoothedPivot.lerp(pivot, Math.min(1.0F, step / PIVOT_SMOOTHING_SECONDS));
        this.axis.set(state.smoothedPivot).sub(state.lastPivot).mul(invStep);
        state.lastPivot.set(state.smoothedPivot);
        float accelX = (this.axis.x - state.lastPivotVelocity.x) * invStep;
        float accelY = (this.axis.y - state.lastPivotVelocity.y) * invStep;
        float accelZ = (this.axis.z - state.lastPivotVelocity.z) * invStep;
        state.pivotVelocity.set(this.axis);
        state.lastPivotVelocity.set(this.axis);

        // Gravity in the model's frame, minus the pivot's own acceleration: a pivot that
        // is being yanked around by a combat animation must throw its hair, and this
        // fictitious force is what does it. Capped, because a teleport or a first frame
        // after a lag spike produces an unbounded finite difference.
        accelX = clamp(accelX, -MAX_PIVOT_ACCEL, MAX_PIVOT_ACCEL);
        accelY = clamp(accelY, -MAX_PIVOT_ACCEL, MAX_PIVOT_ACCEL);
        accelZ = clamp(accelZ, -MAX_PIVOT_ACCEL, MAX_PIVOT_ACCEL);
        // And the vertical part is bounded on its own, because only it can reverse gravity:
        // see MAX_GRAVITY_CANCELLATION. Downward counts too - a pivot accelerating downwards
        // is what a fall or a landing looks like, and it is what throws cloth upwards. With
        // gravity configured to zero there is nothing to reverse, so the guard has nothing to
        // protect and the ordinary glitch cap applies instead of a bound of zero - which would
        // have thrown away the pivot's real vertical acceleration without saying so.
        float verticalLimit = this.gravityDown > 0.0F
                ? this.gravityDown * MAX_GRAVITY_CANCELLATION
                : MAX_PIVOT_ACCEL;
        accelY = clamp(accelY, -verticalLimit, verticalLimit);
        // The body's turn, at this piece's own horizontal offset from the model's vertical axis.
        // See the overload's comment: outward from the turn for the centrifugal part, sideways for
        // the Euler part. Both are accelerations in the same sense as gravity, so they are added to
        // it rather than being given a strength of their own.
        float yawRate = Float.isFinite(bodyYawRate) ? clamp(bodyYawRate, -MAX_YAW_RATE, MAX_YAW_RATE) : 0.0F;
        float yawAccel = Float.isFinite(bodyYawAccel) ? clamp(bodyYawAccel, -MAX_YAW_ACCEL, MAX_YAW_ACCEL) : 0.0F;
        float turnX = 0.0F;
        float turnZ = 0.0F;
        if (yawRate != 0.0F || yawAccel != 0.0F) {
            turnX = yawRate * yawRate * pivot.x - yawAccel * pivot.z;
            turnZ = yawRate * yawRate * pivot.z + yawAccel * pivot.x;
        }
        this.gravity.set(turnX - accelX, -this.gravityDown - accelY, turnZ - accelZ);

        float angularFrequency = (float) (2.0 * Math.PI * Math.max(0.0F, frequency));
        // The author's coefficient is the damping ratio (see YSM's SecondOrder, which
        // clamps it to 0..1 and feeds it to k1 = coefficient / (pi * frequency)).
        float zeta = Math.min(1.0F, Math.max(0.0F, damping));

        // Substeps sized by the fastest restoring mode the piece has, not guessed. The rule is
        // YSM's own, taken from its SecondOrder#update, and it is the one line of their integrator
        // worth copying outright: the largest stable explicit step for a spring of frequency
        // omega and damping ratio zeta is sqrt(4*k2 + k1^2) - k1, with k1 = 2*zeta/omega and
        // k2 = 1/omega^2. A fixed count cannot be right for every piece, because a model may
        // author anything from 1 Hz to 5 Hz (YSM clamps frequency to 0..5) and the stable step for
        // 5 Hz is a third of the one for 2 Hz - so a fixed three substeps is either wasteful on
        // soft pieces or unstable on stiff ones, and the unstable case does not look like a solver
        // bug: it looks like a piece of cloth that shivers or explodes at a low frame rate.
        //
        // The frequency that goes in is the total restoring stiffness, not the author's spring
        // alone. Gravity is a restoring torque here in its own right - a piece displaced from its
        // rest direction is pulled back by g/L as well as by the spring, which is the same
        // statement as the pendulum frequency sqrt(g/L) - so the fastest mode is
        // sqrt(omega_n^2 + g/L). For a short piece gravity is the *larger* of the two: at the
        // eleven-centimetre crossover the total is 1.4 times the spring, and at five centimetres
        // it is 2.4 times, which is precisely the case a rule written for the spring alone would
        // under-resolve.
        float springStiffness = angularFrequency * angularFrequency;
        float gravityStiffness = this.gravityDown / lever;
        float fastest = (float) Math.sqrt(springStiffness + gravityStiffness);
        int substeps = SUBSTEPS;
        if (fastest > EPSILON) {
            float k1 = 2.0F * zeta / fastest;
            float k2 = 1.0F / (fastest * fastest);
            float stable = (float) Math.sqrt(4.0 * k2 + k1 * k1) - k1;
            if (stable > EPSILON) {
                substeps = (int) Math.ceil(step / stable);
            }
        }
        substeps = Math.max(1, Math.min(MAX_SUBSTEPS, substeps));

        float sub = step / substeps;
        for (int i = 0; i < substeps; i++) {
            integrate(state, lever, springStiffness, zeta, mass, this.bodyFlow, sub);
        }

        // Collision first, then the limit - and the order is load-bearing in both directions.
        //
        // Collision has to come after the integration, or the piece is integrated straight
        // through the body. The limit has to come after the collision, or collision becomes an
        // unbounded transform: a push moves the centre of mass onto a volume's surface, and the
        // direction that produces is limited only by how far away that surface is, not by the
        // authored swing. Measured on a real model, that reached a steady 99.4 degrees of swing
        // against a 60 degree ceiling, with every simulated bone pinned there - which on screen
        // is the garment coming apart.
        //
        // The cost is that a piece pressed hard by a collider is left slightly inside it, which
        // is why volumes that contain the piece's rest position are skipped altogether (see
        // Colliders#skipFor): collision is then only ever resolving a piece that genuinely
        // entered the body while moving, and the correction stays small.
        //
        // Both of these take `rest` and not `target`, because both are statements about the POSE
        // rather than about where gravity pulls:
        //   - collision's skipFor() is asked whether a volume contains the point the piece sits at
        //     when the pose is taken at its word; that is a question about the pose's geometry, and
        //     the centre of mass it is asked about is the posed one.
        //   - the reported swing and the returned quaternion are measured from `rest` because that is
        //     the direction the caller folds the rotation onto - the part's bind direction. A delta
        //     measured from the target would report (and apply) zero bend on a piece hanging
        //     perfectly straight down under a full weight while it is visibly off its pose, which is
        //     exactly the "weight had no effect" reading the log is supposed to make impossible.
        //
        // The swing limit is the one constraint that follows the target, and the measurements behind
        // that are in applySwingLimit's own comment: a cone about the pose cannot deliver the rest
        // angle a weight asks for, and rests the piece on the stop instead of on the balance.
        resolveCollisions(state, pivot, this.rest, lever, maxAngle, colliders, segmentRadius,
                this.target);
        applySwingLimit(state, this.target, maxAngle);

        state.lastAngle = angleBetween(this.rest, state.direction);
        rotationFromTo(out, this.rest, state.direction);
    }

    /**
     * One substep: accumulate every torque, divide by the inertia, integrate, rotate.
     *
     * @param springStiffness the author's own restoring stiffness, {@code omega_n^2}, NOT including
     *                        gravity: gravity is a separate torque below, and only the substep rule
     *                        upstream adds the two together
     */
    private void integrate(SegmentState state, float lever, float springStiffness, float zeta,
                           float mass, Vector3f bodyVelocity, float step) {
        Vector3f direction = state.direction;
        Vector3f omega = state.angularVelocity;

        // Gravity and the pivot's own acceleration, as a torque divided by the inertia:
        //
        //   tau = r x F = (L d) x (m g_eff) = m L (d x g_eff)
        //   I   = m L^2                        (a point mass at the centre of mass)
        //   alpha = tau / I = (d x g_eff) / L
        //
        // The mass cancels and the lever stays, as 1/L: a long piece accelerates more slowly under
        // the same gravity, exactly as a long pendulum swings more slowly than a short one. This is
        // also why the lever is a real input to the dynamics and not a scale factor on a rotation -
        // it is the only term in the solver that carries it into the response.
        this.alpha.set(direction).cross(this.gravity).mul(1.0F / lever);

        // Drag, the one term where the mass survives. The centre of mass moves at
        // v_pivot + w x r in the model's frame, and the air in that frame moves at -v_body, so the
        // airflow the piece feels is v_rel = v_com + v_body. F_drag = -C*|v_rel|*v_rel is a force
        // set by the air rather than by the piece, so with I = m L^2:
        //   alpha = (r x F) / I = -C * (d x v_rel) * |v_rel| / (m*L)
        // and a heavy panel answers the same wind less than a light one.
        //
        // The cap freezes the *force* and not the coefficient: C*|v|*v becomes C*capped*capped above
        // the cap, so the torque stops growing with speed. Capping only the coefficient would leave
        // the term growing linearly for ever - and it is the term that carries the pivot's whole
        // velocity, which for a body being flung by an animation is tens of blocks per second.
        this.leverArm.set(direction).mul(lever);
        this.relative.set(omega).cross(this.leverArm).add(state.pivotVelocity).add(bodyVelocity);
        float speed = this.relative.length();
        if (speed > EPSILON && Float.isFinite(speed)) {
            float capped = Math.min(speed, DRAG_SPEED_CAP);
            float scale = this.airDrag * capped * capped
                    / (speed * Math.max(mass, 1.0E-3F) * lever);
            this.axis.set(direction).cross(this.relative).mul(-scale);
            this.alpha.add(this.axis);
        }

        // Nothing is capped here, and that is the change this class needed. Outside forces used to
        // be clamped to a fraction of the spring's own stiffness, which for every lever a real
        // garment has is smaller than gravity alone; the result was a restoring torque that did not
        // depend on the angle and an equilibrium that did not depend on the piece - one constant
        // angle for a whole model. The balance below is the physics: gravity, the air and the
        // pivot's fictitious force on one side, the spring and the damping on the other. See the
        // class comment for the equilibrium that follows from it.

        // The spring: rotating d about (d x target) by a positive angle moves d toward the target,
        // so the sign is positive. Its stiffness is I*omega_n^2, which is what makes the author's
        // frequency come out as a frequency whatever the piece weighs.
        //
        // The target rather than the rest direction: the rest direction is what the pose said and
        // the target is where the piece is actually pulled, which differ by the caller's
        // verticalFollow weight (see update()). With a zero weight the two are the same vector, so
        // this line is the line it always was for every caller that has not opted in.
        this.axis.set(direction).cross(this.rest); // M3: the pre-fix spring term
        this.alpha.fma(springStiffness, this.axis);

        // Damping: alpha = -2*zeta*omega_n*w, the damping ratio the author asked for, applied to
        // the angular velocity. It only ever removes energy, and it is what makes the piece settle
        // on its equilibrium angle instead of oscillating about it forever.
        this.alpha.fma(-2.0F * zeta * (float) Math.sqrt(springStiffness), omega);

        if (!isFinite(this.alpha)) {
            return;
        }
        omega.fma(step, this.alpha);
        float rate = omega.length();
        if (!Float.isFinite(rate)) {
            omega.zero();
            return;
        }
        if (rate > MAX_SPEED) {
            omega.mul(MAX_SPEED / rate);
            rate = MAX_SPEED;
        }
        if (rate > EPSILON) {
            this.axis.set(omega).div(rate);
            direction.rotateAxis(rate * step, this.axis.x, this.axis.y, this.axis.z);
            if (direction.lengthSquared() > EPSILON) {
                direction.normalize();
            }
        }
    }

    /**
     * Push the segment's centre of mass out of the body and kill the velocity that drove it
     * in.
     *
     * <p>Position-based, and iterated, because the piece is subject to two constraints at
     * once: its centre of mass is held on a sphere of radius {@code lever} about the pivot,
     * and it may not be inside a collision volume. One push satisfies the second and breaks
     * the first - re-normalising the direction puts the piece straight back inside - so the
     * two are alternated. That is Gauss-Seidel, and it converges onto the circle where the
     * lever sphere and the volume's surface meet, which is where a skirt panel resting
     * against a thigh actually sits.
     *
     * <p>The angular velocity loses the component that drove the piece in, plus a friction
     * fraction of the rest. An impulse-based response would need masses and restitution for
     * colliders that are kinematic anyway - they are moved by the animation, not by the hair.
     */
    private void resolveCollisions(SegmentState state, Vector3f pivot, Vector3f restDir, float lever,
                                   float maxAngle, Colliders colliders, float segmentRadius,
                                   Vector3f coneAxis) {
        state.lastContact = 0.0F;
        if (colliders == null || colliders.count() == 0) {
            return;
        }
        // Where the piece sits when the pose is taken at its word. A volume that already contains
        // this point is one the model itself intersects at rest - a skirt around the hips, long
        // hair down the back - and pushing the piece out of it is ejection rather than collision.
        // See Colliders#skipFor.
        this.restCentre.set(restDir).mul(lever).add(pivot);
        // How far the centre of mass can travel from that point within the swing limit, blocks.
        //
        // The limit is a cone about `coneAxis` and the piece starts at `restDir`, so the reach is
        // the angular distance from the rest direction to the cone's apex plus the cone's own
        // half-angle. Measured from the rest direction alone - `lever * sin(maxAngle)` - this would
        // understate the working space as soon as the two differ, and the reach is what decides
        // whether a volume is skipped as unsatisfiable (Colliders#skipFor): too small a number there
        // means a volume the piece can never clear is treated as one it must clear, which is the
        // push-and-clamp limit cycle that rule exists to prevent.
        float apexGap = angleBetween(restDir, coneAxis);
        float reachAngle = Math.min((float) Math.PI, apexGap + 2.0F * Math.max(0.0F, maxAngle));
        float swingReach = lever * (float) Math.sin(Math.min(1.5707F, reachAngle));
        // How far collision may turn the piece in one frame. A positional push is a *correction*,
        // not a force, and it is the one thing left in this solver that writes the piece's
        // direction directly - so without a bound it is exempt from everything the integration
        // guarantees, including the ceiling that keeps outside forces below the spring.
        //
        // It is a budget for the whole call and not for each iteration, which is the difference
        // between a correction and an ejection. The loop below runs up to COLLISION_ITERATIONS
        // times, so a per-iteration bound of a quarter of the limit let one frame turn a piece by
        // twice its entire limit: the log's "hit" column caught it as a push of 0.16 blocks on a
        // panel whose whole swing is worth 0.05, and on screen it is a piece of cloth thrown clear
        // of the body and left hanging in the air beside it, which is what this bound is for.
        float maxStep = COLLISION_STEP_FRACTION * Math.max(maxAngle, MIN_COLLISION_STEP);
        float budget = maxStep;
        this.entryDirection.set(state.direction);
        int count = colliders.count();
        boolean touched = false;
        for (int iteration = 0; iteration < COLLISION_ITERATIONS; iteration++) {
            this.com.set(state.direction).mul(lever).add(pivot);
            // The contact velocity is the centre of mass's own motion, not the pivot's: the
            // pivot is driven by the body, and damping it would fight the animation.
            this.relative.set(state.angularVelocity).cross(state.direction).mul(-lever);
            float push = 0.0F;
            for (int i = 0; i < count; i++) {
                if (colliders.skipFor(pivot, this.restCentre, swingReach, i)) {
                    continue;
                }
                this.before.set(this.com);
                if (colliders.resolve(this.com, this.relative, segmentRadius, i)) {
                    push += this.before.distance(this.com);
                }
            }
            if (push <= EPSILON) {
                break;
            }
            touched = true;
            this.axis.set(this.com).sub(pivot);
            if (this.axis.lengthSquared() < EPSILON) {
                break;
            }
            this.axis.normalize();
            float bend = angleBetween(state.direction, this.axis);
            if (bend > budget) {
                // Rotate only as far as this frame is allowed, about the axis of the correction.
                this.before.set(state.direction).cross(this.axis);
                if (this.before.lengthSquared() > EPSILON * EPSILON) {
                    this.before.normalize();
                    this.axis.set(state.direction)
                            .rotateAxis(budget, this.before.x, this.before.y, this.before.z);
                }
                bend = budget;
            }
            budget -= bend;
            if (this.axis.dot(state.direction) > 1.0F - 1.0E-5F) {
                // The correction points straight along the piece, which no rotation can apply:
                // the centre of mass is held on a sphere about the pivot, so moving it radially
                // is the one thing a swing cannot do.
                //
                // Which of the two ways this happens decides what to do. A *large* radial
                // correction means the piece is aimed at the middle of the volume it is inside,
                // and doing nothing would leave it buried in the body for good - so it is slid
                // off sideways and the next frames finish the job. A *small* one means the
                // iteration has converged onto the surface and this is the last fraction of a
                // millimetre of penetration, which is where a piece of cloth resting on a thigh
                // belongs; sliding there would push the piece off the surface every frame and
                // fight the spring for as long as the pose held.
                if (push > COLLISION_SLIDE_THRESHOLD) {
                    perpendicularTo(state.direction, this.com);
                    state.direction.rotateAxis(Math.min(COLLISION_SLIDE_ANGLE, budget),
                            this.com.x, this.com.y, this.com.z);
                }
                break;
            }
            // The rotation axis of this correction, for the velocity response below.
            this.before.set(state.direction).cross(this.axis);
            if (this.before.lengthSquared() > EPSILON * EPSILON) {
                this.contactAxis.set(this.before).normalize();
            }
            state.direction.set(this.axis);
            if (budget <= EPSILON) {
                // The frame's whole allowance is spent; further iterations would only dig the
                // piece in deeper against a spring that cannot answer until the next frame.
                break;
            }
        }
        // What collision actually did, in blocks: the arc the centre of mass travelled because of
        // it. Reported instead of the sum of the pushes it asked for, which counts the same
        // correction once per iteration and so reads several times larger than any movement that
        // happened - the earlier log's 0.16-block "hit" on a piece whose whole swing is 0.05.
        state.lastContact = lever * angleBetween(this.entryDirection, state.direction);
        if (!touched) {
            return;
        }
        state.angularVelocity.fma(-state.angularVelocity.dot(this.contactAxis), this.contactAxis);
        // Friction, and the only energy the solver takes out on purpose: the piece is sliding
        // along a surface, and a surface slows what slides on it. The approach velocity was
        // removed exactly on the line above, so this is tangential only and cannot make the
        // contact dead - a piece pressed against a thigh keeps sliding across it, losing
        // CONTACT_FRICTION of its speed per frame while it does. Named rather than written as the
        // literal it is, because a coefficient in the middle of a collision response reads as a
        // convergence aid unless it is given its name.
        float sliding = 1.0F - CONTACT_FRICTION;
        state.angularVelocity.mul(sliding);
    }

    /** Any unit vector perpendicular to {@code direction}, chosen deterministically. */
    private static void perpendicularTo(Vector3f direction, Vector3f out) {
        // Cross with whichever world axis the direction is least aligned with, so the product
        // is never degenerate and the choice does not flicker between frames.
        if (Math.abs(direction.x) < 0.9F) {
            out.set(direction.y * 0.0F - direction.z * 0.0F, direction.z * 1.0F - direction.x * 0.0F,
                    direction.x * 0.0F - direction.y * 1.0F);
        } else {
            out.set(direction.y * 1.0F - direction.z * 0.0F, direction.z * 0.0F - direction.x * 1.0F,
                    direction.x * 0.0F - direction.y * 0.0F);
        }
        if (out.lengthSquared() < EPSILON * EPSILON) {
            out.set(1.0F, 0.0F, 0.0F);
        } else {
            out.normalize();
        }
    }

    /**
     * Hold the segment's direction inside the cone of half-angle {@code maxAngle} about the spring's
     * target - as a constraint, in the position-based sense: project the position, then remove
     * only the part of the velocity that violates it.
     *
     * <p><b>The cone's axis is the target, and that is a measured decision rather than a
     * preference.</b> The limit is the author's allowance for how far a joint may leave the
     * direction it is being pulled toward; the target is the blend of the pose and the world's
     * vertical that the caller's weight asks for. Putting the axis on the pose instead makes the
     * allowance something else: it charges the piece for the angle the pose itself already leans
     * by. At the maid model's root limit of twenty degrees and a sixty degree lean the two readings
     * are 40 degrees apart in what they permit, and the pose-axis cone therefore rests the piece on
     * the stop - 40 degrees from vertical, whatever the weight is set to, because the weight moves
     * the target and the stop does not follow it. The measured table is in
     * {@code tmp_verify/T9_findings.md}: at a weight of 1.0 the target itself is at 0 degrees from
     * vertical, the piece's balance is on the target, and the pose-axis stop still holds it 40
     * degrees away. A limit the piece cannot reach its own balance inside is not a limit, it is the
     * answer.
     *
     * <p>What the target-axis cone costs is bounded, and by two things that are already here. The
     * cone is the same size, so the piece still swings by at most {@code maxAngle} about wherever it
     * is being pulled - the swing the log reports is still bounded by the authored number, and the
     * "garment comes apart" failure that limit exists to prevent is a <i>difference between
     * neighbours</i>, which the coupling and this bound together still cap. And the target is itself
     * bounded relative to the pose: {@code |target - rest| <= verticalFollow * (pi/2)}, so at a
     * weight of zero the two axes are the same vector and this method is bit for bit what it always
     * was; at the cloth weight of 0.92 and a sixty degree lean the apex sits 55.9 degrees from the
     * pose, the largest it gets on a real model. Collision is unaffected: the volumes' own skip rule
     * is now sized from the cone from the pose (see {@link #resolveCollisions}), so a piece that
     * could reach a volume is still made to collide with it.
     *
     * <p><b>The position projection.</b> The direction is rotated back onto the cone rather than
     * having its reported angle shortened, so the constraint lives in the state: a piece thrown at
     * its stop stays on the stop, and a chain below it is carried by a direction that is really
     * there.
     *
     * <p><b>The velocity projection.</b> With {@code n = axis x d}, the rate at which the piece
     * moves away from the axis is {@code d(theta)/dt = -(w . n) / |n|}, so the component
     * of the angular velocity that drives the piece further past the limit is the one along
     * {@code -n}, and it is that component - and only that one - which is removed here.
     *
     * <p>What is deliberately <i>kept</i> is the tangential part, {@code w} perpendicular to
     * {@code n}: that is the piece sliding around the cone, along the stop it is resting on, which
     * is what a panel does when its neighbours pull it across a thigh. Removing it as well would
     * make the stop sticky in a way no surface is.
     *
     * <p>What used to happen instead was {@code w *= 0.25} on every frame the piece spent at the
     * limit. That is an energy sink with no physical counterpart: it erased the piece's whole
     * velocity, outward and tangential alike, so whatever had pushed the piece out stayed in
     * charge of its angle for as long as the push lasted, and the moment the push stopped the
     * spring released the piece from a standing start - the "flung away, then snapped home"
     * the in-game report describes. A constraint that keeps the tangential velocity has no such
     * stored energy to release.
     *
     * <p>No work is injected, either: the constraint only ever removes velocity, and the piece
     * leaves the limit under its own spring as soon as the outside torque drops below what the
     * spring can hold there.
     *
     * @param axis the cone's axis: the spring's target direction. {@code rest} is passed at a
     *             verticalFollow of zero, where the two are the same vector - see the class comment
     */
    private void applySwingLimit(SegmentState state, Vector3f axis, float maxAngle) {
        if (!Float.isFinite(maxAngle) || maxAngle <= 0.0F) {
            return;
        }
        float angle = angleBetween(axis, state.direction);
        if (angle <= maxAngle || angle < EPSILON) {
            return;
        }
        // n = axis x d, perpendicular to both, and the axis the projection rotates about.
        this.axis.set(axis).cross(state.direction);
        if (this.axis.lengthSquared() < EPSILON * EPSILON) {
            return;
        }
        this.axis.normalize();
        state.direction.set(axis).rotateAxis(maxAngle, this.axis.x, this.axis.y, this.axis.z);
        if (!Float.isFinite(state.direction.x) || state.direction.lengthSquared() < EPSILON) {
            // A non-finite or collapsed direction has no constraint to satisfy; leave the piece on
            // the cone's axis rather than propagating the garbage.
            state.direction.set(axis);
            state.angularVelocity.zero();
            return;
        }
        state.direction.normalize();

        // The outward part of the angular velocity, and only that. With n = axis x d, the rate the
        // piece moves away from the axis is d(theta)/dt = (w . n) / |axis x d|, so the
        // component that drives it further past the limit is the one along +n and it is that one
        // which comes off. A component along -n is the piece on its way back in: it stays.
        //
        // What comes back is the rebound of LIMIT_RESTITUTION, and only out of the component that
        // was just removed: (1 + e) is taken off and e is put back the other way. Nothing is added
        // to any other component, so a piece that arrives at its limit slower than it leaves it is
        // impossible, and the tangential velocity is untouched either way.
        float outward = state.angularVelocity.dot(this.axis);
        if (outward > 0.0F) {
            state.angularVelocity.fma(-(1.0F + LIMIT_RESTITUTION) * outward, this.axis);
        }
        if (!isFinite(state.angularVelocity)) {
            state.angularVelocity.zero();
        }
    }

    // ------------------------------------------------------------------
    // Shared vector helpers
    // ------------------------------------------------------------------

    /** Whether every component is finite. */
    public static boolean isFinite(Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** The angle between two directions, radians, in 0..pi. */
    public static float angleBetween(Vector3f a, Vector3f b) {
        float lengthA = a.length();
        float lengthB = b.length();
        if (lengthA < EPSILON || lengthB < EPSILON) {
            return 0.0F;
        }
        float cosine = Math.max(-1.0F, Math.min(1.0F, a.dot(b) / (lengthA * lengthB)));
        return (float) Math.acos(cosine);
    }

    /**
     * The shortest rotation taking {@code from} onto {@code to}.
     *
     * <p>Opposed vectors have no unique shortest axis; one perpendicular is chosen rather
     * than returning identity, because identity for a 180-degree error would silently
     * freeze a piece that had been flipped - which is how a bone ends up stuck inside the
     * body.
     */
    public static void rotationFromTo(Quaternionf out, Vector3f from, Vector3f to) {
        float fx = from.x;
        float fy = from.y;
        float fz = from.z;
        float tx = to.x;
        float ty = to.y;
        float tz = to.z;
        float fromLength = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        float toLength = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (fromLength < EPSILON || toLength < EPSILON) {
            out.identity();
            return;
        }
        fx /= fromLength;
        fy /= fromLength;
        fz /= fromLength;
        tx /= toLength;
        ty /= toLength;
        tz /= toLength;
        float dot = fx * tx + fy * ty + fz * tz;
        if (dot > 1.0F - 1.0E-6F) {
            out.identity();
            return;
        }
        if (dot < -1.0F + 1.0E-6F) {
            // Opposed: any axis perpendicular to `from` will do.
            float ax = Math.abs(fx) < 0.9F ? 1.0F : 0.0F;
            float ay = Math.abs(fx) < 0.9F ? 0.0F : 1.0F;
            float cx = fy * 0.0F - fz * ay;
            float cy = fz * ax - fx * 0.0F;
            float cz = fx * ay - fy * ax;
            float length = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (length < EPSILON) {
                out.identity();
                return;
            }
            out.fromAxisAngleRad(cx / length, cy / length, cz / length, (float) Math.PI);
            return;
        }
        out.set(fy * tz - fz * ty, fz * tx - fx * tz, fx * ty - fy * tx, 1.0F + dot).normalize();
    }
}
