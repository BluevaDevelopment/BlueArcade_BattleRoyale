package net.blueva.arcade.modules.battleroyale.support.loot;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.modules.battleroyale.state.ArenaState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class LootService {

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final StatsAPI statsAPI;

    public LootService(ModuleInfo moduleInfo, ModuleConfigAPI moduleConfig, StatsAPI statsAPI) {
        this.moduleInfo = moduleInfo;
        this.moduleConfig = moduleConfig;
        this.statsAPI = statsAPI;
    }

    public void handleChestLoot(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state,
                                Player player,
                                Block block) {
        if (player == null || block == null) {
            return;
        }

        Material blockType = block.getType();
        boolean isNormalChest = blockType == Material.CHEST || blockType == Material.TRAPPED_CHEST;
        boolean isSpecialChest = blockType == Material.ENDER_CHEST;
        if (!isNormalChest && !isSpecialChest) {
            return;
        }

        if (state.isChestLooted(block.getLocation())) {
            return;
        }

        List<LootEntry> entries = parseEntries(isSpecialChest
                ? "loot.special_chests.items"
                : "loot.chests.items");
        if (entries.isEmpty()) {
            return;
        }

        state.markChestLooted(block.getLocation());

        String countBasePath = isSpecialChest ? "loot.special_chests.item_count" : "loot.chests.item_count";
        int minItems = moduleConfig.getInt(countBasePath + ".min", isSpecialChest ? 4 : 3);
        int maxItems = moduleConfig.getInt(countBasePath + ".max", isSpecialChest ? 8 : 6);
        int itemCount = ThreadLocalRandom.current().nextInt(Math.max(1, minItems), Math.max(minItems, maxItems) + 1);

        Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
        block.setType(Material.AIR);
        block.getWorld().spawnParticle(isSpecialChest ? Particle.DRAGON_BREATH : Particle.FLAME,
                dropLocation, 20, 0.4, 0.4, 0.4, 0.01);

        for (int i = 0; i < itemCount; i++) {
            LootEntry entry = pickEntry(entries);
            if (entry == null) {
                continue;
            }
            int amount = entry.minAmount == entry.maxAmount
                    ? entry.minAmount
                    : ThreadLocalRandom.current().nextInt(entry.minAmount, entry.maxAmount + 1);
            ItemStack stack = new ItemStack(entry.material, Math.max(1, amount));
            block.getWorld().dropItemNaturally(dropLocation, stack);
        }

        if (statsAPI != null) {
            statsAPI.addModuleStat(player, moduleInfo.getId(), "chests_looted", 1);
        }
    }

    public boolean isChestLooted(ArenaState state, Block block) {
        return block != null && state.isChestLooted(block.getLocation());
    }

    public List<TrackedChest> loadChests(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null || context.getDataAccess() == null) {
            return List.of();
        }

        List<String> entries = context.getDataAccess().getGameData("loot.chests.locations", List.class);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<TrackedChest> chests = new ArrayList<>();
        for (String entry : entries) {
            TrackedChest trackedChest = parseTrackedChest(entry);
            if (trackedChest != null) {
                chests.add(trackedChest);
            }
        }
        return chests;
    }

    public void restoreChests(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        for (TrackedChest chest : state.getTrackedChests()) {
            Location location = chest.getLocation();
            if (location == null) {
                continue;
            }
            context.getSchedulerAPI().runAtLocation(location, () -> {
                if (location.getWorld() != null) {
                    location.getBlock().setType(chest.getMaterial());
                }
            });
        }
    }

    public void startChestMarkers(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_battle_royale_chest_markers";

        context.getSchedulerAPI().runTimer(taskId, () -> {
            for (TrackedChest chest : state.getTrackedChests()) {
                Location location = chest.getLocation();
                if (location == null || location.getWorld() == null) {
                    continue;
                }
                Material currentType = location.getBlock().getType();
                if (currentType != Material.CHEST
                        && currentType != Material.TRAPPED_CHEST
                        && currentType != Material.ENDER_CHEST) {
                    continue;
                }

                Location particleLocation = location.clone().add(0.5, 1.1, 0.5);
                Particle particle = currentType == Material.ENDER_CHEST
                        ? Particle.PORTAL
                        : Particle.HAPPY_VILLAGER;
                location.getWorld().spawnParticle(particle, particleLocation, 6, 0.2, 0.3, 0.2, 0.01);
            }
        }, 20L, 40L);
    }

    private List<LootEntry> parseEntries(String path) {
        List<String> entries = moduleConfig.getStringList(path);
        List<LootEntry> parsed = new ArrayList<>();
        if (entries == null) {
            return parsed;
        }

        for (String entry : entries) {
            try {
                String[] parts = entry.split(":");
                if (parts.length < 4) {
                    continue;
                }
                Material material = Material.valueOf(parts[0].toUpperCase());
                int min = Integer.parseInt(parts[1]);
                int max = Integer.parseInt(parts[2]);
                int weight = Integer.parseInt(parts[3]);
                if (weight <= 0) {
                    continue;
                }
                parsed.add(new LootEntry(material, Math.max(1, min), Math.max(min, max), weight));
            } catch (Exception ignored) {
                // Ignore malformed entries
            }
        }

        return parsed;
    }

    private TrackedChest parseTrackedChest(String entry) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }

        String[] parts = entry.split(":");
        if (parts.length < 4) {
            return null;
        }

        String worldName = parts[0];
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            Material material = Material.CHEST;
            if (parts.length >= 5) {
                material = Material.valueOf(parts[4].toUpperCase(Locale.ROOT));
            }
            if (material != Material.CHEST
                    && material != Material.TRAPPED_CHEST
                    && material != Material.ENDER_CHEST) {
                material = Material.CHEST;
            }

            Location location = new Location(org.bukkit.Bukkit.getWorld(worldName), x, y, z);
            if (location.getWorld() == null) {
                return null;
            }
            return new TrackedChest(location, material);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LootEntry pickEntry(List<LootEntry> entries) {
        int total = entries.stream().mapToInt(entry -> entry.weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        int count = 0;
        for (LootEntry entry : entries) {
            count += entry.weight;
            if (roll < count) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    private static class LootEntry {
        private final Material material;
        private final int minAmount;
        private final int maxAmount;
        private final int weight;

        private LootEntry(Material material, int minAmount, int maxAmount, int weight) {
            this.material = material;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.weight = weight;
        }
    }
}
