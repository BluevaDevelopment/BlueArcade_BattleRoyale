package net.blueva.arcade.modules.battleroyale.support.loot;

import org.bukkit.Location;
import org.bukkit.Material;

public class TrackedChest {

    private final Location location;
    private final Material material;

    public TrackedChest(Location location, Material material) {
        this.location = location;
        this.material = material;
    }

    public Location getLocation() {
        return location;
    }

    public Material getMaterial() {
        return material;
    }
}
