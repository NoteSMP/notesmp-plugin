package de.notesmp.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class NoteSMPPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private HttpClient http;
    private String apiBase;
    private String botBase;
    private String apiKey;
    private boolean includeSubstatistics;
    private boolean activityLogEnabled;

    @Override
    public void onEnable() {
        logUpdateNotice();
        saveDefaultConfig();
        apiBase = trimSlash(getConfig().getString("api.base-url", "https://notesmp-api.notesmp-de.workers.dev"));
        botBase = trimSlash(getConfig().getString("api.bot-url", "https://notesmp-bot.notesmp-de.workers.dev"));
        apiKey = getConfig().getString("api.key", "").trim();
        includeSubstatistics = getConfig().getBoolean("statistics.include-substatistics", true);
        activityLogEnabled = getConfig().getBoolean("activity-log.enabled", false);
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                // GitHub release asset URLs redirect to the actual asset host.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        if (apiKey.isBlank() || apiKey.equals("CHANGE_ME")) {
            consoleLog("ERROR", "Configure api.key in plugins/NoteSMP/config.yml before using API features.");
        }
        if (!isHttpUrl(apiBase) || !isHttpUrl(botBase)) {
            consoleLog("ERROR", "Invalid API URL in plugins/NoteSMP/config.yml. Both URLs must be valid HTTP(S) URLs.");
        }

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("verify") != null) getCommand("verify").setExecutor(this);
        if (getCommand("notesmp") != null) {
            getCommand("notesmp").setExecutor(this);
            getCommand("notesmp").setTabCompleter(this);
        }

        // The website owns server status checks. The plugin only sends a lightweight refresh signal.
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::requestWebsiteStatusRefresh, 20L);
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::pollLiveProfileRequest, 40L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::pollLiveProfileRequest, 40L, 40L);

        long snapshotTicks = 24L * 60L * 60L * 20L;
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::runSnapshotIfDue, 100L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::runDailySnapshot, snapshotTicks, snapshotTicks);

        scheduleUpdateCheck();
        consoleLog("INFO", "NoteSMP plugin enabled. Live profile channel and 24-hour snapshot sync scheduled.");
    }

    @Override
    public void onDisable() {
        // Do not publish an offline/status payload. The website determines server status
        // from a fresh public Minecraft status ping.
    }

    private void reconcileSavedAdvancements() {
        if (!ready() || Bukkit.getWorlds().isEmpty()) return;
        java.nio.file.Path directory = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("advancements");
        if (!java.nio.file.Files.isDirectory(directory)) return;
        Map<String, org.bukkit.advancement.Advancement> registry = new LinkedHashMap<>();
        java.util.Iterator<org.bukkit.advancement.Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            org.bukkit.advancement.Advancement advancement = iterator.next();
            registry.put(advancement.getKey().toString(), advancement);
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.json"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        UUID uuid;
                        try { uuid = UUID.fromString(fileName.substring(0, fileName.length() - 5)); }
                        catch (IllegalArgumentException ignored) { return; }
                        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                        if (player.getName() == null) return;
                        List<Map<String, Object>> earned = new ArrayList<>();
                        try {
                            JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
                            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                                if (entry.getKey().startsWith("minecraft:recipes/") || entry.getKey().endsWith("/root")) continue;
                                org.bukkit.advancement.Advancement advancement = registry.get(entry.getKey());
                                if (advancement == null || !entry.getValue().isJsonObject()) continue;
                                JsonObject progress = entry.getValue().getAsJsonObject();
                                JsonObject criteria = progress.has("criteria") && progress.get("criteria").isJsonObject()
                                        ? progress.getAsJsonObject("criteria") : new JsonObject();
                                if (!isAdvancementComplete(entry.getValue().getAsJsonObject(), advancement)) continue;
                                Instant earnedAt = Instant.now();
                                for (JsonElement date : criteria.asMap().values()) {
                                    if (!date.isJsonPrimitive() || !date.getAsJsonPrimitive().isString()) continue;
                                    try {
                                        Instant parsed = Instant.parse(date.getAsString());
                                        if (parsed.isBefore(earnedAt)) earnedAt = parsed;
                                    } catch (Exception ignored) { }
                                }
                                Map<String, Object> record = new LinkedHashMap<>();
                                record.put("advancementId", entry.getKey());
                                record.put("title", advancementTitle(advancement));
                                record.put("icon", advancementIcon(advancement));
                                record.put("earnedAt", earnedAt.toString());
                                earned.add(record);
                            }
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("uuid", uuid.toString());
                            payload.put("username", player.getName());
                            payload.put("advancements", earned);
                            postJson(apiBase + "/v1/minecraft/advancements", toJson(payload), response -> {
                                if (response == null || response.statusCode() < 200 || response.statusCode() >= 300)
                                    consoleLog("WARN", "Startup advancement reconciliation failed for " + player.getName());
                            });
                        } catch (Exception exception) {
                            consoleLog("WARN", "Could not reconcile advancement file " + fileName + ": " + exception.getMessage());
                        }
                    });
        } catch (Exception exception) {
            consoleLog("WARN", "Could not scan saved player advancements: " + exception.getMessage());
        }
    }

    private void syncPlayerAdvancements(Player player) {
        if (!ready() || !player.isOnline()) return;
        List<Map<String, Object>> earned = new ArrayList<>();
        java.util.Iterator<org.bukkit.advancement.Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            org.bukkit.advancement.Advancement advancement = iterator.next();
            if ((advancement.getKey().getNamespace().equals("minecraft") && advancement.getKey().getKey().startsWith("recipes/")) || advancement.getKey().getKey().endsWith("/root")) continue;
            org.bukkit.advancement.AdvancementProgress progress = player.getAdvancementProgress(advancement);
            if (!progress.isDone()) continue;
            Instant earnedAt = Instant.now();
            for (String criterion : progress.getAwardedCriteria()) {
                java.util.Date date = progress.getDateAwarded(criterion);
                if (date != null && date.toInstant().isBefore(earnedAt)) earnedAt = date.toInstant();
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("advancementId", advancement.getKey().toString());
            record.put("title", advancementTitle(advancement));
            record.put("icon", advancementIcon(advancement));
            record.put("earnedAt", earnedAt.toString());
            earned.add(record);
        }
        activityLog("Backfilling " + earned.size() + " earned advancements for " + player.getName());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", player.getUniqueId().toString());
        payload.put("username", player.getName());
        payload.put("advancements", earned);
        postJson(apiBase + "/v1/minecraft/advancements", toJson(payload), response -> {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                consoleLog("WARN", "Advancement history sync failed for " + player.getName()
                        + (response == null ? " (network error)" : " (HTTP " + response.statusCode() + ")"));
            }
        });
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        // Persist each unlock immediately; the daily full snapshot remains the reconciliation safety net.
        syncPlayerAdvancements(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::requestWebsiteStatusRefresh, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::requestWebsiteStatusRefresh, 1L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("notesmp")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
                sender.sendMessage("[NoteSMP] Running version " + getPluginMeta().getVersion() + ".");
            } else if (args.length == 1 && (args[0].equalsIgnoreCase("update") || args[0].equalsIgnoreCase("updates")) && sender.hasPermission("notesmp.update")) {
                sender.sendMessage("Checking and staging the latest NoteSMP update. A server restart is required to apply it safely.");
                activityLog("Manual plugin update check requested by " + sender.getName());
                getServer().getScheduler().runTaskAsynchronously(this, () -> checkForUpdate(sender));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("activitylog") && sender.hasPermission("notesmp.activitylog")) {
                if (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off")) {
                    sender.sendMessage("Usage: /notesmp activitylog <on|off>");
                } else {
                    activityLogEnabled = args[1].equalsIgnoreCase("on");
                    getConfig().set("activity-log.enabled", activityLogEnabled);
                    saveConfig();
                    sender.sendMessage("NoteSMP detailed activity log is " + (activityLogEnabled ? "enabled" : "disabled") + ".");
                    consoleLog("INFO", "Detailed activity log " + (activityLogEnabled ? "enabled" : "disabled") + " by " + sender.getName() + ".");
                }
            } else {
                sender.sendMessage(sender.hasPermission("notesmp.update") ? "Usage: /notesmp <updates|update|version|activitylog on|off>" : "Usage: /notesmp version");
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (args.length != 1 || !args[0].matches("\\d{6}")) {
            player.sendMessage("Usage: /verify <six-digit-code>");
            return true;
        }
        if (!ready()) {
            player.sendMessage("NoteSMP account linking is not configured yet. Please contact an administrator.");
            return true;
        }

        String code = args[0];
        String body = "{\"code\":" + quote(code)
                + ",\"minecraftUuid\":" + quote(player.getUniqueId().toString())
                + ",\"minecraftName\":" + quote(player.getName()) + "}";
        player.sendMessage("Checking your NoteSMP verification code…");

        postJson(botBase + "/minecraft/verify", body, response -> {
            boolean success = response != null && response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body() != null && response.body().contains("\"ok\":true");
            getServer().getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                if (success) {
                    player.sendMessage("Your Minecraft account is now linked to Discord. You can close this message.");
                } else {
                    player.sendMessage("Verification failed: the code may be expired, incorrect, or already used.");
                }
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("notesmp") || args.length < 1 || args.length > 2) return List.of();
        List<String> suggestions = new ArrayList<>();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            if ("version".startsWith(prefix)) suggestions.add("version");
            if (sender.hasPermission("notesmp.update") && "update".startsWith(prefix)) suggestions.add("update");
        }
        if (args.length == 1 && sender.hasPermission("notesmp.activitylog") && "activitylog".startsWith(prefix)) suggestions.add("activitylog");
        if (args.length == 2 && args[0].equalsIgnoreCase("activitylog") && sender.hasPermission("notesmp.activitylog")) {
            if ("on".startsWith(args[1].toLowerCase(Locale.ROOT))) suggestions.add("on");
            if ("off".startsWith(args[1].toLowerCase(Locale.ROOT))) suggestions.add("off");
        }
        return suggestions;
    }

    private void activityLog(String message) {
        if (activityLogEnabled) consoleLog("INFO", "[activity] " + message);
    }

    private void consoleLog(String level, String message) {
        String levelColor = switch (level) {
            case "ERROR" -> "§c";
            case "WARN" -> "§e";
            default -> "§7";
        };
        Bukkit.getConsoleSender().sendMessage("§f[§eNote§6SMP§f]§r " + levelColor + message);
    }

    private void logUpdateNotice() {
        java.nio.file.Path notice = getDataFolder().toPath().resolve("pending-update.txt");
        if (!java.nio.file.Files.exists(notice)) return;
        try {
            List<String> lines = java.nio.file.Files.readAllLines(notice);
            String tag = lines.isEmpty() ? "unknown" : lines.get(0).trim();
            String commit = lines.size() < 2 ? "unknown" : lines.get(1).trim();
            String shortCommit = commit.length() > 7 ? commit.substring(0, 7) : commit;
            consoleLog("INFO", "Plugin updated to " + tag + " — Commit[" + shortCommit + "](https://github.com/NoteSMP/notesmp-plugin/commit/" + commit + ")");
            java.nio.file.Files.deleteIfExists(notice);
        } catch (Exception e) {
            consoleLog("WARN", "Could not read plugin update notice: " + e.getMessage());
        }
    }

    private void scheduleUpdateCheck() {
        if (!getConfig().getBoolean("updates.enabled", true)) return;
        long interval = Math.max(30L, getConfig().getLong("updates.check-interval-minutes", 360L));
        // Check shortly after enable so a pending release is staged before the next restart.
        getServer().getScheduler().runTaskLaterAsynchronously(this, () -> checkForUpdate(null), 40L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> checkForUpdate(null), interval * 1200L, interval * 1200L);
    }

    private void checkForUpdate(CommandSender requester) {
        try {
            activityLog("Fetching GitHub release metadata for plugin update check");
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/NoteSMP/notesmp-plugin/releases?per_page=100"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                consoleLog("WARN", "GitHub update check returned HTTP " + response.statusCode()); return;
            }

            // GitHub's release listing can put stable releases ahead of newer dev builds.
            // Scan release objects and select the greatest NoteSMP plugin version.
            String selectedRelease = null;
            String latest = null;
            for (String release : jsonArrayObjects(response.body())) {
                String name = jsonString(release, "name");
                if (name == null || !name.startsWith("NoteSMP Plugin ")) continue;
                String version = name.replaceFirst("^NoteSMP\\s+Plugin\\s+", "").trim();
                if (latest == null || isNewerVersion(version, latest)) {
                    latest = version;
                    selectedRelease = release;
                }
            }
            if (selectedRelease == null || latest == null
                    || !isNewerVersion(latest, getPluginMeta().getVersion())) {
                replyUpdate(requester, "NoteSMP v" + getPluginMeta().getVersion() + " is up to date. No update queued.");
                return;
            }

            String tag = jsonString(selectedRelease, "tag_name");
            String commit = jsonString(selectedRelease, "target_commitish");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\\"browser_download_url\\\"\\s*:\\s*\\\"(https://github\\.com/NoteSMP/notesmp-plugin/releases/download/[^\\\"\\s]+\\.jar)\\\"")
                    .matcher(selectedRelease);
            if (!m.find()) { getLogger().warning("Release " + tag + " has no JAR asset."); return; }
            String url = m.group(1);
            HttpResponse<byte[]> jar = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (jar.statusCode() < 200 || jar.statusCode() >= 300 || jar.body().length < 1024 || jar.body().length > 50 * 1024 * 1024) {
                consoleLog("WARN", "Update JAR download failed or had an invalid size (HTTP " + jar.statusCode() + ")."); return;
            }
            java.nio.file.Path dir = getDataFolder().getParentFile().toPath().resolve("update");
            java.nio.file.Files.createDirectories(dir);
            // Stage under the current JAR's exact filename so Paper can safely swap it at startup.
            String installedName = getFile().getName();
            java.nio.file.Path target = dir.resolve(installedName);
            java.nio.file.Path temp = dir.resolve(installedName + ".part");
            java.nio.file.Files.write(temp, jar.body());
            try {
                java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // Write the success marker only after the complete JAR is staged.
            java.nio.file.Files.writeString(getDataFolder().toPath().resolve("pending-update.txt"), tag + "\n" + (commit == null ? "unknown" : commit));
            String updateMessage = "Downloaded NoteSMP " + tag + " to plugins/update/" + installedName + ". Restart the server to apply it; the server will not be restarted automatically.";
            consoleLog("INFO", updateMessage);
            replyUpdate(requester, updateMessage);
        } catch (Exception ex) {
            consoleLog("WARN", "Update check failed: " + ex.getMessage());
            replyUpdate(requester, "Update check failed: " + ex.getMessage());
        }
    }

    private void replyUpdate(CommandSender requester, String message) {
        if (requester != null) getServer().getScheduler().runTask(this, () -> requester.sendMessage("[NoteSMP] " + message));
    }

    private static List<String> jsonArrayObjects(String json) {
        List<String> objects = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) start = i;
            } else if (c == '}' && depth > 0) {
                if (--depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static String jsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(json);
        if (!m.find()) return null;
        return m.group(1).replace("\\\\/", "/").replace("\\\\\"", "\\\"").replace("\\\\\\\\", "\\\\");
    }

    private static boolean isNewerVersion(String candidate, String current) {
        String[] a = candidate.replaceFirst("^[vV]", "").split("-", 2);
        String[] b = current.replaceFirst("^[vV]", "").split("-", 2);
        String[] an = a[0].split("\\."), bn = b[0].split("\\.");
        for (int i = 0; i < Math.max(an.length, bn.length); i++) {
            long x = i < an.length ? parseNumeric(an[i]) : 0L;
            long y = i < bn.length ? parseNumeric(bn[i]) : 0L;
            if (x != y) return x > y;
        }
        // A stable release outranks a prerelease with the same numeric core.
        if (a.length == 1 && b.length > 1) return true;
        if (a.length > 1 && b.length == 1) return false;
        if (a.length > 1) return a[1].compareToIgnoreCase(b[1]) > 0;
        return false;
    }

    private static long parseNumeric(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private boolean ready() {
        return http != null && isHttpUrl(apiBase) && isHttpUrl(botBase)
                && !apiKey.isBlank() && !apiKey.equals("CHANGE_ME");
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            String scheme = URI.create(value).getScheme();
            return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requestWebsiteStatusRefresh() {
        if (!ready()) return;
        postJson(apiBase + "/v1/minecraft/status-refresh", "{}", response -> {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300)
                consoleLog("WARN", "Website status refresh signal failed.");
        });
    }

    private void runSnapshotIfDue() {
        java.nio.file.Path marker = getDataFolder().toPath().resolve("last-full-snapshot.txt");
        long last = 0L;
        try { if (java.nio.file.Files.exists(marker)) last = Long.parseLong(java.nio.file.Files.readString(marker).trim()); }
        catch (Exception ignored) { }
        if (System.currentTimeMillis() - last >= 24L * 60L * 60L * 1000L) runDailySnapshot();
    }

    private void runDailySnapshot() {
        if (!ready()) return;
        // Bukkit player/statistic and advancement APIs are accessed on the server thread.
        getServer().getScheduler().runTask(this, () -> {
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (player.getName() != null) sendPlayerStats(player);
            }
            reconcileSavedAdvancements();
            try {
                java.nio.file.Files.createDirectories(getDataFolder().toPath());
                java.nio.file.Files.writeString(getDataFolder().toPath().resolve("last-full-snapshot.txt"), Long.toString(System.currentTimeMillis()));
            } catch (Exception exception) {
                consoleLog("WARN", "Could not persist daily snapshot timestamp: " + exception.getMessage());
            }
        });
    }

    private void pollLiveProfileRequest() {
        if (!ready()) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v1/minecraft/live/poll"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + apiKey).GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) return;
            getServer().getScheduler().runTask(this, () -> {
                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!root.has("request") || !root.get("request").isJsonObject()) return;
                    JsonObject pending = root.getAsJsonObject("request");
                    String requestId = pending.get("requestId").getAsString();
                    UUID uuid = UUID.fromString(pending.get("uuid").getAsString());
                    OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                    if (player.getName() == null) return;
                    Map<String, Object> statistics = collectStatistics(player);
                    List<Map<String, Object>> advancements = collectAdvancementsFromDisk(player);
                    Map<String, Object> baseStats = new LinkedHashMap<>();
                    baseStats.put("playtime_seconds", Math.max(0, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L));
                    baseStats.put("deaths", safeStatistic(player, "DEATHS"));
                    baseStats.put("blocks_placed", sumMaterialStatistic(player, Statistic.USE_ITEM, true));
                    baseStats.put("blocks_broken", sumMaterialStatistic(player, Statistic.valueOf("MINE_BLOCK"), true));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("requestId", requestId);
                    payload.put("baseStats", baseStats);
                    payload.put("firstJoin", isoDate(player.getFirstPlayed()));
                    payload.put("lastSeen", isoDate(player.isOnline() ? System.currentTimeMillis() : player.getLastPlayed()));
                    payload.put("statistics", statistics.entrySet().stream().map(entry -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("stat_id", entry.getKey());
                        item.put("stat_value", entry.getValue());
                        return item;
                    }).toList());
                    payload.put("totalAdvancements", countAvailableAdvancements());
                    payload.put("advancements", advancements.stream().map(item -> {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("advancement_id", item.get("advancementId"));
                        record.put("advancement_title", item.get("title"));
                        record.put("earned_at", item.get("earnedAt"));
                        record.put("icon_name", item.get("icon"));
                        return record;
                    }).toList());
                    postJson(apiBase + "/v1/minecraft/live/response", toJson(payload), result -> {
                        if (result == null || result.statusCode() < 200 || result.statusCode() >= 300)
                            consoleLog("WARN", "Could not return live profile data for " + player.getName());
                    });
                } catch (Exception exception) {
                    consoleLog("WARN", "Could not process live profile request: " + exception.getMessage());
                }
            });
        });
    }

    private int countAvailableAdvancements() {
        int total = 0;
        java.util.Iterator<org.bukkit.advancement.Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            String id = iterator.next().getKey().toString();
            if (id.startsWith("minecraft:recipes/") || id.matches(".*/root$")) continue;
            total++;
        }
        return total;
    }

    private List<Map<String, Object>> collectAdvancementsFromDisk(OfflinePlayer player) {
        List<Map<String, Object>> earned = new ArrayList<>();
        if (player.getName() == null) return earned;

        // Prefer Bukkit's live progress API when the player is online; use the world
        // advancement JSON for offline profiles so the website can still request them.
        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            java.util.Iterator<org.bukkit.advancement.Advancement> liveIterator = Bukkit.advancementIterator();
            while (liveIterator.hasNext()) {
                org.bukkit.advancement.Advancement advancement = liveIterator.next();
                String id = advancement.getKey().toString();
                if (id.startsWith("minecraft:recipes/") || id.endsWith("/root")) continue;
                org.bukkit.advancement.AdvancementProgress progress = onlinePlayer.getAdvancementProgress(advancement);
                if (!progress.isDone()) continue;
                Instant earnedAt = Instant.now();
                for (String criterion : progress.getAwardedCriteria()) {
                    java.util.Date date = progress.getDateAwarded(criterion);
                    if (date != null && date.toInstant().isBefore(earnedAt)) earnedAt = date.toInstant();
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("advancementId", id);
                item.put("title", advancementTitle(advancement));
                item.put("icon", advancementIcon(advancement));
                item.put("earnedAt", earnedAt.toString());
                earned.add(item);
            }
            return earned;
        }

        if (Bukkit.getWorlds().isEmpty()) return earned;
        java.nio.file.Path file = Bukkit.getWorlds().get(0).getWorldFolder().toPath()
                .resolve("advancements").resolve(player.getUniqueId() + ".json");
        if (!java.nio.file.Files.isRegularFile(file)) {
            activityLog("No saved advancement file found for " + player.getName() + " at " + file);
            return earned;
        }
        if (!java.nio.file.Files.isRegularFile(file)) return earned;
        Map<String, org.bukkit.advancement.Advancement> registry = new LinkedHashMap<>();
        java.util.Iterator<org.bukkit.advancement.Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            org.bukkit.advancement.Advancement advancement = iterator.next();
            registry.put(advancement.getKey().toString(), advancement);
        }
        try {
            JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getKey().startsWith("minecraft:recipes/") || entry.getKey().endsWith("/root")) continue;
                org.bukkit.advancement.Advancement advancement = registry.get(entry.getKey());
                if (advancement == null || !entry.getValue().isJsonObject()) continue;
                JsonObject progress = entry.getValue().getAsJsonObject();
                JsonObject criteria = progress.has("criteria") && progress.get("criteria").isJsonObject()
                        ? progress.getAsJsonObject("criteria") : new JsonObject();
                if (!isAdvancementComplete(progress, advancement)) continue;
                Instant earnedAt = Instant.now();
                for (JsonElement date : criteria.asMap().values()) {
                    if (!date.isJsonPrimitive() || !date.getAsJsonPrimitive().isString()) continue;
                    try { Instant parsed = Instant.parse(date.getAsString()); if (parsed.isBefore(earnedAt)) earnedAt = parsed; }
                    catch (Exception ignored) { }
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("advancementId", entry.getKey());
                item.put("title", advancementTitle(advancement));
                item.put("icon", advancementIcon(advancement));
                item.put("earnedAt", earnedAt.toString());
                earned.add(item);
            }
        } catch (Exception exception) {
            consoleLog("WARN", "Could not read live advancement file for " + player.getName() + ": " + exception.getMessage());
        }
        return earned;
    }


    private boolean isAdvancementComplete(JsonObject progress, org.bukkit.advancement.Advancement advancement) {
        if (progress.has("done") && progress.get("done").isJsonPrimitive()
                && progress.get("done").getAsJsonPrimitive().isBoolean()
                && progress.get("done").getAsBoolean()) return true;
        JsonObject criteria = progress.has("criteria") && progress.get("criteria").isJsonObject()
                ? progress.getAsJsonObject("criteria") : new JsonObject();
        var requirements = advancement.getRequirements().getRequirements();
        return !requirements.isEmpty() && requirements.stream().allMatch(requirement ->
                requirement.getRequiredCriteria().stream().anyMatch(criteria::has));
    }

    private String advancementTitle(org.bukkit.advancement.Advancement advancement) {
        io.papermc.paper.advancement.AdvancementDisplay display = advancement.getDisplay();
        return display == null
                ? advancement.getKey().getKey().replace('_', ' ')
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(display.title());
    }

    private String advancementIcon(org.bukkit.advancement.Advancement advancement) {
        io.papermc.paper.advancement.AdvancementDisplay display = advancement.getDisplay();
        return display == null ? null : display.icon().getType().getKey().toString();
    }

    private void sendPlayerStats(OfflinePlayer player) {
        if (!ready() || player.getName() == null) return;
        UUID uuid = player.getUniqueId();
        activityLog("Collecting detailed statistics for " + player.getName() + " (" + uuid + ")");
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("playtime_seconds", Math.max(0, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L));
        base.put("deaths", safeStatistic(player, "DEATHS"));
        base.put("blocks_placed", sumMaterialStatistic(player, Statistic.USE_ITEM, true));
        base.put("blocks_broken", sumMaterialStatistic(player, Statistic.valueOf("MINE_BLOCK"), true));
        Map<String, Object> statistics = collectStatistics(player);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", uuid.toString());
        payload.put("username", player.getName());
        payload.put("firstJoin", isoDate(player.getFirstPlayed()));
        payload.put("lastSeen", isoDate(player.isOnline() ? System.currentTimeMillis() : player.getLastPlayed()));
        payload.put("baseStats", base);
        payload.put("statistics", statistics);
        postJson(apiBase + "/v1/minecraft/playerstats", toJson(payload), response -> {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                consoleLog("WARN", "Player-stat sync failed for " + player.getName() + (response == null ? " (network error)" : " (HTTP " + response.statusCode() + ")"));
            }
        });
    }

    private long sumMaterialStatistic(OfflinePlayer player, Statistic statistic, boolean blocksOnly) {
        long total = 0;
        for (Material material : Material.values()) {
            if (blocksOnly && !material.isBlock()) continue;
            try { total += player.getStatistic(statistic, material); }
            catch (IllegalArgumentException ignored) { }
        }
        return total;
    }

    private int safeStatistic(OfflinePlayer player, String name) {
        try { return player.getStatistic(Statistic.valueOf(name)); }
        catch (IllegalArgumentException | NoSuchFieldError ignored) { return 0; }
    }

    private Map<String, Object> collectStatistics(OfflinePlayer player) {
        Map<String, Object> values = new LinkedHashMap<>();
        String[][] requested = {
            {"mob_kills", "MOB_KILLS"}, {"player_kills", "PLAYER_KILLS"}, {"deaths", "DEATHS"},
            {"time_since_last_death", "TIME_SINCE_DEATH"}, {"damage_absorbed", "DAMAGE_ABSORBED"},
            {"damage_dealt", "DAMAGE_DEALT"}, {"damage_resisted", "DAMAGE_RESISTED"},
            {"damage_taken", "DAMAGE_TAKEN"}, {"jumps", "JUMP"}, {"sneak_time", "SNEAK_TIME"},
            {"distance_climbed", "CLIMB_ONE_CM"}, {"distance_crouched", "CROUCH_ONE_CM"},
            {"distance_fallen", "FALL_ONE_CM"}, {"distance_flown", "FLY_ONE_CM"},
            {"distance_sprinted", "SPRINT_ONE_CM"}, {"distance_swum", "SWIM_ONE_CM"},
            {"distance_walked", "WALK_ONE_CM"}, {"distance_walked_under_water", "WALK_UNDER_WATER_ONE_CM"},
            {"distance_by_boat", "BOAT_ONE_CM"}, {"distance_by_elytra", "AVIATE_ONE_CM"},
            {"distance_by_happy_ghast", "HAPPY_GHAST_ONE_CM"}, {"distance_by_horse", "HORSE_ONE_CM"},
            {"distance_by_minecart", "MINECART_ONE_CM"}, {"fish_caught", "FISH_CAUGHT"},
            {"animals_bred", "BREED_ANIMALS"}, {"chests_opened", "OPEN_CHEST"},
            {"barrels_opened", "OPEN_BARREL"}, {"shulker_boxes_opened", "OPEN_SHULKER_BOX"},
            {"items_dropped", "DROP"}, {"items_enchanted", "ENCHANT_ITEM"},
            {"talked_to_villagers", "TALKED_TO_VILLAGER"}, {"traded_with_villagers", "TRADED_WITH_VILLAGER"},
            {"time_since_last_rest", "TIME_SINCE_REST"}, {"times_slept_in_a_bed", "SLEEP_IN_BED"}
        };
        for (String[] stat : requested) {
            try { values.put(stat[0], Math.max(0, player.getStatistic(Statistic.valueOf(stat[1])))); }
            catch (IllegalArgumentException | NoSuchFieldError ignored) { values.put(stat[0], 0); }
        }
        // Bukkit has no PLACE_BLOCK statistic; USE_ITEM over block materials is the
        // closest available persisted counter and avoids an invalid enum lookup.
        values.put("blocks_placed", sumMaterialStatistic(player, Statistic.USE_ITEM, true));
        values.put("blocks_broken", sumMaterialStatistic(player, Statistic.valueOf("MINE_BLOCK"), true));
        return values;
    }

    private void postJson(String url, String body, java.util.function.Consumer<HttpResponse<String>> callback) {
        activityLog("HTTP POST " + url + " payload=" + body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    activityLog("HTTP result " + url + " -> " + (error != null ? "ERROR " + error.getMessage() : "HTTP " + response.statusCode()));
                    if (error != null) {
                        consoleLog("WARN", "NoteSMP API request failed: " + error.getMessage());
                        if (callback != null) callback.accept(null);
                    } else if (callback != null) {
                        callback.accept(response);
                    }
                });
    }

    private static String isoDate(long epochMillis) {
        return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(String.valueOf(entry.getKey()))).append(':').append(toJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> items) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                out.append(toJson(item));
            }
            return out.append(']').toString();
        }
        return quote(value.toString());
    }

}
