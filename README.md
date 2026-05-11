# PotatoMC 🥔

**Mod d'optimisation extrême pour Minecraft 1.21.11 (Fabric).**

Ambition : remplacer à terme l'écosystème Sodium + Lithium + FerriteCore + Starlight + Krypton par un mod unifié, conçu pour des configs ultra légères sans sacrifier les configs musclées.

## État actuel — v0.1 (work in progress)

### Lighting engine custom

Réécriture complète du moteur de lumière (cible : 3-10× plus rapide que vanilla sur les chargements de chunks, à la manière de Starlight, mais maintenu sur 1.21.11).

- ✅ Storage bit-packed (`long[256]` par section, 4 bits par bloc, Z-curve Morton indexing)
- ✅ BFS pooled zero-allocation (queue préallouée 16k entrées, réutilisée par thread)
- ✅ Propagation cross-section (BFS world-space)
- ✅ Block light bit-exact vs vanilla (validé sur radius 1 → `diff_count: 0, max_delta: 0`)
- ✅ Sky light incrémental (heightmap cache + lazy column init + vanilla-truth import, bit-exact validé)
- ⚠️ Threading workers (ForkJoinPool pour les recompute de colonnes en parallèle) — implémenté, bit-exact validé, mais sans gain mesurable sur `bulk_writes_no_read` (cf. notes ci-dessous)
- 🚧 Batch scheduler
- ✅ Microbench harness comparatif vs vanilla (voir « Benchmark results » ci-dessous)
- 🚧 Comparatif Starlight / Phosphor (à venir)

### Constrained-environment bench — v0.2-wip (seed=42, 2026-05-11)

> Cible réaliste : free hosting / Aternos / VPS bon marché. `runServerLimited` boote le serveur avec
> `-XX:ActiveProcessorCount=1 -Xmx1G -Xms1G -XX:+UseSerialGC`. Le but est de mesurer le gain de notre
> batching + threading + memory layout sur la cible réelle, pas sur un M-series 10 cœurs où vanilla
> est déjà over-provisionné.

Reproduire : `scripts/test-bench-limited.sh` (limited) puis `scripts/test-bench.sh` (unrestricted).

| Workload                | Iters | Unrestricted (Potato / Vanilla / Speedup) | Limited 1c/1G (Potato / Vanilla / Speedup) |
|-------------------------|-------|--------------------------------------------|---------------------------------------------|
| `single_block_update`   | 100/200 | 3 086 / 106 788 / 0.029×                | 1 017 / 72 352 / 0.014×                     |
| `bulk_random_updates`   | 100   | 1 846 / 253 644 / 0.007×                   | 1 150 / 195 694 / 0.006×                    |
| `full_chunk_relight`    | 50/100 | 602 / 99 941 / 0.006×                     | 398 / 139 072 / 0.003×                      |
| `bulk_writes_no_read`   | 100   | 621 / 7 144 / 0.087×                       | 640 / 4 001 / **0.160×**                    |

> **Lecture honnête** : la contrainte 1-cœur/1 GB ralentit vanilla plus que nous sur `bulk_writes_no_read`
> (notre meilleur workload : batching différé exposable), faisant remonter le speedup de 0.087× → 0.160× (×1.84).
> Sur les autres workloads, la contrainte ne change pas le verdict — vanilla reste largement devant tant
> que le flush-on-read force un BFS sync à chaque itération. Conclusion : la cible ≥5× n'est pas
> atteinte ; le gap dominant reste algorithmique (flush-on-read, opacité partagée), pas du tuning JVM.

### Benchmark results — v0.2-wip (seed=42, post-incremental-sky-light)

