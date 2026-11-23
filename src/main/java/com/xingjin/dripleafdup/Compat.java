package com.xingjin.dripleafdup;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.World;

/**
 * 多版本兼容：World#getMinHeight/getMaxHeight 为 1.18+ API，
 * 1.17.1 上通过反射调用，不可用时回退固定高度 0~255（max 独占）。
 */
public final class Compat {

    private static final Method MIN_HEIGHT;
    private static final Method MAX_HEIGHT;

    static {
        Method min = null;
        Method max = null;
        try {
            min = World.class.getMethod("getMinHeight");
        } catch (Throwable ignored) {
        }
        try {
            max = World.class.getMethod("getMaxHeight");
        } catch (Throwable ignored) {
        }
        MIN_HEIGHT = min;
        MAX_HEIGHT = max;
    }

    private static final int LEGACY_MIN = 0;
    private static final int LEGACY_MAX = 256;

    private static final Map<String, int[]> HEIGHT_CACHE = new HashMap<>();

    private Compat() {
    }

    /**
     * 获取世界高度范围（主线程调用）。
     *
     * @return int[]{minY, maxYExclusive}
     */
    public static int[] worldHeight(World world) {
        return HEIGHT_CACHE.computeIfAbsent(world.getName(), name -> {
            int min = LEGACY_MIN;
            int max = LEGACY_MAX;
            try {
                if (MIN_HEIGHT != null) {
                    min = (Integer) MIN_HEIGHT.invoke(world);
                }
                if (MAX_HEIGHT != null) {
                    max = (Integer) MAX_HEIGHT.invoke(world);
                }
            } catch (Throwable t) {
                min = LEGACY_MIN;
                max = LEGACY_MAX;
            }
            if (max <= min) {
                min = LEGACY_MIN;
                max = LEGACY_MAX;
            }
            return new int[]{min, max};
        });
    }
}
