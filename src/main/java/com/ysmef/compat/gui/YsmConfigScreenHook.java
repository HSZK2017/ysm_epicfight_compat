package com.ysmef.compat.gui;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.gpu.YsmGpuRenderEnable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Adds the "YSM-EF Compat: GPU Rendering" checkbox (mirroring the OpenYSM /
 * ModernYSM checkable option buttons) to the YSM config screen of the model
 * selection GUI, bound to this mod's enableGpuRender client config.
 *
 * The YSM release (and OpenYSM) config screen is
 * com.elfmcys.yesstevemodel.client.gui.ExtraPlayerConfigScreen ("YSM Config
 * GUI"): 22px checkbox rows at y+45..y+243 and the loading_state_position
 * button at y+264. The mixin moves that button down 22px (freeing the y+265
 * row) and appends a checkbox there.
 *
 * Forge 1.20.1 runs with SRG names (m_7856_ = init, m_142416_ =
 * addRenderableWidget), so all reflective member lookups use the SRG names.
 *
 * ModernYSM already ships its own UseGpuRenderer checkbox, so nothing is added
 * for it (see {@link #shouldAddGpuRenderOption()}).
 */
public final class YsmConfigScreenHook {

    /** Translation key of the checkbox label (same prefix both GUIs use). */
    public static final String GPU_RENDER_OPTION_KEY = "ysm_ef_compat_gpu_render";

    private static final String SCREEN_CLASS = "com.elfmcys.yesstevemodel.client.gui.ExtraPlayerConfigScreen";

    private YsmConfigScreenHook() {}

    /**
     * Append the checkbox row to the config screen's init (m_7856_): one row
     * below the last checkbox, at the y offset given by the caller (the mixin
     * moves the loading_state_position button down to free that row).
     */
    public static void addGpuRenderCheckbox(Screen screen, int yOffset) {
        if (!shouldAddGpuRenderOption()) {
            return;
        }
        try {
            int x = (screen.width - 420) / 2;
            int y = (screen.height - 265) / 2;
            Object widget = new YsmGpuRenderCheckbox(x + 5, y + yOffset,
                    Component.translatable("gui.yes_steve_model.config." + GPU_RENDER_OPTION_KEY));
            // Screen.addRenderableWidget is protected; SRG name at runtime (m_142416_).
            Method addRenderableWidget = null;
            for (Method method : Screen.class.getMethods()) {
                if (method.getName().equals("m_142416_")) {
                    addRenderableWidget = method;
                    break;
                }
            }
            if (addRenderableWidget == null) {
                return;
            }
            addRenderableWidget.setAccessible(true);
            addRenderableWidget.invoke(screen, widget);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to add the GPU render checkbox to the YSM config screen", t);
        }
    }

    /**
     * OpenYSM-with-OptionScreen variant (ModernYSM-style GUI): append a
     * BooleanOptionRow bound to this mod's enableGpuRender config to the
     * "performance" group of the ExtraPlayerConfigScreen. All OpenYSM classes
     * are accessed reflectively; if the GUI API differs from the expected one,
     * the row is simply not added (the config still works).
     */
    public static void addOpenYsmGpuRenderOption(Screen screen) {
        if (!shouldAddGpuRenderOption()) {
            return;
        }
        try {
            Object groups = readGroupsField(screen);
            if (!(groups instanceof List<?> list)) {
                return;
            }
            Class<?> groupClass = Class.forName("rip.ysm.gui.OptionGroup");
            Object group = findGroup(list, groupClass, "performance");
            boolean createdGroup = false;
            if (group == null) {
                group = groupClass.getConstructor(String.class).newInstance("ysm_ef_compat");
                ((java.util.List) list).add(group);
                createdGroup = true;
            }

            Class<?> optionClass = Class.forName("rip.ysm.gui.Option");
            Method ofBoolean = optionClass.getMethod("ofBoolean", String.class, ForgeConfigSpec.BooleanValue.class);
            Object option = ofBoolean.invoke(null, GPU_RENDER_OPTION_KEY, YSMCompatConfig.ENABLE_GPU_RENDER);

            Class<?> rowClass = Class.forName("rip.ysm.gui.components.BooleanOptionRow");
            Object row = rowClass.getConstructor(int.class, int.class, int.class, int.class, optionClass)
                    .newInstance(0, 0, 0, 22, option);

            Method add = findAddMethod(groupClass);
            if (add != null) {
                add.invoke(group, row);
            }
            if (createdGroup) {
                YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: added '{}' option group to the OpenYSM config screen", "ysm_ef_compat");
            } else {
                YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: added GPU render option to the OpenYSM 'performance' group");
            }
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to add the GPU render option to the OpenYSM config screen", t);
        }
    }

    private static Object readGroupsField(Screen screen) throws Exception {
        Class<?> cls = screen.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field field = cls.getDeclaredField("groups");
                field.setAccessible(true);
                return field.get(screen);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static Object findGroup(List<?> groups, Class<?> groupClass, String key) {
        try {
            Method getKey = groupClass.getMethod("getTranslationKey");
            for (Object group : groups) {
                if (key.equals(getKey.invoke(group))) {
                    return group;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findAddMethod(Class<?> groupClass) {
        for (Method method : groupClass.getMethods()) {
            if (method.getName().equals("add") && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    /** Whether the checkbox row should be shown at all (not for ModernYSM, which has its own). */
    public static boolean shouldAddGpuRenderOption() {
        return YsmGpuRenderEnable.fork() != YsmGpuRenderEnable.YsmFork.MODERN_YSM;
    }
}
