# NoteSMP Paper Plugin

Paper plugin for Minecraft Java 26.3. It connects the MintServers Paper server to the NoteSMP Cloudflare API and Discord bot.

## Features

- Sends server heartbeat data to the NoteSMP API every 60 seconds.
- Publishes public player records and all non-zero vanilla statistics, including block, item and entity sub-statistics, every 5 minutes.
- Sends stats for players regardless of whether their Discord account is verified.
- Adds in-game `/verify <six-digit-code>` to complete the Discord account-linking flow.

## Build

The GitHub Actions workflow builds the plugin automatically on every push to `main`.

1. Open the repository's **Actions** tab.
2. Open the latest **Build NoteSMP Paper Plugin** run.
3. Download the `notesmp-plugin` artifact after the run succeeds.
4. Extract the JAR from the artifact.

The project targets Java 25 and Paper API 26.3. Paper's current Maven coordinates use the `26.3.build.*` format. See the [Paper project setup guide](https://docs.papermc.io/paper/dev/project-setup/).

## Install on MintServers

1. Stop the Minecraft server.
2. Upload the plugin JAR to the server's `plugins/` folder.
3. Start the server once so it creates `plugins/NoteSMP/config.yml`.
4. Stop the server again and open that config.
5. Set `api.key` to the same secret value used as `MINECRAFT_API_KEY` in both Cloudflare Workers:
   - `notesmp-api`
   - `notesmp-bot`
6. Start the server.

Do not publish the API key or commit it to this repository.

## Verification test

1. In Discord, run `/verify YourMinecraftName`.
2. Copy the six-digit code from the private response.
3. In Minecraft, run `/verify 123456` using the code you received.
4. The plugin calls the bot's protected `/minecraft/verify` endpoint. The code is single-use and expires after two minutes.

## Data flow

- Heartbeat: `POST /v1/minecraft/heartbeat` on `notesmp-api`.
- Player stats: `POST /minecraft/playerstats` on `notesmp-bot`.
- Account verification: `POST /minecraft/verify` on `notesmp-bot`.

The bot and API Workers use the existing shared D1 database. No local PC process is required; the plugin runs inside the hosted Minecraft server.
