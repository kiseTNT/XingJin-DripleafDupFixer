package com.xingjin.dripleafdup;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.SmallDripleaf;

/**
 * 小型垂滴叶残缺（幽灵）结构判定。
 *
 * <p>合法结构：上半段（TOP）正下方必须是 SMALL_DRIPLEAF；
 * 单格下半段（BOTTOM）正下方必须有实际支撑方块。
 * 残缺结构：TOP 但正下方不是垂滴叶，或 BOTTOM 但正下方为空气/液体。
 */
public final class DripleafUtil {

    private static final Material DRIPLEAF = Material.SMALL_DRIPLEAF;

    private DripleafUtil() {
    }

    public static boolean isSmallDripleaf(Block block) {
        return block != null && block.getType() == DRIPLEAF;
    }

    /** 获取相邻方块，越出世界边界返回 null。仅主线程调用。 */
    public static Block relative(Block block, BlockFace face) {
        World world = block.getWorld();
        int[] height = Compat.worldHeight(world);
        int y = block.getY() + face.getModY();
        if (y < height[0] || y >= height[1]) {
            return null;
        }
        if (face.getModX() == 0 && face.getModZ() == 0) {
            return world.getBlockAt(block.getX(), y, block.getZ());
        }
        return block.getRelative(face);
    }

    /** 主线程判定：是否为残缺（幽灵）小型垂滴叶。 */
    public static boolean isBroken(Block block) {
        if (!isSmallDripleaf(block)) {
            return false;
        }
        // BlockData 类型不符时不拦截，宁可放过不可误杀
        if (!(block.getBlockData() instanceof SmallDripleaf data)) {
            return false;
        }

        Block below = relative(block, BlockFace.DOWN);
        Block above = relative(block, BlockFace.UP);
        return broken(
                data.getHalf() == Bisected.Half.TOP,
                isSmallDripleaf(below),
                isSmallDripleaf(above),
                hasSupport(below == null ? null : below.getType()));
    }

    /**
     * 异步判定：基于区块快照，不访问实时世界。
     * x/z 为 0-15，y 为世界绝对 Y。
     */
    public static boolean isBroken(ChunkSnapshot snapshot, int x, int y, int z, int minY, int maxY) {
        if (snapshot.getBlockType(x, y, z) != DRIPLEAF) {
            return false;
        }
        if (!(snapshot.getBlockData(x, y, z) instanceof SmallDripleaf data)) {
            return false;
        }

        // 上下相邻方块与当前同列，必在同一区块快照内
        int belowY = y - 1;
        int aboveY = y + 1;
        boolean belowIsDripleaf = belowY >= minY && snapshot.getBlockType(x, belowY, z) == DRIPLEAF;
        boolean aboveIsDripleaf = aboveY < maxY && snapshot.getBlockType(x, aboveY, z) == DRIPLEAF;
        Material supportType = belowY >= minY ? snapshot.getBlockType(x, belowY, z) : null;

        return broken(data.getHalf() == Bisected.Half.TOP, belowIsDripleaf, aboveIsDripleaf,
                hasSupport(supportType));
    }

    /** 两条路径共用的核心判定 */
    private static boolean broken(boolean top, boolean belowIsDripleaf,
                                  boolean aboveIsDripleaf, boolean support) {
        if (top) {
            return !belowIsDripleaf;
        }
        if (aboveIsDripleaf) {
            return false;
        }
        return !support;
    }

    /** 只把空气/液体判为无支撑，其余一律视为有支撑，避免误判 */
    private static boolean hasSupport(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        return type != Material.WATER && type != Material.LAVA;
    }
}
