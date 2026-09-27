# MapManager Core v4

MapManager Core v4 is a new implementation of the MapManager map-management plugin. This repository is the v4 project; the v3 checkout is used as a behavior and migration reference only.

## Framework decision

- MMC owns map metadata, map roles, and every map-entry decision.
- LuckPerms supplies permissions configured on the `worldbase` parent group. MMC may mirror map-role membership into child groups that inherit `worldbase`, but LuckPerms is not an authority for map admission.
- Multiverse-Core performs world creation, import, load, unload, spawn, and deletion operations. MMC owns the lifecycle workflow and its metadata updates.
- Public API contracts are separated from their implementation. Storage ports and storage models are internal to the implementation and are never part of the API module.
- JSON and MySQL storage are selectable backends behind the same internal repository contracts.
- Mutating operations return structured outcomes. A single notifier turns outcomes into messages, logs, and operation events, including failures in the form `failed, <reason>, <operation>`.

The Maven reactor uses `studio.lorian.mapmanager` as its group ID and contains `api` (public contracts) and `plugin` (Paper entry point and implementation scaffolds). All source code is Java. Config loading, JSON/MySQL storage, map settings, roles, admission decisions, map creation, import, and initialization are implemented. Other lifecycle operations, teleportation, and admission listeners remain unfinished; the plugin does not publish its API services yet.

## Create a map

`/mmc create <name> <flat|normal|void|nether|end> [alias=<display name>] [group=<group id>] [admin=<username>] [key=value ...]` requires `mapmanager.command.create`. The map name must use 4–16 lowercase letters, digits, `_`, or `-`. An explicit group ID uses the same characters and allows 1–16 characters. The alias defaults to the map name; the group defaults to the map name. If `admin` is omitted, a player issuing the command becomes the first administrator; the console leaves it unset.

`/create` accepts the same arguments as `/mmc create`.

Supported keys are `flat.height` (1–320), `seed`, `structures` (`true` or `false`), `biome`, `generator`, `generator-settings`, and `property.<Multiverse property>`. For example: `/mmc create buildworld flat flat.height=64 structures=false alias=BuildWorld`.

Creation saves the map as `PENDING` before Multiverse creates the world. After saving its spawn, first administrator, and LuckPerms group inheritance, it changes the map to `ACTIVE`. A failed stage leaves the map `PENDING` for inspection and returns an operation ID. Automatic recovery is not implemented yet.

After Multiverse creates or imports a world, MMC initializes its Multiverse difficulty (peaceful), game mode (creative), auto-load and spawn loading options, disables ordinary entity spawning, and applies the v3 gamerules for random ticks, fire, weather, daylight, mob griefing, mob spawning, and advancement announcements. These settings are saved before the map becomes `ACTIVE`. `/mmc init <name>` reapplies them to an already loaded MMC map and requires `mapmanager.command.init`.

Initialization sets the Multiverse alias to `&3<name>` for private maps and `&2<name>` for public maps. The database stores alias text without Minecraft color codes; the Multiverse alias includes the visibility color. Creation and import use the world name when no alias is supplied, or the supplied alias otherwise. Manual initialization uses the saved alias. Changing a map's public status reads its current Multiverse alias, removes color codes, and applies the new color without changing the alias text. `MapAliasService#setAlias(UUID)` resets to the world name; `setAlias(UUID, String)` saves the supplied alias without color codes and updates the Multiverse alias.

## Import an existing world

Place the world folder directly under the server root, for example `server-root/buildworld/level.dat`, then run `/mmc import buildworld [normal|flat|void|nether|end] [alias=<display name>] [group=<group id>] [admin=<username>]`. The type defaults to `normal`; the command requires `mapmanager.command.import`. Import rejects a missing folder or missing `level.dat`. It follows the same `PENDING` to `ACTIVE` metadata and LuckPerms setup as creation. Failed imports leave the source world folder intact.

`/import` accepts the same arguments as `/mmc import`.

See [ARCHITECTURE.md](ARCHITECTURE.md) for module boundaries, role semantics, lifecycle flow, migration, and implementation order. See [API_INVENTORY.md](API_INVENTORY.md) for the source-based v3 interface classification.
