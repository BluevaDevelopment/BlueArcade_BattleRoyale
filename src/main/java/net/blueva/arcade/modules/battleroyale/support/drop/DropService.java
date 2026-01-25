package net.blueva.arcade.modules.battleroyale.support.drop;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.modules.battleroyale.state.ArenaState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;

public class DropService {

    private final ModuleConfigAPI moduleConfig;

    public DropService(ModuleConfigAPI moduleConfig) {
        this.moduleConfig = moduleConfig;
    }

    public void startDrop(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                          ArenaState state) {
        if (!moduleConfig.getBoolean("drop.enabled", true)) {
            return;
        }

        Location[] route = computeRoute(context, state);
        if (route == null) {
            return;
        }

        Location start = route[0];
        Location end = route[1];
        World world = start.getWorld();
        state.setDropCarrier(null);

        EnderDragon dragon = world.spawn(start, EnderDragon.class, entity -> {
            entity.setSilent(true);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setAI(true);
            entity.setPhase(EnderDragon.Phase.CIRCLING);
            entity.setPersistent(false);
            entity.setCustomNameVisible(false);
        });
        state.setDropDragon(dragon);

        for (Player player : context.getPlayers()) {
            if (player.isOnline()) {
                dragon.addPassenger(player);
                applyDropInvisibility(state, player);
            }
        }

        Vector direction = end.toVector().subtract(start.toVector());
        double length = direction.length();
        if (length <= 0.1) {
            cleanup(state);
            return;
        }
        direction.normalize();

        double speed = moduleConfig.getDouble("drop.speed_blocks_per_tick", 0.6);
        String taskId = "arena_" + state.getArenaId() + "_battle_royale_drop";
        final double[] traveled = {0.0};
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        context.getSchedulerAPI().runTimer(taskId, () -> {
            context.getSchedulerAPI().runAtEntity(dragon, () -> {
                if (state.isEnded()) {
                    context.getSchedulerAPI().cancelTask(taskId);
                    cleanup(state);
                    return;
                }

                if (dragon.isDead() || !dragon.isValid()) {
                    context.getSchedulerAPI().cancelTask(taskId);
                    cleanup(state);
                    return;
                }

                checkForManualExit(state, dragon);

                traveled[0] += speed;
                if (traveled[0] >= length) {
                    end.setYaw(yaw);
                    dragon.teleport(end);
                    dragon.setVelocity(new Vector(0, 0, 0));
                    context.getSchedulerAPI().cancelTask(taskId);
                    forceDrop(context, state);
                    cleanup(state);
                    return;
                }

                Location next = start.clone().add(direction.clone().multiply(traveled[0]));
                next.setYaw(yaw);
                dragon.teleport(next);
                dragon.setVelocity(direction.clone().multiply(speed));
            });
        }, 0L, 1L);
    }

    public void handleDropExit(ArenaState state, Player player) {
        EnderDragon dragon = state.getDropDragon();
        if (dragon == null) {
            return;
        }
        boolean isPassenger = player.isInsideVehicle() && player.getVehicle() == dragon;
        if (!isPassenger && !dragon.getPassengers().contains(player)) {
            return;
        }
        ItemStack previousChestplate = player.getInventory().getChestplate();
        state.markDropping(player.getUniqueId(), previousChestplate);
        player.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
        player.setFallDistance(0.0f);
        dragon.removePassenger(player);
        player.leaveVehicle();
        clearDropInvisibility(state, player);
    }

    public void handleLanding(ArenaState state, Player player) {
        if (!state.isDropping(player.getUniqueId())) {
            return;
        }

        ItemStack restore = state.restoreChestplate(player.getUniqueId());
        player.getInventory().setChestplate(restore);
        player.setFallDistance(0.0f);
    }

    public void cleanup(ArenaState state) {
        EnderDragon dragon = state.getDropDragon();
        for (Player player : dragon != null ? dragon.getPassengers().stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .toList() : List.<Player>of()) {
            clearDropInvisibility(state, player);
        }
        if (dragon != null && dragon.isValid()) {
            dragon.remove();
        }
        state.setDropDragon(null);
        state.setDropCarrier(null);
    }

    private void forceDrop(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           ArenaState state) {
        EnderDragon dragon = state.getDropDragon();
        if (dragon == null) {
            return;
        }

        List<Player> passengers = dragon.getPassengers().stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .toList();
        for (Player player : passengers) {
            handleDropExit(state, player);
        }

        for (Player player : context.getPlayers()) {
            if (player.isInsideVehicle() && player.getVehicle() == dragon) {
                handleDropExit(state, player);
            }
        }
    }

    private Location[] computeRoute(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    ArenaState state) {
        Location center = getPlayAreaCenter(context);
        if (center == null) {
            return null;
        }

        double flightHeight = moduleConfig.getDouble("drop.flight_height", center.getY() + 40.0);

        Location[] bounds = getBounds(context);
        if (bounds != null) {
            Location min = bounds[0];
            Location max = bounds[1];
            double minX = Math.min(min.getX(), max.getX());
            double maxX = Math.max(min.getX(), max.getX());
            double minZ = Math.min(min.getZ(), max.getZ());
            double maxZ = Math.max(min.getZ(), max.getZ());
            double centerX = (minX + maxX) / 2.0;
            double centerZ = (minZ + maxZ) / 2.0;
            double sizeX = maxX - minX;
            double sizeZ = maxZ - minZ;
            double edgeOffset = moduleConfig.getDouble("drop.edge_offset", 4.0);

            if (sizeX >= sizeZ) {
                double offset = clampEdgeOffset(edgeOffset, sizeX);
                return new Location[]{
                        new Location(center.getWorld(), minX + offset, flightHeight, centerZ),
                        new Location(center.getWorld(), maxX - offset, flightHeight, centerZ)
                };
            }

            double offset = clampEdgeOffset(edgeOffset, sizeZ);
            return new Location[]{
                    new Location(center.getWorld(), centerX, flightHeight, minZ + offset),
                    new Location(center.getWorld(), centerX, flightHeight, maxZ - offset)
            };
        }

        double halfSizeZ = getPlayAreaHalfSize(context);
        double startZ = center.getZ() - halfSizeZ;
        double endZ = center.getZ() + halfSizeZ;
        return new Location[]{
                new Location(center.getWorld(), center.getX(), flightHeight, startZ),
                new Location(center.getWorld(), center.getX(), flightHeight, endZ)
        };
    }

    private Location getPlayAreaCenter(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
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
        if (players.isEmpty()) {
            return null;
        }
        return players.get(0).getLocation();
    }

    private double getPlayAreaHalfSize(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        return moduleConfig.getDouble("drop.default_half_size", 60.0);
    }

    private double clampEdgeOffset(double desiredOffset, double size) {
        if (size <= 0.0) {
            return 0.0;
        }
        double maxOffset = Math.max(0.0, (size / 2.0) - 1.0);
        return Math.max(0.0, Math.min(desiredOffset, maxOffset));
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

    private void applyDropInvisibility(ArenaState state, Player player) {
        state.markDropInvisible(player.getUniqueId());
        PotionEffect effect = new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, true);
        player.addPotionEffect(effect);
    }

    private void clearDropInvisibility(ArenaState state, Player player) {
        if (state.unmarkDropInvisible(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private void checkForManualExit(ArenaState state, EnderDragon dragon) {
        for (Player player : dragon.getPassengers().stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .toList()) {
            if (player.isSneaking()) {
                handleDropExit(state, player);
            }
        }
    }

}
