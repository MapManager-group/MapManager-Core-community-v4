# MapManager Core v4

MapManager Core v4 is a new implementation of the MapManager map-management plugin. This repository is the v4 project; the v3 checkout is used as a behavior and migration reference only.

## Framework decision

- MMC owns map metadata, map roles, and every map-entry decision.
- LuckPerms supplies permissions configured on the `worldbase` parent group. MMC may mirror map-role membership into child groups that inherit `worldbase`, but LuckPerms is not an authority for map admission.
- Multiverse-Core performs world creation, import, initialization, and deletion operations. MMC owns the lifecycle workflow and its metadata updates. On-demand world loading is not implemented yet.
- Public API contracts are separated from their implementation. Storage ports and storage models are internal to the implementation and are never part of the API module.
- JSON and MySQL storage are selectable backends behind the same internal repository contracts.
- Mutating operations return structured outcomes. A single notifier renders localized MiniMessage notifications; the API keeps stable failure-reason and operation codes. The default language is `zh_CN`, configured with `language` in `config.yml`; bundled languages are in `plugin/src/main/resources/langs/`.

The Maven reactor uses `studio.lorian.mapmanager` as its group ID and contains `api` (public contracts) and `plugin` (Paper entry point and implementation). All source code is Java. JSON/MySQL storage, map settings, roles, admission decisions, map creation, import, initialization, deletion, command-based teleportation, and configurable strict map-entry checks are implemented. Startup checks the sibling `plugins/MapManager-Core` directory for both `worlds.json` and `groups.json`; when present, it backs them up under the v4 data directory and imports their map and role data into the selected repository. Migration errors disable the plugin. Loading worlds on demand and API service registration are still pending.

## Create a map

`/world create <name> [flat|normal|void|nether|end] [alias=<display name>] [group=<group id>] [admin=<username>] [key=value ...]` is console-only and has no permission node. If no type is given, the world is flat. The map name must use 4–16 lowercase letters, digits, `_`, or `-`. An explicit group ID uses the same characters and allows 1–16 characters. The alias defaults to the map name; the group defaults to the map name. The effective group ID cannot be `default` or `worldbase`, since those are LuckPerms base groups. `admin` optionally names the first administrator. Creation and import reject built-in reserved names (`cache`, `config`, `world`, `log`, `test`, `overworld`, `default`, `worldbase`, `apply`, `public`, `private`) and names beginning with `plugin`, `version`, `admin`, `mapmanager`, `builder`, `visitor`, `lib`, or `mmc`. Configure an additional Java regular expression under `creation.blocked-world-name-regex` in `config.yml`; it matches the whole map name and defaults to `.*_bak$`. Configuration cannot remove built-in restrictions.

Supported keys are `flat.height` (1–320), `seed`, `structures` (`true` or `false`), `biome`, `generator`, `generator-settings`, and `property.<Multiverse property>`. For example: `/world create buildworld flat flat.height=64 structures=false alias=BuildWorld`.

World creation, import, and initialization are console-only operations. Player subcommands have no Bukkit permission node; MMC authorizes them using map access and roles.

Creation saves the map as `PENDING` before Multiverse creates the world. After saving its spawn, first administrator, and LuckPerms group inheritance, it changes the map to `ACTIVE`. A failed stage leaves the map `PENDING` for inspection and returns an operation ID. Automatic recovery is not implemented yet.

After Multiverse creates or imports a world, MMC initializes its Multiverse difficulty (peaceful), game mode (creative), auto-load and spawn loading options, disables ordinary entity spawning, and applies the v3 gamerules for random ticks, fire, weather, daylight, mob griefing, mob spawning, and advancement announcements. These settings are saved before the map becomes `ACTIVE`. `/world init <name>` is console-only and reapplies them to an already loaded MMC map. Map admins can inspect or change the stored settings with `/world physics [true|false|info]` and `/world explosion [true|false|info]`. Physics controls block physics and falling blocks; explosion controls block and entity explosions in registered maps.

All players can run `/world tp <name>` or its shorthand `/wtp <name>`. MMC checks access to the active map and teleports to the saved map spawn or the world's default spawn. The target world must already be loaded; on-demand loading is not implemented.

