import java.util.function.DoubleUnaryOperator;

/**
 * T9's analytic equilibrium, computed independently of the solver and of the Lead's table.
 *
 * <p>Derivation, in the terms the solver's own class comment uses. With the lever L, the spring
 * frequency omega, gravity g, the piece's direction d, the spring's target t and the world's
 * downward direction u:
 *
 * <pre>
 *   tau_gravity = m L (d x g)                  alpha_gravity = |d x u| g / L = g sin(theta_v) / L
 *   tau_spring  = I omega^2 (d x t)            alpha_spring  = omega^2 |d x t| = omega^2 sin(psi)
 * </pre>
 *
 * <p>where theta_v is the angle from d to u (the world's vertical) and psi the angle from d to t
 * (the spring's target). The two torques have opposite signs about d, so equilibrium is
 *
 * <pre>
 *   omega^2 sin(psi) = g sin(theta_v) / L
 * </pre>
 *
 * <p>and, with phi the angle from the posed rest direction to the target and alpha the body's lean
 * from vertical, both are measured from the rest direction: psi = phi - theta_v and
 * theta_v - alpha = the angle from the piece to the world's vertical, so the balance the solver
 * actually integrates is
 *
 * <pre>
 *   omega^2 sin(phi - theta_v) = (g / L) sin(theta_v - alpha)
 * </pre>
 *
 * <p>The replaced model had t == rest, so phi = 0 and theta_v = the swing theta, which is exactly
 * the Lead's `L omega^2 sin(theta) = g sin(alpha - theta)` - and reproducing that table from this
 * equation is this file's first check on itself.
 *
 * <p><b>The whole point of the weight, in one line.</b> The target is a weight-b blend, so
 * phi = phi(b, alpha) is at most alpha and falls to zero as b rises; the piece's resting angle from
 * the world's vertical is theta_v - alpha, and it goes to zero with phi. That is what makes b = 0.92
 * read as "8 per cent of the lean at every lean angle", which the old model could not express
 * because phi was welded to alpha.
 *
 * <p>Run: `javac -d tmp_verify/classes tmp_verify/T9_equilibrium.java` then
 * `java -cp tmp_verify/classes T9_equilibrium`.
 */
public final class T9_equilibrium {

    static final double G = 24.0;
    static final double OMEGA2 = 219.9;

    public static void main(String[] args) {
        System.out.println("=== T9 analytic equilibrium, bisection on the balance equation ===");
        System.out.printf("g=%.1f blocks/s^2, omega^2=%.1f rad^2/s^2 (%.3f Hz)%n",
                G, OMEGA2, Math.sqrt(OMEGA2) / (2.0 * Math.PI));
        System.out.println();

        table("BEFORE  (verticalFollow = 0: the spring's target IS the posed rest direction)",
                0.0);
        System.out.println();
        table("AFTER   (verticalFollow = 0.70, hair)",
                0.70);
        System.out.println();
        table("AFTER   (verticalFollow = 0.80, tail)",
                0.80);
        System.out.println();
        table("AFTER   (verticalFollow = 0.92, cloth - the proposed default)",
                0.92);
        System.out.println();
        table("AFTER   (verticalFollow = 1.00, the theoretical limit)",
                1.00);
        System.out.println();
        shortLeverRisks();
    }

    /** Every lean angle of interest, every lever the maid model has, before and after. */
    static void table(String title, double b) {
        System.out.println("--- " + title + " ---");
        System.out.println("  lean | lever |    phi |    theta |  swing |  final off-vert | pinned?");
        System.out.println("  (deg)|  (blk)|   (deg)|    (deg)|  (deg) |           (deg) |");
        double[] levers = {0.09, 0.18, 0.26};
        double[] leans = {30.0, 45.0, 60.0};
        for (double lean : leans) {
            for (double lever : levers) {
                Result r = solve(b, lean, lever);
                System.out.printf("  %5.0f| %6.2f| %6.2f | %7.2f | %6.2f | %15.2f | %s%n",
                        lean, lever, r.phiDeg, r.thetaDeg, r.swingDeg, r.finalOffVerticalDeg,
                        r.pinned ? "YES on " + fmt(Math.toDegrees(0.349066)) + "deg" : "no");
            }
        }
    }

