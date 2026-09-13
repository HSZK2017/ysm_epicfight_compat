package com.ysmef.compat.compat;

import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether another mod owns how a player looks right now - a transformation into a
 * demon, a possession, a costume that mod draws itself - and whether one is hiding
 * the player for a moment.
 *
 * <p>While another mod owns the look, this mod steps aside for that player: no
 * converted YSM mesh, no Epic Fight layers of ours over someone else's model. The
 * moment the look is given back, YSM is back too. Without this the compat mod keeps
 * posing and layering a model that another mod has replaced - the two draw over each
 * other.
 *
 * <p>Two ways to notice, ported from EpicYSM's {@code LookOwners} (MIT):
 * <ul>
 *   <li><b>the general one</b>: a mod that turns a player into something else gives
 *       the player a skeleton of its own, and the patch's armature stops being Epic
 *       Fight's biped. This needs no knowledge of that mod.</li>
 *   <li><b>the specific ones</b>: a detector registered per mod, for mods that keep
 *       the biped skeleton and only swap the mesh, where the skeleton gives nothing
 *       away.</li>
 * </ul>
 *
 * <p>The two questions have different lifetimes and are cached differently, which
 * matters for both correctness and cost:
 * <ul>
 *   <li>{@link #ownsLook} is asked once per player per tick - a transformation is a
 *       slow-changing state, and detectors may reflect into another mod.</li>
 *   <li>{@link #hiddenLately} is asked every frame - a teleport or a cut changes
 *       within a single animation.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class LookOwners {

    /** One mod's way of saying "this player is mine right now". */
    public interface Detector {
        /** A short name for the log, e.g. the mod's id. */
        String name();

        /** Null when the mod does not own the look; a few words on why when it does. */
        String owns(AbstractClientPlayer player, LivingEntityPatch<?> patch);

        /**
         * Null when the mod is not hiding the player; a few words on why when it is -
         * a teleport, a cut where the character is meant to vanish. While a mod hides
         * a player, nothing draws that player, this mod included.
         */
        default String hides(AbstractClientPlayer player, LivingEntityPatch<?> patch) {
            return null;
        }
    }

    private static final List<Detector> DETECTORS = new CopyOnWriteArrayList<>();

    /** Per player: the tick the answer was found on, and the answer. */
    private static final Map<UUID, Answer> ANSWERS = new ConcurrentHashMap<>();
    /** Per player: an ongoing hidden episode. */
    private static final Map<UUID, Episode> HIDDEN = new ConcurrentHashMap<>();
    /** Per player: the reason last reported, so the log says it once per episode. */
    private static final Map<UUID, String> SAID = new ConcurrentHashMap<>();

    /** How long a cancelled render keeps counting as "hidden now": a few frames. */
    private static final long RECENT_NANOS = 100_000_000L;

    /** Epic Fight's biped name and joint list, read once from its armature registry. */
    private static volatile String bipedName;
    private static volatile List<String> bipedJoints;

    private record Answer(int tick, String reason) {}

    /**
     * An ongoing or recently-ended hidden episode.
     *
     * <p>{@code lastHiddenNanos} / {@code lastShownNanos} are the state: the player
     * counts as hidden while a hidden signal is more recent than a rendered frame.
     * Both are updated every frame, but only a <i>transition</i> is logged.
     *
     * <p>That distinction is the whole point. The first version of this class logged
     * on every record and every clear, and because the render hook both records a
     * cancelled render and reports a completed one on consecutive frames, it
     * flip-flopped: one episode per frame, 52k log lines, 97% of the session's log.
     * A flapping signal is exactly the case where logging every edge is useless.
     */
    private static final class Episode {
        String by;
        long lastHiddenNanos;
        long lastShownNanos;
        /** Whether the transition into and out of "hidden" has been logged. */
        boolean loggedHidden;
        /** Consecutive frames the hidden signal has repeated (diagnostic). */
        int hiddenFrames;
    }

    private LookOwners() {}

    /** Detectors shipped with this mod; each registers only when its mod is present. */
    public static void registerBuiltIn() {
        // No built-in detectors yet: the skeleton heuristic below covers mods that
        // bring their own armature, which is the common case. Mods that keep the
        // biped and only swap the mesh need one detector each, added here.
    }

    /** Register a detector for another mod. Logged, so the pairing is discoverable. */
    public static void register(Detector detector) {
        DETECTORS.add(detector);
        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: compatibility - {} may take over a player's look; YSM steps aside while it does",
                detector.name());
    }

    /** True while another mod owns this player's look. Answered once a tick. */
    public static boolean ownsLook(AbstractClientPlayer player) {
        return reason(player) != null;
    }

    /** Why another mod owns this player's look, or null when the look is ours. */
    public static String reason(AbstractClientPlayer player) {
        if (player == null) {
            return null;
        }
        int tick = player.tickCount;
        UUID id = player.getUUID();
        Answer cached = ANSWERS.get(id);
        if (cached != null && cached.tick() == tick) {
            return cached.reason();
        }

        String found = null;
        try {
            found = look(player);
        } catch (Throwable t) {
            // A detector that fails says nothing, rather than claiming the look.
        }
        ANSWERS.put(id, new Answer(tick, found));

        String before = SAID.get(id);
        if (found != null && !found.equals(before)) {
            SAID.put(id, found);
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: player '{}': another mod has taken over the look ({}); YSM steps aside until it is given back",
                    player.getGameProfile().getName(), found);
        } else if (found == null && before != null) {
            SAID.remove(id);
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: player '{}': the look is given back; YSM draws again",
                    player.getGameProfile().getName());
        }
        return found;
    }

    /**
     * True while this player is hidden by whatever means - a detector saying so now,
     * or a render of this player cancelled by another mod within the last few frames.
     * Asked every frame, because it changes inside an animation.
     *
     * <p>Recording a hidden signal here and clearing it in {@link #shown} would
     * flip-flop: the render hook reports a cancelled render and a completed one on
     * consecutive frames, so the player would enter and leave "hidden" every frame.
     * Instead both facts are stamped and the state is derived from which stamp is
     * newer, which is stable when the signal repeats and only changes when it really
     * does.
     *
     * @param askedBy whoever asked, for the log: an object is named by its class, a
     *                string is used as-is
     */
    public static boolean hiddenLately(AbstractClientPlayer player, Object askedBy) {
        if (player == null) {
            return false;
        }
        boolean detected = hidden(player, askedBy);
        Episode episode = HIDDEN.get(player.getUUID());
        long now = System.nanoTime();

        if (episode == null) {
            if (!detected) {
                return false;
            }
            episode = new Episode();
            HIDDEN.put(player.getUUID(), episode);
        }

        if (detected) {
            episode.lastHiddenNanos = now;
            episode.hiddenFrames++;
            if (!episode.loggedHidden) {
                episode.loggedHidden = true;
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: player '{}': hidden ({}); nothing draws the player while it lasts (asked by {})",
                        player.getGameProfile().getName(),
                        episode.by == null ? "a cancelled render" : episode.by,
                        describe(askedBy));
            }
        } else {
            episode.lastShownNanos = now;
        }

        // Hidden while the last hidden signal is newer than the last rendered frame,
        // and recent enough to still belong to this moment.
        boolean hiddenNow = episode.lastHiddenNanos > episode.lastShownNanos
                && now - episode.lastHiddenNanos < RECENT_NANOS;
        if (!hiddenNow && episode.loggedHidden) {
            episode.loggedHidden = false;
            episode.hiddenFrames = 0;
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: player '{}': shown again",
                    player.getGameProfile().getName());
        }
        return hiddenNow;
    }

    /** A caller, named for the log. */
    private static String describe(Object askedBy) {
        return askedBy == null ? "nobody"
                : askedBy instanceof String name ? name : askedBy.getClass().getName();
    }

    /**
     * Whether a detector wants this player not drawn at all, right now. Pure
     * detection: the state and the logging live in {@link #hiddenLately}.
     */
    public static boolean hidden(AbstractClientPlayer player, Object askedBy) {
        if (player == null || DETECTORS.isEmpty()) {
            return false;
        }
        try {
            LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);
            if (patch == null) {
                return false;
            }
            for (Detector detector : DETECTORS) {
                String why = detector.hides(player, patch);
                if (why != null) {
                    rememberReason(player, detector.name() + " (" + why + ")");
                    return true;
                }
            }
        } catch (Throwable t) {
            // A detector that fails hides nothing.
        }
        return false;
    }

    /**
     * Another mod cancelled this player's render before anyone drew. Records the
     * reason; the hidden state itself is derived by {@link #hiddenLately}, which the
     * render hook calls for every player on every frame.
     */
    public static void hiddenByAnotherMod(AbstractClientPlayer player, Object askedBy) {
        rememberReason(player, "another mod, which cancelled the render");
    }

    /**
     * Kept for callers that report a completed render: the state is derived from the
     * per-frame stamps, so nothing needs clearing here. The render hook reaches
     * {@link #hiddenLately} on the same frame, which stamps the visible side.
     */
    public static void shown(AbstractClientPlayer player) {
        // Intentionally empty - see the class comment on why this must not clear
        // state: doing so per frame is what made the first version flip-flop.
    }

    /** Record why the player is hidden, without touching the state stamps. */
    private static void rememberReason(AbstractClientPlayer player, String by) {
        Episode episode = HIDDEN.get(player.getUUID());
        if (episode == null) {
            episode = new Episode();
            HIDDEN.put(player.getUUID(), episode);
        }
        episode.by = by;
    }

    /** The look's owner, or null: the skeleton heuristic first, then the detectors. */
    private static String look(AbstractClientPlayer player) {
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);
        if (patch == null) {
            return null;
        }
        Armature armature = patch.getArmature();
        if (armature != null && !isBiped(armature)) {
            return "skeleton " + armature;
        }
        for (Detector detector : DETECTORS) {
            String why = detector.owns(player, patch);
            if (why != null) {
                return detector.name() + ": " + why;
            }
        }
        return null;
    }

    /**
     * Whether an armature is Epic Fight's own biped, by name, or a skeleton that has
     * every one of its joints.
     *
     * <p>The second case matters: a weapon addon that brings a mesh of its own (a
     * thorn wheel, a set of claws) gives the player the biped with the weapon's
     * joints added, and the biped's joints still pose the model correctly. Only a
     * skeleton missing some of the biped's joints is a different skeleton, and
     * therefore another mod's business.
     */
    private static boolean isBiped(Armature armature) {
        try {
            String name = bipedName;
            if (name == null) {
                name = String.valueOf(Armatures.BIPED.get());
                bipedName = name;
            }
            if (name.equals(String.valueOf(armature))) {
                return true;
            }
            return hasEveryBipedJoint(armature);
        } catch (Throwable t) {
            // Cannot read Epic Fight's biped: assume it is ours, so this mod keeps
            // working rather than stepping aside for everyone.
            return true;
        }
    }

    private static boolean hasEveryBipedJoint(Armature armature) {
        try {
            List<String> joints = bipedJoints;
            if (joints == null) {
                List<String> names = new ArrayList<>();
                gather(Armatures.BIPED.get().rootJoint, names);
                joints = List.copyOf(names);
                bipedJoints = joints;
            }
            if (joints.isEmpty()) {
                return true;
            }
            for (String joint : joints) {
                if (armature.searchJointByName(joint) == null) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void gather(Joint joint, List<String> out) {
        if (joint == null) {
            return;
        }
        out.add(joint.getName());
        for (Joint child : joint.getSubJoints()) {
            gather(child, out);
        }
    }

    /** Forget one player's state (disconnect). */
    public static void forget(UUID player) {
        ANSWERS.remove(player);
        SAID.remove(player);
        HIDDEN.remove(player);
    }

    /** Forget every player's state (world leave). */
    public static void resetAll() {
        ANSWERS.clear();
        SAID.clear();
        HIDDEN.clear();
    }
}
