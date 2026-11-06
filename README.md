# NoteSMP Paper Plugin

Paper plugin connecting the NoteSMP Minecraft Java server to the NoteSMP Cloudflare API and Discord account-verification service.

- **Plugin name:** `NoteSMP`
- **Main class:** `de.notesmp.plugin.NoteSMPPlugin`
- **Build:** Maven, Java 25, Paper API 26.3
- **Configuration:** `plugins/NoteSMP/config.yml`

## Features

- Registers a player in the public website directory immediately when they join.
- Signals the website/API to refresh server status after server startup, player join and player quit.
- Answers live player-profile requests with current statistics and advancement data.
- Saves a complete player-statistics snapshot to D1 every day at **05:00 Europe/Berlin**, for players online or last active within the preceding 25 hours.
- Catches up the daily snapshot after startup if that day's 05:00 run was missed.
- Records newly earned advancements immediately and reconciles the saved advancement history during scheduled snapshots.
- Completes Discord/Minecraft account linking with in-game `/verify`.
- Checks GitHub Releases for plugin updates and can stage an update for the next server restart.

Live profile responses are intended for immediate display; the scheduled player-data snapshot is the persisted statistics update. The plugin does not publish a periodic server heartbeat.

## Commands and permissions

| Command | Purpose | Permission |
|---|---|---|
| `/verify <six-digit-code>` | Completes Discord/Minecraft account linking. | `notesmp.verify` (default: true) |
| `/notesmp version` | Shows the installed plugin version. | None |
| `/notesmp updates` or `/notesmp update` | Checks and stages the latest plugin update; restart required to apply. | `notesmp.update` (default: op) |
| `/notesmp playerdata update` | Manually submits the player-data snapshot for players active in the last 25 hours. | `notesmp.playerdata` (default: op) |
| `/notesmp playerdata wipe` | Deletes player directory records, statistics, advancements and pending refreshes from D1. | `notesmp.playerdata` (default: op) |
| `/notesmp activitylog on|off` | Enables/disables detailed activity logging. | `notesmp.activitylog` (default: op) |

The player-data wipe does not delete website posts, events, gallery content, Discord configuration, account-link settings or creator records.

## Configuration

The plugin creates `plugins/NoteSMP/config.yml` on first start.

| Setting | Description |
|---|---|
| `api.base-url` | NoteSMP API Worker URL. |
| `api.bot-url` | NoteSMP bot Worker URL. |
| `api.key` | Shared `MINECRAFT_API_KEY` used by the API and bot Workers. |
| `statistics.poll-interval-seconds` | How often the plugin checks for queued/live profile requests (default: 5 seconds). |
| `statistics.include-substatistics` | Enables material/entity sub-statistics in explicit refresh responses. |
| `updates.enabled` | Enables GitHub release checks. |
| `updates.check-interval-minutes` | Update-check interval (default: 360 minutes). |
| `activity-log.enabled` | Enables detailed request/synchronization diagnostics. |

The configuration also contains a legacy heartbeat interval setting; current status handling uses refresh signals and API-side Minecraft status checks, not periodic plugin heartbeat payloads.

## Install on the server

1. Download the JAR from the [GitHub Releases](https://github.com/NoteSMP/notesmp-plugin/releases).
2. Stop the Paper server.
3. Upload the JAR to the server's `plugins/` directory.
4. Start the server once to generate the plugin configuration.
5. Set `api.key` to the same secret as `MINECRAFT_API_KEY` in both Cloudflare Workers.
6. Restart the server and check the console for the NoteSMP startup message.

The automatic updater stages a downloaded JAR; a restart is required before the new version is loaded.

## Build from source

Requirements: Java 25 and Maven.

```bash
mvn clean package
```

GitHub Actions builds on pushes to `main`, uploads a build artifact, and publishes a prerelease for main-branch builds. Version tags beginning with `v` publish a non-prerelease release.

## Verification flow

1. Run `/verify playername:<Minecraft-name>` in the NoteSMP Discord server.
2. Copy the six-digit code from the private Discord response.
3. Run `/verify <six-digit-code>` in Minecraft before the code expires.
4. The in-game chat confirms when the Minecraft account has been linked.

Never log or share API keys. Verification codes are short-lived and single-use.
