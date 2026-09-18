import com.ysmef.compat.model.runtime.YsmDynamicBoneSolver;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * T9's frame-by-frame probe, run against the project's own compiled solver.
 *
 * <p>Written because an analytic table and a measured one disagreed by 3.27 degrees on one case,
 * and the disagreement has to be resolved by watching the frames rather than by arguing about the
 * model. Prints, per frame: the swing from the posed rest direction, the swing from the spring's
 * own target, and the angle from the world's vertical - which together say whether a piece is on
 * its balance, on its limit, or somewhere in between.
 *
 * <p>Run: {@code java -cp build/classes/java/main;<joml.jar>;tmp_verify/classes T9_probe}
 */
public final class T9_probe {

    static final float GRAVITY = 24.0F;
    static final float FREQUENCY = (float) (Math.sqrt(220.0) / (2.0 * Math.PI));
    static final float DAMPING = (float) (24.0 / (2.0 * Math.sqrt(220.0)));
    static final float ROOT_LIMIT = (float) Math.toRadians(20.0);
    static final float CLOTH = 0.92F;
    static final Vector3f DOWN = new Vector3f(0.0F, -1.0F, 0.0F);

    public static void main(String[] args) {
        Vector3f rest = leaningDir(60.0F);
        System.out.println("rest = " + fmt(rest) + "  (60 deg from vertical)");
        System.out.println();

        run("world down, weight 0.92", rest, DOWN, CLOTH, 0.26F);
        run("body axis as down, weight 0.92", rest, rest, CLOTH, 0.26F);
        run("world down, weight 0.0", rest, DOWN, 0.0F, 0.26F);
        run("world down, weight 1.0", rest, DOWN, 1.0F, 0.26F);
        System.out.println();

        System.out.println("=== the settled angle at every weight, both down targets ===");
        System.out.println("  weight | world down: off-vert |  swing | body axis: off-vert |  swing");
        for (float weight : new float[]{0.0F, 0.3F, 0.6F, 0.8F, 0.92F, 1.0F}) {
            Result w = settle(rest, DOWN, weight, 0.26F);
            Result b = settle(rest, rest, weight, 0.26F);
            System.out.printf("   %4.2f  | %19.2f | %6.2f | %19.2f | %6.2f%n",
                    weight, w.offVertical, w.swing, b.offVertical, b.swing);
        }
        System.out.println();

        System.out.println("=== the lever sweep at the cloth weight, world down (the acceptance table) ===");
        System.out.println("  lever | off-vertical | swing from rest | swing from target | pinned");
        for (float lever : new float[]{0.03F, 0.05F, 0.09F, 0.11F, 0.18F, 0.26F}) {
            Result r = settle(rest, DOWN, CLOTH, lever);
            System.out.printf("  %5.3f | %12.2f | %15.2f | %17.2f | %s%n",
                    lever, r.offVertical, r.swing, r.fromTarget, r.pinned ? "YES" : "no");
        }
        System.out.println();

        System.out.println("=== the lean sweep at the cloth weight ===");
        System.out.println("  lean | off-vertical | swing from rest | from target");
        for (float lean : new float[]{0.0F, 15.0F, 30.0F, 45.0F, 60.0F, 75.0F}) {
            Result r = settle(leaningDir(lean), DOWN, CLOTH, 0.26F);
            System.out.printf("  %4.0f | %12.2f | %15.2f | %11.2f%n",
                    lean, r.offVertical, r.swing, r.fromTarget);
        }
    }

    /** One settled run's numbers. */
    static final class Result {
        float offVertical;
        float swing;
        float fromTarget;
        boolean pinned;
    }

    static void run(String label, Vector3f rest, Vector3f downTarget, float weight, float lever) {
        System.out.println("--- " + label + " ---");
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        float dt = 1.0F / 60.0F;
        Vector3f target = expectedTarget(rest, downTarget, weight);
        for (int i = 0; i <= 300; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, 0.0F, weight, downTarget,
                    pivot, rest, lever, FREQUENCY, DAMPING, 1.0F, ROOT_LIMIT,
                    new Vector3f(), YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, dt, out);
            if (i == 0 || i == 1 || i == 5 || i == 10 || i == 30 || i == 60 || i == 150 || i == 300) {
                System.out.printf("  f%-4d dir=%s offVert=%7.2f swingRest=%7.2f swingTarget=%7.2f%n",
                        i, fmt(state.direction),
                        deg(YsmDynamicBoneSolver.angleBetween(DOWN, state.direction)),
                        deg(YsmDynamicBoneSolver.angleBetween(rest, state.direction)),
                        deg(YsmDynamicBoneSolver.angleBetween(target, state.direction)));
            }
        }
        System.out.println();
    }

    static Result settle(Vector3f rest, Vector3f downTarget, float weight, float lever) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        float dt = 1.0F / 60.0F;
        for (int i = 0; i <= 600; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, 0.0F, weight, downTarget,
                    pivot, rest, lever, FREQUENCY, DAMPING, 1.0F, ROOT_LIMIT,
                    new Vector3f(), YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, dt, out);
        }
        Vector3f target = expectedTarget(rest, downTarget, weight);
        Result r = new Result();
        r.offVertical = deg(YsmDynamicBoneSolver.angleBetween(DOWN, state.direction));
        r.swing = deg(YsmDynamicBoneSolver.angleBetween(rest, state.direction));
        r.fromTarget = deg(YsmDynamicBoneSolver.angleBetween(target, state.direction));
        r.pinned = Math.abs(r.fromTarget - deg(ROOT_LIMIT)) < 0.5F;
        return r;
    }

    /** The solver's own blend, recomputed here so the probe can say where the target should be. */
    static Vector3f expectedTarget(Vector3f rest, Vector3f downTarget, float weight) {
        Vector3f target = new Vector3f(rest).mul(1.0F - weight);
        target.fma(weight, downTarget);
        return target.normalize();
    }

    static Vector3f leaningDir(float degrees) {
        double radians = Math.toRadians(degrees);
        return new Vector3f((float) Math.sin(radians), -(float) Math.cos(radians), 0.0F);
    }

    static float deg(float radians) {
        return (float) Math.toDegrees(radians);
    }

    static String fmt(Vector3f v) {
        return String.format("(%.4f,%.4f,%.4f)", v.x, v.y, v.z);
    }
}