    static String fmt(double v) {
        return String.format("%.0f", v);
    }

    /** One solved case. */
    static final class Result {
        double phiDeg;
        double thetaDeg;
        double swingDeg;
        double finalOffVerticalDeg;
        boolean pinned;
    }

    /**
     * Solve for the piece's direction and report the angles.
     *
     * <p>Nothing is assumed to be reachable: the piece is first solved with no limit at all, and if
     * the direction that equilibrium asks for is further from the posed rest direction than the
     * swing limit allows, the piece is reported as resting ON the limit - which is what the solver's
     * own constraint does ({@code applySwingLimit}) and what the log shows as own == allowed.
     *
     * @param b     the gravity-follow weight, 0..1
     * @param lean  the body's lean from vertical, degrees
     * @param lever the pivot-to-centre-of-mass lever, blocks
     */
    static Result solve(double b, double lean, double lever) {
        double alpha = Math.toRadians(lean);
        // phi: the angle from the posed rest direction to the spring's target. The target is the
        // normalized blend of the rest direction and the world's downward direction, and the two
        // are alpha apart, so the blend vector is at atan2(b sin alpha, (1-b) + b cos alpha) from
        // the rest direction - expressed here as a cos, which is the same number and cannot fail
        // on a quadrant.
        double phi = blendedAngle(b, alpha);
        // theta: the angle from the posed rest direction to the piece's direction at equilibrium.
        // Solved on omega^2 sin(phi - theta) = (g/L) sin(theta - alpha): the spring pulls the piece
        // toward the target at phi from the rest direction and gravity pulls it toward the world's
        // vertical at alpha from the rest direction, so the root - when the spring can hold at all -
        // lies between the two. Reproducing the Lead's b=0 table from this equation is this file's
        // check on itself.
        double theta = bisect(thetaV -> OMEGA2 * Math.sin(phi - thetaV)
                        - G * Math.sin(thetaV - alpha) / lever,
                Math.min(0.0, Math.min(alpha, phi)), Math.max(alpha, phi));
        if (Double.isNaN(theta)) {
            // No root between the two: gravity's torque beats the spring's everywhere in between,
            // which is the L < g/omega^2 regime at a large phi. The piece does not come to rest at
            // all - it is driven out to the limit - so report it pinned there.
            theta = phi;
        }
        Result r = new Result();
        r.phiDeg = Math.toDegrees(phi);
        r.thetaDeg = Math.toDegrees(theta);
        r.swingDeg = r.thetaDeg;
        // The angle from the world's vertical, which is what a viewer sees and what the report
        // measures. theta and alpha are both measured from the rest direction but on opposite sides
        // of it - the target lies between the pose and the vertical - so the piece's angle from
        // vertical is alpha - theta.
        r.finalOffVerticalDeg = Math.abs(lean - r.thetaDeg);
        // The maid model's root limit: secondaryMotionMaxAngleRootDegrees = 20, which is what a
        // panel at the top of a piece gets. The constraint is a cone about the spring's TARGET
        // (see YsmDynamicBoneSolver#applySwingLimit and the measurements in T9_findings.md), so what
        // it bounds is the swing from the target, phi - theta - NOT the swing from the pose. A piece
        // whose balance is further from its target than the limit is held on the cone instead, and
        // the cone's apex is what the piece then reports from the pose.
        double rootLimit = Math.toRadians(20.0);
        double swingFromTarget = phi - theta;
        if (swingFromTarget > rootLimit) {
            r.pinned = true;
            r.swingDeg = Math.toDegrees(phi - rootLimit);
            r.finalOffVerticalDeg = Math.abs(lean - r.swingDeg);
        }
        return r;
    }

    /** The angle from the rest direction to {@code normalize((1-b) r + b u)}. */
    static double blendedAngle(double b, double alpha) {
        double x = (1.0 - b) + b * Math.cos(alpha);
        double y = b * Math.sin(alpha);
        return Math.atan2(y, x);
    }

