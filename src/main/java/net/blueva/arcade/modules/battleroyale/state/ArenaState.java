package net.blueva.arcade.modules.battleroyale.state;

import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.modules.battleroyale.support.loot.TrackedChest;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ArenaState {

    private final GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context;
    private final Map<UUID, Integer> playerKills = new ConcurrentHashMap<>();
    private final Set<UUID> droppingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dropInvisiblePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> planePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> planeExitReleaseRequired = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ItemStack> storedChestplates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> planeBoardedAt = new ConcurrentHashMap<>();
    private final Set<String> lootedChests = ConcurrentHashMap.newKeySet();
    private final Map<String, TrackedChest> trackedChests = new ConcurrentHashMap<>();

    private UUID winnerId;
    private boolean ended;
    private int supplyTicks;

    private int stormStageIndex;
    private int stormNextStageIndex;
    private int stormPhaseTicks;
    private int stormPhaseDuration;
    private boolean stormMoving;
    private double stormRadius;
    private double stormMaxRadius;
    private Location stormCenter;
    private int stormLightningTicks;

    private ArmorStand dropVehicle;
    private List<Entity> planeDisplays;
    private WorldBorder stormBorder;

    private Boolean respawnRegionCheckResult;

    public ArenaState(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        this.context = context;
    }

    public GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> getContext() {
        return context;
    }

    public int getArenaId() {
        return context.getArenaId();
    }

    public void initializePlayer(UUID playerId) {
        playerKills.putIfAbsent(playerId, 0);
    }

    public int addKill(UUID playerId) {
        return playerKills.merge(playerId, 1, Integer::sum);
    }

    public int getKills(UUID playerId) {
        return playerKills.getOrDefault(playerId, 0);
    }

    public Map<UUID, Integer> getKillSnapshot() {
        return new ConcurrentHashMap<>(playerKills);
    }

    public boolean markEnded() {
        boolean wasEnded = ended;
        ended = true;
        return wasEnded;
    }

    public boolean isEnded() {
        return ended;
    }

    public void setWinner(UUID winnerId) {
        this.winnerId = winnerId;
    }

    public UUID getWinnerId() {
        return winnerId;
    }

    public int incrementSupplyTicks(int increment) {
        supplyTicks += increment;
        return supplyTicks;
    }

    public void resetSupplyTicks() {
        supplyTicks = 0;
    }

    public void markDropping(UUID playerId, ItemStack previousChestplate) {
        droppingPlayers.add(playerId);
        if (previousChestplate != null) {
            storedChestplates.put(playerId, previousChestplate.clone());
        }
    }

    public boolean isDropping(UUID playerId) {
        return droppingPlayers.contains(playerId);
    }

    public ItemStack restoreChestplate(UUID playerId) {
        droppingPlayers.remove(playerId);
        return storedChestplates.remove(playerId);
    }

    public void markDropInvisible(UUID playerId) {
        dropInvisiblePlayers.add(playerId);
    }

    public boolean unmarkDropInvisible(UUID playerId) {
        return dropInvisiblePlayers.remove(playerId);
    }

    public Set<UUID> getDropInvisiblePlayers() {
        return Set.copyOf(dropInvisiblePlayers);
    }

    public void addPlanePlayer(UUID playerId, boolean requireSneakRelease) {
        planePlayers.add(playerId);
        planeBoardedAt.put(playerId, System.currentTimeMillis());
        if (requireSneakRelease) {
            planeExitReleaseRequired.add(playerId);
        } else {
            planeExitReleaseRequired.remove(playerId);
        }
    }

    public void removePlanePlayer(UUID playerId) {
        planePlayers.remove(playerId);
        planeBoardedAt.remove(playerId);
        planeExitReleaseRequired.remove(playerId);
    }

    public boolean isOnPlane(UUID playerId) {
        return planePlayers.contains(playerId);
    }

    public Set<UUID> getPlanePlayers() {
        return Set.copyOf(planePlayers);
    }

    public boolean requiresPlaneExitRelease(UUID playerId) {
        return planeExitReleaseRequired.contains(playerId);
    }

    public void markPlaneExitRelease(UUID playerId) {
        planeExitReleaseRequired.remove(playerId);
    }

    public boolean hasPlaneExitGraceElapsed(UUID playerId, long graceMillis) {
        Long boardedAt = planeBoardedAt.get(playerId);
        if (boardedAt == null) {
            return false;
        }
        return System.currentTimeMillis() - boardedAt >= Math.max(0L, graceMillis);
    }

    public void clearPlanePlayers() {
        planePlayers.clear();
        planeBoardedAt.clear();
        planeExitReleaseRequired.clear();
    }

    public boolean markChestLooted(Location location) {
        if (location == null) {
            return false;
        }
        return lootedChests.add(toKey(location));
    }

    public boolean isChestLooted(Location location) {
        if (location == null) {
            return false;
        }
        return lootedChests.contains(toKey(location));
    }

    public void trackChest(Location location, Material material) {
        if (location == null || material == null) {
            return;
        }
        trackedChests.putIfAbsent(toKey(location), new TrackedChest(location, material));
    }

    public List<TrackedChest> getTrackedChests() {
        return List.copyOf(trackedChests.values());
    }

    public void setStormCenter(Location stormCenter) {
        this.stormCenter = stormCenter;
    }

    public Location getStormCenter() {
        return stormCenter;
    }

    public double getStormRadius() {
        return stormRadius;
    }

    public void setStormRadius(double stormRadius) {
        this.stormRadius = stormRadius;
    }

    public double getStormMaxRadius() {
        return stormMaxRadius;
    }

    public void setStormMaxRadius(double stormMaxRadius) {
        this.stormMaxRadius = stormMaxRadius;
    }

    public int getStormStageIndex() {
        return stormStageIndex;
    }

    public void setStormStageIndex(int stormStageIndex) {
        this.stormStageIndex = stormStageIndex;
    }

    public int getStormNextStageIndex() {
        return stormNextStageIndex;
    }

    public void setStormNextStageIndex(int stormNextStageIndex) {
        this.stormNextStageIndex = stormNextStageIndex;
    }

    public boolean isStormMoving() {
        return stormMoving;
    }

    public void setStormMoving(boolean stormMoving) {
        this.stormMoving = stormMoving;
    }

    public int incrementStormPhaseTicks() {
        return ++stormPhaseTicks;
    }

    public void resetStormPhaseTicks() {
        stormPhaseTicks = 0;
    }

    public int getStormPhaseTicks() {
        return stormPhaseTicks;
    }

    public int getStormPhaseDuration() {
        return stormPhaseDuration;
    }

    public void setStormPhaseDuration(int stormPhaseDuration) {
        this.stormPhaseDuration = stormPhaseDuration;
    }

    public int incrementStormLightningTicks() {
        return ++stormLightningTicks;
    }

    public void resetStormLightningTicks() {
        stormLightningTicks = 0;
    }

    public ArmorStand getDropVehicle() {
        return dropVehicle;
    }

    public void setDropVehicle(ArmorStand dropVehicle) {
        this.dropVehicle = dropVehicle;
    }

    public List<Entity> getPlaneDisplays() {
        return planeDisplays;
    }

    public void setPlaneDisplays(List<Entity> planeDisplays) {
        this.planeDisplays = planeDisplays;
    }

    public WorldBorder getStormBorder() {
        return stormBorder;
    }

    public void setStormBorder(WorldBorder stormBorder) {
        this.stormBorder = stormBorder;
    }

    public boolean hasRespawnRegion() {
        if (respawnRegionCheckResult == null) {
            respawnRegionCheckResult = context.getDataAccess().hasGameData("game.play_area.bounds.min.x")
                    && context.getDataAccess().hasGameData("game.play_area.bounds.min.y")
                    && context.getDataAccess().hasGameData("game.play_area.bounds.min.z")
                    && context.getDataAccess().hasGameData("game.play_area.bounds.max.x")
                    && context.getDataAccess().hasGameData("game.play_area.bounds.max.y")
                    && context.getDataAccess().hasGameData("game.play_area.bounds.max.z");
        }
        return respawnRegionCheckResult;
    }

    private String toKey(Location location) {
        return location.getWorld().getName() + ":" +
                location.getBlockX() + ":" +
                location.getBlockY() + ":" +
                location.getBlockZ();
    }
}
