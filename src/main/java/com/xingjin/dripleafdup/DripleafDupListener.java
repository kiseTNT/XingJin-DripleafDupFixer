package com.xingjin.dripleafdup;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** 骨粉交互拦截：玩家手持骨粉右键残缺垂滴叶时取消催熟并告警。 */
public final class DripleafDupListener implements Listener {

    private final XingJinDripleafDupFixer plugin;

    public DripleafDupListener(XingJinDripleafDupFixer plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBoneMeal(PlayerInteractEvent event) {
        if (!plugin.isFixEnabled()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BONE_MEAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!DripleafUtil.isSmallDripleaf(block)) {
            return;
        }
        if (!DripleafUtil.isBroken(block)) {
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        // 主手/副手各触发一次事件，仅在主手时提示，避免重复
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        plugin.handleExploit(event.getPlayer(), block, "残缺小型垂滴叶（缺少合法下半段或支撑方块）");
    }

    /** 玩家退出时清理冷却记录与提示任务 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.clearPlayerState(event.getPlayer().getUniqueId());
    }
}
