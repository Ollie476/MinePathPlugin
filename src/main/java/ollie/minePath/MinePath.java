package ollie.minePath;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MinePath extends JavaPlugin {
    public long snapshotDelay = 30;
    public long snapshotIndex = 0;

    private final String filePath = "player_data.csv";

    private static BukkitTask snapshotTask;

    @Override
    public void onEnable() {
        getCommand("minepath").setExecutor(new Commands(this));
        getCommand("minepath").setTabCompleter(new TabCompleter() {
            @Override
            public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {
                String[] results = {"start", "stop", "get", "set", "clear"};
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

        snapshotTask = new BukkitRunnable() {
            @Override
            public void run() {
                StringBuilder fileAdditions = new StringBuilder();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    fileAdditions.append(snapshotIndex).append(",").append(player.getName()).append(",").append(player.getLocation().getBlockX()).append(",").append(player.getLocation().getBlockZ()).append("\n");
                }

                try {
                    addToPlayerDataFile(fileAdditions.toString());
                } catch (IOException ignored) {}
                snapshotIndex++;
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
        snapshotIndex = 0;
    }
}
