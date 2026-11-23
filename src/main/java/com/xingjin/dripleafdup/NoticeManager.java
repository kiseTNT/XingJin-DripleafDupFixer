package com.xingjin.dripleafdup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 多通道提示（Title / ActionBar / Toast / Narrator / BossBar），全部可由 config.yml 自定义。
 *
 * <p>Toast 用隐藏成就实现（{@link UnsafeValues#loadAdvancement} 注册 impossible 触发器成就，
 * 发放后延迟撤销）；Narrator 走系统消息，客户端开启旁白后才会朗读；
 * BossBar 按玩家独立创建并倒计时移除。所有任务按玩家 UUID 管理，退出时清理。
 */
public final class NoticeManager {

    /** Toast 弹出后延迟撤销的时间（tick），撤销后才能再次弹出 */
    private static final long TOAST_REVOKE_DELAY_TICKS = 200L;

    private final XingJinDripleafDupFixer plugin;
    private final NamespacedKey toastKey;

    private boolean titleEnabled;
    private String titleMain;
    private String titleSub;
    private int titleFadeIn;
    private int titleStay;
    private int titleFadeOut;

    private boolean actionBarEnabled;
    private String actionBarMessage;
    private int actionBarStay;

    private boolean toastEnabled;
    private String toastTitle;
    private String toastDescription;
    private Advancement toastAdvancement;

    private boolean narratorEnabled;
    private String narratorMessage;

    private boolean bossBarEnabled;
    private String bossBarText;
    private BarColor bossBarColor;
    private BarStyle bossBarStyle;
    private int bossBarStay;

    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, BukkitTask> bossBarTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> actionBarTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> toastRevokeTasks = new HashMap<>();

    public NoticeManager(XingJinDripleafDupFixer plugin) {
        this.plugin = plugin;
        this.toastKey = new NamespacedKey(plugin, "dripleaf_dup_warning");
    }

    /** 从配置读取全部提示项并重建 Toast 成就。 */
    public void reload(FileConfiguration cfg) {
        titleEnabled = cfg.getBoolean("title-enabled", true);
        titleMain = cfg.getString("title-main", "&c&l检测到刷物品BUG行为");
        titleSub = cfg.getString("title-sub", "&7残缺垂滴叶禁止使用骨粉");
        titleFadeIn = clampInt(cfg.getInt("title-fade-in", 10), 0, 600);
        titleStay = clampInt(cfg.getInt("title-stay", 60), 0, 600);
        titleFadeOut = clampInt(cfg.getInt("title-fade-out", 20), 0, 600);

        actionBarEnabled = cfg.getBoolean("action-bar-enabled", true);
        actionBarMessage = cfg.getString("action-bar-message", "&c残缺垂滴叶禁止使用骨粉！请勿尝试！");
        actionBarStay = clampInt(cfg.getInt("action-bar-stay", 60), 0, 400);

        toastEnabled = cfg.getBoolean("toast-enabled", true);
        toastTitle = cfg.getString("toast-title", "&c刷物品BUG行为已被拦截");
        toastDescription = cfg.getString("toast-description", "&7残缺垂滴叶禁止使用骨粉");

        narratorEnabled = cfg.getBoolean("narrator-enabled", true);
        narratorMessage = cfg.getString("narrator-message",
                "警告：检测到刷物品BUG行为，请勿对残缺垂滴叶使用骨粉！");

        bossBarEnabled = cfg.getBoolean("boss-bar-enabled", true);
        bossBarText = cfg.getString("boss-bar-text", "&c残缺垂滴叶禁止使用骨粉！");
        bossBarColor = parseEnum(BarColor.class, cfg.getString("boss-bar-color", "RED"), BarColor.RED);
        bossBarStyle = parseEnum(BarStyle.class, cfg.getString("boss-bar-style", "SOLID"), BarStyle.SOLID);
        bossBarStay = clampInt(cfg.getInt("boss-bar-stay", 60), 20, 1200);

        rebuildToast();
    }

    /** 触发全部已启用的提示通道（主线程调用）。 */
    public void show(Player player, Location loc) {
        if (titleEnabled) {
            showTitle(player, loc);
        }
        if (actionBarEnabled) {
            showActionBar(player, loc);
        }
        if (toastEnabled) {
            showToast(player, loc);
        }
        if (narratorEnabled) {
            // 系统消息，客户端开启“旁白”后会被朗读
            player.sendMessage(XingJinDripleafDupFixer.color(applyVars(narratorMessage, player, loc)));
        }
        if (bossBarEnabled) {
            showBossBar(player, loc);
        }
    }

    /** 清理指定玩家的全部提示状态。 */
    public void clear(UUID uuid) {
        removeBar(uuid);
        BukkitTask ab = actionBarTasks.remove(uuid);
        if (ab != null) {
            ab.cancel();
        }
        BukkitTask tr = toastRevokeTasks.remove(uuid);
        if (tr != null) {
            tr.cancel();
        }
    }

    /** 插件卸载：回收所有玩家状态 */
    public void shutdown() {
        for (UUID uuid : new HashMap<>(bossBarTasks).keySet()) {
            clear(uuid);
        }
    }

    @SuppressWarnings("deprecation")
    private void showTitle(Player player, Location loc) {
        player.sendTitle(
                XingJinDripleafDupFixer.color(applyVars(titleMain, player, loc)),
                XingJinDripleafDupFixer.color(applyVars(titleSub, player, loc)),
                titleFadeIn, titleStay, titleFadeOut);
    }

    @SuppressWarnings("deprecation")
    private void showActionBar(Player player, Location loc) {
        BukkitTask old = actionBarTasks.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }

        final String text = XingJinDripleafDupFixer.color(applyVars(actionBarMessage, player, loc));
        player.sendActionBar(text);

        // 原生 ActionBar 只显示约 2 秒，stay 更长时每秒重发
        if (actionBarStay > 20) {
            final int[] resendLeft = {(actionBarStay - 1) / 20};
            actionBarTasks.put(player.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (resendLeft[0]-- <= 0) {
                    BukkitTask self = actionBarTasks.remove(player.getUniqueId());
                    if (self != null) {
                        self.cancel();
                    }
                    return;
                }
                player.sendActionBar(text);
            }, 20L, 20L));
        }
    }

    @SuppressWarnings("deprecation")
    private void rebuildToast() {
        toastAdvancement = null;
        UnsafeValues unsafe = Bukkit.getUnsafe();
        try {
            unsafe.removeAdvancement(toastKey);
        } catch (Exception ignored) {
        }
        if (!toastEnabled) {
            return;
        }

        String icon = resolveToastIcon();
        Advancement adv = null;
        // 各版本成就 JSON 差异：图标 1.17~1.20.4 用 item，1.20.5~1.21.x 用 id+count，
        // 26.x 用 id（无 count）且 criteria 键带 minecraft: 前缀；逐组尝试，独立捕获
        String[] icons = {
                "{\"id\":\"" + icon + "\",\"count\":1}",
                "{\"id\":\"" + icon + "\"}",
                "{\"item\":\"" + icon + "\"}"
        };
        String[] criteriaKeys = {"trigger", "minecraft:trigger"};
        for (String criteriaKey : criteriaKeys) {
            for (String iconJson : icons) {
                try {
                    adv = unsafe.loadAdvancement(toastKey, buildAdvancementJson(criteriaKey, iconJson));
                } catch (Exception ignored) {
                }
                if (adv != null) {
                    break;
                }
            }
            if (adv != null) {
                break;
            }
        }
        // removeAdvancement 在部分版本不生效时直接复用已注册成就，避免 "already exists" 异常
        if (adv == null) {
            adv = Bukkit.getAdvancement(toastKey);
        }
        if (adv == null) {
            // 新版服务端（如 26.x）成就 schema 可能变化，静默降级，不影响其余提示通道
            plugin.getLogger().fine("[AntiDup] Toast 成就注册失败，Toast 提示已禁用（当前服务端版本不支持）。");
        }
        toastAdvancement = adv;
    }

    private String resolveToastIcon() {
        Material mat = Material.BARRIER;
        try {
            Material configured = Material.valueOf(plugin.getConfig().getString("toast-icon", "BARRIER")
                    .toUpperCase(Locale.ROOT).replace(' ', '_'));
            if (configured.isItem()) {
                mat = configured;
            }
        } catch (Exception ignored) {
        }
        return "minecraft:" + mat.name().toLowerCase(Locale.ROOT);
    }

    private String resolveToastFrame() {
        String frame = plugin.getConfig().getString("toast-frame", "CHALLENGE");
        String lower = frame == null ? "" : frame.toLowerCase(Locale.ROOT);
        return lower.equals("task") || lower.equals("goal") || lower.equals("challenge") ? lower : "challenge";
    }

    private String buildAdvancementJson(String criteriaKey, String iconJson) {
        return "{\"criteria\":{\"" + criteriaKey + "\":{\"trigger\":\"minecraft:impossible\"}},"
                + "\"display\":{"
                + "\"icon\":" + iconJson
                + ",\"title\":" + legacyToJson(toastTitle)
                + ",\"description\":" + legacyToJson(toastDescription)
                + ",\"frame\":\"" + resolveToastFrame() + "\""
                + ",\"show_toast\":true"
                + ",\"announce_to_chat\":false"
                + ",\"hidden\":true"
                + "}}";
    }

    private void showToast(Player player, Location loc) {
        Advancement adv = toastAdvancement;
        if (adv == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        // 上一次 Toast 未撤销则无法再次弹出（默认 10 秒一次）
        if (progress.isDone()) {
            return;
        }
        if (!progress.awardCriteria("trigger")) {
            return;
        }

        BukkitTask old = toastRevokeTasks.remove(uuid);
        if (old != null) {
            old.cancel();
        }
        toastRevokeTasks.put(uuid, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            toastRevokeTasks.remove(uuid);
            if (!player.isOnline()) {
                return;
            }
            AdvancementProgress p = player.getAdvancementProgress(adv);
            if (p != null && p.isDone()) {
                p.revokeCriteria("trigger");
            }
        }, TOAST_REVOKE_DELAY_TICKS));
    }

    @SuppressWarnings("deprecation")
    private void showBossBar(Player player, Location loc) {
        UUID uuid = player.getUniqueId();
        removeBar(uuid);

        BossBar bar = Bukkit.createBossBar(
                ChatColor.translateAlternateColorCodes('&', applyVars(bossBarText, player, loc)),
                bossBarColor, bossBarStyle);
        bar.setProgress(1.0D);
        bar.addPlayer(player);
        bossBars.put(uuid, bar);

        final int total = bossBarStay;
        final int[] remaining = {total};
        bossBarTasks.put(uuid, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remaining[0] -= 20;
            if (remaining[0] <= 0 || !player.isOnline()) {
                removeBar(uuid);
                return;
            }
            bar.setProgress(Math.max(0.0D, Math.min(1.0D, (double) remaining[0] / total)));
        }, 20L, 20L));
    }

    private void removeBar(UUID uuid) {
        BukkitTask task = bossBarTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        BossBar bar = bossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
    }

    /** 替换占位符；player / loc 允许为 null。 */
    private String applyVars(String template, Player player, Location loc) {
        if (template == null) {
            return "";
        }
        String out = template;
        if (player != null) {
            out = out.replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString());
        }
        if (loc != null) {
            out = out.replace("%world%", loc.getWorld() == null ? "unknown" : loc.getWorld().getName())
                    .replace("%x%", String.valueOf(loc.getBlockX()))
                    .replace("%y%", String.valueOf(loc.getBlockY()))
                    .replace("%z%", String.valueOf(loc.getBlockZ()));
        }
        return out;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String name, T fallback) {
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 将 & / § 颜色代码文本转换为分段保留样式的 JSON 文本组件。 */
    static String legacyToJson(String input) {
        String text = input == null ? "" : input;
        List<String> segments = new ArrayList<>();

        int color = -1;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        boolean obfuscated = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch == '&' || ch == '\u00A7') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                boolean colorCode = (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
                boolean styleCode = code == 'k' || code == 'l' || code == 'm' || code == 'n'
                        || code == 'o' || code == 'r';
                if (colorCode || styleCode) {
                    flushSegment(segments, current, color, bold, italic, underlined, strikethrough, obfuscated);
                    if (colorCode) {
                        color = code;
                    } else if (code == 'k') {
                        obfuscated = true;
                    } else if (code == 'l') {
                        bold = true;
                    } else if (code == 'm') {
                        strikethrough = true;
                    } else if (code == 'n') {
                        underlined = true;
                    } else if (code == 'o') {
                        italic = true;
                    } else {
                        color = -1;
                        bold = false;
                        italic = false;
                        underlined = false;
                        strikethrough = false;
                        obfuscated = false;
                    }
                    i++;
                    continue;
                }
            }
            current.append(ch);
        }
        flushSegment(segments, current, color, bold, italic, underlined, strikethrough, obfuscated);

        if (segments.isEmpty()) {
            return "{\"text\":\"\"}";
        }
        if (segments.size() == 1) {
            return segments.get(0);
        }
        return "[" + String.join(",", segments) + "]";
    }

    private static void flushSegment(List<String> segments, StringBuilder current, int color,
                                     boolean bold, boolean italic, boolean underlined,
                                     boolean strikethrough, boolean obfuscated) {
        if (current.length() == 0) {
            return;
        }
        StringBuilder json = new StringBuilder("{\"text\":\"").append(escapeJson(current.toString())).append('"');
        if (color >= 0) {
            json.append(",\"color\":\"").append(colorName(color)).append('"');
        }
        json.append(",\"bold\":").append(bold)
                .append(",\"italic\":").append(italic)
                .append(",\"underlined\":").append(underlined)
                .append(",\"strikethrough\":").append(strikethrough)
                .append(",\"obfuscated\":").append(obfuscated)
                .append('}');
        segments.add(json.toString());
        current.setLength(0);
    }

    private static String colorName(int code) {
        return switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            default -> "white";
        };
    }

    private static String escapeJson(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
