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
- 🚧 Threading workers (chunks distants → ForkJoinPool)
- 🚧 Batch scheduler
- ✅ Microbench harness comparatif vs vanilla (voir « Benchmark results » ci-dessous)
- 🚧 Comparatif Starlight / Phosphor (à venir)

### Benchmark results — v0.1 (seed=42, post-batching)

> ⚠️ **Disclaimer** : chiffres pris pendant qu'une autre tâche de dev tournait en parallèle sur la même machine. Seul le **ratio Potato/Vanilla mesuré dans le même run** est interprétable. Campagne reproductible sur machine idle planifiée.

Mesures via `scripts/pmh bench <workload>` (deux runs dos-à-dos, notre moteur puis vanilla via `EngineHolder.runBypassed`).

| Workload                | Iters | Potato ops/s | Vanilla ops/s | Speedup |
|-------------------------|-------|--------------|---------------|---------|
| `single_block_update`   | 200   | 1 737        | 72 522        | 0.024×  |
| `bulk_random_updates`   | 100   | 1 244        | 195 995       | 0.006×  |
| `full_chunk_relight`    | 50    | 617          | 79 396        | 0.008×  |
| `bulk_writes_no_read`   | 100   | 458          | 1 755         | 0.261×  |

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
```

## Specs & plans

- [Design du lighting engine](docs/superpowers/specs/2026-05-11-lighting-engine-design.md)
- [Design du harness](docs/superpowers/specs/2026-05-11-potato-harness-design.md)
- [Plan v0.1 lighting](docs/superpowers/plans/2026-05-11-lighting-engine-v0.1.md)
- [Plan v0 harness](docs/superpowers/plans/2026-05-11-potato-harness-v0.md)

## Licence

À définir. Pour l'instant : tout droits réservés, repo public pour transparence.
