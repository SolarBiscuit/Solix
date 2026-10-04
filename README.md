Fork of Starlight for that also backports features from ScalableLux Forge 1.20.1 (only NeoForge 1.21 supported for ScalableLux). A lot of bug fixes from later release of Starlight that were never fully ported to 1.20.1 are added as well as some new function (commits 6585522, 2084f46, 2b3204b, 4d59d86, and c4d051c are the ones of note).

Configuration options to disable the parallelism for the light scheduler are added so it behaves more like "standard" Starlight. 

Performance-wise, this should be perform as at least as well as Starlight, although I haven't formally benchmarked it 1:1 yet. 
**
Compatability:** Explicit compatability & testing done with C2ME (should be treated effectively like Starlight). Tested with 300+ Forge 1.20.1 mods. Also added in some exception checks with several popular mods, namely Quark. 

Legacy Starlight Description

Starlight (Forge)
==
Forge mod for completely rewriting the vanilla light engine.

## Known Issues
At this point in Starlight's development, I expect problems with mod conflicts.

## Download
[CurseForge (Forge)](https://www.curseforge.com/minecraft/mc-mods/starlight-forge)
[Modrinth (Forge)](https://modrinth.com/mod/starlight-forge)

## Contact
[Discord](https://discord.gg/tuinity)

## Results
~~The graph below shows how much time the light engine was active while generating 10404 chunks:~~
See "Notice about invalid gen test results" in [TECHNICAL_DETAILS.md](TECHNICAL_DETAILS.md) 
for why there is no 1.20 data for this test.

Below is a graph detailing how long light updates took for breaking/placing
a block on a large platform at y = 254 down to a large platform at y = 0:
![Block update at height graph](https://i.imgur.com/ZQx7Ek0.png)
- Tested via [LightBench](https://github.com/Spottedleaf/lightbench) on 1.20-rc1
- World is just a flat world with bedrock at y = 0 and grass at y = 254
- CPU: Ryzen 9 7950X

Below is a graph detailing light update times for a simple glowstone
place/break:
![Simple glowstone block update](https://i.imgur.com/MrA2PQk.png)
- Tested via [LightBench](https://github.com/Spottedleaf/lightbench) on 1.20-rc1
- World is just a flat world with bedrock at y = 0 and grass at y = 254
- CPU: Ryzen 9 7950X
- Tested breaking and placing the glowstone on the bedrock platform,
  where skylight could not interfere with the test.

The results indicate that Starlight is faster for light propagation, but 
the absolute times indicate that it is unlikely to affect FPS in any
situation on the client.

## Purpose
Currently, Starlight's light section management is depended on for both Paper's rewrite
of the chunk system (people in the modding space call this "TACS") and Folia's additional
changes to that system. In Starlight, light sections are tied directly to ChunkAccess objects.

The light section management prevents most bugs (including performance ones)
involving missing/absent light sections, as Starlight assumes that no updates
are possible unless the chunk exists. This is why there are basically zero performance or bug related
issues with the light section management in Starlight. 

This difference in light section management allows Paper's rewrite of the chunk system (which is designed
as a solid base for [Folia](https://github.com/PaperMC/Folia) to build on, which adds regionized multithreading
to the dedicated server) to greatly simplify and optimize its chunk unload/load logic as it no longer needs to
consider any light engine state as there is no light engine state.

The "stateless" property of Starlight allows the chunk system to run light updates / generation
in parallel provided the scheduling is done right, see patch "Increase parallelism for neighbour writing chunk statuses"
in 1.19 Folia. This is critical to allowing the chunk system to scale beyond 10 worker threads,
which is important for dedicated servers with more players to stress chunk generation.
