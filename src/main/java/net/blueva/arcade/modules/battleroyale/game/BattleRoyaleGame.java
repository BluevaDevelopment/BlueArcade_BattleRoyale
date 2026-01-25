package net.blueva.arcade.modules.battleroyale.game;

import net.blueva.arcade.api.config.CoreConfigAPI;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.api.team.TeamInfo;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.battleroyale.state.ArenaState;
import net.blueva.arcade.modules.battleroyale.support.DescriptionService;
import net.blueva.arcade.modules.battleroyale.support.PlaceholderService;
import net.blueva.arcade.modules.battleroyale.support.combat.CombatService;
import net.blueva.arcade.modules.battleroyale.support.drop.DropService;
import net.blueva.arcade.modules.battleroyale.support.loadout.PlayerLoadoutService;
import net.blueva.arcade.modules.battleroyale.support.loot.LootService;
import net.blueva.arcade.modules.battleroyale.support.outcome.OutcomeService;
import net.blueva.arcade.modules.battleroyale.support.storm.StormService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BattleRoyaleGame {

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final CoreConfigAPI coreConfig;
    private final StatsAPI statsAPI;

    private final Map<Integer, ArenaState> arenas = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerArena = new ConcurrentHashMap<>();

    private final DescriptionService descriptionService;
    private final PlayerLoadoutService loadoutService;
    private final PlaceholderService placeholderService;
    private final OutcomeService outcomeService;
    private final CombatService combatService;
    private final LootService lootService;
    private final StormService stormService;
    private final DropService dropService;

    public BattleRoyaleGame(ModuleInfo moduleInfo,
                            ModuleConfigAPI moduleConfig,
                            CoreConfigAPI coreConfig,
                            StatsAPI statsAPI) {
        this.moduleInfo = moduleInfo;
        this.moduleConfig = moduleConfig;
        this.coreConfig = coreConfig;
        this.statsAPI = statsAPI;

        this.descriptionService = new DescriptionService(moduleConfig);
        this.loadoutService = new PlayerLoadoutService(moduleConfig);
        this.placeholderService = new PlaceholderService(moduleConfig, this);
        this.outcomeService = new OutcomeService(moduleInfo, statsAPI, this, placeholderService);
        this.combatService = new CombatService(moduleConfig, coreConfig, statsAPI, this, loadoutService);
        this.lootService = new LootService(moduleInfo, moduleConfig, statsAPI);
        this.stormService = new StormService(moduleInfo, moduleConfig, statsAPI, this);
        this.dropService = new DropService(moduleConfig);
    }

    public void startGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        int arenaId = context.getArenaId();

        context.getSchedulerAPI().cancelArenaTasks(arenaId);
        ArenaState state = new ArenaState(context);
        arenas.put(arenaId, state);
        state.setTrackedChests(lootService.loadChests(context));

        TeamsAPI teamsAPI = context.getTeamsAPI();
        for (Player player : context.getPlayers()) {
            playerArena.put(player, arenaId);
            state.initializePlayer(player.getUniqueId());
            if (teamsAPI != null && teamsAPI.isEnabled() && teamsAPI.getTeam(player) == null) {
                teamsAPI.autoAssignPlayer(player);
            }
        }

        descriptionService.sendDescription(context);
    }

    public void handleCountdownTick(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    int secondsLeft) {
        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            context.getSoundsAPI().play(player, coreConfig.getSound("sounds.starting_game.countdown"));

            String title = coreConfig.getLanguage("titles.starting_game.title")
                    .replace("{game_display_name}", moduleInfo.getName())
                    .replace("{time}", String.valueOf(secondsLeft));

            String subtitle = coreConfig.getLanguage("titles.starting_game.subtitle")
                    .replace("{game_display_name}", moduleInfo.getName())
                    .replace("{time}", String.valueOf(secondsLeft));

            context.getTitlesAPI().sendRaw(player, title, subtitle, 0, 20, 5);
        }
    }

    public void handleCountdownFinish(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            String title = coreConfig.getLanguage("titles.game_started.title")
                    .replace("{game_display_name}", moduleInfo.getName());

            String subtitle = coreConfig.getLanguage("titles.game_started.subtitle")
                    .replace("{game_display_name}", moduleInfo.getName());

            context.getTitlesAPI().sendRaw(player, title, subtitle, 0, 20, 20);
            context.getSoundsAPI().play(player, coreConfig.getSound("sounds.starting_game.start"));
        }
    }

    public void beginPlaying(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }

        if (context.getAlivePlayers().isEmpty() && !context.getPlayers().isEmpty()) {
            context.setPlayers(context.getPlayers());
        }

        stormService.initializeStorm(context, state);
        startGameTimer(context, state);
        dropService.startDrop(context, state);
        lootService.startChestMarkers(context, state);

        for (Player player : context.getPlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            loadoutService.restoreVitals(player);
            loadoutService.giveStartingItems(player);
            loadoutService.applyStartingEffects(player);
            context.getScoreboardAPI().showScoreboard(player, getScoreboardPath());
        }
    }

    public void finishGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        int arenaId = context.getArenaId();
        context.getSchedulerAPI().cancelArenaTasks(arenaId);

        ArenaState state = arenas.remove(arenaId);
        if (state != null) {
            lootService.restoreChests(context, state);
            dropService.cleanup(state);
            stormService.clearWorldBorder(context, state);
        }
        removePlayersFromArena(arenaId, context.getPlayers());

        if (statsAPI != null) {
            for (Player player : context.getPlayers()) {
                statsAPI.addModuleStat(player, moduleInfo.getId(), "games_played", 1);
            }
        }
    }

    public void shutdown() {
        Set<ArenaState> states = Set.copyOf(arenas.values());
        for (ArenaState state : states) {
            state.getContext().getSchedulerAPI().cancelModuleTasks("battle_royale");
            dropService.cleanup(state);
            stormService.clearWorldBorder(state.getContext(), state);
        }

        arenas.clear();
        playerArena.clear();
    }

    public Map<String, String> getPlaceholders(Player player) {
        return placeholderService.buildPlaceholders(player);
    }

    public GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> getContext(Player player) {
        Integer arenaId = playerArena.get(player);
        if (arenaId == null) {
            return null;
        }
        ArenaState state = arenas.get(arenaId);
        return state != null ? state.getContext() : null;
    }

    public ArenaState getArenaState(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null) {
            return null;
        }
        return arenas.get(context.getArenaId());
    }

    public int getPlayerKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return 0;
        }
        return state.getKills(player.getUniqueId());
    }

    public void addPlayerKill(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        state.addKill(player.getUniqueId());
    }

    public void healKiller(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           Player killer) {
        loadoutService.handleKillRegeneration(context, killer);
        context.getSoundsAPI().play(killer, coreConfig.getSound("sounds.in_game.respawn"));
    }

    public void handleKill(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           Player attacker,
                           Player victim) {
        combatService.handleKillCredit(context, attacker);
        combatService.handleElimination(context, victim, attacker);
        checkForTeamVictory(context);
    }

    public void handleNonCombatDeath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     Player victim) {
        combatService.handleElimination(context, victim, null);
        checkForTeamVictory(context);
    }

    public void handleDropExit(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                               Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        dropService.handleDropExit(state, player);
    }

    public void handleDropLanding(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        dropService.handleLanding(state, player);
    }

    public void handleChestLoot(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                Player player,
                                org.bukkit.block.Block block) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        lootService.handleChestLoot(context, state, player, block);
    }

    public boolean isChestLooted(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                 Player player,
                                 org.bukkit.block.Block block) {
        ArenaState state = getArenaState(context);
        if (state == null || player == null) {
            return false;
        }
        return lootService.isChestLooted(state, block);
    }

    public void endGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }

        outcomeService.endGame(context, state);
    }

    public ModuleConfigAPI getModuleConfig() {
        return moduleConfig;
    }

    public CoreConfigAPI getCoreConfig() {
        return coreConfig;
    }

    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    public StatsAPI getStatsAPI() {
        return statsAPI;
    }

    public Map<Player, Integer> getPlayerArena() {
        return playerArena;
    }

    public void removePlayersFromArena(int arenaId, List<Player> players) {
        for (Player player : players) {
            playerArena.remove(player);
        }
    }

    public String getScoreboardPath() {
        return "scoreboard.default";
    }

    public List<String> getAliveTeamIds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            List<String> ids = new ArrayList<>();
            if (!context.getAlivePlayers().isEmpty()) {
                ids.add("solo");
            }
            return ids;
        }

        Set<String> teamIds = new HashSet<>();
        for (Player player : context.getAlivePlayers()) {
            TeamInfo team = teamsAPI.getTeam(player);
            if (team != null) {
                teamIds.add(team.getId());
            }
        }
        return new ArrayList<>(teamIds);
    }

    public Map<String, Integer> getTeamKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        Map<String, Integer> teamKills = new HashMap<>();
        TeamsAPI teamsAPI = context.getTeamsAPI();
        for (Player player : context.getPlayers()) {
            int kills = getPlayerKills(context, player);
            String teamId = "solo";
            if (teamsAPI != null && teamsAPI.isEnabled()) {
                TeamInfo team = teamsAPI.getTeam(player);
                if (team != null) {
                    teamId = team.getId();
                }
            }
            teamKills.merge(teamId, kills, Integer::sum);
        }
        return teamKills;
    }

    public List<Player> getTeamPlayers(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       String teamId) {
        TeamsAPI teamsAPI = context.getTeamsAPI();
        List<Player> players = new ArrayList<>();
        for (Player player : context.getPlayers()) {
            if (teamsAPI == null || !teamsAPI.isEnabled()) {
                players.add(player);
                continue;
            }
            TeamInfo team = teamsAPI.getTeam(player);
            if (team != null && team.getId().equalsIgnoreCase(teamId)) {
                players.add(player);
            }
        }
        return players;
    }

    public void checkForTeamVictory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null || state.isEnded()) {
            return;
        }

        if (shouldEndForVictory(context)) {
            endGame(context);
        }
    }

    public void tickStorm(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        stormService.tickStorm(context, state);
    }

    public boolean isDropping(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              Player player) {
        ArenaState state = getArenaState(context);
        if (state == null || player == null) {
            return false;
        }
        return state.isDropping(player.getUniqueId());
    }

    private void startGameTimer(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state) {
        int arenaId = context.getArenaId();

        int gameTime = moduleConfig.getInt("game.time_limit_seconds", 0);
        boolean hasTimeLimit = gameTime > 0;
        final int[] timeLeft = {gameTime};
        String taskId = "arena_" + arenaId + "_battle_royale_timer";

        context.getSchedulerAPI().runTimer(taskId, () -> {
            if (state.isEnded()) {
                context.getSchedulerAPI().cancelTask(taskId);
                return;
            }

            tickStorm(context);

            List<Player> alivePlayers = context.getAlivePlayers();
            List<Player> allPlayers = context.getPlayers();

            if (hasTimeLimit && timeLeft[0] > 0) {
                timeLeft[0]--;
                if (timeLeft[0] <= 0) {
                    endGame(context);
                    return;
                }
            }

            if (shouldEndForVictory(context)) {
                endGame(context);
                return;
            }

            String actionBarTemplate = coreConfig.getLanguage("action_bar.in_game.global");
            for (Player player : allPlayers) {
                if (!player.isOnline()) {
                    continue;
                }

                Map<String, String> customPlaceholders = placeholderService.buildPlaceholders(player);
                if (hasTimeLimit && timeLeft[0] > 0) {
                    customPlaceholders.put("time", String.valueOf(timeLeft[0]));
                }
                customPlaceholders.put("alive", String.valueOf(alivePlayers.size()));
                customPlaceholders.put("spectators", String.valueOf(context.getSpectators().size()));

                if (actionBarTemplate != null && hasTimeLimit) {
                    String actionBarMessage = actionBarTemplate
                            .replace("{time}", String.valueOf(timeLeft[0]))
                            .replace("{round}", String.valueOf(context.getCurrentRound()))
                            .replace("{round_max}", String.valueOf(context.getMaxRounds()));
                    context.getMessagesAPI().sendActionBar(player, actionBarMessage);
                }

                context.getScoreboardAPI().update(player, getScoreboardPath(), customPlaceholders);
            }
        }, 0L, 20L);
    }

    private boolean shouldEndForVictory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            return getAliveTeamIds(context).size() <= 1;
        }
        return context.getAlivePlayers().size() <= 1;
    }
}
