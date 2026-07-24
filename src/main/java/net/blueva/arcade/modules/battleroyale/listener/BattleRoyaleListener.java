package net.blueva.arcade.modules.battleroyale.listener;

import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GamePhase;
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
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class BattleRoyaleListener implements Listener {

    private final BattleRoyaleGame game;

    public BattleRoyaleListener(BattleRoyaleGame game) {
        this.game = game;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);

        if (context == null || !context.isPlayerPlaying(player)) {
            return;
        }

        if (event.getTo() == null) {
            return;
        }

        if (game.isDropping(context, player) && player.isOnGround()) {
            game.handleDropLanding(context, player);
            return;
        }
        ArenaState state = game.getArenaState(context);

        if (context.getPhase() != GamePhase.PLAYING) {
            return;
        }

        if (game.isDropping(context, player) || (state != null && state.isOnPlane(player.getUniqueId()))) {
            return;
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);
        if (context == null) {
            return;
        }
        ArenaState state = game.getArenaState(context);
        if (state != null && state.isOnPlane(player.getUniqueId())) {
            game.handlePlaneSneakToggle(context, player, event.isSneaking());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        
        Material type = event.getClickedBlock().getType();
        
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.ENDER_CHEST) {
            Player player = event.getPlayer();
            GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                    game.getContext(player);
                    
            if (context == null || !context.isPlayerPlaying(player)) {
                return;
            }

            if (context.getPhase() != GamePhase.PLAYING) {
                event.setCancelled(true);
                return;
            }

            ArenaState state = game.getArenaState(context);
            if (state != null && state.isPlayerPlacedChest(event.getClickedBlock().getLocation())) {
                // Player-placed chests behave as plain vanilla chests
                return;
            }

            if (game.isChestLooted(context, player, event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }

            event.setCancelled(true);
            game.handleChestLoot(context, player, event.getClickedBlock());
            return;
        }
        
        if (isInteractiveBlock(type)) {
            return;
        }
    }
    
    private boolean isInteractiveBlock(Material type) {
        String name = type.name();
        return name.endsWith("_DOOR")
                || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_TRAPDOOR")
                || type == Material.LEVER;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);

        if (context == null || !context.isPlayerPlaying(player)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        ArenaState state = game.getArenaState(context);

        if (game.getModuleConfig().getBoolean("block_rules.break_only_player_placed", false)
                && (state == null || !state.isPlayerPlacedBlock(event.getBlock().getLocation()))) {
            event.setCancelled(true);
            return;
        }

        Material type = event.getBlock().getType();
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.ENDER_CHEST) {
            if (state != null && state.isPlayerPlacedChest(event.getBlock().getLocation())) {
                // Player-placed chests break as plain vanilla blocks
            } else {
                // Breaking a chest loots it directly, same as interacting with it
                event.setCancelled(true);
                game.handleChestLoot(context, player, event.getBlock());
                return;
            }
        }

        if (state == null) {
            event.setCancelled(true);
            return;
        }

        if (!state.hasRespawnRegion()) {
            event.setCancelled(true);
            return;
        }

        state.untrackPlacedBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);

        if (context == null || !context.isPlayerPlaying(player)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        ArenaState state = game.getArenaState(context);
        if (state == null) {
            event.setCancelled(true);
            return;
        }

        if (!state.hasRespawnRegion()) {
            event.setCancelled(true);
            return;
        }

        state.trackPlacedBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        game.handleChestExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        game.handleChestExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(target);
        if (context == null || !context.isPlayerPlaying(target)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || !context.isPlayerPlaying(attacker)) {
            event.setCancelled(true);
            return;
        }

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            TeamInfo<Player, Material> attackerTeam = teamsAPI.getTeam(attacker);
            TeamInfo<Player, Material> targetTeam = teamsAPI.getTeam(target);
            if (attackerTeam != null && targetTeam != null && attackerTeam.getId().equalsIgnoreCase(targetTeam.getId())) {
                event.setCancelled(true);
                return;
            }
        }

        double finalHealth = target.getHealth() - event.getFinalDamage();
        if (finalHealth > 0) {
            return;
        }

        event.setCancelled(true);
        game.handleKill(context, attacker, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGenericDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(target);
        if (context == null || !context.isPlayerPlaying(target)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        if (game.isDropping(context, target)) {
            event.setCancelled(true);
            return;
        }

        double finalHealth = target.getHealth() - event.getFinalDamage();
        if (finalHealth > 0) {
            return;
        }

        event.setCancelled(true);
        game.handleNonCombatDeath(context, target);
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }

        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }

        return null;
    }
}
