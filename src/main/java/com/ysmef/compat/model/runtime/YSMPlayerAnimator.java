package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.ysm.script.Molang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player evaluator for a converted YSM model's molang scripts, replicating
 * YSM's model-changing behavior on top of Epic Fight's skinning.
 *
 * Each frame, in the same spirit as YSM's animation systems:
 * - the molang query context is refreshed from the player (health, speeds,
 *   held items, head rotation, ...)
 * - pre_parallelN/parallelN looped animations are evaluated (variable-driven bone
 *   scales decide which model variant is visible; timeline molang drives the
 *   spring physics used for tails/hair/clothes)
 * - the locomotion state animation (idle/walk/run/fly/swim/...) and hold/use
 *   condition overlays are evaluated for secondary (non-EF-mapped) bones
 *
 * Results are applied per Epic Fight part ("y/<bone>"): parts whose effective
 * scale collapses are hidden; other parts receive a bind-space delta transform
 * (conjugated local animation delta) that composes with Epic Fight's joint pose
 * (final = Pose_ef x Delta_ysm x bindVertex), so YSM bone animation rides on top
 * of Epic Fight's combat animation instead of fighting it.
 */
public final class YSMPlayerAnimator implements Molang.Env {

    private static final double HIDE_SCALE_EPSILON = 0.01;
    private static final float MIN_SPEED = 0.05F;

    private final YSMRuntimeModel model;

    // molang state
    private final Map<String, Double> vars = new HashMap<>();
    private final Map<String, Double> queries = new HashMap<>();
    private ItemStack mainHand = ItemStack.EMPTY;
    private ItemStack offHand = ItemStack.EMPTY;
    private String currentState = "";
    private float animTimeCurrent;

    // frame tracking
    private final Map<String, Double> animStart = new HashMap<>();
    private final Map<String, Float> animLastT = new HashMap<>();
    private double lastPosX, lastPosY, lastPosZ;
    private boolean hasLastPos;
    private final double[] posDelta = new double[3];
    private float lastHeadYawDeg;
    private double lastYawSampleTime = -1;
    private double yawSpeedDeg;

    // evaluation scratch (indexed by bone list position)
    private final float[][] animPos;    // converted, blocks
    private final float[][] animRot;    // converted, radians
    private final float[][] animScale;
    private final boolean[] hasPos, hasRot, hasScale;
    private final Matrix4f[] localAnim;
    private final Matrix4f[] deltaModel;
    private final Matrix4f[] chainDelta;
    private final float[] effMinScale;

    private final List<YSMRuntimeModel.CompiledAnim> activeAnims = new ArrayList<>();

    public YSMPlayerAnimator(YSMRuntimeModel model) {
        this.model = model;
        int n = model.bones.length;
        animPos = new float[n][3];
        animRot = new float[n][3];
        animScale = new float[n][3];
        hasPos = new boolean[n];
        hasRot = new boolean[n];
        hasScale = new boolean[n];
        localAnim = new Matrix4f[n];
        deltaModel = new Matrix4f[n];
        chainDelta = new Matrix4f[n];
        effMinScale = new float[n];
        for (int i = 0; i < n; i++) {
            localAnim[i] = new Matrix4f();
            deltaModel[i] = new Matrix4f();
            chainDelta[i] = new Matrix4f();
        }
    }

    /**
     * Evaluate the scripts for this entity this frame and apply per-part hidden
     * flags and transforms to the mesh about to be drawn.
     */
    public void apply(YSMMesh mesh, LivingEntity entity, OpenMatrix4f[] poses, float partialTick) {
        double now = (entity.tickCount + partialTick) / 20.0;
        mainHand = entity.getItemInHand(InteractionHand.MAIN_HAND);
        offHand = entity.getItemInHand(InteractionHand.OFF_HAND);
        updatePosDelta(entity);

        String state = resolveState(entity);
        if (!state.equals(currentState)) {
            currentState = state;
            animStart.put(state, now);
            animLastT.remove(state);
        }
        fillQueries(entity, partialTick, now);

        activeAnims.clear();
        activeAnims.addAll(model.parallels);
        YSMRuntimeModel.CompiledAnim stateAnim = model.states.get(state);
        if (stateAnim != null) {
            activeAnims.add(stateAnim);
        }
        YSMRuntimeModel.CompiledAnim overlay = resolveOverlay(entity, state);
        if (overlay != null) {
            activeAnims.add(overlay);
        }

        resetScratch();
        for (YSMRuntimeModel.CompiledAnim anim : activeAnims) {
            evalAnim(anim, now);
        }

        composeAndApply(mesh, entity, poses);
    }

