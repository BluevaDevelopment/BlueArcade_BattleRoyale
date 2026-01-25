package net.blueva.arcade.modules.battleroyale.support;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.modules.battleroyale.state.ArenaState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class SupplyService {

    private final ModuleConfigAPI moduleConfig;

    public SupplyService(ModuleConfigAPI moduleConfig) {
        this.moduleConfig = moduleConfig;
    }

    public void giveTimedSupplies(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  ArenaState state) {
        int interval = moduleConfig.getInt("items.timed_supplies.interval_ticks", 0);
        List<String> supplies = moduleConfig.getStringList("items.timed_supplies.items");

        if (interval <= 0 || supplies == null || supplies.isEmpty()) {
            return;
        }

        int ticks = state.incrementSupplyTicks(20);
        if (ticks % interval != 0) {
            return;
        }

        for (Player player : context.getPlayers()) {
            if (!context.isPlayerPlaying(player)) {
                continue;
            }

            for (String itemString : supplies) {
                try {
                    String[] parts = itemString.split(":");
                    if (parts.length >= 2) {
                        Material material = Material.valueOf(parts[0].toUpperCase());
                        int amount = Integer.parseInt(parts[1]);
                        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(material, amount));
                    }
                } catch (Exception ignored) {
                    // Ignore malformed entries
                }
            }
        }
    }
}
