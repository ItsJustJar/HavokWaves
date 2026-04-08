# HavokWaves (Paper 1.21.10 + Folia)

HavokWaves is a production-oriented Minecraft plugin that simulates water waves for vanilla clients using PacketEvents packet block changes and server-side buoyancy.

## Core Design

- Visual wave rendering is packet-only and per-player.
- The world is never permanently modified for wave visuals.
- "Interactable" behavior is implemented with server-authoritative buoyancy (players + boats).
- Paper and Folia are both supported through runtime scheduler routing.

## How It Works

### 1) Packet Illusion (Visuals)

- The plugin finds surface-water columns around each player.
- It computes wave height `h(x,z,t)` from one shared wave model with:
  - drifting oval/circular swell sets
  - sporadic packet gating (`occurrence`)
  - shoreline attenuation (shallower water => smaller/less frequent waves)
- It sends temporary block updates with PacketEvents to that player only:
  - crest: fake water appears above the surface in moving packets
  - shoreline run-up: crest packets can push up to 6 blocks inland then recede
  - shoreline hit particles: splash/bubble bursts when wave fronts impact shore
- Each player has an active fake-block cache to keep visuals synchronized and restore originals cleanly.

### 2) Interaction / Buoyancy (Physics Feel)

- Packet block changes are visual only, so physics interaction is done server-side.
- Players in water/swimming get smooth velocity-based vertical corrections toward wave height.
- Player buoyancy is crest-following with a dead-zone to avoid "stuck bouncing."
- Boats near/on water get smooth bobbing with the same wave function.
- Clamping and smoothing avoid hard teleports and reduce jitter.
- Simulated drowning can occur when a visual crest submerges a player's eyes.

### 3) Paper vs Folia Scheduling

- Runtime detection chooses scheduler strategy:
  - Paper: Bukkit scheduler on main thread.
  - Folia:
    - entity scheduler for per-player updates and buoyancy
    - region scheduler for chunk/world reads during surface scans
- This keeps API access thread-safe for both server types.

## Commands

- `/waves on` - enable waves for yourself.
- `/waves off` - disable waves for yourself.
- `/waves reload` - reload config (`waves.admin`).
- `/waves debug` - print runtime + key config values (`waves.admin`).

## Permissions

- `waves.use`
- `waves.admin`

## Build

```bash
mvn clean package
```

Built jar:

- `target/havokwaves-1.0.0.jar`

## Configuration

See `src/main/resources/config.yml` for defaults including:

- simulation/render radius
  - For smoother movement, keep `simulation-radius` larger than `render-radius` (for example `48` vs `32`).
- update interval
- clear vs storm wave profile
- wave `frequency` control (lower = fewer/slower wave cycles)
- `height-variation` control (higher = more mixed crest heights)
- `occurrence` control (lower = sporadic packets, higher = frequent packets)
- buoyancy tuning for players and boats
- max packet block updates per tick per player
- world whitelist and optional per-world overrides

## Performance Notes

- Increase `update-interval-ticks` to reduce CPU/network load.
- Keep `render-radius` and `simulation-radius` reasonable.
- Use `max-block-updates-per-tick-per-player` to cap per-player packet load.
- Surface scans are cached and re-run incrementally on movement/chunk changes rather than every tick.

## Honesty / Limitations

- Packet-based waves are a per-player illusion.
- Interactable behavior is buoyancy adjustment, not true fluid height changes.
- A stepped/blocky look can occur due to block-based rendering.
- Tune amplitude, wavelength, frequency, and update interval to reduce visual artifacts.