    // ------------------------------------------------------------------
    // Animation evaluation
    // ------------------------------------------------------------------

    private void resetScratch() {
        int n = model.bones.length;
        for (int i = 0; i < n; i++) {
            hasPos[i] = false;
            hasRot[i] = false;
            hasScale[i] = false;
            animPos[i][0] = 0;
            animPos[i][1] = 0;
            animPos[i][2] = 0;
            animRot[i][0] = 0;
            animRot[i][1] = 0;
            animRot[i][2] = 0;
            animScale[i][0] = 1;
            animScale[i][1] = 1;
            animScale[i][2] = 1;
        }
    }

    private void evalAnim(YSMRuntimeModel.CompiledAnim anim, double now) {
        double start = animStart.computeIfAbsent(anim.name, k -> now);
        double t = Math.max(0, now - start);
        double tEval = t;
        if (anim.loop == ScriptAnimLoop.REPEAT && anim.length > 1e-4) {
            tEval = t % anim.length;
        } else if (anim.loop == ScriptAnimLoop.HOLD && anim.length > 1e-4) {
            tEval = Math.min(t, anim.length);
        }

        // timelines fire when the (looped) animation time passes them
        animTimeCurrent = (float) tEval;
        float lastT = animLastT.getOrDefault(anim.name, -1f);
        fireTimelines(anim, lastT, (float) tEval);
        animLastT.put(anim.name, (float) tEval);

        for (Map.Entry<Integer, YSMRuntimeModel.CompiledChannels> entry : anim.bones.entrySet()) {
            int boneIdx = entry.getKey();
            YSMRuntimeModel.CompiledChannels channels = entry.getValue();
            if (channels.rot != null) {
                evalChannel(channels.rot, (float) tEval, animRot[boneIdx]);
                // bedrock degrees -> radians, x/y negated (YSM convention)
                animRot[boneIdx][0] = (float) Math.toRadians(-animRot[boneIdx][0]);
                animRot[boneIdx][1] = (float) Math.toRadians(-animRot[boneIdx][1]);
                animRot[boneIdx][2] = (float) Math.toRadians(animRot[boneIdx][2]);
                hasRot[boneIdx] = true;
            }
            if (channels.pos != null) {
                evalChannel(channels.pos, (float) tEval, animPos[boneIdx]);
                // bedrock pixels -> blocks, x negated (YSM convention)
                animPos[boneIdx][0] = -animPos[boneIdx][0] / 16.0f;
                animPos[boneIdx][1] = animPos[boneIdx][1] / 16.0f;
                animPos[boneIdx][2] = animPos[boneIdx][2] / 16.0f;
                hasPos[boneIdx] = true;
            }
            if (channels.scale != null) {
                evalChannel(channels.scale, (float) tEval, animScale[boneIdx]);
                hasScale[boneIdx] = true;
            }
        }
    }

