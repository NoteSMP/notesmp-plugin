package de.notesmp.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
import java.util.concurrent.CompletableFuture;

public final class NoteSMPPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private HttpClient http;
    private String apiBase;
    private String botBase;
    private String apiKey;
    private boolean includeSubstatistics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        apiBase = trimSlash(getConfig().getString("api.base-url", "https://notesmp-api.notesmp-de.workers.dev"));
        botBase = trimSlash(getConfig().getString("api.bot-url", "https://notesmp-bot.notesmp-de.workers.dev"));
        apiKey = getConfig().getString("api.key", "").trim();
        includeSubstatistics = getConfig().getBoolean("statistics.include-substatistics", true);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

        if (apiKey.isBlank() || apiKey.equals("CHANGE_ME")) {
            getLogger().severe("Configure api.key in plugins/NoteSMP/config.yml before using API features.");
        }

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("verify") != null) getCommand("verify").setExecutor(this);

        long heartbeatTicks = Math.max(20L, getConfig().getLong("heartbeat.interval-seconds", 60L) * 20L);
        long statsTicks = Math.max(100L, getConfig().getLong("statistics.interval-seconds", 300L) * 20L);
        getServer().getScheduler().runTaskTimer(this, this::sendHeartbeat, 20L, heartbeatTicks);
        getServer().getScheduler().runTaskTimer(this, this::sendAllPlayerStats, 100L, statsTicks);

        getLogger().info("NoteSMP plugin enabled. Heartbeat and player-stat synchronization scheduled.");
    }

    @Override
    public void onDisable() {
        if (http != null && !apiKey.isBlank() && !apiKey.equals("CHANGE_ME")) {
            postJson(apiBase + "/v1/minecraft/offline", "{}", null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTaskLater(this, () -> sendPlayerStats(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sendPlayerStats(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
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

    private boolean ready() {
        return http != null && !apiKey.isBlank() && !apiKey.equals("CHANGE_ME");
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                getLogger().warning("Server heartbeat failed with HTTP " + response.statusCode());
            }
        });
    }

    private void sendAllPlayerStats() {
        if (!ready()) return;
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.hasPlayedBefore()) sendPlayerStats(player);
        }
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                getLogger().warning("Player-stat sync failed for " + player.getName() + " (HTTP " + response.statusCode() + ")");
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        getLogger().warning("NoteSMP API request failed: " + error.getMessage());
                        if (callback != null) callback.accept(new FailedResponse());
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

    private static final class FailedResponse extends java.net.http.HttpResponse<String> {
        @Override public int statusCode() { return 599; }
        @Override public java.net.http.HttpRequest request() { return null; }
        @Override public Optional<java.net.http.HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public java.util.Map<String, java.util.List<String>> headers() { return java.util.Map.of(); }
        @Override public String body() { return ""; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://invalid.local"); }
        @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
    }
}
