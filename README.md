# Mists

A Fabric 1.20.1 mod that adds a **world-progression system** to Minecraft. Built specifically for **maark's [True Survival](https://modrinth.com/modpack/minecraft-true-survival) modpack**.

> The player wakes on a small deserted island. The world beyond is hidden behind a wall of mist. Growing in strength (via the LevelZ skill system) is the only way to push the mist back and reach the lands beyond.

---

## What it does

- Forces world spawn onto a small, hand-shaped **plains starter island** (~4 chunks, organic outline, no animals, no structures, no extra resources).
- Pre-generates a **ring archipelago** of random vanilla-biome islands at increasing distances around spawn at world creation.
- Surrounds each player with a personal **mist boundary** — visible particles, hostile waters, and an invisible wall — at a radius determined by their **LevelZ total level**.
- As the player levels up, **the mist retreats**, revealing the next ring of islands. At level 30, the mist disappears entirely and the full vanilla world becomes accessible.

This mod does *not* alter biomes, mobs, structures, items, recipes, or any other vanilla mechanic. The mist is the only added element. Everything visible through it is real, pre-generated, vanilla-feel terrain.

---

## Progression tiers

| Tier | Required Level | Mist radius from spawn | What unlocks |
|---|---|---|---|
| 1 | 0 | 120 blocks | Spawn plains island only |
| 2 | 5 | 350 blocks | Tier 2 ring — 3–5 random islands, 6–16 chunks each |
| 3 | 10 | 650 blocks | Tier 3 ring — 3–5 random islands, 10–28 chunks each |
| 4 | 15 | 1000 blocks | Tier 4 ring — 3–5 random islands, 16–48 chunks each |
| 5 | 30 | ∞ (disabled) | Full vanilla worldgen beyond ~1000 blocks |

Each island in a ring rolls a random vanilla biome (or Biome Makeover biome if installed). On lucky rolls, the island may be large enough to contain a generated structure — including, on very lucky tier-2/3 rolls, a full woodland mansion.

---

## The mist (three layers)

The boundary is **not** a vanilla world border. It is a layered system:

1. **Visual mist ring** — semi-transparent particle wall at the boundary radius, ~30 blocks deep. Distant unlocked islands are visible as silhouettes through the haze. Accompanied by ambient howling-wind audio.
2. **Hostile waters** — a ~15-block band just inside the wall. Players who push into it suffer escalating Slowness, Nausea, and drowning damage. Brief exploration possible; sustained pushing is fatal.
3. **Invisible wall** — a hard server-side movement clamp at the inner edge. Boats stop. Swimming stops. Walking stops. Falling stops. Enderpearls thrown across the boundary are cancelled and refunded.

The boundary is a **full vertical column** from `y=-64` to `y=320`. Tunneling under and flying over are both blocked.

---

## Multiplayer

- **Per-player progression.** Each player has their own mist radius driven by their own LevelZ level. Two players on the same server at different levels will see and be blocked by different boundaries.
- A **higher-level player can sail to far islands alone**. A lower-level passenger sharing their boat will cause the boat to stop at the *lower* player's boundary (lowest-common-denominator for vehicles and mounts).
- Players are never visually hidden from each other — only the mist particles are per-viewer.

---

## Architecture

Three loosely-coupled subsystems:

1. **WorldGen (server-only)** — At world creation, after vanilla worldgen finishes the spawn region, this system: forces spawn onto a freshly-built plains island, places tier 2/3/4 island rings at the specified radii with random angles and sizes, carves any natural land inside the ring zone down to deep ocean, and writes `mists.dat` to the world save containing all island metadata.
2. **BoundarySystem (server-only)** — Per-player tick handler. Reads each player's LevelZ total level, computes their mist radius, enforces the invisible wall and hostile-water effects, and syncs the current radius to the client via a custom packet.
3. **MistRenderer (client-only)** — Receives radius packets and renders the partially-transparent particle ring centered on world spawn. Plays ambient audio when the player is near the boundary. Purely cosmetic; enforces nothing.

The server is fully authoritative. The client only displays what the server tells it.

---

## LevelZ integration

- The mod **reads** each player's LevelZ total level. It does not write LevelZ state.
- On a LevelZ level change, the player's tier is re-evaluated. If the player crosses a threshold, the server sends a `MistRetreatPacket` to that client and the new radius is animated over ~3 seconds with a low rumble cue.
- LevelZ is a hard required dependency. The mod will refuse to load without it.

---

## Bypass prevention

| Vector | Handling |
|---|---|
| Walking / swimming / boats | Server-side movement clamp on player tick |
| Falling | Vertical column extends full world height; clamp applies |
| Tunneling under | Full column extends to bedrock |
| Flying / Elytra | Same clamp regardless of `y` |
| Enderpearls | Cancelled and refunded if destination crosses the boundary |
| Chorus fruit | Random teleport destination clamped to allowed zone |
| Ridden mobs | Lowest-level rider's radius applies |
| Multi-passenger boats | Lowest-level passenger's radius applies |
| `/tp`, `/spreadplayers`, KubeJS scripts | Not blocked — admins/server scripts can bypass intentionally |

---

## Compatibility

Designed to coexist cleanly with the full True Survival v1.0.16 mod list:

- **LevelZ** (`levelz-true-survival-1.4.13`) — required, the progression source
- **ToughAsNails** — thirst/temperature unaffected; mist hostile-waters effect stacks naturally
- **HardcoreRevival** — death and K.O. handled vanilla; respawn returns to spawn island with whatever mist radius the player's level dictates
- **Enhanced Celestials** — no conflict; blood moons happen normally inside the unlocked zone
- **Biome Makeover** — biomes are eligible to roll on tier-ring islands
- **SereneSeasons** — season effects propagate normally across the world
- **Sodium / Lithium / FerriteCore / EntityCulling** — particle rendering uses standard Fabric API hooks; no expected conflict

No worldgen mods are touched outside the ~1000 block radius around spawn. Beyond that, the seed runs as if Mists were not installed.

---

## Death and respawn

- Vanilla and HardcoreRevival handle death entirely. This mod does not override death behavior.
- On respawn at world spawn, the player reappears on the spawn island with whatever mist radius their current level dictates.
- LevelZ progress is preserved through death (vanilla LevelZ behavior).

---

## Development status

Pre-implementation. This document is the design spec. The mod is being built from scratch in Fabric 1.20.1.

## Credits

- **Mod author:** [@xSIRDON](https://github.com/xSIRDON)
- **Modpack:** [True Survival](https://modrinth.com/modpack/minecraft-true-survival) by maark.
- Built specifically as a contribution to the True Survival experience.

## License

MIT — see [LICENSE](LICENSE).
