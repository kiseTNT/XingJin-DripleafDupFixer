package com.xingjin.dripleafdup;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * XingJin-DripleafDupFixer 主类：修复“残缺小型垂滴叶骨粉催熟刷物品”漏洞。
 * 兼容 Paper 1.17.1 ~ 1.21.3，纯 Paper 原生 API，无第三方依赖。
 */
public final class XingJinDripleafDupFixer extends JavaPlugin {

    /** 管理员权限节点 */
    public static final String PERM_ADMIN = "xingjin.dripleaf.admin";

    /** 同玩家告警冷却，冷却内仍照常拦截，只是不重复刷日志/提示 */
    private static final long ALERT_COOLDOWN_MS = 1000L;

    /** 落雷点随机尝试次数，全部失败则放弃本次落雷 */
    private static final int LIGHTNING_ATTEMPTS = 10;

    private final Map<UUID, Long> lastAlert = new HashMap<>();

    private boolean fixEnabled;
    private boolean notifyPlayer;
    private String playerMessage;
    private boolean notifyAdmin;
    private String adminMessage;
    private boolean scanGhostBlock;
    private long scanInterval;

    private boolean effectSound;
    private String effectSoundName;
    private float effectSoundVolume;
    private float effectSoundPitch;

    private boolean effectLightning;
    private boolean effectLightningEffectOnly;
    private double effectLightningRadius;
    private double effectLightningSafety;

    private GhostScanTask scanTask;
    private NoticeManager notice;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        notice = new NoticeManager(this);
        loadSettings();

        getServer().getPluginManager().registerEvents(new DripleafDupListener(this), this);

        if (fixEnabled && scanGhostBlock) {
            startScanner();
        }

