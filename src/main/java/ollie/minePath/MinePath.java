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

record BlockInfo(BlockData data, int y) {}

public final class MinePath extends JavaPlugin {
    public long snapshotDelay;
    public long snapshotIndex;

    private final String recordingDataPath = "recording_data.csv";
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

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException ignored) {}
        }

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
                            .append(player.getLocation().getBlockX())
                            .append(",")
                            .append(player.getLocation().getBlockZ())
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

            addToCSVFile(recordingDataPath, "£\n", true); // £ = UUID data map

            uuidMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(uuidEntry-> {
                        String key = uuidEntry.getKey().toString();
                        Integer value = uuidEntry.getValue();
                        try {
                            addToCSVFile(recordingDataPath, value + "," + Bukkit.getOfflinePlayer(uuidEntry.getKey()).getName() + "," + key + "\n", true);
                        } catch (IOException ignore) {}
                    });

            addToCSVFile(recordingDataPath, "?\n0,~\n", true); // £ = team data map

            teamMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(teamEntry -> {
                        String key = teamEntry.getKey().getName();
                        Integer value = teamEntry.getValue();

                        try {
                            addToCSVFile(recordingDataPath, value + "," + key + "," + teamEntry.getKey().getColor().name().toLowerCase() + "\n", true);
                        } catch (IOException ignore) {}
                    });


            addToCSVFile(recordingDataPath, "$\n", true); // $ = world data map

            worldMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(worldEntry -> {
                        String key = worldEntry.getKey();
                        Integer value = worldEntry.getValue();
                        try {
                            addToCSVFile(recordingDataPath, value + "," + key + "\n", true);
                        } catch (IOException ignore) {}
                    });


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
                    makeMapFile(world, minX, minZ, maxX, maxZ);

                sendToOperators("---- " + worldName + " ----");
                sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] TopLeft: (" + minX + ", " + minZ + ")");
                sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] BottomRight: (" + maxX + ", " + maxZ + ")");
                sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] " + (maxX - minX+1) + "x" + (maxZ - minZ+1));
                sendToOperators("----" + "-".repeat(worldName.length()) + "----");
            }

            playerBounds.clear();
        }
    }


    public void makeMapFile(World world, int minX, int minZ, int maxX, int maxZ) throws IOException {
        long startTime = System.nanoTime();

        List<List<ChunkSnapshot>> chunkSnapshots = new ArrayList<>();

        for (int z = Math.floorDiv(minZ,16); z <= Math.floorDiv(maxZ,16); z++) {
            List<ChunkSnapshot> chunkRow = new ArrayList<>();
            for (int x = Math.floorDiv(minX, 16); x <= Math.floorDiv(maxX, 16); x++) {
                chunkRow.add(world.getChunkAt(x,z).getChunkSnapshot());
            }
            chunkSnapshots.add(chunkRow);
        }

        String initData = "#" + worldMap.get(world.getName()) + "\n" + minX + "|" + minZ + "," + maxX + "|" + maxZ + "\n";

        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                BlockInfo prevBlockInfo = null;
                int blockCount = 1;
                for (List<ChunkSnapshot> chunkRow : chunkSnapshots)
                    for (int z = 0; z < 16; z++) {
                        for (ChunkSnapshot snapshot : chunkRow) {

                            int chunkX = snapshot.getX() * 16;
                            int chunkZ = snapshot.getZ() * 16;

                            int worldZ = chunkZ + z;

                            if (minZ > worldZ || maxZ < worldZ)
                                continue;

                            for (int x = 0; x < 16; x++) {
                                int worldX = chunkX + x;

                                if (minX > worldX || maxX < worldX)
                                    continue;

                                BlockInfo blockInfo = getHighestValidBlockData(x, z, snapshot, world);

                                if (prevBlockInfo != null && Objects.equals(blockInfo, prevBlockInfo))
                                    blockCount++;
                                else {
                                    if (prevBlockInfo != null) {
                                        Material mat = prevBlockInfo.data().getMaterial();
                                        int id = blockMap.get(mat);

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

                File file = new File(getDataFolder(), recordingDataPath);
                BufferedWriter writer = null;
                try {
                    writer = new BufferedWriter(new FileWriter(file, true));
                } catch (IOException ignore) {}

                try {
                    writer.write(initData);
                    writer.flush();
                    writer.write(sb.toString());
                    writer.flush();
                } catch (IOException ignored) {}

                if (prevBlockInfo != null) {
                    try {
                        Material mat = prevBlockInfo.data().getMaterial();
                        int id = blockMap.get(mat);
                        writer.write(id + "," + prevBlockInfo.y() + "," + blockCount + "\n");
                    } catch (IOException ignored) {
                    }
                }

                try {
                    writer.close();
                } catch (IOException ignored) {}


                sendToOperators(String.format(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] All processes have been completed for {%s}, took %.3f second(s)", world.getName(), ((System.nanoTime() - startTime) / 1_000_000_000d)));
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

//    public void makeMapFile(World world, int minX, int minZ, int maxX, int maxZ) throws IOException {
//        final AtomicLong startTime = new AtomicLong(System.nanoTime());
//
//        String initData = "#" + worldMap.get(world.getName()) + "\n" + minX + "|" + minZ + "," + maxX + "|" + maxZ + "\n";
//        File file = new File(getDataFolder(), recordingDataPath);
//
//        final BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
//        writer.write(initData);
//
//        long dx = maxX - minX + 1;
//
//        int minBatch = 20;
//        int maxBatch = 200;
//        long targetBlocks = 100_000;;
//        int batchSize = (int)Math.max(minBatch, Math.min(maxBatch, targetBlocks / dx));
//
//        final Queue<List<BlockInfo>> batchQueue = new ConcurrentLinkedQueue<>(); // A batch, in this context, means a split of data (in a given number of rows)
//
//        final AtomicReference<BukkitTask> blockRef = new AtomicReference<>();
//        final AtomicReference<BukkitTask> writeRef = new AtomicReference<>();
//
//        final AtomicInteger currentZ = new AtomicInteger(minZ);
//        final AtomicBoolean hasAllBlockData = new AtomicBoolean(false);
//
//        final long blocksPerBatch = batchSize * dx;
//        int delayBaseline = 5000;
//
//        int delay;
//        if (blocksPerBatch <= 50_000)
//            delay = Math.min(40, Math.max(2, (int)Math.sqrt((double) blocksPerBatch / delayBaseline)));
//        else
//            delay = Math.min(200, Math.max(20, (int)(blocksPerBatch / delayBaseline))) * 3;
//
//
//        sendToOperators(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] Fetching block data...");
//
//        BukkitTask blockTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
//            @Override
//            public void run() {
//                int startZ = currentZ.get();
//                int endZ = Math.min(startZ + batchSize, maxZ);
//
//                List<BlockInfo> batch = new ArrayList<>((int) (batchSize * dx));
//
//                for (int z = startZ; z <= endZ; z++) {
//                    for (int x = minX; x <= maxX; x++) {
//                        Block highestBlock = getHighestValidMaterial(x, z, world);
//
//                        if (highestBlock != null) {
//                            Integer id = blockMap.get(highestBlock.getType());
//
//                            if (id == null || id > blockMap.size()) {
//                                Bukkit.getLogger().warning(highestBlock.getType().toString().toLowerCase());
//                                batch.add(new BlockInfo(blockMap.get(Material.VOID_AIR), -64));
//                            }
//                            else
//                                batch.add(new BlockInfo(id, highestBlock.getY()));
//                        }
//                        else {
//                            batch.add(new BlockInfo(blockMap.get(Material.VOID_AIR), -64));
//                        }
//                    }
//                }
//
//                batchQueue.add(batch);
//
//                if (currentZ.get() > maxZ) {
//                    blockRef.get().cancel();
//                    hasAllBlockData.set(true);
//                } else {
//                    currentZ.set(endZ + 1);
//                }
//            }
//        }, 0, delay);
//
//        blockRef.set(blockTask);
//
//
//        BukkitTask writeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
//            @Override
//            public void run() {
//                List<BlockInfo> batch = batchQueue.poll();
//                if (batch != null) {
//                    StringBuilder sb = new StringBuilder(batch.size() * 8);
//                    BlockInfo prevInfo = null;
//                    int similarCounter = 1;
//                    for (BlockInfo info : batch) {
//                        if (prevInfo != null && prevInfo.id() == info.id() && prevInfo.y() == info.y()) {
//                            similarCounter++;
//                        }
//                        else {
//                            if (prevInfo != null)
//                                sb.append(prevInfo.id()).append(",").append(prevInfo.y()).append(",").append(similarCounter).append("\n");
//                            similarCounter = 1;
//                        }
//                        prevInfo = info;
//                    }
//
//                    if (prevInfo != null) {
//                        sb.append(prevInfo.id())
//                                .append(",")
//                                .append(prevInfo.y())
//                                .append(",")
//                                .append(similarCounter)
//                                .append("\n");
//                    }
//
//                    try {
//                        writer.write(sb.toString());
//                        writer.flush();
//                    } catch (IOException ignored) {}
//                }
//                else {
//                    if (!hasAllBlockData.get())
//                        return;
//                    sendToOperators(String.format(ChatColor.BOLD + "" + ChatColor.GOLD + "[MINEPATH] All processes have been completed for {%s}, took %.3f second(s)", world.getName(), ((System.nanoTime() - startTime.get())/1_000_000_000d)));
//                    try {
//                        writer.close();
//                    } catch (IOException ignored) {}
//
//                    writeRef.get().cancel();
//                }
//            }
//        }, 0L, 5L);
//
//        writeRef.set(writeTask);
//    }