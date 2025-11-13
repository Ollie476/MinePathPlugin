package ollie.minePath;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

record BlockInfo(int id, int y) {}

public final class MinePath extends JavaPlugin {
    public long snapshotDelay;
    public long snapshotIndex;

    private final String recordingDataPath = "recording_data.csv";
    private final List<Location> playerLocations = new ArrayList<>();

    private static BukkitTask snapshotTask;
    private static final HashMap<Material, Integer> blockMap = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        InputStream in = getResource("block_mapping.csv");
        if (in != null) {
            try (Scanner scanner = new Scanner(in)) {
                int lineNumber = 1;
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    blockMap.put(Material.getMaterial(line.split(",")[0].toUpperCase()), lineNumber);
                    lineNumber++;
                }
            }
        }


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

    public void addToCSVFile(String path, String data, boolean append) throws IOException {
        if (!getDataFolder().exists())
            getDataFolder().mkdirs();

        File file = new File(getDataFolder(), path);

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException ignored) {}
        }

        FileWriter writer = new FileWriter(file, append);
        writer.write(data);
        writer.close();
    }

    public void startSnapshotTask() throws IOException {
        resetRecordingFile();
        addToCSVFile(recordingDataPath, "", false);
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
                    addToCSVFile(recordingDataPath, fileAdditions.toString(), true);
                } catch (IOException ignored) {}
                snapshotIndex++;
                getConfig().set("snapshot_index", snapshotIndex);
                saveConfig();
            }
        }.runTaskTimer(this, 0, snapshotDelay * 20);
    }

    public void stopSnapshotTask() throws IOException {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            getConfig().set("is_playing", false);
            saveConfig();

            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (Location loc : playerLocations) {
                if (loc.getBlockX() < minX) minX = loc.getBlockX();
                if (loc.getBlockZ() < minZ) minZ = loc.getBlockZ();
                if (loc.getBlockX() > maxX) maxX = loc.getBlockX();
                if (loc.getBlockZ() > maxZ) maxZ = loc.getBlockZ();
            }

            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] Successfully stopped the recorder");

            World overworld = Bukkit.getWorld("world");

            sendToOperators("--------------------");
            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] TopLeft: (" + minX + ", " + minZ + ")");
            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] BottomRight: (" + maxX + ", " + maxZ + ")");
            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] " + (maxX - minX) + "x" + (maxZ - minZ));
            sendToOperators("--------------------");

            makeMapFile(overworld, minX, minZ, maxX, maxZ);
        }
    }

    public void makeMapFile(World world, int minX, int minZ, int maxX, int maxZ) throws IOException {
        final AtomicLong startTime = new AtomicLong(System.nanoTime());

        String initData = "$\n" + minX + "|" + minZ + "," + maxX + "|" + maxZ + "\n";
        File file = new File(getDataFolder(), recordingDataPath);

        if (!getDataFolder().exists())
            getDataFolder().mkdirs();
        if (!file.exists())
            file.createNewFile();

        final BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
        writer.write(initData);

        long dx = maxX - minX + 1;
        long dz = maxZ - minZ + 1;

        int minBatch = 20;
        int maxBatch = 200;
        long targetBlocks = 100_000;;
        int batchSize = (int)Math.max(minBatch, Math.min(maxBatch, targetBlocks / dx));

        final Queue<List<BlockInfo>> batchQueue = new ConcurrentLinkedQueue<>(); // A batch, in this context, means a split of data (in a given number of rows)

        final AtomicReference<BukkitTask> blockRef = new AtomicReference<>();
        final AtomicReference<BukkitTask> writeRef = new AtomicReference<>();

        final AtomicInteger currentZ = new AtomicInteger(minZ);
        final AtomicBoolean hasAllBlockData = new AtomicBoolean(false);

        final long blocksPerBatch = batchSize * dx;
        int execBaseline = 10500;
        int executeTime = Math.max(1, (int)(blocksPerBatch / execBaseline));

        int delayBaseline = 5000;

        int delay;
        if (blocksPerBatch <= 50_000)
            delay = Math.min(40, Math.max(2, (int)Math.sqrt((double) blocksPerBatch / delayBaseline)));
        else
            delay = Math.min(200, Math.max(20, (int)(blocksPerBatch / delayBaseline))) * 3;

        long totalRows = (maxZ - minZ + 1);
        long batches = (long) Math.ceil((double) totalRows / batchSize);
        long estimatedTime = (batches * (delay + executeTime)) / 20;

        sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] Fetching block data...");
        sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] Estimated time -> "+ estimatedTime + " seconds for " + (dz+1)*2 / batchSize  + " batches of size " + batchSize);

        BukkitTask blockTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                int startZ = currentZ.get();
                int endZ = Math.min(startZ + batchSize, maxZ);

                List<BlockInfo> batch = new ArrayList<>((int) (batchSize * dx));

                for (int z = startZ; z <= endZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        Block highestBlock = world.getHighestBlockAt(x, z);
                        Integer id = blockMap.get(highestBlock.getType());
                        if (id == null || id > blockMap.size())
                            Bukkit.getLogger().warning(highestBlock.getType().toString().toLowerCase());
                        else
                            batch.add(new BlockInfo(id, highestBlock.getY()));
                    }
                }

                batchQueue.add(batch);

                if (currentZ.get() > maxZ) {
                    blockRef.get().cancel();
                    hasAllBlockData.set(true);
                } else {
                    currentZ.set(endZ + 1);
                }
            }
        }, 0, delay);

        blockRef.set(blockTask);


        BukkitTask writeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                List<BlockInfo> batch = batchQueue.poll();
                if (batch != null) {
                    StringBuilder sb = new StringBuilder(batch.size() * 8);
                    for (BlockInfo info : batch) {
                        sb.append(info.id()).append(",").append(info.y()).append("\n");
                    }
                    try {
                        writer.write(sb.toString());
                        writer.flush();
                    } catch (IOException ignored) {}
                }
                else {
                    if (!hasAllBlockData.get())
                        return;
                    sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] All processes have been completed, took " + ((System.nanoTime() - startTime.get())/1_000_000_000) + " second(s)");
                    try {
                        writer.close();
                    } catch (IOException ignored) {}

                    writeRef.get().cancel();
                }
            }
        }, 0L, 5L);

        writeRef.set(writeTask);
    }


    public void resetRecordingFile() throws IOException {
        sendToOperators(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] The recording data has been cleared");

        playerLocations.clear();
        snapshotIndex = 0;
        getConfig().set("snapshot_index", 0);
        getConfig().set("is_playing", false);
        addToCSVFile(recordingDataPath, "", false);
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
