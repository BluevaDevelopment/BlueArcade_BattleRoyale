package net.blueva.arcade.modules.battleroyale.support.drop;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.modules.battleroyale.state.ArenaState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DropService {

    private final ModuleConfigAPI moduleConfig;

    private static final int[][] PLANE_OFFSETS = {
            {0, 0,  3},
            {0, 0,  2},
            {0, 0,  1},
            {0, 0,  0},
            {0, 0, -1},
            {0, 0, -2},
            {0, 0, -3},
            {-1, 0, 1}, {-2, 0, 1}, {-3, 0, 1},
            { 1, 0, 1}, { 2, 0, 1}, { 3, 0, 1},
            {-1, 0, -2}, {1, 0, -2},
            {0, 1, -2},
    };

    private static final Material[] PLANE_MATERIALS = {
            Material.WHITE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.CYAN_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.CYAN_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS,
    };

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

        Vector direction = end.toVector().subtract(start.toVector());
        double length = direction.length();
        if (length <= 0.1) {
            return;
        }
        direction.normalize();

        Vector right = new Vector(-direction.getZ(), 0.0, direction.getX());

        ArmorStand vehicle = world.spawn(start, ArmorStand.class, entity -> {
            entity.setVisible(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setCustomNameVisible(false);
            entity.setBasePlate(false);
            entity.setArms(false);
        });
        state.setDropVehicle(vehicle);

        double speed = moduleConfig.getDouble("drop.speed_blocks_per_tick", 0.6);
        int interpolationTicks = Math.max(1, moduleConfig.getInt("drop.interpolation_ticks", 5));
        List<BlockDisplay> displays = spawnPlaneDisplays(world, start, direction, right, interpolationTicks);
        state.setPlaneDisplays(displays);

        for (Player player : context.getPlayers()) {
            if (player.isOnline()) {
                state.addPlanePlayer(player.getUniqueId());
                applyDropInvisibility(state, player);
                Location mountLoc = start.clone();
                mountLoc.setYaw(player.getLocation().getYaw());
                mountLoc.setPitch(player.getLocation().getPitch());
                player.teleport(mountLoc);
                player.setFallDistance(0.0f);
                player.setAllowFlight(true);
                player.setFlying(true);
            }
        }

        String taskId = "arena_" + state.getArenaId() + "_battle_royale_drop";
        String playerTaskId = "arena_" + state.getArenaId() + "_battle_royale_drop_players";
        final double[] traveled = {0.0};
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        final double stepDistance = speed * interpolationTicks;

        context.getSchedulerAPI().runTimer(taskId, () -> {
            context.getSchedulerAPI().runAtEntity(vehicle, () -> {
                if (state.isEnded()) {
                    context.getSchedulerAPI().cancelTask(taskId);
                    context.getSchedulerAPI().cancelTask(playerTaskId);
                    cleanup(state);
                    return;
                }

                if (vehicle.isDead() || !vehicle.isValid()) {
                    context.getSchedulerAPI().cancelTask(taskId);
                    context.getSchedulerAPI().cancelTask(playerTaskId);
                    cleanup(state);
                    return;
                }

                double remaining = length - traveled[0];
                double step = Math.min(stepDistance, remaining);
                traveled[0] += step;

                Location next;
                boolean finished = traveled[0] >= length - 1.0e-6;
                if (finished) {
                    next = end.clone();
                } else {
                    next = start.clone().add(direction.clone().multiply(traveled[0]));
                }
                next.setYaw(yaw);
                vehicle.teleport(next);
                teleportPlaneDisplays(state.getPlaneDisplays(), next, direction, right);

                if (finished) {
                    context.getSchedulerAPI().cancelTask(taskId);
                    context.getSchedulerAPI().cancelTask(playerTaskId);
                    movePlanePlayers(context, state, next);
                    forceDrop(context, state);
                    cleanup(state);
                }
            });
        }, 0L, interpolationTicks);

        final long[] tickCounter = {0L};
        final Vector perTickMove = direction.clone().multiply(speed);
        context.getSchedulerAPI().runTimer(playerTaskId, () -> {
            context.getSchedulerAPI().runAtEntity(vehicle, () -> {
                if (state.isEnded() || vehicle.isDead() || !vehicle.isValid()) {
                    return;
                }
                tickCounter[0]++;
                double traveledNow = Math.min(length, speed * tickCounter[0]);
                Location target = start.clone().add(direction.clone().multiply(traveledNow));
                updatePlanePlayersVelocity(context, state, target, perTickMove);
            });
        }, 1L, 1L);
    }

    public void handleDropExit(ArenaState state, Player player) {
        if (!state.isOnPlane(player.getUniqueId())) {
            return;
        }
        ItemStack previousChestplate = player.getInventory().getChestplate();
        state.removePlanePlayer(player.getUniqueId());
        player.setFlying(false);
        player.setAllowFlight(false);
        state.markDropping(player.getUniqueId(), previousChestplate);
        player.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
        player.setFallDistance(0.0f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, Integer.MAX_VALUE, 0, false, false, false));
        clearDropInvisibility(state, player);
    }

    public void handleLanding(ArenaState state, Player player) {
        if (!state.isDropping(player.getUniqueId())) {
            return;
        }

        ItemStack restore = state.restoreChestplate(player.getUniqueId());
        player.getInventory().setChestplate(restore);
        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
        player.setFallDistance(0.0f);
    }

    public void cleanup(ArenaState state) {
        for (Player player : state.getContext().getPlayers()) {
            if (state.isOnPlane(player.getUniqueId()) && player.isOnline()) {
                clearDropInvisibility(state, player);
                player.setFlying(false);
                player.setAllowFlight(false);
            }
        }
        state.clearPlanePlayers();

        ArmorStand vehicle = state.getDropVehicle();
        if (vehicle != null && vehicle.isValid()) {
            vehicle.remove();
        }
        state.setDropVehicle(null);

        List<BlockDisplay> displays = state.getPlaneDisplays();
        if (displays != null) {
            for (BlockDisplay bd : displays) {
                if (bd != null && bd.isValid()) {
                    bd.remove();
                }
            }
        }
        state.setPlaneDisplays(null);
    }

    private void forceDrop(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           ArenaState state) {
        for (UUID uid : state.getPlanePlayers()) {
            for (Player player : context.getPlayers()) {
                if (player.getUniqueId().equals(uid) && player.isOnline()) {
                    handleDropExit(state, player);
                }
            }
        }
    }

    private void movePlanePlayers(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    ArenaState state, Location planePos) {
        for (Player player : context.getPlayers()) {
            if (!state.isOnPlane(player.getUniqueId()) || !player.isOnline()) {
                continue;
            }
            Location dest = planePos.clone();
            dest.setYaw(player.getLocation().getYaw());
            dest.setPitch(player.getLocation().getPitch());
            player.teleport(dest);
            player.setFallDistance(0.0f);
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            if (!player.isFlying()) {
                player.setFlying(true);
            }
        }
    }

    private void updatePlanePlayersVelocity(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                            ArenaState state, Location target, Vector perTickMove) {
        final double driftThresholdSq = 16.0;
        for (Player player : context.getPlayers()) {
            if (!state.isOnPlane(player.getUniqueId()) || !player.isOnline()) {
                continue;
            }
            Location loc = player.getLocation();
            double dx = target.getX() - loc.getX();
            double dy = target.getY() - loc.getY();
            double dz = target.getZ() - loc.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > driftThresholdSq) {
                Location dest = target.clone();
                dest.setYaw(loc.getYaw());
                dest.setPitch(loc.getPitch());
                player.teleport(dest);
            } else {
                Vector v = perTickMove.clone();
                v.setX(v.getX() + dx * 0.2);
                v.setY(v.getY() + dy * 0.2);
                v.setZ(v.getZ() + dz * 0.2);
                player.setVelocity(v);
            }

            player.setFallDistance(0.0f);
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            if (!player.isFlying()) {
                player.setFlying(true);
            }
        }
    }

    private List<BlockDisplay> spawnPlaneDisplays(World world, Location origin,
                                                   Vector forward, Vector right,
                                                   int interpolationTicks) {
        List<BlockDisplay> displays = new ArrayList<>();
        for (int i = 0; i < PLANE_OFFSETS.length; i++) {
            int lr = PLANE_OFFSETS[i][0];
            int up = PLANE_OFFSETS[i][1];
            int lf = PLANE_OFFSETS[i][2];
            Material mat = PLANE_MATERIALS[i];
            Location loc = blockLocation(origin, forward, right, lr, up, lf);
            BlockDisplay bd = world.spawn(loc, BlockDisplay.class, entity -> {
                entity.setBlock(mat.createBlockData());
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setTeleportDuration(interpolationTicks);
            });
            displays.add(bd);
        }
        return displays;
    }

    private void teleportPlaneDisplays(List<BlockDisplay> displays, Location origin,
                                        Vector forward, Vector right) {
        if (displays == null) {
            return;
        }
        for (int i = 0; i < displays.size() && i < PLANE_OFFSETS.length; i++) {
            BlockDisplay bd = displays.get(i);
            if (bd == null || !bd.isValid()) {
                continue;
            }
            int lr = PLANE_OFFSETS[i][0];
            int up = PLANE_OFFSETS[i][1];
            int lf = PLANE_OFFSETS[i][2];
            bd.teleport(blockLocation(origin, forward, right, lr, up, lf));
        }
    }

    private Location blockLocation(Location origin, Vector forward, Vector right,
                                    int lr, int up, int lf) {
        return origin.clone().add(
                right.clone().multiply(lr)
                        .add(new Vector(0, up, 0))
                        .add(forward.clone().multiply(lf))
        );
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

}
