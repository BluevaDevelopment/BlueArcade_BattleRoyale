package net.blueva.arcade.modules.battleroyale.setup;

import net.blueva.arcade.api.setup.GameSetupHandler;
import net.blueva.arcade.api.setup.SetupContext;
import net.blueva.arcade.api.setup.TabCompleteContext;
import net.blueva.arcade.api.setup.TabCompleteResult;
import net.blueva.arcade.modules.battleroyale.BattleRoyaleModule;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class BattleRoyaleSetup implements GameSetupHandler {

    private final BattleRoyaleModule module;

    public BattleRoyaleSetup(BattleRoyaleModule module) {
        this.module = module;
    }

    @Override
    public boolean handle(SetupContext context) {
        return handleInternal(castSetupContext(context));
    }

    private boolean handleInternal(SetupContext<Player, CommandSender, Location> context) {
        String subcommand = context.getArg(context.getStartIndex() - 1);
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
        return List.of("team", "region");
    }

    private boolean handleTeamConfig(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(2)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.usage"));
            return true;
        }

        String setting = context.getHandlerArg(0);
        if (setting == null || (!setting.equalsIgnoreCase("count") && !setting.equalsIgnoreCase("size"))) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.usage"));
            return true;
        }

        String valueRaw = context.getHandlerArg(1);
        if (valueRaw == null || !isNumber(valueRaw)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    module.getCoreConfig().getLanguage(context.getPlayer(), "admin_commands.errors.invalid_number")
                            .replace("{value}", valueRaw == null ? "" : valueRaw));
            return true;
        }

        int value = Integer.parseInt(valueRaw);
        if (value <= 0) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.invalid_value")
                            .replace("{setting}", setting));
            return true;
        }

        if (setting.equalsIgnoreCase("count") && value < 2) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.invalid_count"));
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
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.invalid_limit")
                            .replace("{max_players}", String.valueOf(maxPlayers)));
            return true;
        }

        context.getData().setTeamConfig(teamCount, teamSize);
        context.getData().save();

        context.getMessagesAPI().sendRaw(context.getPlayer(),
                getSetupMessage(context.getPlayer(), "team.success")
                        .replace("{game}", context.getGameId())
                        .replace("{arena_id}", String.valueOf(context.getArenaId()))
                        .replace("{setting}", setting.toLowerCase())
                        .replace("{value}", String.valueOf(value)));
        return true;
    }

    private boolean handleRegion(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(1)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.usage"));
            return true;
        }

        String action = context.getHandlerArg(0);
        if (action == null) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.usage"));
            return true;
        }

        if ("clear".equalsIgnoreCase(action)) {
            context.getData().remove("game.play_area");
            context.getData().remove("regeneration.regions");
            context.getData().save();
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.cleared"));
            return true;
        }

        if (!"set".equalsIgnoreCase(action)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.usage"));
            return true;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return true;
        }

        if (!context.getSelection().hasCompleteSelection(player)) {
            context.getMessagesAPI().sendRaw(player,
                    getSetupMessage(context.getPlayer(), "region.must_use_stick"));
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

        context.getMessagesAPI().sendRaw(player,
                getSetupMessage(context.getPlayer(), "region.set")
                        .replace("{blocks}", String.valueOf(blocks))
                        .replace("{x}", String.valueOf(x))
                        .replace("{y}", String.valueOf(y))
                        .replace("{z}", String.valueOf(z)));
        return true;
    }

    private String getSetupMessage(Player player, String key) {
        String message = module.getModuleConfig().getTranslation(player, "setup_messages." + key);
        if (message == null) {
            return "";
        }
        return message;
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
