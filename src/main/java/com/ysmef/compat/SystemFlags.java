package com.ysmef.compat;

import java.util.Locale;

/**
 * Boolean-valued system flags, read the way the person typing one expects.
 *
 * <p>{@code -Dname} and {@code -Dname=true} ask for the flag to be on; {@code -Dname=false} asks for
 * it to be <b>off</b>. That second half is the whole reason this class exists. The flags used to be
 * read with {@code System.getProperty(name) != null} at each call site, which means the property's
 * <i>presence</i> was the signal - so a launcher profile carrying {@code -Dysm_ef_compat.disable_gpu=false}
 * left the GPU path disabled, and {@code -Dysm_ef_compat.force_cpu_render=false} forced the CPU path.
 * Writing an explicit {@code =false} to undo a flag is the first thing a person tries, and it did the
 * opposite of what it said; three separate call sites had the same trap.
 *
 * <p>{@code 0}, {@code no} and {@code off} are read the same as {@code false}, case-insensitively and
 * ignoring surrounding spaces; anything else with a value counts as on. An absent property is off,
 * which is also what a call site wants when it asks about a {@code disable_*} flag.
 *
 * <p>No Minecraft types here on purpose: a plain unit test can hold this behaviour, and the three
 * call sites get one implementation instead of three copies of the rule.
 */
public final class SystemFlags {

    private SystemFlags() {}

    /** Whether the named system property asks for its flag to be on. */
    public static boolean enabled(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !(normalized.equals("false") || normalized.equals("0")
                || normalized.equals("no") || normalized.equals("off"));
    }
}