    private void fireTimelines(YSMRuntimeModel.CompiledAnim anim, float lastT, float nowT) {
        if (anim.timelines.length == 0) {
            return;
        }
        if (lastT < 0) {
            // first evaluation: fire entries at t=0
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time <= nowT) {
                    runTimeline(entry);
                }
            }
            return;
        }
        if (nowT >= lastT) {
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time > lastT && entry.time <= nowT) {
                    runTimeline(entry);
                }
            }
        } else {
            // looped past the end: fire the tail entries, then the wrapped ones
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time > lastT) {
                    runTimeline(entry);
                }
            }
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time <= nowT) {
                    runTimeline(entry);
                }
            }
        }
    }

    private void runTimeline(YSMRuntimeModel.CompiledTimeline entry) {
        for (Molang.Expr expr : entry.code) {
            expr.eval(this);
        }
    }

    private void evalChannel(YSMRuntimeModel.CompiledChannel channel, float t, float[] out) {
        float[] times = channel.times;
        int n = times.length;
        int right = 1;
        while (right < n && times[right] <= t) {
            right++;
        }
        if (right >= n) {
            evalValue(channel.post[n - 1], out);
            return;
        }
        if (right == 0) {
            evalValue(channel.post[0], out);
            return;
        }
        int left = right - 1;
        float t0 = times[left];
        float t1 = times[right];
        int lerp = channel.lerps[right];
        if (lerp == ScriptAnimKeyLerp.STEP || t1 <= t0) {
            evalValue(channel.post[left], out);
            return;
        }
        float alpha = (t - t0) / (t1 - t0);
        float[] leftVal = {0, 0, 0};
        float[] rightVal = {0, 0, 0};
        evalValue(channel.post[left], leftVal);
        evalValue(channel.pre[right] != null ? channel.pre[right] : channel.post[right], rightVal);
        if (lerp == ScriptAnimKeyLerp.CATMULLROM && n >= 2) {
            float[] p0 = {0, 0, 0};
            float[] p3 = {0, 0, 0};
            evalValue(channel.post[Math.max(0, left - 1)], p0);
            evalValue(channel.post[Math.min(n - 1, right + 1)], p3);
            for (int i = 0; i < 3; i++) {
                out[i] = catmullRom(p0[i], leftVal[i], rightVal[i], p3[i], alpha);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                out[i] = leftVal[i] + (rightVal[i] - leftVal[i]) * alpha;
            }
        }
    }

    private void evalValue(Molang.Expr[] axes, float[] out) {
        out[0] = (float) axes[0].eval(this);
        out[1] = (float) axes[1].eval(this);
        out[2] = (float) axes[2].eval(this);
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    // ------------------------------------------------------------------
    // Composition & application
    // ------------------------------------------------------------------

    private void composeAndApply(YSMMesh mesh, LivingEntity entity, OpenMatrix4f[] poses) {
        int n = model.bones.length;
        boolean[] composed = new boolean[n];
        for (int i = 0; i < n; i++) {
            composeBone(i, composed);
        }
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String boneName = partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
            Integer boneIdx = model.boneIndex.get(boneName);
            MeshPart part = entry.getValue();
            if (boneIdx == null) {
                continue;
            }
            boolean hidden = effMinScale[boneIdx] < HIDE_SCALE_EPSILON;
            part.setHidden(hidden);
            if (!hidden && !isIdentity(chainDelta[boneIdx])) {
                mesh.setRuntimeTransform(partName, OpenMatrix4f.importFromMojangMatrix(new Matrix4f(chainDelta[boneIdx])));
            }
        }
    }

    private static boolean isIdentity(Matrix4f m) {
        return Math.abs(m.m00() - 1) < 1e-5f && Math.abs(m.m11() - 1) < 1e-5f
                && Math.abs(m.m22() - 1) < 1e-5f && Math.abs(m.m33() - 1) < 1e-5f
                && Math.abs(m.m01()) < 1e-5f && Math.abs(m.m02()) < 1e-5f && Math.abs(m.m03()) < 1e-5f
                && Math.abs(m.m10()) < 1e-5f && Math.abs(m.m12()) < 1e-5f && Math.abs(m.m13()) < 1e-5f
                && Math.abs(m.m20()) < 1e-5f && Math.abs(m.m21()) < 1e-5f && Math.abs(m.m23()) < 1e-5f
                && Math.abs(m.m30()) < 1e-5f && Math.abs(m.m31()) < 1e-5f && Math.abs(m.m32()) < 1e-5f;
    }

    /**
     * Computes the bone's animated local transform, its model-space animation
     * delta (conjugated into bind space) and the composed delta of the whole
     * chain up to the nearest Epic-Fight-driven ancestor, plus the effective
     * visibility scale along the chain.
     */
    private void composeBone(int i, boolean[] composed) {
        if (composed[i]) {
            return;
        }
        YSMRuntimeModel.BoneRt bone = model.bones[i];
        if (bone.parent >= 0) {
            composeBone(bone.parent, composed);
        }

        float sx = hasScale[i] ? animScale[i][0] : 1f;
        float sy = hasScale[i] ? animScale[i][1] : 1f;
        float sz = hasScale[i] ? animScale[i][2] : 1f;

        if (bone.mapped) {
            // Epic Fight owns the gross pose of mapped bones; only scale channels
            // (variant visibility / player size scripts) are honored.
            localAnim[i].translation(bone.px, bone.py, bone.pz)
                    .rotateZ(bone.rz).rotateY(bone.ry).rotateX(bone.rx)
                    .scale(sx, sy, sz)
                    .translate(-bone.px, -bone.py, -bone.pz);
        } else {
            float ox = bone.px + (hasPos[i] ? animPos[i][0] : 0);
            float oy = bone.py + (hasPos[i] ? animPos[i][1] : 0);
            float oz = bone.pz + (hasPos[i] ? animPos[i][2] : 0);
            float rx = hasRot[i] ? animRot[i][0] : bone.rx;
            float ry = hasRot[i] ? animRot[i][1] : bone.ry;
            float rz = hasRot[i] ? animRot[i][2] : bone.rz;
            localAnim[i].translation(ox, oy, oz)
                    .rotateZ(rz).rotateY(ry).rotateX(rx)
                    .scale(sx, sy, sz)
                    .translate(-bone.px, -bone.py, -bone.pz);
        }

        // delta of this bone's animated local vs its bind local, conjugated into
        // model bind space so it can pre-multiply the Epic Fight joint pose
        Matrix4f localDelta = new Matrix4f(localAnim[i]).mul(bone.bindLocalInv);
        if (isIdentity(localDelta)) {
            deltaModel[i].identity();
        } else {
            deltaModel[i].set(bone.bindWorld).mul(localDelta).mul(new Matrix4f(bone.bindWorld).invert());
        }

        float parentMinScale = 1f;
        if (bone.parent >= 0) {
            parentMinScale = effMinScale[bone.parent];
            chainDelta[i].set(chainDelta[bone.parent]).mul(deltaModel[i]);
        } else {
            chainDelta[i].set(deltaModel[i]);
        }
        effMinScale[i] = parentMinScale * Math.min(sx, Math.min(sy, sz));
        composed[i] = true;
    }

    // ------------------------------------------------------------------
    // State machine (mirrors YSM's AnimationRegister predicates)
    // ------------------------------------------------------------------

    private String resolveState(LivingEntity entity) {
        if (entity.isDeadOrDying()) {
            return "death";
        }
        if (entity.getPose() == Pose.SLEEPING) {
            return "sleep";
        }
        if (entity.isSwimming()) {
            return "swim";
        }
        if (entity.getPose() == Pose.SWIMMING && isMoving(entity)) {
            return "climb";
        }
        if (entity.getPose() == Pose.SWIMMING) {
            return "climbing";
        }
        if (entity.isPassenger()) {
            ResourceLocation vehicleId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getVehicle().getType());
            if (vehicleId != null) {
                String vehicleAnim = "vehicle$" + vehicleId;
                if (model.conditionAnims.containsKey(vehicleAnim)) {
                    currentVehicleAnim = vehicleAnim;
                }
            }
            if (entity.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat) {
                return "boat";
            }
            return model.states.containsKey("ride") ? "ride" : "sit";
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return "fly";
        }
        if (entity.getPose() == Pose.FALL_FLYING && entity.isFallFlying()) {
            return "elytra_fly";
        }
        if (entity.isInWater()) {
            return "swim_stand";
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING && isMoving(entity)) {
            return "sneak";
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING) {
            return "sneaking";
        }
        if (entity.onGround() && entity.isSprinting()) {
            return "run";
        }
        if (entity.onGround() && isMoving(entity)) {
            return "walk";
        }
        if (model.states.containsKey("idle")) {
            return "idle";
        }
        return "new_idle_empty";
    }

    private String currentVehicleAnim = null;

    private boolean isMoving(LivingEntity entity) {
        return Math.abs(entity.walkAnimation.speed(Minecraft.getInstance().getFrameTime())) > MIN_SPEED;
    }

    /**
     * Hold/use condition overlay: played alongside the locomotion state by YSM
     * (arm/overlay layer), affecting secondary bones (magic circles, props).
     */
    private YSMRuntimeModel.CompiledAnim resolveOverlay(LivingEntity entity, String state) {
        if (entity.isUsingItem()) {
            InteractionHand hand = entity.getUsedItemHand();
            String prefix = hand == InteractionHand.MAIN_HAND ? "use_mainhand:" : "use_offhand:";
            YSMRuntimeModel.CompiledAnim anim = findConditionAnim(prefix, entity.getUseItem());
            if (anim != null) {
                return anim;
            }
            String generic = hand == InteractionHand.MAIN_HAND ? "use_mainhand" : "use_offhand";
            return model.states.get(generic);
        }
        if (!entity.swinging) {
            if (!mainHand.isEmpty()) {
                YSMRuntimeModel.CompiledAnim anim = findConditionAnim("hold_mainhand:", mainHand);
                if (anim != null) {
                    return anim;
                }
            } else {
                YSMRuntimeModel.CompiledAnim anim = model.conditionAnims.get("hold_mainhand:empty");
                if (anim != null) {
                    return anim;
                }
            }
        }
        if (currentVehicleAnim != null && entity.isPassenger()) {
            return model.conditionAnims.get(currentVehicleAnim);
        }
        return null;
    }

    private YSMRuntimeModel.CompiledAnim findConditionAnim(String prefix, ItemStack stack) {
        YSMRuntimeModel.CompiledAnim best = null;
        int bestLen = -1;
        for (Map.Entry<String, YSMRuntimeModel.CompiledAnim> entry : model.conditionAnims.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String tag = name.substring(prefix.length());
            if (tag.equals("empty")) {
                if (stack.isEmpty() && tag.length() > bestLen) {
                    best = entry.getValue();
                    bestLen = tag.length();
                }
                continue;
            }
            if (itemMatches(stack, tag) && tag.length() > bestLen) {
                best = entry.getValue();
                bestLen = tag.length();
            }
        }
        return best;
    }

    /** YSM condition-tag matching: "$modid:item" exact, ":tag"/"tag" substring of the item id. */
    private static boolean itemMatches(ItemStack stack, String tag) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        if (tag.startsWith("$")) {
            return id.toString().equals(tag.substring(1));
        }
        String t = tag.startsWith(":") ? tag.substring(1) : tag;
        return id.getPath().contains(t) || id.toString().equals(t);
    }

    // ------------------------------------------------------------------
    // Molang Env
    // ------------------------------------------------------------------

    @Override
    public double getVar(String path) {
        return vars.getOrDefault(path, 0.0);
    }

    @Override
    public boolean hasVar(String path) {
        return vars.containsKey(path);
    }

    @Override
    public void setVar(String path, double value) {
        vars.put(path, value);
    }

    @Override
    public double getQuery(String path) {
        if (path.startsWith("q.")) {
            path = "query." + path.substring(2);
        }
        if (path.equals("query.anim_time")) {
            return animTimeCurrent;
        }
        return queries.getOrDefault(path, 0.0);
    }

    @Override
    public double callFunction(String name, double[] args) {
        switch (name) {
            case "math.sin":
                return Math.sin(Math.toRadians(args[0]));
            case "math.cos":
                return Math.cos(Math.toRadians(args[0]));
            case "math.tan":
                return Math.tan(Math.toRadians(args[0]));
            case "math.asin":
                return Math.toDegrees(Math.asin(args[0]));
            case "math.acos":
                return Math.toDegrees(Math.acos(args[0]));
            case "math.atan":
                return Math.toDegrees(Math.atan(args[0]));
            case "math.atan2":
                return Math.toDegrees(Math.atan2(args[0], args[1]));
            case "math.abs":
                return Math.abs(args[0]);
            case "math.floor":
                return Math.floor(args[0]);
            case "math.ceil":
                return Math.ceil(args[0]);
            case "math.round":
                return Math.round(args[0]);
            case "math.trunc":
                return (long) (args[0] >= 0 ? Math.floor(args[0]) : Math.ceil(args[0]));
            case "math.sqrt":
                return args[0] < 0 ? 0 : Math.sqrt(args[0]);
            case "math.pow":
                return Math.pow(args[0], args[1]);
            case "math.exp":
                return Math.exp(args[0]);
            case "math.ln":
                return args[0] <= 0 ? 0 : Math.log(args[0]);
            case "math.log":
                return args[0] <= 0 ? 0 : Math.log(args[0]);
            case "math.lerp":
                return args[0] + (args[1] - args[0]) * args[2];
            case "math.min":
                return Math.min(args[0], args[1]);
            case "math.max":
                return Math.max(args[0], args[1]);
            case "math.clamp":
                return Math.max(args[1], Math.min(args[2], args[0]));
            case "math.mod":
                return args[1] == 0 ? 0 : args[0] % args[1];
            case "math.random":
                return args[0] + Math.random() * (args[1] - args[0]);
            case "math.pi":
                return Math.PI;
            case "math.sign":
                return Math.signum(args[0]);
            case "query.position_delta":
                int axis = (int) args[0];
                return axis >= 0 && axis < 3 ? posDelta[axis] : 0;
            default:
                return 0;
        }
    }

    @Override
    public double callStringFunction(String name, String[] args) {
        if (name.equals("ctrl.hold") && args.length >= 2 && args[0] != null && args[1] != null) {
            ItemStack stack = args[0].toLowerCase().startsWith("off") ? offHand : mainHand;
            return itemMatchesLoose(stack, args[1]) ? 1.0 : 0.0;
        }
        return 0;
    }

    private static boolean itemMatchesLoose(ItemStack stack, String pattern) {
        if (pattern.startsWith("$")) {
            if (stack.isEmpty()) {
                return false;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return id != null && id.toString().equals(pattern.substring(1));
        }
        if (stack.isEmpty()) {
            return pattern.equals(":empty") || pattern.equals("empty");
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String t = pattern.startsWith(":") ? pattern.substring(1) : pattern;
        return id.getPath().contains(t) || id.getNamespace().equals(t);
    }

    // ------------------------------------------------------------------
    // Query context
    // ------------------------------------------------------------------

    private void updatePosDelta(LivingEntity entity) {
        if (hasLastPos) {
            posDelta[0] = entity.getX() - lastPosX;
            posDelta[1] = entity.getY() - lastPosY;
            posDelta[2] = entity.getZ() - lastPosZ;
        } else {
            hasLastPos = true;
        }
        lastPosX = entity.getX();
        lastPosY = entity.getY();
        lastPosZ = entity.getZ();
    }

    private void fillQueries(LivingEntity entity, float partialTick, double now) {
        Map<String, Double> q = queries;
        Minecraft mc = Minecraft.getInstance();

        float headYaw = entity.yHeadRotO + (entity.yHeadRot - entity.yHeadRotO) * partialTick;
        float bodyYaw = entity.yBodyRotO + (entity.yBodyRot - entity.yBodyRotO) * partialTick;
        float netHeadYaw = net.minecraft.util.Mth.wrapDegrees(headYaw - bodyYaw);
        float headPitch = entity.getViewXRot(partialTick);

        if (lastYawSampleTime >= 0 && now > lastYawSampleTime) {
            yawSpeedDeg = (netHeadYaw - lastHeadYawDeg) / (now - lastYawSampleTime);
        }
        lastHeadYawDeg = netHeadYaw;
        lastYawSampleTime = now;

        double dx = entity.getX() - entity.xo;
        double dz = entity.getZ() - entity.zo;
        double groundSpeed = 20.0 * Math.sqrt(dx * dx + dz * dz);
        double verticalSpeed = 20.0 * (entity.getY() - entity.yo);

        boolean onGround = entity.onGround();
        boolean crouching = entity.getPose() == Pose.CROUCHING;

        q.put("query.life_time", now);
        q.put("query.health", (double) entity.getHealth());
        q.put("query.max_health", (double) entity.getMaxHealth());
        q.put("query.hurt_time", (double) entity.hurtTime);
        q.put("query.vertical_speed", verticalSpeed);
        q.put("query.ground_speed", groundSpeed);
        q.put("query.yaw_speed", yawSpeedDeg);
        q.put("query.is_sneaking", onGround && crouching ? 1.0 : 0.0);
        q.put("query.is_swimming", entity.isSwimming() ? 1.0 : 0.0);
        q.put("query.is_sprinting", entity.isSprinting() ? 1.0 : 0.0);
        q.put("query.is_on_ground", onGround ? 1.0 : 0.0);
        q.put("query.is_jumping", !isCreativeFlying(entity) && !entity.isPassenger() && !onGround && !entity.isInWater() ? 1.0 : 0.0);
        q.put("query.is_riding", entity.isPassenger() ? 1.0 : 0.0);
        q.put("query.is_sleeping", entity.isSleeping() ? 1.0 : 0.0);
        q.put("query.is_in_water", entity.isInWater() ? 1.0 : 0.0);
        q.put("query.is_in_water_or_rain", entity.isInWaterRainOrBubble() ? 1.0 : 0.0);
        q.put("query.is_gliding", entity.isFallFlying() ? 1.0 : 0.0);
        q.put("query.is_on_fire", entity.isOnFire() ? 1.0 : 0.0);
        q.put("query.is_playing_dead", entity.isDeadOrDying() ? 1.0 : 0.0);
        q.put("query.is_spectator", entity instanceof Player player && player.isSpectator() ? 1.0 : 0.0);
        q.put("query.is_using_item", entity.isUsingItem() ? 1.0 : 0.0);
        q.put("query.is_eating", entity.getUseItem().getUseAnimation() == net.minecraft.world.item.UseAnim.EAT ? 1.0 : 0.0);
        q.put("query.is_first_person", mc.options.getCameraType() == CameraType.FIRST_PERSON ? 1.0 : 0.0);
        q.put("query.item_in_use_duration", entity.getTicksUsingItem() / 20.0);
        q.put("query.item_max_use_duration", entity.getUseItem().getUseDuration() / 20.0);
        q.put("query.item_remaining_use_duration", entity.getUseItemRemainingTicks() / 20.0);
        q.put("query.walk_distance", (double) entity.moveDist);
        q.put("query.modified_distance_moved", (double) entity.walkDist);
        q.put("query.body_x_rotation", (double) entity.getXRot());
        q.put("query.body_y_rotation", (double) net.minecraft.util.Mth.wrapDegrees(entity.getYRot()));
        q.put("query.head_x_rotation", (double) netHeadYaw);
        q.put("query.head_y_rotation", (double) headPitch);
        q.put("query.cardinal_facing_2d", (double) entity.getDirection().get3DDataValue());
        q.put("query.time_of_day", (entity.level().getDayTime() % 24000L) / 24000.0);
        q.put("query.time_stamp", (double) entity.level().getDayTime());
        q.put("query.moon_phase", (double) entity.level().getMoonPhase());
        q.put("query.player_level", (double) (entity instanceof Player player ? player.experienceLevel : 0));
        q.put("query.has_rider", entity.isVehicle() ? 1.0 : 0.0);
        q.put("query.actor_count", 0.0);
        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            q.put("query.distance_from_camera", mc.gameRenderer.getMainCamera().getPosition().distanceTo(entity.position()));
        }

        q.put("ysm.head_yaw", (double) netHeadYaw);
        q.put("ysm.head_pitch", (double) headPitch);
        q.put("ysm.has_mainhand", mainHand.isEmpty() ? 0.0 : 1.0);
        q.put("ysm.has_offhand", offHand.isEmpty() ? 0.0 : 1.0);
        q.put("ysm.has_helmet", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty() ? 0.0 : 1.0);
        q.put("ysm.has_chest_plate", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty() ? 0.0 : 1.0);
        q.put("ysm.has_leggings", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).isEmpty() ? 0.0 : 1.0);
        q.put("ysm.has_boots", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty() ? 0.0 : 1.0);
        q.put("ysm.has_elytra", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA) ? 1.0 : 0.0);
        q.put("ysm.is_sleep", entity.getPose() == Pose.SLEEPING ? 1.0 : 0.0);
        q.put("ysm.is_sneak", onGround && crouching ? 1.0 : 0.0);
        q.put("ysm.is_passenger", entity.isPassenger() ? 1.0 : 0.0);
        q.put("ysm.is_riptide", entity.isAutoSpinAttack() ? 1.0 : 0.0);
        q.put("ysm.armor_value", (double) entity.getArmorValue());
        q.put("ysm.hurt_time", (double) entity.hurtTime);
        q.put("ysm.food_level", (double) (entity instanceof Player player ? player.getFoodData().getFoodLevel() : 20));

        q.put("ctrl.idle", currentState.equals("idle") || currentState.equals("new_idle_empty") ? 1.0 : 0.0);
        q.put("ctrl.run", currentState.equals("run") ? 1.0 : 0.0);
        q.put("ctrl.walk", currentState.equals("walk") ? 1.0 : 0.0);
        q.put("ctrl.playing_extra_animation", 0.0);
    }

    private static boolean isCreativeFlying(LivingEntity entity) {
        return entity instanceof Player player && player.getAbilities().flying;
    }

    // loop-mode constants mirrored from ScriptAnim to keep switch sites readable
    private static final class ScriptAnimLoop {
        static final int REPEAT = com.ysmef.compat.ysm.script.ScriptAnim.LOOP_REPEAT;
        static final int HOLD = com.ysmef.compat.ysm.script.ScriptAnim.LOOP_HOLD;
    }

    private static final class ScriptAnimKeyLerp {
        static final int STEP = com.ysmef.compat.ysm.script.ScriptAnim.Key.LERP_STEP;
        static final int CATMULLROM = com.ysmef.compat.ysm.script.ScriptAnim.Key.LERP_CATMULLROM;
    }
}
