package net.blueva.arcade.modules.battleroyale.game;

import net.blueva.arcade.api.config.CoreConfigAPI;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GamePhase;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BattleRoyaleGame {

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final CoreConfigAPI coreConfig;
    private final StatsAPI statsAPI;

    private final Map<Integer, ArenaState> arenas = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerArena = new ConcurrentHashMap<>();
    private final Map<Integer, Set<UUID>> countdownPreparedByArena = new ConcurrentHashMap<>();

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

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
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
        ArenaState state = getArenaState(context);
        Location countdownView = state != null ? resolveCountdownSpectatorLocation(context) : null;

        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            if (countdownView != null && markCountdownPrepared(context.getArenaId(), player.getUniqueId())) {
                Location targetView = countdownView.clone();
                context.getSchedulerAPI().runAtEntity(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.teleport(targetView);
                    player.setGameMode(GameMode.SPECTATOR);
                });
            }

            context.getSoundsAPI().play(player, coreConfig.getSound("sounds.starting_game.countdown"));

            String title = coreConfig.getLanguage(player, "titles.starting_game.title")
                    .replace("{game_display_name}", moduleInfo.getName())
                    .replace("{time}", String.valueOf(secondsLeft));

            String subtitle = coreConfig.getLanguage(player, "titles.starting_game.subtitle")
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

            String title = coreConfig.getLanguage(player, "titles.game_started.title")
                    .replace("{game_display_name}", moduleInfo.getName());

            String subtitle = coreConfig.getLanguage(player, "titles.game_started.subtitle")
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

        for (Player player : context.getPlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            loadoutService.restoreVitals(player);
            loadoutService.giveStartingItems(player);
            loadoutService.applyStartingEffects(player);
            context.getScoreboardAPI().showScoreboard(player, getScoreboardPath(context));
        }

        stormService.initializeStorm(context, state);
        startGameTimer(context, state);
        dropService.startDrop(context, state);
    }

    public void finishGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        int arenaId = context.getArenaId();
        context.getSchedulerAPI().cancelArenaTasks(arenaId);

        ArenaState state = arenas.remove(arenaId);
        countdownPreparedByArena.remove(arenaId);
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
        countdownPreparedByArena.clear();
    }

    private boolean markCountdownPrepared(int arenaId, UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return countdownPreparedByArena
                .computeIfAbsent(arenaId, ignored -> ConcurrentHashMap.newKeySet())
                .add(playerId);
    }

    private Location resolveCountdownSpectatorLocation(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null || context.getArenaAPI() == null) {
            return null;
        }

        Location min = context.getArenaAPI().getBoundsMin();
        Location max = context.getArenaAPI().getBoundsMax();
        if (min == null || max == null || min.getWorld() == null) {
            return null;
        }

        World world = min.getWorld();
        double minY = Math.min(min.getY(), max.getY());
        double maxY = Math.max(min.getY(), max.getY());
        double centerX = (min.getX() + max.getX()) / 2.0;
        double centerZ = (min.getZ() + max.getZ()) / 2.0;
        double minAllowedY = minY + 1.0;
        double maxAllowedY = maxY - 1.0;
        double preferredY = maxY - 2.0;
        double centerY = maxAllowedY >= minAllowedY
                ? Math.max(minAllowedY, Math.min(preferredY, maxAllowedY))
                : (minY + maxY) / 2.0;

        Location location = new Location(world, centerX, centerY, centerZ);
        location.setYaw(0.0f);
        location.setPitch(60.0f);
        return location;
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

    public void handlePlaneSneakToggle(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       Player player,
                                       boolean sneaking) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        dropService.handlePlaneSneakToggle(state, player, sneaking);
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

    /**
     * Handles chests destroyed by an explosion (TNT, creepers, ...). Each affected chest is
     * registered in memory and looted the same way as when a player interacts with it.
     */
    public void handleChestExplosion(List<org.bukkit.block.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }

        boolean breakOnlyPlaced = moduleConfig.getBoolean("block_rules.break_only_player_placed", false);
        for (ArenaState state : arenas.values()) {
            GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context = state.getContext();
            if (context == null || context.getPhase() != GamePhase.PLAYING) {
                continue;
            }

            World arenaWorld = context.getArenaAPI() != null ? context.getArenaAPI().getWorld() : null;
            Iterator<org.bukkit.block.Block> iterator = blocks.iterator();
            while (iterator.hasNext()) {
                org.bukkit.block.Block block = iterator.next();
                if (arenaWorld != null && !arenaWorld.equals(block.getWorld())) {
                    continue;
                }
                if (breakOnlyPlaced) {
                    if (state.isPlayerPlacedBlock(block.getLocation())) {
                        state.untrackPlacedBlock(block.getLocation());
                    } else {
                        iterator.remove();
                        continue;
                    }
                }
                Material type = block.getType();
                if (type != Material.CHEST && type != Material.TRAPPED_CHEST && type != Material.ENDER_CHEST) {
                    continue;
                }
                lootService.handleChestLoot(context, state, null, block);
                if (block.getType() == Material.AIR) {
                    iterator.remove();
                }
            }
        }
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

    public String getScoreboardPath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        return isSoloMode(context) ? "scoreboard.solo" : "scoreboard.default";
    }

    public boolean isSoloMode(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null) {
            return true;
        }
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            return true;
        }
        if (context.getDataAccess() == null) {
            return false;
        }
        Integer teamSize = context.getDataAccess().getGameData("teams.size", Integer.class);
        Integer teamCount = context.getDataAccess().getGameData("teams.count", Integer.class);
        if (teamSize != null && teamSize <= 1) {
            return true;
        }
        return teamCount != null && teamCount <= 1;
    }

    public List<String> getAliveTeamIds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            List<String> ids = new ArrayList<>();
            if (!context.getAlivePlayers().isEmpty()) {
                ids.add("solo");
            }
            return ids;
        }

        Set<String> teamIds = new HashSet<>();
        for (Player player : context.getAlivePlayers()) {
            TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
            if (team != null) {
                teamIds.add(team.getId());
            }
        }
        return new ArrayList<>(teamIds);
    }

    public Map<String, Integer> getTeamKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        Map<String, Integer> teamKills = new HashMap<>();
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        for (Player player : context.getPlayers()) {
            int kills = getPlayerKills(context, player);
            String teamId = "solo";
            if (teamsAPI != null && teamsAPI.isEnabled()) {
                TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
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
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        List<Player> players = new ArrayList<>();
        for (Player player : context.getPlayers()) {
            if (teamsAPI == null || !teamsAPI.isEnabled()) {
                players.add(player);
                continue;
            }
            TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
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
            for (Player player : allPlayers) {
                String actionBarTemplate = coreConfig.getLanguage(player, "action_bar.in_game.global");
                if (!player.isOnline()) {
                    continue;
                }

                Map<String, String> customPlaceholders = placeholderService.buildPlaceholders(player);
                if (hasTimeLimit && timeLeft[0] > 0) {
                    customPlaceholders.put("time", formatCountdownTime(timeLeft[0]));
                }
                customPlaceholders.put("alive", String.valueOf(alivePlayers.size()));
                customPlaceholders.put("spectators", String.valueOf(context.getSpectators().size()));

                if (actionBarTemplate != null && hasTimeLimit) {
                    String actionBarMessage = actionBarTemplate
                            .replace("{time}", formatCountdownTime(timeLeft[0]))
                            .replace("{round}", String.valueOf(context.getCurrentRound()))
                            .replace("{round_max}", String.valueOf(context.getMaxRounds()));
                    context.getMessagesAPI().sendActionBar(player, actionBarMessage);
                }

                context.getScoreboardAPI().update(player, getScoreboardPath(context), customPlaceholders);
            }
        }, 0L, 20L);
    }

    private boolean shouldEndForVictory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            return getAliveTeamIds(context).size() <= 1;
        }
        return context.getAlivePlayers().size() <= 1;
    }

    private static String formatCountdownTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return String.format("%02d:%02d", safeSeconds / 60, safeSeconds % 60);
    }

}
