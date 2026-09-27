# MapManager Core v4

MapManager Core v4 is a new implementation of the MapManager map-management plugin. This repository is the v4 project; the v3 checkout is used as a behavior and migration reference only.

## Framework decision

- MMC owns map metadata, map roles, and every map-entry decision.
- LuckPerms supplies permissions configured on the `worldbase` parent group. MMC may mirror map-role membership into child groups that inherit `worldbase`, but LuckPerms is not an authority for map admission.
- Multiverse-Core performs world creation, import, load, unload, spawn, and deletion operations. MMC owns the lifecycle workflow and its metadata updates.
- Public API contracts are separated from their implementation. Storage ports and storage models are internal to the implementation and are never part of the API module.
- JSON and MySQL storage are selectable backends behind the same internal repository contracts.
- Mutating operations return structured outcomes. A single notifier turns outcomes into messages, logs, and operation events, including failures in the form `failed, <reason>, <operation>`.

The Maven reactor uses `studio.lorian.mapmanager` as its group ID and contains `api` (public contracts) and `plugin` (Paper entry point and implementation scaffolds). All source code is Java. Config loading, JSON/MySQL storage, map settings, roles, and admission decisions are implemented. World lifecycle, teleportation, command registration, and external plugin adapters remain unfinished; the plugin does not publish its API services yet.

See [ARCHITECTURE.md](ARCHITECTURE.md) for module boundaries, role semantics, lifecycle flow, migration, and implementation order. See [API_INVENTORY.md](API_INVENTORY.md) for the source-based v3 interface classification.
