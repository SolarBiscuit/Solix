# Solix Fabric edition

Forge 1.20.1 (`main`) is the primary version of Solix. The Fabric 1.20.1 edition lives on the branch `fabric-1.20.1` and
gets the Forge changes in occasional backports (major updates, or after a long gap). Both use Mojang mappings, so the light
engine source is the same on both branches and a backport is a `git merge`.

## Versions

- `mod_version` in `gradle.properties` is the Forge version the branch is synced to. The jar is
  `solix-<mod_version>-mc1.20.1-fabric.jar`, and fabric.mod.json says `<mod_version>+fabric`.
- A Fabric-only change between backports sets `fabric_revision` (1, 2, …): `0.3.7+fabric.1`, `0.3.7+fabric.2`, …
- A backport sets `mod_version` to the new Forge version and empties `fabric_revision`.
- Every build gets a `CHANGELOG.txt` entry headed with its full version (`0.3.7+fabric.1 (date)`).

## Backport procedure

The branch is checked out as a git worktree next to the Forge project (`Solix OtherBuilds/[1.20.1] Solix Fabric`).

1. In the worktree: `git merge main`.
2. Resolve the conflicts. They should only be in the files in the table below, the build files, and `README.md`.
   Keep the Fabric side of each loader-specific spot and take everything else from `main`.
3. Check the Forge changes for new loader-specific code: `net.minecraftforge` imports, Forge-only methods
   (`getLightEmission(level, pos)`, `getExistingBlockEntity`, …), new access transformer lines (translate them into
   `solix.accesswidener`), and new mixins (add them to `solix.mixins.json`; a mixin on Forge-patched code may need a
   different injection point on Fabric).
4. `gradle.properties`: `mod_version` = the Forge version, `fabric_revision` empty. Add a changelog entry.
5. `./gradlew build`, then run the checks in "Testing".

## Where Fabric differs from Forge

| File | Fabric | Forge |
|---|---|---|
| `build.gradle`, `settings.gradle`, `gradle.properties` | Fabric Loom 1.7, Mojang mappings, Fabric Loader | ForgeGradle 6 |
| `fabric.mod.json` | id `solix`, `provides: starlight`, `breaks` scalablelux/phosphor/connectormod | `META-INF/mods.toml`, `pack.mcmeta` |
| `solix.accesswidener` | access widener (named) | `META-INF/accesstransformer.cfg` (SRG) |
| `Solix.java` | no entrypoint; debug logging only | `@Mod`, DisplayTest, event listeners |
| `mixin/debug/MinecraftServerDebugMixin` | Fabric only: debug logging after the levels load | (events in `Solix.java`) |
| `mixin/SolixMixinPlugin` | `FabricLoader`; ignores Solix's own provided `starlight` id; gates the debug mixin | `LoadingModList` |
| `common/config/SolixConfig` | `FabricLoader#getConfigDir` | `FMLPaths.CONFIGDIR` |
| `common/blockstate/PositionalEmission` | Porting Lib's `LightEmissiveBlock` (by name), `getLightEmission` via a method handle | Forge's `IForgeBlock`, Zeta special case |
| `common/light/StarLightEngine#callEmission` | per-state emission unless Porting Lib positional | Forge's `getLightEmission(level, pos)` |
| `common/light/LightBlockGetter#getBlockEntity` | `chunk.blockEntities.get(pos)` (AW) | `getExistingBlockEntity` |
| `mixin/compat/c2me/C2MEChunkDataSerializerMixin` | redirects `FabricLoader#isModLoaded` (redundant with `provides`, kept as a safety net) | redirects `ModList#isLoaded` |

Everything else (scheduler, FlowSched, StarLightInterface and its anonymous classes, SWMRNibbleArray, all other mixins)
is identical.

## Fabric compatibility notes

- `provides: starlight` is what makes Bobby and C2ME use their Starlight code paths. Bobby mixes into
  `StarLightInterface$1/$2` (`getDataLayerData`, `getLightValue`), and C2ME resolves `SWMRNibbleArray`, `SWMRNibbleArray$SaveState`
  and `ExtendedChunk` by name. Do not add or reorder anonymous classes in `StarLightInterface`, and do not rename those classes.
- A provided id has Solix's version. If a mod ever declares a version condition on `starlight`, replace `provides` with a
  nested stub mod (id `starlight`, version `1.1.2+solix`) via Loom `include`.
- Porting Lib (Create Fabric, Farmer's Delight Refabricated, Lodestone, …) wraps the vanilla `BlockLightEngine#getEmission` with
  its positional `LightEmissiveBlock#getLightEmission`. Solix does not run that engine, so `PositionalEmission` does the same
  for the Starlight engine.

## Testing

- `./gradlew runServer` / `runClient` (dev, Mojang names, `mixin.debug.verify` on): world creation, `Solix: light propagation = …`
  in the log, no mixin errors.
- Light must match the Forge build: relight the same vanilla test world with both and compare with `lightdiff.py --blocks`
  (the Forge harness in `sfx/solix-work`).
- Mod stacks: Sodium + Iris + Lithium + C2ME + ModernFix + FerriteCore + Krypton + Bobby + Xaero, and a Porting Lib mod with
  positional emission (Create Fabric copycats). `-Dsolix.debug.positionalEmission=true` lists the blocks Solix treats as
  position-aware.
