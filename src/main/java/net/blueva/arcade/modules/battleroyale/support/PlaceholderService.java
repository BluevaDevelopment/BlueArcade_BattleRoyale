package net.blueva.arcade.modules.battleroyale.support;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.team.TeamInfo;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.battleroyale.game.BattleRoyaleGame;
import net.blueva.arcade.modules.battleroyale.state.ArenaState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaceholderService {

    private final ModuleConfigAPI moduleConfig;
    private final BattleRoyaleGame game;

    public PlaceholderService(ModuleConfigAPI moduleConfig, BattleRoyaleGame game) {
        this.moduleConfig = moduleConfig;
        this.game = game;
    }

    public Map<String, String> buildPlaceholders(Player player) {
        Map<String, String> placeholders = new HashMap<>();

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context = game.getContext(player);
        if (context != null) {
            placeholders.put("alive", String.valueOf(context.getAlivePlayers().size()));
            placeholders.put("spectators", String.valueOf(context.getSpectators().size()));
            placeholders.put("kills", String.valueOf(game.getPlayerKills(context, player)));
            placeholders.put("alive_teams", String.valueOf(game.getAliveTeamIds(context).size()));

            TeamsAPI teamsAPI = context.getTeamsAPI();
            if (teamsAPI != null && teamsAPI.isEnabled()) {
                TeamInfo team = teamsAPI.getTeam(player);
                placeholders.put("team", team != null ? team.getDisplayName() : "-");
            } else {
                placeholders.put("team", moduleConfig.getStringFrom("language.yml", "scoreboard.solo_team_label", "Solo"));
            }

            ArenaState state = game.getArenaState(context);
            if (state != null) {
                placeholders.put("storm_radius", String.valueOf((int) Math.ceil(state.getStormRadius())));
                placeholders.put("storm_stage", String.valueOf(state.getStormStageIndex() + 1));
                placeholders.put("storm_status", resolveStormStatus(player, state));
            }
        }

        return placeholders;
    }

    private String resolveStormStatus(Player player, ArenaState state) {
        if (player == null || state == null) {
            return moduleConfig.getStringFrom("language.yml", "scoreboard.storm_status.safe", "Safe");
        }

        if (state.getStormCenter() == null || state.getStormRadius() <= 0) {
            return moduleConfig.getStringFrom("language.yml", "scoreboard.storm_status.safe", "Safe");
        }

        if (player.getWorld() == null || !player.getWorld().equals(state.getStormCenter().getWorld())) {
            return moduleConfig.getStringFrom("language.yml", "scoreboard.storm_status.safe", "Safe");
        }

        double dx = player.getLocation().getX() - state.getStormCenter().getX();
        double dz = player.getLocation().getZ() - state.getStormCenter().getZ();
        double distanceSquared = (dx * dx) + (dz * dz);
        double radius = state.getStormRadius();
        if (distanceSquared <= radius * radius) {
            return moduleConfig.getStringFrom("language.yml", "scoreboard.storm_status.safe", "Safe");
        }

        return moduleConfig.getStringFrom("language.yml", "scoreboard.storm_status.unsafe", "In Storm");
    }

    public List<Player> getPlayersSortedByKills(
            GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
            List<Player> players,
            int limit) {
        Map<Player, Integer> killCounts = new HashMap<>();
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            killCounts.put(player, game.getPlayerKills(context, player));
        }

        List<Map.Entry<Player, Integer>> sorted = new java.util.ArrayList<>(killCounts.entrySet());
        sorted.sort((a, b) -> {
            int compare = Integer.compare(b.getValue(), a.getValue());
            if (compare != 0) {
                return compare;
            }
            return a.getKey().getName().compareToIgnoreCase(b.getKey().getName());
        });

        List<Player> orderedPlayers = new java.util.ArrayList<>();
        for (Map.Entry<Player, Integer> entry : sorted) {
            orderedPlayers.add(entry.getKey());
            if (orderedPlayers.size() >= limit) {
                break;
            }
        }

        return orderedPlayers;
    }

    public ModuleConfigAPI getModuleConfig() {
        return moduleConfig;
    }
}
