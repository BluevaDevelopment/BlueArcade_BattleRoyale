package net.blueva.arcade.modules.battleroyale.support.loadout;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

public class PlayerLoadoutService {

    private final ModuleConfigAPI moduleConfig;

    public PlayerLoadoutService(ModuleConfigAPI moduleConfig) {
        this.moduleConfig = moduleConfig;
    }

    public void giveStartingItems(Player player) {
        List<String> startingItems = moduleConfig.getStringList("items.starting_items");
        if (startingItems == null || startingItems.isEmpty()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        for (String itemString : startingItems) {
            try {
                String[] parts = itemString.split(":");
                if (parts.length >= 2) {
                    Material material = Material.valueOf(parts[0].toUpperCase());
                    int amount = Integer.parseInt(parts[1]);
                    int slot = parts.length >= 3 ? Integer.parseInt(parts[2]) : -1;

                    org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material, amount);

                    if (slot == 40) {
                        inventory.setItemInOffHand(item);
                    } else if (slot == 39) {
                        inventory.setHelmet(item);
                    } else if (slot == 38) {
                        inventory.setChestplate(item);
                    } else if (slot == 37) {
                        inventory.setLeggings(item);
                    } else if (slot == 36) {
                        inventory.setBoots(item);
                    } else if (slot >= 0 && slot < 36) {
                        inventory.setItem(slot, item);
                    } else {
                        inventory.addItem(item);
                    }
                }
            } catch (Exception ignored) {
                // Ignore malformed entries
            }
        }
    }

    public void applyStartingEffects(Player player) {
        List<String> startingEffects = moduleConfig.getStringList("effects.starting_effects");
        applyEffects(player, startingEffects);
    }

    public void applyRespawnEffects(Player player) {
        List<String> respawnEffects = moduleConfig.getStringList("effects.respawn_effects");
        applyEffects(player, respawnEffects);
    }

    public void restoreVitals(Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
    }

    public void handleKillRegeneration(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       Player killer) {
        double healAmount = moduleConfig.getDouble("combat.kill_regeneration.health", 6.0);
        if (healAmount <= 0 || context == null) {
            return;
        }

        double newHealth = Math.min(killer.getMaxHealth(), killer.getHealth() + healAmount);
        killer.setHealth(newHealth);
    }

    private void applyEffects(Player player, List<String> effects) {
        if (effects == null || effects.isEmpty()) {
            return;
        }

        for (String effectString : effects) {
            try {
                String[] parts = effectString.split(":");
                if (parts.length >= 3) {
                    org.bukkit.potion.PotionEffectType effectType =
                            org.bukkit.potion.PotionEffectType.getByName(parts[0].toUpperCase());
                    int duration = Integer.parseInt(parts[1]);
                    int amplifier = Integer.parseInt(parts[2]);

                    if (effectType != null) {
                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                                effectType, duration, amplifier, false, false
                        ));
                    }
                }
            } catch (Exception ignored) {
                // Ignore malformed entries
            }
        }
    }
}
