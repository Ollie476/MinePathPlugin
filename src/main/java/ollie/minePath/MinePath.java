package ollie.minePath;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

record BlockInfo(BlockData data, int y) {}

public final class MinePath extends JavaPlugin {
    public long snapshotDelay;
    public long snapshotIndex;

    private final String recordingDataPath = "snapshots.csv";
    private final String teamMapPath = "teamMap.csv";
    private final String UUIDMapPath = "uuidMap.csv";
    private final String zipOutputPath = "recordingData.zip";

    private final Map<UUID, Integer> uuidMap = new HashMap<>();
    private final Map<String, Integer> worldMap = new HashMap<>();
    private final Map<Team, Integer> teamMap = new HashMap<>();
    private final Map<String, List<Location>> playerBounds = new HashMap<>();


    private static final HashMap<Material, Integer> blockMap = new HashMap<>();
    private static BukkitTask snapshotTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        InputStream in = getResource("block_accurate.csv");
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
        createFile(file);

        FileWriter writer = new FileWriter(file, append);
        writer.write(data);
        writer.close();
    }

    private BlockInfo getHighestValidBlockData(int x, int z, ChunkSnapshot snapshot, World world) {
        for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
            BlockData blockData = snapshot.getBlockData(x, y, z);
            Material material = blockData.getMaterial();
            if (material != Material.BARRIER && material != Material.AIR && material != Material.LIGHT && material != Material.STRUCTURE_VOID && material != Material.CAVE_AIR) {
                return new BlockInfo(blockData, y);
            }
        }
        int y = world.getMinHeight();
        return new BlockInfo(snapshot.getBlockData(x, y, z), y);
    }

    public void startSnapshotTask() throws IOException {
        snapshotIndex = 0;
        getConfig().set("snapshot_index", 0);
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
                    String worldName = player.getWorld().getName();

                    if (!worldMap.containsKey(worldName))
                        worldMap.put(worldName, worldMap.size());

                    if (!uuidMap.containsKey(player.getUniqueId()))
                        uuidMap.put(player.getUniqueId(), uuidMap.size());

                    Team playerTeam = null;
                    for (Team team : player.getScoreboard().getTeams()) {
                        if (team.hasPlayer(player)) {
                            playerTeam = team;
                            break;
                        }
                    }
                    int teamIndex = 0;

                    if (playerTeam != null && !playerTeam.getEntries().isEmpty()) {
                        if (!teamMap.containsKey(playerTeam)) {
                            teamMap.put(playerTeam, teamMap.size() + 1);
                        }
                        teamIndex = teamMap.get(playerTeam);
                    }

                    fileAdditions.append(snapshotIndex)
                            .append(",")
                            .append(uuidMap.get(player.getUniqueId()))
                            .append(",")
                            .append(Math.round(player.getLocation().getX() * 10))
                            .append(",")
                            .append(Math.round(player.getLocation().getZ() * 10))
                            .append(",")
                            .append(worldMap.get(worldName))
                            .append(",")
                            .append(teamIndex)
                            .append(",")
                            .append(player.getGameMode().getValue())
                            .append("\n");

                    if (playerBounds.get(worldName) == null)
                        playerBounds.put(worldName, new ArrayList<>());

                    playerBounds.get(worldName).add(player.getLocation());
                }

                try {
                    addToCSVFile(recordingDataPath, fileAdditions.toString(), true);
                } catch (IOException ignored) {}
                snapshotIndex++;
                getConfig().set("snapshot_index", snapshotIndex);
                saveConfig();
            }
        }.runTaskTimer(this, 0, Math.max(snapshotDelay, 1));
    }

    public void stopSnapshotTask() throws IOException {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            getConfig().set("is_playing", false);
            saveConfig();

            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] Successfully stopped the recorder");
            addToCSVFile(teamMapPath, "", false);
            uuidMap.entrySet().stream() // UUID map file
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(uuidEntry-> {
                        String key = uuidEntry.getKey().toString();
                        Integer value = uuidEntry.getValue();
                        try {
                            addToCSVFile(UUIDMapPath, value + "," + Bukkit.getOfflinePlayer(uuidEntry.getKey()).getName() + "," + key + "\n", true);
                        } catch (IOException ignore) {}
                    });

            addToCSVFile(teamMapPath, "0,~\n", false); // base team

            teamMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(teamEntry -> {
                        String key = teamEntry.getKey().getName();
                        Integer value = teamEntry.getValue();

                        try {
                            addToCSVFile(teamMapPath, value + "," + key + "," + teamEntry.getKey().getColor().name().toLowerCase() + "\n", true);
                        } catch (IOException ignore) {}
                    });

            int worldAmount = playerBounds.size();
            int worldCount = 0;

            for (String worldName : playerBounds.keySet()) {
                World world = Bukkit.getWorld(worldName);

                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

                for (Location loc : playerBounds.get(worldName)) {
                    if (loc.getBlockX() < minX) minX = loc.getBlockX();
                    if (loc.getBlockZ() < minZ) minZ = loc.getBlockZ();
                    if (loc.getBlockX() > maxX) maxX = loc.getBlockX();
                    if (loc.getBlockZ() > maxZ) maxZ = loc.getBlockZ();
                }

                if (minZ == maxZ && minX == maxX)
                    continue;

                if (world != null)
                    makeMapFile(world, minX, minZ, maxX, maxZ, worldAmount == worldCount+1);

                sendToOperators("---- " + worldName + " ----");
                sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] TopLeft: (" + minX + ", " + minZ + ")");
                sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] BottomRight: (" + maxX + ", " + maxZ + ")");
                sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] " + (maxX - minX+1) + "x" + (maxZ - minZ+1));
                sendToOperators("----" + "-".repeat(worldName.length()) + "----");
                worldCount++;
            }

            playerBounds.clear();

        }
    }

    private void createFile(File file) {
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException ignored) {}
        }
    }


    public void makeMapFile(World world, int minX, int minZ, int maxX, int maxZ, boolean isFinalWorldFile) throws IOException {
        long startTime = System.nanoTime();

        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;

        int rowCount = (maxChunkZ - minChunkZ) + 1;
        int rowLength = (maxChunkX - minChunkX) + 1;


        ChunkSnapshot[][] chunkSnapshots = new ChunkSnapshot[rowCount][rowLength];

        File file = new File(getDataFolder(), world.getName() + ".csv");
        createFile(file);


        for (int z = minChunkZ; z <= maxChunkZ; z++) {
            int indexZ = z - minChunkZ;
            for (int x = minChunkX; x <= maxChunkX; x++) {
                int indexX = x - minChunkX;
                chunkSnapshots[indexZ][indexX] = world.getChunkAt(x,z).getChunkSnapshot(true, false, false);
            }
        }

        String initData = minX + "|" + minZ + "," + maxX + "|" + maxZ + "\n";

        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                BlockInfo prevBlockInfo = null;
                int blockCount = 1;

                for (ChunkSnapshot[] chunkRow: chunkSnapshots) {
                    for (int z = 0; z < 16; z++)
                        for (ChunkSnapshot chunk: chunkRow) {
                            int chunkZ = chunk.getZ() * 16;

                            int worldZ = chunkZ + z;

                            if (minZ > worldZ || maxZ < worldZ)
                                continue;

                            int chunkX = chunk.getX() * 16;

                            for (int x = 0; x < 16; x++) {
                                int worldX = chunkX + x;

                                if (minX > worldX || maxX < worldX)
                                    continue;

                                BlockInfo blockInfo = getHighestValidBlockData(x, z, chunk, world);

                                if (prevBlockInfo != null && Objects.equals(blockInfo, prevBlockInfo)) {
                                    blockCount++;
                                }
                                else {
                                    if (prevBlockInfo != null) { // write block info
                                        Material mat = prevBlockInfo.data().getMaterial();

                                        int id;

                                        if (blockMap.containsKey(mat)) {
                                            id = blockMap.get(mat);
                                        }
                                        else {
                                            Bukkit.getLogger().warning("[MINEPATH] " + mat.name() + " is not supported by Minepath");
                                            id = blockMap.get(Material.AIR);
                                        }

                                        sb.append(id)
                                                .append(",")
                                                .append(prevBlockInfo.y())
                                                .append(",")
                                                .append(blockCount)
                                                .append("\n");
                                    }
                                    blockCount = 1;
                                }

                                prevBlockInfo = blockInfo;

                            }
                        }
                }
                // File writing logic here
                BufferedWriter writer = null;
                try {
                    writer = new BufferedWriter(new FileWriter(file, true));
                    writer.write(initData);
                    writer.flush();
                    writer.write(sb.toString());
                    writer.flush();
                } catch (IOException ignored) {}

                if (prevBlockInfo != null) {
                    try {
                        Material mat = prevBlockInfo.data().getMaterial();

                        int id;

                        if (blockMap.containsKey(mat)) {
                            id = blockMap.get(mat);
                        } else {
                            Bukkit.getLogger().warning("[MINEPATH] " + mat.name() + " is not supported by Minepath");
                            id = blockMap.get(Material.AIR);
                        }

                        writer.write(id + "," + prevBlockInfo.y() + "," + blockCount);
                        writer.close();
                    } catch (IOException ignored) {}
                }

                sendToOperators(String.format(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] All processes have been completed for {%s}, took %.3f second(s)", world.getName(), ((System.nanoTime() - startTime) / 1_000_000_000d)));

                File zipOutputFile = new File(getDataFolder(), zipOutputPath);
                createFile(zipOutputFile);
                if (isFinalWorldFile) {
                    try {
                        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipOutputFile));
                        byte[] bytes = new byte[65536];

                        File[] files = getDataFolder().listFiles();

                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile()) {
                                    if (!file.getName().equals(zipOutputPath)) {
                                        ZipEntry entry = new ZipEntry(file.getName());
                                        out.putNextEntry(entry);

                                        try (FileInputStream in = new FileInputStream(file)) {
                                            int length;
                                            while ((length = in.read(bytes)) >= 0) {
                                                out.write(bytes, 0, length);
                                            }
                                        }

                                        out.closeEntry();
                                    }
                                    String filePath = file.getAbsolutePath();

                                    if (filePath.endsWith(".csv")) {
                                        if (!file.delete()) {
                                            getLogger().warning("Failed to delete: " + filePath);
                                        }
                                    }
                                }
                            }
                            out.close();
                            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] All Processes have now been complete! Your data is now ready.");
                        }
                    } catch (IOException ignored) {
                        sendToOperators(ChatColor.BOLD + "" + ChatColor.RED + "[MINEPATH] An error has occurred compress your data");
                    }

                }
            }
        });
    }


    public void resetRecordingFile() throws IOException {
        sendToOperators(ChatColor.BOLD+ "" + ChatColor.GOLD + "[MINEPATH] The recording data has been cleared");

        playerBounds.clear();
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
            sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] Snapshot delay is now " + newDelay + " tick(s)");
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