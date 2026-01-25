package net.blueva.arcade.modules.battleroyale.setup;

import net.blueva.arcade.api.setup.GameSetupHandler;
import net.blueva.arcade.api.setup.SetupContext;
import net.blueva.arcade.api.setup.TabCompleteContext;
import net.blueva.arcade.api.setup.TabCompleteResult;
import net.blueva.arcade.modules.battleroyale.BattleRoyaleModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BattleRoyaleSetup implements GameSetupHandler {

    private final BattleRoyaleModule module;
    private final Map<Player, Integer> activeSearchTasks = new ConcurrentHashMap<>();

    public BattleRoyaleSetup(BattleRoyaleModule module) {
        this.module = module;
    }

    @Override
    public boolean handle(SetupContext context) {
        return handleInternal(castSetupContext(context));
    }

    private boolean handleInternal(SetupContext<Player, CommandSender, Location> context) {
        String subcommand = context.getArg(context.getStartIndex() - 1);
        if ("searchchests".equalsIgnoreCase(subcommand)) {
            return handleSearchChests(context);
        }
        if ("region".equalsIgnoreCase(subcommand)) {
            return handleRegion(context);
        }
        return handleTeamConfig(context);
    }

    @Override
    public TabCompleteResult tabComplete(TabCompleteContext context) {
        return tabCompleteInternal(castTabContext(context));
    }

    private TabCompleteResult tabCompleteInternal(TabCompleteContext<Player, CommandSender> context) {
        if (context.getRelativeArgIndex() == 0
                && "team".equalsIgnoreCase(context.getArg(context.getStartIndex() - 1))) {
            return TabCompleteResult.of("count", "size");
        }
        if (context.getRelativeArgIndex() == 0
                && "region".equalsIgnoreCase(context.getArg(context.getStartIndex() - 1))) {
            return TabCompleteResult.of("set", "clear");
        }
        return TabCompleteResult.empty();
    }

    @Override
    public List<String> getSubcommands() {
        return List.of("team", "searchchests", "region");
    }

    private boolean handleTeamConfig(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(2)) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("team.usage"));
            return true;
        }

        String setting = context.getHandlerArg(0);
        if (setting == null || (!setting.equalsIgnoreCase("count") && !setting.equalsIgnoreCase("size"))) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("team.usage"));
            return true;
        }

        String valueRaw = context.getHandlerArg(1);
        if (valueRaw == null || !isNumber(valueRaw)) {
            context.getMessagesAPI().send(context.getPlayer(),
                    module.getCoreConfig().getLanguage("admin_commands.errors.invalid_number")
                            .replace("{value}", valueRaw == null ? "" : valueRaw));
            return true;
        }

        int value = Integer.parseInt(valueRaw);
        if (value <= 0) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("team.invalid_value")
                            .replace("{setting}", setting));
            return true;
        }

        if (setting.equalsIgnoreCase("count") && value < 2) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("team.invalid_count"));
            return true;
        }

        int teamCount = context.getData().getInt("teams.count", 0);
        int teamSize = context.getData().getInt("teams.size", 0);
        if (setting.equalsIgnoreCase("count")) {
            teamCount = value;
        } else {
            teamSize = value;
        }

        int maxPlayers = context.getData().getArenaInt("arena.basic.max_players", 0);
        if (teamCount > 0 && teamSize > 0 && maxPlayers > 0 && teamCount * teamSize > maxPlayers) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("team.invalid_limit")
                            .replace("{max_players}", String.valueOf(maxPlayers)));
            return true;
        }

        context.getData().setTeamConfig(teamCount, teamSize);
        context.getData().save();

        context.getMessagesAPI().send(context.getPlayer(),
                getSetupMessage("team.success")
                        .replace("{game}", context.getGameId())
                        .replace("{arena_id}", String.valueOf(context.getArenaId()))
                        .replace("{setting}", setting.toLowerCase())
                        .replace("{value}", String.valueOf(value)));
        return true;
    }

    private boolean handleSearchChests(SetupContext<Player, CommandSender, Location> context) {
        Player player = context.getPlayer();
        if (player == null) {
            return true;
        }

        if (!context.getData().has("bounds.min.x") || !context.getData().has("bounds.max.x")) {
            context.getMessagesAPI().send(player,
                    getSetupMessage("search_chests.missing_bounds"));
            return true;
        }

        Plugin plugin = Bukkit.getPluginManager().getPlugin("BlueArcade3");
        if (plugin == null) {
            return true;
        }

        Integer existingTask = activeSearchTasks.remove(player);
        if (existingTask != null) {
            Bukkit.getScheduler().cancelTask(existingTask);
        }

        String worldName = context.getData().getString("basic.world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            context.getMessagesAPI().send(player,
                    getSetupMessage("search_chests.missing_bounds"));
            return true;
        }

        int minX = (int) Math.floor(context.getData().getDouble("bounds.min.x", 0));
        int maxX = (int) Math.floor(context.getData().getDouble("bounds.max.x", 0));
        int minY = (int) Math.floor(context.getData().getDouble("bounds.min.y", 0));
        int maxY = (int) Math.floor(context.getData().getDouble("bounds.max.y", 0));
        int minZ = (int) Math.floor(context.getData().getDouble("bounds.min.z", 0));
        int maxZ = (int) Math.floor(context.getData().getDouble("bounds.max.z", 0));

        int totalBlocks = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        int blocksPerTick = Math.max(200, module.getModuleConfig().getInt("loot.chests.search.blocks_per_tick", 1500));
        List<String> chests = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        context.getMessagesAPI().send(player,
                getSetupMessage("search_chests.start")
                        .replace("{blocks}", String.valueOf(totalBlocks)));

        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            private int index;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelTask();
                    return;
                }

                int processed = 0;
                while (processed < blocksPerTick && index < totalBlocks) {
                    int xOffset = index % (maxX - minX + 1);
                    int yOffset = (index / (maxX - minX + 1)) % (maxY - minY + 1);
                    int zOffset = index / ((maxX - minX + 1) * (maxY - minY + 1));
                    int x = minX + xOffset;
                    int y = minY + yOffset;
                    int z = minZ + zOffset;
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.ENDER_CHEST) {
                        chests.add(world.getName() + ":" + x + ":" + y + ":" + z + ":" + type.name());
                    }
                    index++;
                    processed++;
                }

                String elapsed = formatElapsed(startTime);
                String actionBar = getSetupMessage("search_chests.action_bar")
                        .replace("{time}", elapsed)
                        .replace("{found}", String.valueOf(chests.size()));
                context.getMessagesAPI().sendActionBar(player, actionBar);

                if (index >= totalBlocks) {
                    context.getData().setStringList("loot.chests.locations", chests);
                    context.getData().save();
                    context.getMessagesAPI().send(player,
                            getSetupMessage("search_chests.complete")
                                    .replace("{found}", String.valueOf(chests.size()))
                                    .replace("{time}", elapsed));
                    cancelTask();
                }
            }

            private void cancelTask() {
                Integer id = activeSearchTasks.remove(player);
                if (id != null) {
                    Bukkit.getScheduler().cancelTask(id);
                }
            }
        }, 1L, 1L);

        activeSearchTasks.put(player, taskId);
        return true;
    }

    private boolean handleRegion(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(1)) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("region.usage"));
            return true;
        }

        String action = context.getHandlerArg(0);
        if (action == null) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("region.usage"));
            return true;
        }

        if ("clear".equalsIgnoreCase(action)) {
            context.getData().remove("game.play_area");
            context.getData().remove("regeneration.regions");
            context.getData().save();
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("region.cleared"));
            return true;
        }

        if (!"set".equalsIgnoreCase(action)) {
            context.getMessagesAPI().send(context.getPlayer(),
                    getSetupMessage("region.usage"));
            return true;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return true;
        }

        if (!context.getSelection().hasCompleteSelection(player)) {
            context.getMessagesAPI().send(player,
                    getSetupMessage("region.must_use_stick"));
            return true;
        }

        Location pos1 = context.getSelection().getPosition1(player);
        Location pos2 = context.getSelection().getPosition2(player);

        context.getData().registerRegenerationRegion("game.play_area", pos1, pos2);
        context.getData().save();

        int x = (int) Math.abs(pos2.getX() - pos1.getX()) + 1;
        int y = (int) Math.abs(pos2.getY() - pos1.getY()) + 1;
        int z = (int) Math.abs(pos2.getZ() - pos1.getZ()) + 1;
        int blocks = x * y * z;

        context.getMessagesAPI().send(player,
                getSetupMessage("region.set")
                        .replace("{blocks}", String.valueOf(blocks))
                        .replace("{x}", String.valueOf(x))
                        .replace("{y}", String.valueOf(y))
                        .replace("{z}", String.valueOf(z)));
        return true;
    }

    private String getSetupMessage(String key) {
        String message = module.getModuleConfig().getStringFrom("language.yml", "setup_messages." + key);
        if (message == null) {
            return "";
        }
        return message;
    }

    private String formatElapsed(long startTime) {
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - startTime) / 1000L);
        long minutes = elapsedSeconds / 60L;
        long seconds = elapsedSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private boolean isNumber(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }


    @SuppressWarnings("unchecked")
    private SetupContext<Player, CommandSender, Location> castSetupContext(SetupContext context) {
        return (SetupContext<Player, CommandSender, Location>) context;
    }

    @SuppressWarnings("unchecked")
    private TabCompleteContext<Player, CommandSender> castTabContext(TabCompleteContext context) {
        return (TabCompleteContext<Player, CommandSender>) context;
    }
}
