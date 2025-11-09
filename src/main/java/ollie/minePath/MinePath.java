package ollie.minePath;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class MinePath extends JavaPlugin {
    public long snapshotDelay;
    public long snapshotIndex;

    private final String filePath = "player_data.csv";
    private final List<Location> playerLocations = new ArrayList<>();

    private static BukkitTask snapshotTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getConfig().contains("delay"))
            getConfig().set("delay", 30);
        if (!getConfig().contains("snapshot_index"))
            getConfig().set("snapshot_index", 0);
        if (!getConfig().contains("is_playing"))
            getConfig().set("is_playing", false);

        saveConfig();

        snapshotDelay = getConfig().getInt("delay");
        snapshotIndex = getConfig().getInt("snapshot_index");
        boolean is_playing = getConfig().getBoolean("is_playing");

        if (is_playing) {
            try {
                startSnapshotTask();
            } catch (IOException ignored) {}
        }

        getCommand("minepath").setExecutor(new Commands(this));
        getCommand("minepath").setTabCompleter(new TabCompleter() {
            @Override
            public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {
                String[] results = {"start", "stop", "info", "set_delay", "reset"};
                List<String> out = new ArrayList<>();

                if (args.length == 1) {
                    for (String result : results)
                        if (result.toLowerCase().startsWith(args[0].toLowerCase()))
                            out.add(result);
                    return out;
                }
                return List.of();
            }
        });
    }

    public void startSnapshotTask() throws IOException {
        if (snapshotTask != null) {
            snapshotTask.cancel();
        }

        getConfig().set("is_playing", true);
        saveConfig();
        sendToOperators(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] Successfully started the recorder");

        snapshotTask = new BukkitRunnable() {
            @Override
            public void run() {
                StringBuilder fileAdditions = new StringBuilder();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    fileAdditions.append(snapshotIndex).append(",").append(player.getName()).append(",").append(player.getLocation().getBlockX()).append(",").append(player.getLocation().getBlockZ()).append("\n");
                    playerLocations.add(player.getLocation());
                }

                try {
                    addToPlayerDataFile(fileAdditions.toString());
                } catch (IOException ignored) {}
                snapshotIndex++;
                getConfig().set("snapshot_index", snapshotIndex);
                saveConfig();
            }

            public void addToPlayerDataFile(String data) throws IOException {
                if (!getDataFolder().exists())
                    getDataFolder().mkdirs();

                File file = new File(getDataFolder(), filePath);

                if (!file.exists()) {
                    try {
                        file.createNewFile();
                    } catch (IOException ignored) {}
                }

                FileWriter writer = new FileWriter(file, true);
                writer.write(data);
                writer.close();
            }

        }.runTaskTimer(this, 0, snapshotDelay * 20);
    }

    public void stopSnapshotTask() throws IOException {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            getConfig().set("is_playing", false);
            saveConfig();

            double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;

            for (Location loc : playerLocations) {
                if (loc.getBlockX() < minX) minX = loc.getBlockX();
                if (loc.getBlockZ() < minZ) minZ = loc.getBlockZ();
                if (loc.getBlockX() > maxX) maxX = loc.getBlockX();
                if (loc.getBlockZ() > maxZ) maxZ = loc.getBlockZ();
            }
            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] Successfully stopped the recorder");
            sendToOperators("--------------------");
            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] TopLeft: (" + minX + ", " + minZ + ")");
            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] BottomRight: (" + maxX + ", " + maxZ + ")");
            sendToOperators("--------------------");
        }
    }

    public void clearPlayerDataFile() throws IOException {
        if (!getDataFolder().exists())
            getDataFolder().mkdirs();

        File file = new File(getDataFolder(), filePath);

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException ignored) {}
        }

        FileWriter writer = new FileWriter(file, false);
        writer.write("");
        writer.close();

        sendToOperators(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] The recording data has been cleared");

        playerLocations.clear();
        snapshotIndex = 0;
        getConfig().set("snapshot_index", 0);
        getConfig().set("is_playing", false);
        stopSnapshotTask();
        saveConfig();
    }

    public void setDelay(Player sender, String newDelay) {
        try {
            snapshotDelay = Integer.parseInt(newDelay);
            getConfig().set("delay", snapshotDelay);
            saveConfig();
            sendToOperators(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] Snapshot delay is now " + newDelay + " seconds");
        }
        catch (Exception e) {
            sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.RED + "[MINEPATH] " + newDelay + " is not an integer value");
        }
    }

    private void sendToOperators(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp())
                player.sendMessage(message);
        }
    }
}
