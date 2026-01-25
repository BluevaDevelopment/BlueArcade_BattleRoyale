package net.blueva.arcade.modules.battleroyale.support.storm;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.modules.battleroyale.game.BattleRoyaleGame;
import net.blueva.arcade.modules.battleroyale.state.ArenaState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.WorldBorder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class StormService {

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final StatsAPI statsAPI;
    private final BattleRoyaleGame game;

    public StormService(ModuleInfo moduleInfo, ModuleConfigAPI moduleConfig, StatsAPI statsAPI, BattleRoyaleGame game) {
        this.moduleInfo = moduleInfo;
        this.moduleConfig = moduleConfig;
        this.statsAPI = statsAPI;
        this.game = game;
    }

    public void initializeStorm(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state) {
        if (!moduleConfig.getBoolean("storm.enabled", true)) {
            return;
        }

        List<StormStage> stages = getStages();
        if (stages.isEmpty()) {
            return;
        }

        Location center = resolveCenter(context);
        state.setStormCenter(center);
        double maxRadius = resolveMaxRadius(context, center);
        state.setStormMaxRadius(maxRadius);

        double radiusPercent = stages.get(0).radiusPercent;
        state.setStormRadius(maxRadius * radiusPercent);
        state.setStormStageIndex(0);
        state.setStormMoving(false);
        state.setStormNextStageIndex(resolveNextStageIndex(0, stages));
        state.resetStormPhaseTicks();
        state.setStormPhaseDuration(resolveInitialStandbySeconds(state, stages));
        initializeWorldBorder(context, state);
        announceStormIncoming(context, state, stages);
    }

    public void tickStorm(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                          ArenaState state) {
        if (!moduleConfig.getBoolean("storm.enabled", true)) {
            return;
        }

        List<StormStage> stages = getStages();
        if (stages.isEmpty()) {
            return;
        }

        if (state.getStormCenter() == null) {
            initializeStorm(context, state);
        }

        tickStormPhase(context, state, stages);
        syncStormRadius(state);
        updateWorldBorder(context, state);
        applyStormDamage(context, state, stages);
        spawnStormLightning(context, state);
    }

    private void applyStormDamage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  ArenaState state,
                                  List<StormStage> stages) {
        if (context.getPhase() == null) {
            return;
        }

        int damageStageIndex = state.isStormMoving() ? state.getStormNextStageIndex() : state.getStormStageIndex();
        StormStage stage = stages.get(Math.min(damageStageIndex, stages.size() - 1));
        double damage = Math.max(0.0, stage.damagePerSecond);
        if (damage <= 0) {
            return;
        }

        double safeRadius = resolveCurrentRadius(state);
        for (Player player : context.getAlivePlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            if (state.isDropping(player.getUniqueId())) {
                continue;
            }
            if (!isInsideSafeZone(state, player.getLocation(), safeRadius)) {
                double finalHealth = player.getHealth() - damage;
                if (finalHealth <= 0) {
                    if (statsAPI != null) {
                        statsAPI.addModuleStat(player, moduleInfo.getId(), "storm_damage_taken", (int) Math.ceil(damage));
                    }
                    game.handleNonCombatDeath(context, player);
                } else {
                    player.damage(damage);
                    if (statsAPI != null) {
                        statsAPI.addModuleStat(player, moduleInfo.getId(), "storm_damage_taken", (int) Math.ceil(damage));
                    }
                }
            }
        }
    }

    private void spawnStormLightning(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state) {
        if (!moduleConfig.getBoolean("storm.lightning.enabled", true)) {
            return;
        }

        int interval = moduleConfig.getInt("storm.lightning.interval_seconds", 3);
        if (interval <= 0) {
            return;
        }

        int ticks = state.incrementStormLightningTicks();
        if (ticks < interval) {
            return;
        }
        state.resetStormLightningTicks();

        int strikes = moduleConfig.getInt("storm.lightning.strikes_per_wave", 3);
        Location center = state.getStormCenter();
        if (center == null) {
            return;
        }

        double radius = Math.max(0.0, state.getStormMaxRadius());
        World world = center.getWorld();
        for (int i = 0; i < strikes; i++) {
            Location strikeLocation = randomStormLocation(center, radius, resolveCurrentRadius(state));
            world.strikeLightningEffect(strikeLocation);
        }
    }

    public boolean isInsideSafeZone(ArenaState state, Location location) {
        return isInsideSafeZone(state, location, resolveCurrentRadius(state));
    }

    private boolean isInsideSafeZone(ArenaState state, Location location, double safeRadius) {
        if (state.getStormCenter() == null || location == null) {
            return true;
        }
        if (!location.getWorld().equals(state.getStormCenter().getWorld())) {
            return true;
        }

        double dx = location.getX() - state.getStormCenter().getX();
        double dz = location.getZ() - state.getStormCenter().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        return distance <= safeRadius;
    }

    private Location resolveCenter(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        Location[] bounds = getBounds(context);
        if (bounds != null) {
            Location min = bounds[0];
            Location max = bounds[1];
            double centerX = (min.getX() + max.getX()) / 2.0;
            double centerY = (min.getY() + max.getY()) / 2.0;
            double centerZ = (min.getZ() + max.getZ()) / 2.0;
            return new Location(min.getWorld(), centerX, centerY, centerZ);
        }

        List<Player> players = context.getPlayers();
        return players.isEmpty() ? null : players.get(0).getLocation();
    }

    private double resolveMaxRadius(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    Location center) {
        if (center == null) {
            return moduleConfig.getDouble("storm.default_radius", 80.0);
        }
        Location[] bounds = getBounds(context);
        if (bounds != null) {
            Location min = bounds[0];
            Location max = bounds[1];
            double halfX = Math.abs(max.getX() - min.getX()) / 2.0;
            double halfZ = Math.abs(max.getZ() - min.getZ()) / 2.0;
            return Math.max(1.0, Math.min(halfX, halfZ));
        }
        return moduleConfig.getDouble("storm.default_radius", 80.0);
    }

    private Location randomStormLocation(Location center, double maxRadius, double safeRadius) {
        double minRadius = Math.min(maxRadius, Math.max(safeRadius + 2.0, safeRadius));
        double maxPickRadius = Math.max(minRadius, maxRadius + 1.0);
        double radius = ThreadLocalRandom.current().nextDouble(minRadius, maxPickRadius);
        double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2);
        double x = center.getX() + radius * Math.cos(angle);
        double z = center.getZ() + radius * Math.sin(angle);
        double y = center.getY();
        return new Location(center.getWorld(), x, y, z);
    }

    private List<StormStage> getStages() {
        List<String> stageLines = moduleConfig.getStringList("storm.stages");
        List<StormStage> stages = new ArrayList<>();
        if (stageLines == null) {
            return stages;
        }
        int defaultStandby = Math.max(0, moduleConfig.getInt("storm.standby_seconds", 10));
        for (String line : stageLines) {
            String[] parts = line.split(":");
            if (parts.length < 3) {
                continue;
            }
            try {
                double radius = Double.parseDouble(parts[0]);
                int duration = Integer.parseInt(parts[1]);
                double damage = Double.parseDouble(parts[2]);
                int standby = parts.length >= 4 ? Integer.parseInt(parts[3]) : defaultStandby;
                stages.add(new StormStage(Math.max(0.0, radius), Math.max(1, duration), Math.max(0.0, damage), Math.max(0, standby)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed lines
            }
        }
        return stages;
    }

    private void initializeWorldBorder(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       ArenaState state) {
        if (state.getStormCenter() == null) {
            return;
        }
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(state.getStormCenter());
        border.setSize(Math.max(1.0, state.getStormRadius() * 2.0));
        border.setWarningDistance(0);
        border.setWarningTime(0);
        state.setStormBorder(border);
        for (Player player : context.getPlayers()) {
            player.setWorldBorder(border);
        }
    }

    private void updateWorldBorder(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                   ArenaState state) {
        WorldBorder border = state.getStormBorder();
        if (border == null || state.getStormCenter() == null) {
            return;
        }
        border.setCenter(state.getStormCenter());
        for (Player player : context.getPlayers()) {
            player.setWorldBorder(border);
        }
    }

    public void clearWorldBorder(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                 ArenaState state) {
        state.setStormBorder(null);
        for (Player player : context.getPlayers()) {
            player.setWorldBorder(null);
        }
    }

    private void tickStormPhase(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state,
                                List<StormStage> stages) {
        if (state.getStormNextStageIndex() >= stages.size()) {
            return;
        }

        if (state.isStormMoving()) {
            int ticks = state.incrementStormPhaseTicks();
            if (ticks >= state.getStormPhaseDuration()) {
                finishStormMovement(context, state, stages);
            }
            return;
        }

        int standbyDuration = state.getStormPhaseDuration();
        if (standbyDuration <= 0) {
            startStormMovement(context, state, stages);
            return;
        }

        int ticks = state.incrementStormPhaseTicks();
        if (ticks >= standbyDuration) {
            startStormMovement(context, state, stages);
        }
    }

    private void startStormMovement(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    ArenaState state,
                                    List<StormStage> stages) {
        int nextIndex = state.getStormNextStageIndex();
        if (nextIndex >= stages.size()) {
            return;
        }

        StormStage stage = stages.get(nextIndex);
        double targetRadius = state.getStormMaxRadius() * stage.radiusPercent;
        WorldBorder border = state.getStormBorder();
        if (border != null) {
            border.setSize(Math.max(1.0, targetRadius * 2.0), stage.durationSeconds);
        }

        state.setStormMoving(true);
        state.resetStormPhaseTicks();
        state.setStormPhaseDuration(stage.durationSeconds);
        announceStormMoving(context);
    }

    private void finishStormMovement(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state,
                                     List<StormStage> stages) {
        int newStageIndex = state.getStormNextStageIndex();
        state.setStormStageIndex(newStageIndex);
        state.setStormMoving(false);
        state.resetStormPhaseTicks();
        state.setStormNextStageIndex(resolveNextStageIndex(newStageIndex, stages));
        state.setStormPhaseDuration(resolveStandbySeconds(state, stages));
        announceStormIncoming(context, state, stages);
    }

    private void syncStormRadius(ArenaState state) {
        WorldBorder border = state.getStormBorder();
        if (border != null) {
            state.setStormRadius(Math.max(0.0, border.getSize() / 2.0));
        }
    }

    private double resolveCurrentRadius(ArenaState state) {
        WorldBorder border = state.getStormBorder();
        if (border != null) {
            return Math.max(0.0, border.getSize() / 2.0);
        }
        return Math.max(0.0, state.getStormRadius());
    }

    private int resolveNextStageIndex(int currentStageIndex, List<StormStage> stages) {
        int nextIndex = currentStageIndex + 1;
        if (nextIndex >= stages.size()) {
            return stages.size();
        }
        return nextIndex;
    }

    private int resolveStandbySeconds(ArenaState state, List<StormStage> stages) {
        int nextIndex = state.getStormNextStageIndex();
        if (nextIndex <= state.getStormStageIndex() || nextIndex >= stages.size()) {
            return 0;
        }
        return Math.max(0, stages.get(nextIndex).standbySeconds);
    }

    private int resolveInitialStandbySeconds(ArenaState state, List<StormStage> stages) {
        int stageIndex = state.getStormStageIndex();
        if (stageIndex == 0 && stages.size() > 0) {
            return Math.max(0, stages.get(0).durationSeconds);
        }
        return resolveStandbySeconds(state, stages);
    }

    private void announceStormIncoming(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       ArenaState state,
                                       List<StormStage> stages) {
        if (!moduleConfig.getBoolean("storm.announce_incoming", true)) {
            return;
        }

        int standbySeconds = state.getStormPhaseDuration();
        if (standbySeconds <= 0 || state.getStormNextStageIndex() >= stages.size()) {
            return;
        }

        String message = moduleConfig.getStringFrom("language.yml", "messages.storm.incoming");
        if (message == null || message.isEmpty()) {
            return;
        }

        broadcast(context, message.replace("{seconds}", String.valueOf(standbySeconds)));
    }

    private void announceStormMoving(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (!moduleConfig.getBoolean("storm.announce_moving", true)) {
            return;
        }

        String message = moduleConfig.getStringFrom("language.yml", "messages.storm.moving");
        if (message == null || message.isEmpty()) {
            return;
        }
        broadcast(context, message);
    }

    private void broadcast(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           String message) {
        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            context.getMessagesAPI().sendRaw(player, message);
        }
    }

    private Location[] getBounds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context.getArenaAPI() == null) {
            return null;
        }
        Location min = context.getArenaAPI().getBoundsMin();
        Location max = context.getArenaAPI().getBoundsMax();
        if (min == null || max == null || min.getWorld() == null) {
            return null;
        }
        return new Location[]{min, max};
    }

    private static class StormStage {
        private final double radiusPercent;
        private final int durationSeconds;
        private final double damagePerSecond;
        private final int standbySeconds;

        private StormStage(double radiusPercent, int durationSeconds, double damagePerSecond, int standbySeconds) {
            this.radiusPercent = radiusPercent;
            this.durationSeconds = durationSeconds;
            this.damagePerSecond = damagePerSecond;
            this.standbySeconds = standbySeconds;
        }
    }
}