## Strict map entry

`access-control.strict-map-entry` in `config.yml` defaults to `true`. When enabled, MMC checks and blocks unauthorized player teleports, then verifies the actual world after teleport and world-change events. It also checks a player's world on login and respawn. After `/world admin|builder|visitor remove`, a player who no longer has any effective access to their current map is returned to the Multiverse default world's spawn. If the default world is unavailable, the player cannot enter it, or the return teleport fails, MMC disconnects the player. Setting `strict-map-entry: false` under `access-control` disables this listener's entry and revocation checks. `/world tp` and `/wtp` continue to use their MMC access decision.

## Map settings

The current map's MMC administrator can run `/world public [true|false|info]` to change or view public access. Players with an effective map role and everyone on public maps may enter. `/world setname <name>` changes the display name and Multiverse alias. `/world setspawn` saves the player's current location as the MMC, Bukkit, and Multiverse world spawn.

An MMC administrator of the current map can run `/world kick <online-player>` while in a managed map. The target must be in the same map. MMC moves them to Multiverse-Core's default world spawn and reports a structured `KICK` result. Tab completion lists only online players in the current map.

The console can request deletion with `/world delete <name>`. MMC prints a random six-character code bound to the selected map; enter `/world delete <code>` within 10 seconds to confirm. Players cannot use this command. `deletion.allowed-world-name-regex` in `config.yml` limits eligible names and defaults to `.*_bak$`. MMC refuses the Multiverse default world, the server's first loaded world, and worlds containing players. It checks the expected world directory, records `DELETING`, asks Multiverse to delete the world files, and then removes MMC roles and LuckPerms projection. A failed deletion retains `DELETING`; repeat the two-step command after fixing the cause.

## Map members

Players who can enter the current managed map can use `/world admins`, `/world builders`, and `/world visitors` to list players directly assigned each role. `admin` and `builder` assignments cover every map in the current map group; `visitor` applies only to the current map. An admin also has effective builder and visitor access, and a builder has effective visitor access. Public maps allow everyone to visit regardless of the explicit visitor list.

An MMC administrator of the current map can use `/world admin|builder|visitor add|remove <username-or-UUID>`. MMC stores the grants; admin and builder grants also mirror membership to the corresponding LuckPerms map group, which inherits `worldbase` in that group's worlds. LuckPerms does not authorize MMC commands. Removing one group role preserves LuckPerms membership while the other role remains. If LuckPerms sync fails after MMC storage succeeds, the command reports `Partial`; repeating the command retries the projection.

Tab completion filters subcommands by sender and MMC access or administrator role. It suggests accessible maps for `/world tp` and `/wtp`, managed maps for console `/world init`, importable root folders for `/world import`, world types and option keys for creation/import, and role actions and relevant player names for member commands. For members without a cached name, removal completion uses their UUID.

Initialization sets the Multiverse alias to `&3<name>` for private maps and `&2<name>` for public maps. The database stores alias text without Minecraft color codes; the Multiverse alias includes the visibility color. Creation and import use the world name when no alias is supplied, or the supplied alias otherwise. Manual initialization uses the saved alias. Changing a map's public status reads its current Multiverse alias, removes color codes, and applies the new color without changing the alias text. `MapAliasService#setAlias(UUID)` resets to the world name; `setAlias(UUID, String)` saves the supplied alias without color codes and updates the Multiverse alias.

## Import an existing world

Place the world folder directly under the server root, for example `server-root/buildworld/level.dat`, then run `/world import buildworld [normal|flat|void|nether|end] [alias=<display name>] [group=<group id>] [admin=<username>]`. This command is console-only, has no permission node, and defaults the type to `flat`. Import reads `Data.DataVersion` from `level.dat` and rejects maps newer than the server or with invalid version metadata. It follows the same `PENDING` to `ACTIVE` metadata and LuckPerms setup as creation. Failed imports leave the source world folder intact.

See [STRUCTURE.md](STRUCTURE.md) for the current directory and method dependencies, [ARCHITECTURE.md](ARCHITECTURE.md) for design and lifecycle flow, and [API_INVENTORY.md](API_INVENTORY.md) for the source-based v3 interface classification.