> **Update — sky-light incrémental Case C** : les shifts de heightmap _vers le bas_ (cassage du bloc topmost) sont désormais traités en O(Δh) — on écrit sky=15 sur la plage de cellules nouvellement révélées et on seed un seul BFS. Pas de rebuild complet de colonne. Les shifts _vers le haut_ (Case B) tombent toujours sur le recompute complet (besoin d'un darken-BFS qu'on n'a pas encore). Validate `diff_count: 0`, `sky_max_delta: 0`. `bulk_writes_no_read` remonte de **458 → 638 ops/s** (+39 %, recovery partielle vers la baseline pré-sky de ~996 ops/s).

| Workload                | Iters | Potato ops/s | Vanilla ops/s | Speedup |
|-------------------------|-------|--------------|---------------|---------|
| `single_block_update`   | 200   | 3 622        | 98 296        | 0.037×  |
| `bulk_random_updates`   | 100   | 1 325        | 242 914       | 0.005×  |
| `full_chunk_relight`    | 50    | 561          | 93 926        | 0.006×  |
| `bulk_writes_no_read`   | 100   | 638          | 3 580         | 0.178×  |

### Benchmark results — v0.2-wip (seed=42, post-parallel-column-flush)

> ⚠️ **Disclaimer** : chiffres pris pendant qu'une autre tâche de dev tournait en parallèle sur la même machine. Seul le **ratio Potato/Vanilla mesuré dans le même run** est interprétable. Campagne reproductible sur machine idle planifiée.

Mesures via `scripts/pmh bench <workload>` (deux runs dos-à-dos, notre moteur puis vanilla via `EngineHolder.runBypassed`).

| Workload                | Iters | Potato ops/s | Vanilla ops/s | Speedup |
|-------------------------|-------|--------------|---------------|---------|
| `single_block_update`   | 200   | 2 820        | 108 973       | 0.026×  |
| `bulk_random_updates`   | 100   | 2 241        | 245 019       | 0.009×  |
| `full_chunk_relight`    | 50    | 574          | 47 715        | 0.012×  |
| `bulk_writes_no_read`   | 100   | 453          | 2 578         | 0.176×  |

> **v0.2-wip — column-parallel sky flush** : `flushPending()` dispatche désormais les recompute de colonnes sur un `ForkJoinPool` dédié (cores-1, threads daemon). Phase 1 (server thread) prefetch `getTopY` + import vanilla-truth pour les colonnes neuves — obligatoire car `World.getChunk` est server-thread-pinned et provoque un deadlock sinon. Phase 2 (worker pool) écritures `PackedLightStorage.set` synchronisées + BFS via `WorldBFSWorker` ThreadLocal. Bit-exact préservé (`validate radius=1 → diff_count: 0`). **Mais gain perf zéro sur `bulk_writes_no_read`** : 453 vs 458 ops/s avant — dans le bruit. Le bottleneck dominant n'est pas la parallélisation de colonnes mais :
> 1. `world.getBlockState()` (via OpacityCache) reste un point de contention partagé (ConcurrentHashMap lookups) ;
> 2. `PackedLightStorage.set synchronized` ajoute un coût constant à chaque écriture, même sur le chemin séquentiel ;
> 3. Le dispatch ForkJoin (submit + join + parallelStream split) ajoute une latence fixe par flush qui annule le gain pour 50 colonnes "courtes" (~256 writes + BFS local).
>
> Prochain candidat : éliminer le flush-on-read (rendre `getLightLevel` non-bloquant via valeurs stale-acceptables entre ticks), ce qui libérerait le batching différé déjà en place.

> **Post sky-light incrémental** : la correction du sky-light ajoute par `onBlockChanged` un `getTopY` + (si heightmap shifté) un `recomputeSkyForColumn` (BFS + écritures de 15 sur les cellules open-sky). C'est correct mais coûteux ; `bulk_writes_no_read` recule de ~0.5× → ~0.25× vanilla. Trade-off assumé pour v0.1 : **correctness avant tout** (sky-light bit-exact, validate radius=1 pass), perf à reprendre en v0.2.

> Post-optim (cached opacity + non-alloc access lambdas). Baseline pré-optim était `single_block_update` 14 839 / `bulk_random_updates` 4 686 / `full_chunk_relight` 645. Les chiffres Potato sont dans le bruit du baseline ; vanilla est plus rapide sur ce run (charge machine variable). **Conclusion honnête : les deux bottlenecks ciblés n'étaient pas le chemin chaud dominant** — le flush-on-read force toujours un BFS sync par itération de bench, ce qui domine tout le reste.

**`bulk_writes_no_read` — la forme réaliste.** Ce workload place 50 blocs lumineux puis fait **un seul** read en fin de tick, mimant ce que fait vraiment Minecraft (génération de chunk, explosion, cascade de pistons, redstone). C'est là que le batching différé est censé briller : au lieu de 50 flushes synchrones, on en fait un seul. Le résultat (~0.5× vanilla) le confirme — sur un workload où le batching peut s'exprimer, l'écart se réduit d'un ordre de grandeur par rapport aux workloads read-heavy (0.02×). Le BFS lui-même reste plus cher que vanilla, mais le batching récupère une partie significative du coût ; reste à attaquer le BFS (incrémental sky-light, ForkJoinPool, allocations).

**Lecture honnête** : on est encore loin derrière vanilla. Le batching différé est en place côté écriture (`onBlockChanged` queue les changements, le BFS est flushé sur tick ou avant un read), mais le bench lit la lumière après *chaque* placement, ce qui force un flush sync à chaque itération et annule le bénéfice du batching. Les vrais gains viendront quand on :

1. **Élimine le flush-on-read** — accepter de retourner une valeur légèrement stale entre ticks
2. **Recopie incrémentale du sky-light** — actuellement désactivé du chemin chaud (cf. plus bas)
3. **Threade les chunks distants** sur `ForkJoinPool`
4. **Élimine les allocations du chemin chaud** (`WorldBFSWorker.SectionAccess` anonyme)

Si on est encore loin de vanilla une fois ces 4 fixes faits, on regardera ce que fait Starlight et on bench frontalement.

Reproduire :

```bash
scripts/pmh start
scripts/pmh cmd 'forceload add -2 -2 2 2'
scripts/pmh bench single_block_update 200 42
scripts/pmh bench bulk_random_updates 100 42
scripts/pmh bench full_chunk_relight 50 42
scripts/pmh bench bulk_writes_no_read 100 42
# ou : scripts/test-bench.sh  (smoke test bout-en-bout)
```

### PotatoHarness — outil de test autonome

HTTP control plane embarqué dans le mod (`com.sun.net.httpserver`, zéro deps), actif uniquement en dev (`-Dpotatomc.dev=true`). Permet à des agents ou à la CI de piloter un serveur Minecraft headless sans interaction humaine.

```bash
scripts/pmh start                       # boot serveur Minecraft headless en ~20s
scripts/pmh cmd "forceload add 0 0"
scripts/pmh place glowstone 0 100 0
scripts/pmh light 1 100 0               # → {"vanilla_block":14,"potato_block":14,"match":true,...}
scripts/pmh validate 1 0 100 0          # → diff_count, max_delta vs vanilla
scripts/pmh stats                       # engine_active, sections_tracked
scripts/pmh stop
```

Endpoints : `/health`, `/cmd`, `/light/X/Y/Z`, `/stats`, `/validate`, `/shutdown`.

### Stack technique

- Minecraft 1.21.11 Fabric
- Java 21 + Gradle 9.4 + Fabric Loom 1.16.1
- Yarn mappings 1.21.11+build.5 / Fabric Loader 0.19.2 / Fabric API 0.141.3+1.21.11
- Mixin (Sponge) pour hijacker `WorldChunk.setBlockState`, `ChunkLightProvider.getLightLevel`, `LightingProvider.getLight`
- JUnit 5 pour les tests unitaires (45 passent actuellement)
- Bash + curl + jq pour la CLI `pmh`

## Méthodologie

Chaque module suit trois exigences :

1. **Module complet** — pas de stub, pas de "à finir plus tard"
2. **Outillage debug autonome** — on développe nos propres outils de profiling et validation
3. **Bench comparatif** — chaque optim mesurée contre la concurrence

## Architecture

```
┌─────────────────────────────────────────────────┐
│ Mixin Layer (interception vanilla)              │
│   WorldChunkMixin → onBlockChanged              │
│   ChunkLightProviderMixin → getLightLevel       │
│   LightingProviderMixin → getLight              │
├─────────────────────────────────────────────────┤
│ PotatoLightEngine                                │
│   ├─ PackedLightStorage (long[256] / section)   │
│   ├─ WorldBFSWorker (cross-section flood-fill)  │
│   ├─ MortonIndex (Z-curve)                       │
│   └─ PooledLongQueue (zero-alloc FIFO)          │
├─────────────────────────────────────────────────┤
│ HarnessServer (dev-only HTTP, port 25585)       │
│   /health /cmd /light /stats /validate /shutdown│
└─────────────────────────────────────────────────┘
```

## Build & dev

Le repo est un **monorepo Gradle** produisant deux jars Fabric :

- `potatomc/build/libs/potatomc-0.1.0.jar` — mod prod (engine + commandes + mixins, ~35 KB)
- `potatomeasure/build/libs/potatomeasure-0.1.0.jar` — outil dev/CI (HTTP harness + microbench)

`potatomeasure` ne compile **aucun** import `com.potatomc.*` : il accède au moteur via réflexion (`PotatoMCBridge`) ce qui lui permet de bencher aussi vanilla / Starlight / Lithium sans PotatoMC installé.

```bash
./gradlew build              # compile les deux jars + tests
./gradlew test               # uniquement les tests JUnit (45 tests)
./gradlew :potatomc:runServer  # serveur dev headless (charge potatomc + potatomeasure)
./gradlew :potatomc:runClient  # client dev
scripts/test-pmh.sh          # smoke test harness
scripts/test-engine.sh       # validation engine vs vanilla
scripts/test-bench.sh        # microbench fumée
scripts/test-bench-limited.sh  # microbench sous contrainte 1c/1G/SerialGC
```

## Specs & plans

- [Design du lighting engine](docs/superpowers/specs/2026-05-11-lighting-engine-design.md)
- [Design du harness](docs/superpowers/specs/2026-05-11-potato-harness-design.md)
- [Plan v0.1 lighting](docs/superpowers/plans/2026-05-11-lighting-engine-v0.1.md)
- [Plan v0 harness](docs/superpowers/plans/2026-05-11-potato-harness-v0.md)

## Licence

À définir. Pour l'instant : tout droits réservés, repo public pour transparence.
