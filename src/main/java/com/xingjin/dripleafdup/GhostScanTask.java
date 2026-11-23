package com.xingjin.dripleafdup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

/**
 * 残缺垂滴叶异步定时清理：主线程每 tick 拍 {@link #CHUNKS_PER_TICK} 个区块快照，
 * 异步线程遍历快照判定，主线程每 tick 限量复查并移除。
 */
public final class GhostScanTask implements Runnable {

    /** 主线程每 tick 拍摄快照的区块数量 */
    private static final int CHUNKS_PER_TICK = 4;

    /** 主线程每 tick 执行的清理数量 */
    private static final int REMOVALS_PER_TICK = 128;

    private final XingJinDripleafDupFixer plugin;

    private final Deque<Chunk> pendingChunks = new ArrayDeque<>();
    private final Queue<GhostPos> pendingRemovals = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    private BukkitTask timerTask;
    private BukkitTask producerTask;
    private BukkitTask consumerTask;
    private boolean sweeping;
    private int removedThisSweep;

    /** 一个区块的只读扫描输入：快照 + 世界元数据 */
    private record ChunkSnap(ChunkSnapshot snapshot, String world, int minY, int maxY) {
    }

    /** 一条待清理坐标 */
    private record GhostPos(String world, int x, int y, int z) {
    }

    public GhostScanTask(XingJinDripleafDupFixer plugin) {
        this.plugin = plugin;
    }

    public void start(long intervalSeconds) {
        stop();
        long period = Math.max(20L, intervalSeconds * 20L);
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this, period, period);
        plugin.getLogger().info("[AntiDup] 异步定时清理已启动，间隔 " + intervalSeconds + " 秒。");
    }

    public void stop() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        if (producerTask != null) {
            producerTask.cancel();
            producerTask = null;
        }
        if (consumerTask != null) {
            consumerTask.cancel();
            consumerTask = null;
        }
        sweeping = false;
        pendingChunks.clear();
        pendingRemovals.clear();
        inFlight.set(0);
    }

    @Override
    public void run() {
        // 上一轮未结束则跳过，避免任务叠加
        if (sweeping) {
            return;
        }
        pendingChunks.clear();
        pendingRemovals.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                pendingChunks.add(chunk);
            }
        }
        if (pendingChunks.isEmpty()) {
            return;
        }
        sweeping = true;
        removedThisSweep = 0;
        inFlight.set(0);
        producerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::produce, 1L, 1L);
        consumerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::consume, 1L, 1L);
    }

    private void produce() {
        List<ChunkSnap> batch = new ArrayList<>(CHUNKS_PER_TICK);
        int handled = 0;
        while (handled < CHUNKS_PER_TICK && !pendingChunks.isEmpty()) {
            Chunk chunk = pendingChunks.poll();
            handled++;
            if (chunk == null || !chunk.isLoaded()) {
                continue;
            }
            World world = chunk.getWorld();
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            int[] height = Compat.worldHeight(world);
            batch.add(new ChunkSnap(snapshot, world.getName(), height[0], height[1]));
        }

        if (!batch.isEmpty()) {
            inFlight.incrementAndGet();
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> scanBatch(batch));
            } catch (Exception e) {
                inFlight.decrementAndGet();
                plugin.getLogger().log(Level.WARNING, "[AntiDup] 异步任务投递失败", e);
            }
        }
        if (pendingChunks.isEmpty() && producerTask != null) {
            producerTask.cancel();
            producerTask = null;
        }
    }

    private void scanBatch(List<ChunkSnap> batch) {
        try {
            for (ChunkSnap snap : batch) {
                scanSnapshot(snap);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[AntiDup] 异步扫描出现异常，本批次已跳过", e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void scanSnapshot(ChunkSnap snap) {
        ChunkSnapshot snapshot = snap.snapshot();
        int baseX = snapshot.getX() << 4;
        int baseZ = snapshot.getZ() << 4;
        int minY = snap.minY();
        int maxY = snap.maxY();
        int minSectionY = minY >> 4;
        int maxSectionY = (maxY >> 4) - 1;

        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            // sy 为世界绝对区段坐标（blockY / 16）
            if (snapshot.isSectionEmpty(sectionY)) {
                continue;
            }
            int sectionBaseY = sectionY << 4;
            for (int y = 0; y < 16; y++) {
                int worldY = sectionBaseY + y;
                if (worldY < minY || worldY >= maxY) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!DripleafUtil.isBroken(snapshot, x, worldY, z, minY, maxY)) {
                            continue;
                        }
                        pendingRemovals.add(new GhostPos(snap.world(), baseX + x, worldY, baseZ + z));
                    }
                }
            }
        }
    }

    private void consume() {
        int handled = 0;
        while (handled < REMOVALS_PER_TICK) {
            GhostPos pos = pendingRemovals.poll();
            if (pos == null) {
                break;
            }
            handled++;
            World world = Bukkit.getWorld(pos.world());
            if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            // 清理前用实时世界复查，防止快照过期误删
            if (!DripleafUtil.isBroken(block)) {
                continue;
            }
            plugin.removeGhostBlock(block, "自动扫描");
            removedThisSweep++;
        }

        if (sweeping && producerTask == null && inFlight.get() == 0 && pendingRemovals.isEmpty()) {
            sweeping = false;
            if (consumerTask != null) {
                consumerTask.cancel();
                consumerTask = null;
            }
            if (removedThisSweep > 0) {
                plugin.getLogger().info("[AntiDup] 本轮异步扫描完成，共清理 " + removedThisSweep + " 个残缺垂滴叶。");
            }
        }
    }
}
