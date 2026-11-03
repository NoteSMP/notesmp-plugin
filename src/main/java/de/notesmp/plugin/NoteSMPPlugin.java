package de.notesmp.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
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
    private boolean statsSyncInProgress;
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

        long heartbeatTicks = Math.max(20L, getConfig().getLong("heartbeat.interval-seconds", 60L) * 20L);
        long statsTicks = Math.max(100L, getConfig().getLong("statistics.interval-seconds", 300L) * 20L);
        // Publish an initial state promptly after startup instead of waiting a full interval.
        getServer().getScheduler().runTaskLater(this, this::sendHeartbeat, 20L);
        getServer().getScheduler().runTaskTimer(this, this::sendHeartbeat, heartbeatTicks, heartbeatTicks);
        getServer().getScheduler().runTaskLater(this, this::sendAllPlayerStats, 100L);
        getServer().getScheduler().runTaskTimer(this, this::sendAllPlayerStats, statsTicks, statsTicks);

        scheduleUpdateCheck();
        consoleLog("INFO", "NoteSMP plugin enabled. Heartbeat and player-stat synchronization scheduled.");
    }

    @Override
    public void onDisable() {
        // Wait briefly for the offline marker; asynchronous requests can be cancelled as
        // the plugin/server shuts down, leaving the website stuck showing ONLINE.
        if (ready()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v1/minecraft/offline"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    consoleLog("WARN", "§6[NoteSMP]§r Could not mark server offline during shutdown (HTTP " + response.statusCode() + ").");
                }
            } catch (Exception exception) {
                consoleLog("WARN", "Could not mark server offline during shutdown: " + exception.getMessage());
            }
        }
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (!ready()) return;
        Player player = event.getPlayer();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", player.getUniqueId().toString());
        payload.put("username", player.getName());
        payload.put("advancementId", event.getAdvancement().getKey().toString());
        payload.put("title", event.getAdvancement().getKey().getKey().replace('_', ' '));
        payload.put("earnedAt", Instant.now().toString());
        activityLog("Advancement earned by " + player.getName() + ": " + event.getAdvancement().getKey());
        postJson(apiBase + "/v1/minecraft/advancement", toJson(payload), response -> {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                consoleLog("WARN", "Advancement sync failed for " + player.getName()
                        + (response == null ? " (network error)" : " (HTTP " + response.statusCode() + ")"));
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Publish count/list immediately after Bukkit updates the online-player collection.
        getServer().getScheduler().runTaskLater(this, this::sendHeartbeat, 1L);
        getServer().getScheduler().runTaskLater(this, () -> sendPlayerStats(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sendPlayerStats(event.getPlayer());
        // Delay one tick so the departing player is no longer counted.
        getServer().getScheduler().runTaskLater(this, this::sendHeartbeat, 1L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("notesmp")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
                sender.sendMessage("[NoteSMP] Running version " + getPluginMeta().getVersion() + ".");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("update") && sender.hasPermission("notesmp.update")) {
                sender.sendMessage("Checking latest NoteSMP release...");
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
                sender.sendMessage(sender.hasPermission("notesmp.update") ? "Usage: /notesmp <update|version|activitylog on|off>" : "Usage: /notesmp version");
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
        if (!command.getName().equalsIgnoreCase("notesmp") || args.length != 1) return List.of();
        List<String> suggestions = new ArrayList<>();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if ("version".startsWith(prefix)) suggestions.add("version");
        if (sender.hasPermission("notesmp.update") && "update".startsWith(prefix)) suggestions.add("update");
        if (sender.hasPermission("notesmp.activitylog") && "activitylog".startsWith(prefix)) suggestions.add("activitylog");
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

    private void sendHeartbeat() {
        if (!ready()) return;
        List<String> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) players.add(player.getName());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playersOnline", players.size());
        payload.put("maxPlayers", Bukkit.getMaxPlayers());
        payload.put("motd", Bukkit.getMotd());
        payload.put("version", Bukkit.getMinecraftVersion());
        payload.put("onlinePlayers", players);
        postJson(apiBase + "/v1/minecraft/heartbeat", toJson(payload), response -> {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                consoleLog("WARN", "Server heartbeat failed" + (response == null ? " (network error)" : " with HTTP " + response.statusCode()));
            }
        });
    }

    private void sendAllPlayerStats() {
        if (!ready() || statsSyncInProgress) return;

        // Avoid collecting every offline player's complete statistic map in one server
        // tick. Large player histories can otherwise cause a noticeable tick stall.
        List<OfflinePlayer> players = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.hasPlayedBefore()) players.add(player);
        }
        if (players.isEmpty()) return;

        statsSyncInProgress = true;
        final int[] index = {0};
        getServer().getScheduler().runTaskTimer(this, task -> {
            if (!ready() || index[0] >= players.size()) {
                statsSyncInProgress = false;
                task.cancel();
                return;
            }
            sendPlayerStats(players.get(index[0]++));
            if (index[0] >= players.size()) {
                statsSyncInProgress = false;
                task.cancel();
            }
        }, 1L, 1L);
    }

    private void sendPlayerStats(OfflinePlayer player) {
        if (!ready() || player.getName() == null) return;
        UUID uuid = player.getUniqueId();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("playtime_seconds", Math.max(0, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L));
        base.put("blocks_broken", sumMaterialStatistic(player, Statistic.MINE_BLOCK, true));
        base.put("blocks_placed", sumMaterialStatistic(player, Statistic.USE_ITEM, true));
        base.put("deaths", player.getStatistic(Statistic.DEATHS));
        base.put("kills", player.getStatistic(Statistic.PLAYER_KILLS));

        Map<String, Object> statistics = collectStatistics(player);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", uuid.toString());
        payload.put("username", player.getName());
        payload.put("firstJoin", isoDate(player.getFirstPlayed()));
        payload.put("lastSeen", isoDate(player.isOnline() ? System.currentTimeMillis() : player.getLastPlayed()));
        payload.put("baseStats", base);
        payload.put("statistics", statistics);
        postJson(botBase + "/minecraft/playerstats", toJson(payload), response -> {
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

    private Map<String, Object> collectStatistics(OfflinePlayer player) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Statistic statistic : Statistic.values()) {
            String baseId = statistic.getKey().toString();
            try {
                if (statistic.getType() == Statistic.Type.UNTYPED) {
                    int value = player.getStatistic(statistic);
                    if (value != 0) values.put(baseId, value);
                } else if (includeSubstatistics && statistic.getType() == Statistic.Type.BLOCK) {
                    for (Material material : Material.values()) {
                        if (!material.isBlock()) continue;
                        try {
                            int value = player.getStatistic(statistic, material);
                            if (value != 0) values.put(baseId + ":" + material.getKey(), value);
                        } catch (IllegalArgumentException ignored) { }
                    }
                } else if (includeSubstatistics && statistic.getType() == Statistic.Type.ITEM) {
                    for (Material material : Material.values()) {
                        try {
                            int value = player.getStatistic(statistic, material);
                            if (value != 0) values.put(baseId + ":" + material.getKey(), value);
                        } catch (IllegalArgumentException ignored) { }
                    }
                } else if (includeSubstatistics && statistic.getType() == Statistic.Type.ENTITY) {
                    for (EntityType entityType : EntityType.values()) {
                        try {
                            int value = player.getStatistic(statistic, entityType);
                            if (value != 0) values.put(baseId + ":" + entityType.getKey(), value);
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            } catch (IllegalArgumentException ignored) { }
        }
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