        getLogger().info("XingJin-DripleafDupFixer 已启用 | 拦截: " + fixEnabled
                + " | 定时清理: " + (fixEnabled && scanGhostBlock)
                + " | 扫描间隔: " + scanInterval + "s");
    }

    @Override
    public void onDisable() {
        stopScanner();
        if (notice != null) {
            notice.shutdown();
        }
        lastAlert.clear();
        getLogger().info("XingJin-DripleafDupFixer 已卸载。");
    }

    private void loadSettings() {
        reloadConfig();
        fixEnabled = getConfig().getBoolean("enable", true);
        notifyPlayer = getConfig().getBoolean("notify-player", true);
        playerMessage = getConfig().getString("player-message",
                "&c残缺垂滴叶禁止使用骨粉！这是刷物品BUG，请勿尝试！");
        notifyAdmin = getConfig().getBoolean("notify-admin", true);
        adminMessage = getConfig().getString("admin-message",
                "&e[XingJin] &f%player% &e试图对残缺垂滴叶使用骨粉 &7(%world% %x%, %y%, %z%)");
        scanGhostBlock = getConfig().getBoolean("scan-ghost-block", false);

        // 最小 5 秒，防止误配成 0 导致每 tick 全服扫描
        long raw = getConfig().getLong("scan-interval", 300L);
        if (raw < 5L) {
            raw = 5L;
        }
        scanInterval = raw;

        effectSound = getConfig().getBoolean("effect-sound", true);
        effectSoundName = getConfig().getString("effect-sound-name", "entity.wither.spawn");
        effectSoundVolume = (float) getConfig().getDouble("effect-sound-volume", 100.0D);
        effectSoundPitch = (float) clamp(getConfig().getDouble("effect-sound-pitch", 1.0D), 0.5D, 2.0D);

        effectLightning = getConfig().getBoolean("effect-lightning", true);
        effectLightningEffectOnly = getConfig().getBoolean("effect-lightning-effect-only", true);
        effectLightningRadius = Math.max(1.0D, getConfig().getDouble("effect-lightning-radius", 8.0D));
        effectLightningSafety = Math.max(1.0D, getConfig().getDouble("effect-lightning-safety-distance", 3.0D));

        if (notice != null) {
            notice.reload(getConfig());
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** & 颜色代码转 §（全版本通用） */
    static String color(String input) {
        return input == null ? "" : ChatColor.translateAlternateColorCodes('&', input);
    }

    private void startScanner() {
        if (scanTask != null) {
            scanTask.stop();
        }
        scanTask = new GhostScanTask(this);
        scanTask.start(scanInterval);
    }

    private void stopScanner() {
        if (scanTask != null) {
            scanTask.stop();
            scanTask = null;
        }
    }

    public boolean isFixEnabled() {
        return fixEnabled;
    }

    public void handleExploit(Player player, Block block, String reason) {
        Location loc = block.getLocation();

        // 特效与提示每次拦截都执行，不受告警冷却影响
        playEffects(player);
        notice.show(player, loc);

        long now = System.currentTimeMillis();
        Long last = lastAlert.get(player.getUniqueId());
        if (last != null && now - last < ALERT_COOLDOWN_MS) {
            return;
        }
        lastAlert.put(player.getUniqueId(), now);

        String world = loc.getWorld() == null ? "unknown" : loc.getWorld().getName();
        String line = String.format(
                "[AntiDup] 拦截残缺小型垂滴叶骨粉交互 | 玩家: %s | UUID: %s | 世界: %s | 坐标: %d, %d, %d | 判定: %s",
                player.getName(), player.getUniqueId(), world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), reason);
        getLogger().warning(line);

        if (notifyPlayer) {
            player.sendMessage(color(applyVars(playerMessage, player.getName(),
                    player.getUniqueId(), loc)));
        }

        if (notifyAdmin) {
            broadcastToAdmins(color(applyVars(adminMessage, player.getName(),
                    player.getUniqueId(), loc)));
        }
    }

    public void removeGhostBlock(Block block, String actor) {
        Location loc = block.getLocation();
        String world = loc.getWorld() == null ? "unknown" : loc.getWorld().getName();

        // 不触发物理更新，避免连锁反应
        block.setType(Material.AIR, false);

        String line = String.format(
                "[AntiDup] 已清理残缺小型垂滴叶方块 | 来源: %s | 世界: %s | 坐标: %d, %d, %d",
                actor, world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        getLogger().warning(line);

        if (notifyAdmin) {
            broadcastToAdmins(color(applyVars(adminMessage, actor, new UUID(0L, 0L), loc)));
        }
    }

    private void playEffects(Player player) {
        if (effectSound) {
            // 客户端响度封顶 1.0，volume 超出部分仅扩大可听距离
            player.playSound(player.getLocation(), effectSoundName, effectSoundVolume, effectSoundPitch);
        }
        if (effectLightning) {
            strikeLightning(player);
        }
    }

    /** 在玩家附近随机位置落雷，落雷点避开玩家与所有生物。 */
    private void strikeLightning(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int maxYExclusive = Compat.worldHeight(world)[1];

        for (int attempt = 0; attempt < LIGHTNING_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * (Math.PI * 2);
            double dist = effectLightningRadius <= 1.0D ? 1.0D
                    : 1.0D + random.nextDouble() * (effectLightningRadius - 1.0D);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            Block surface = world.getHighestBlockAt(x, z);
            int strikeY = Math.min(surface.getY() + 1, maxYExclusive - 1);
            Location strike = new Location(world, x + 0.5D, strikeY, z + 0.5D);

            if (strike.distanceSquared(center) < 2.25D) {
                continue;
            }

            boolean unsafe = false;
            for (Entity entity : world.getNearbyEntities(strike,
                    effectLightningSafety, effectLightningSafety, effectLightningSafety)) {
                if (entity instanceof LivingEntity) {
                    unsafe = true;
                    break;
                }
            }
            if (unsafe) {
                continue;
            }

            if (effectLightningEffectOnly) {
                world.strikeLightningEffect(strike);
            } else {
                world.strikeLightning(strike);
            }
            return;
        }
    }

    /** 清理玩家会话状态：冷却记录 + 各类提示任务（退出时调用）。 */
    public void clearPlayerState(UUID uuid) {
        lastAlert.remove(uuid);
        notice.clear(uuid);
    }

    private void broadcastToAdmins(String message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(PERM_ADMIN)) {
                online.sendMessage(message);
            }
        }
    }

    public String applyVars(String template, String playerName, UUID uuid, Location loc) {
        String out = template == null ? "" : template;
        out = out.replace("%player%", playerName)
                .replace("%uuid%", uuid == null ? "" : uuid.toString())
                .replace("%world%", loc.getWorld() == null ? "unknown" : loc.getWorld().getName())
                .replace("%x%", String.valueOf(loc.getBlockX()))
                .replace("%y%", String.valueOf(loc.getBlockY()))
                .replace("%z%", String.valueOf(loc.getBlockZ()));
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            stopScanner();
            if (fixEnabled && scanGhostBlock) {
                startScanner();
            }
            sender.sendMessage(color("&a[XingJin-DripleafDupFixer] 配置已重载"
                    + " | 总开关: " + fixEnabled
                    + " | 定时清理: " + scanGhostBlock
                    + " | 扫描间隔: " + scanInterval + "s"));
            getLogger().info("配置已由 " + sender.getName() + " 重载。");
            return true;
        }
        sender.sendMessage(color("&e用法: /" + label + " reload"));
        return true;
    }
}
