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
                case "set_delay":
                    plugin.setDelay(commandSender, args[1]);
                    break;
                case "start":
                    try {
                        plugin.startSnapshotTask();
                    } catch (IOException ignored) {}
                    break;
                case "stop":
                    try {
                        plugin.stopSnapshotTask();
                    } catch (IOException ignored) {}
                    break;
                case "info":
                    sender.sendMessage("--------------------");
                    sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] Recording intervals: " + plugin.snapshotDelay + " second(s)");
                    sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] Current snapshot index: " + plugin.snapshotIndex);
                    if (plugin.getConfig().getBoolean("is_playing"))
                        sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] The recorder is on");
                    else
                        sender.sendMessage(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] The recorder is off");
                    sender.sendMessage("--------------------");
                    break;
                case "reset":
                    try {
                        plugin.clearPlayerDataFile();
                    } catch (IOException ignored) {}
                    break;
            }
        }
        return true;
    }
}