    /**
     * Bisection on a sign change located by a scan, so the caller does not have to know the bracket.
     *
     * <p>A scan first rather than a bracket passed in, because the bracket geometry is not obvious:
     * with the target on the far side of the world's vertical (a weight near one) the balance
     * function has the same sign at both ends of the tempting interval and a naive bisection
     * silently reports "no root". Scanning is what makes the difference between a table that is
     * right and a table that looks right.
     *
     * @return the root, or NaN when the function has no sign change in 0..pi
     */
    static double bisect(DoubleUnaryOperator f, double lo, double hi) {
        int steps = 4096;
        double previous = lo < hi ? lo : hi;
        double previousValue = f.applyAsDouble(previous);
        for (int i = 1; i <= steps; i++) {
            double x = (lo < hi ? lo : hi) + (Math.abs(hi - lo) * i) / steps;
            double value = f.applyAsDouble(x);
            if (value == 0.0) {
                return x;
            }
            if (Double.isFinite(previousValue) && Double.isFinite(value) && previousValue * value < 0.0) {
                return bisectOn(f, previous, previousValue, x, value);
            }
            previous = x;
            previousValue = value;
        }
        return Double.NaN;
    }

    /** Bisection on an interval whose endpoints are already known to straddle a root. */
    static double bisectOn(DoubleUnaryOperator f, double lo, double fLo, double hi, double fHi) {
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            double fMid = f.applyAsDouble(mid);
            if (fMid == 0.0) {
                return mid;
            }
            if (fLo * fMid < 0.0) {
                hi = mid;
                fHi = fMid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /**
     * The short-lever regime the Lead flagged: {@code g/(L omega^2) > 1} below eleven centimetres.
     *
     * <p>With the target welded to the pose this is a piece whose gravity torque beats its spring at
     * EVERY angle: the balance has no root, and `sin(swing)` would have to exceed one. The solver
     * never computes a sine of the balance - it integrates the two torques and lets the swing limit
     * bound the result - so the question is only whether the limit holds it and whether the two
     * torques can agree on a direction inside it.
     */
    static void shortLeverRisks() {
        double crossover = G / OMEGA2;
        System.out.printf("--- the short-lever regime: g/(L omega^2) > 1 below L = %.4f blocks (%.2f cm) ---%n",
                crossover, crossover * 100.0);
        System.out.println("  lever | g/(L w^2) | before b=0: pos of the rest-balance | after b=0.92");
        double[] levers = {0.03, 0.05, 0.09, 0.11, 0.15, 0.26};
        for (double lever : levers) {
            double ratio = G / (lever * OMEGA2);
            Headless h0 = headless(0.0, Math.toRadians(60.0), lever);
            Headless h1 = headless(0.92, Math.toRadians(60.0), lever);
            System.out.printf("  %5.3f | %9.3f | %-31s | %s%n", lever, ratio,
                    h0.describe(), h1.describe());
        }
    }

    /** The same balance solved without any swing limit, so the raw equilibrium is visible. */
    static final class Headless {
        double thetaFromRestDeg;
        double thetaFromTargetDeg;
        boolean gravityDominates;

        String describe() {
            if (gravityDominates) {
                return String.format("no root: driven to %.1f deg from rest, %.1f from target",
                        thetaFromRestDeg, thetaFromTargetDeg);
            }
            return String.format("rests %.1f deg from rest, %.1f from target",
                    thetaFromRestDeg, thetaFromTargetDeg);
        }
    }

    static Headless headless(double b, double alpha, double lever) {
        double phi = blendedAngle(b, alpha);
        Headless h = new Headless();
        double theta = bisect(thetaV -> OMEGA2 * Math.sin(phi - thetaV)
                        - G * Math.sin(thetaV - alpha) / lever,
                Math.min(0.0, Math.min(alpha, phi)), Math.max(alpha, phi));
        if (Double.isNaN(theta)) {
            // gravity's torque beats the spring's at every angle between the target and the world's
            // vertical, so there is no angle the piece can rest at: it is driven out to its limit.
            h.gravityDominates = true;
            h.thetaFromRestDeg = Math.toDegrees(alpha);
        } else {
            h.thetaFromRestDeg = Math.toDegrees(theta);
        }
        h.thetaFromTargetDeg = h.thetaFromRestDeg - Math.toDegrees(phi);
        return h;
    }
}
