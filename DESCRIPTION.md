# ☀️ Solix

**A faster, smarter light engine for Forge 1.20.1.**
Built on Starlight's rewrite of the vanilla light engine, with ScalableLux's multithreading, and a set of new
optimisations of its own. Same lighting results as vanilla, just less work to get there.

---

## ✨ Why Solix?

Starlight made Minecraft lighting fast. ScalableLux made it run on multiple threads.
**Solix makes it do less work in the first place**, and fixes what the old Forge port got wrong.

| | Starlight (Forge) | ScalableLux | **Solix** |
|---|:---:|:---:|:---:|
| Rewritten light engine | ✅ | ✅ | ✅ |
| Light updates on multiple threads | ❌ | ✅ | ✅ |
| Queued light work ordered by distance to players¹ | ❌ | ❌ | ✅ |
| Placing a block only darkens blocks that lost light² | ❌ | ❌ | ✅ |
| Brightest-first light spreading | ❌ | ❌ | ✅ |
| Skips re-sending unchanged light to players | ❌ | ❌ | ✅ |
| Working opacity cache on Forge | ❌ | — | ✅ |
| Available for Forge 1.20.1 | ✅ | ❌ | ✅ |

<sub>¹ Server with parallel lighting enabled (more than one worker). Vanilla already hands chunks to the light engine roughly closest-first; Solix also orders the light work that is already queued.<br>² When a light source itself is removed, Solix uses Starlight's full darkening, because nearly everything around it loses its light anyway.</sub>

---

## ⚡ What makes it faster

### 🎯 Targeted darkness
When you place a block in a lit room, Starlight darkens the entire area behind it and then re-lights it from
scratch, even though almost all of those blocks are still lit from another direction.
Solix checks first and **only darkens the blocks that really lost their light**. Usually that's a handful
instead of hundreds.

> In our tests (single stone blocks placed and removed in a lantern-lit room): **14–20% less light time** and
> **47% fewer light writes** than Starlight's algorithm.

### 📶 Brightest-first spreading
Light is spread from the brightest level down, so every block is lit **once, at its final level**, instead of being
lit by a dim source and then overwritten by a brighter one.

### 📡 Less network traffic, fewer client re-renders
If a section's light ends up exactly the same after an update, Solix doesn't send it to players again.
Every light packet a client receives makes it re-render that area and its neighbours, so skipping them helps
**client FPS while others build nearby**.

> In our tests, **up to ~40%** of updated sections were unchanged and are no longer sent, depending on the edits.

### 🧵 Multithreaded and prioritised
Server lighting runs on several worker threads (ScalableLux's scheduler). Solix also lights **chunks closest to
players first** (when running with more than one worker), so the area around you is ready sooner when exploring or
generating new terrain.

### 🔧 Forge fixes
- **Working opacity cache.** The Forge port of Starlight computed a fast lookup for how much light each block
  blocks, then threw it away, so every light step took the slow path. Solix keeps it, but still falls back to the
  slow path for blocks that need it (dynamic shapes, modded blocks whose light depends on position).
- **Faster light-source scanning.** New chunks skip sections that cannot contain a light source.
- **Cheaper chunk loading.** The bookkeeping that decides which light sections exist now uses bitmasks.
  It runs for every chunk lit on the server and every chunk a client receives.

---

## ✅ Same lighting

Each release is verified by relighting a test world, applying block edits, and comparing the saved light block by
block against the previous version. **In our test world the result is identical.**
If you ever suspect a lighting issue, one config line switches back to Starlight's original algorithm.

---

## ⚙️ Configuration

`config/solix.toml` (restart to apply):

```toml
parallel = true          # multithreaded server lighting
parallelism = 0          # worker threads, 0 = automatic (CPU threads / 4)
throttle_ms = 10         # stops C2ME's light thread from busy-waiting
propagation = targeted   # targeted | ordered | classic (Starlight's original)
safe_light_view = true   # give other mods' light callbacks a view that never waits on the server thread
```

For benchmarking, `-Dsolix.debug.lightStats=true` logs light engine statistics every 10 seconds.

---

## 🧩 Compatibility

- **Checked against the RatPack 2.9 mod list** (~450 mods, including C2ME, ModernFix, Radium, Embeddium/Xenon,
  Oculus, Neo-Voxy, Flywheel/Create, Mekanism and AE2): no mod hooks the parts of the engine Solix changes.
  Runtime-tested on a dedicated server with C2ME and ModernFix.
- **Works on client, server, or both.** Vanilla clients can join a Solix server and the other way round.
- **Worlds are compatible** with Starlight 1.1.2 in both directions (same saved light format).
- ❌ **Do not install alongside** Starlight, ScalableLux or Lucis. Solix replaces them, and Forge will tell you if
  more than one is installed.

---

## ❓ FAQ

**Will this increase my FPS?**
On the client, lighting is a small part of each frame. You'll notice it most while blocks change around you: building,
redstone, explosions, other players working nearby. Servers benefit more: lighting work is lighter and spread
across threads.

**Does it change how things look?**
No. Light levels are the same as with Starlight and vanilla, only computed more efficiently.

**Is it safe to add to an existing world?**
Yes. Worlds lit by vanilla are relit automatically the first time chunks load.

---

## 💛 Credits

- **[Starlight](https://github.com/PaperMC/Starlight)** by Spottedleaf: the light engine Solix is built on.
- **[ScalableLux](https://github.com/RelativityMC/ScalableLux)** by RelativityMC: multithreaded scheduling.
- **FlowSched** by ishland: the bundled task executor (MIT).

Solix is licensed under **LGPL-3.0**.
