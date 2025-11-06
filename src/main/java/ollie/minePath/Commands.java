package ollie.minePath;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;


public class Commands implements CommandExecutor {
    private final MinePath plugin;

    public Commands(MinePath plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player commandSender) {
            switch (args[0]) {
                case "set":
                    setDelay(commandSender, args[1]);
                    break;
                case "start":
                    try {
                        plugin.startSnapshotTask();
                    } catch (IOException ignored) {}
                    sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] Successfully started the recorder");
                    break;
                case "stop":
                    try {
                        plugin.stopSnapshotTask();
                    } catch (IOException ignored) {}
                    sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] Successfully stopped the recorder");
                    break;
                case "get":
                    sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "The recorder records in intervals of " + plugin.snapshotDelay + " seconds");
                    break;
                case "clear":
                    try {
                        plugin.clearPlayerDataFile();
                    } catch (IOException ignored) {}
                    sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "The player_data.csv file has been cleared");
                    break;

            }
        }
        return true;
    }

    private void setDelay(Player sender, String newDelay) {
        try {
            plugin.snapshotDelay = Integer.parseInt(newDelay);;
            plugin.startSnapshotTask();
            sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] Snapshot delay is now " + newDelay + " seconds");
        }
        catch (Exception e) {
            sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.RED + "[MINEPATH] " + newDelay + " is not an integer value");
        }
    }
}
