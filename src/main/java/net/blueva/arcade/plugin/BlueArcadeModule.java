/*
 * This class is not required for the module itself. It only exists to allow
 * publishing the jar as a Spigot plugin, showing a clear warning that this is
 * a Blue Arcade module and that it requires the main Blue Arcade plugin.
 */
package net.blueva.arcade.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

public class BlueArcadeModule extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
            String jarName = getFile() != null ? getFile().getName() : "unknown";
            getServer().getConsoleSender().sendMessage(ChatColor.RED + "==============================================");
            getServer().getConsoleSender().sendMessage(ChatColor.RED + "THIS IS NOT A PLUGIN. THIS IS A BLUE ARCADE MODULE.");
            getServer().getConsoleSender().sendMessage(ChatColor.RED + "YOU NEED THE BLUE ARCADE PLUGIN FOR THIS MODULE TO WORK.");
            getServer().getConsoleSender().sendMessage(ChatColor.RED + "Module jar: " + jarName);
            getServer().getConsoleSender().sendMessage(ChatColor.RED + "More information: https://blueva.net/");
            getServer().getConsoleSender().sendMessage(ChatColor.RED + "==============================================");
            getServer().getPluginManager().disablePlugin(this);
        }, 1L);
    }
}
