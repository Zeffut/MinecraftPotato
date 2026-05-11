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
- 🚧 Sky light (column rebuild + BFS, en cours)
- 🚧 Threading workers (chunks distants → ForkJoinPool)
- 🚧 Batch scheduler
- ✅ Microbench harness comparatif vs vanilla (voir « Benchmark results » ci-dessous)
- 🚧 Comparatif Starlight / Phosphor (à venir)

### Benchmark results — v0.1 baseline (premières mesures, seed=42)

> ⚠️ **Disclaimer** : ces chiffres ont été pris pendant qu'une autre tâche de dev tournait en parallèle sur la même machine. Les valeurs absolues sont à prendre avec précaution — seul le **ratio Potato/Vanilla mesuré dans le même run** est fiable (les deux moteurs subissaient la même charge système). Une vraie campagne de bench reproductible sur machine idle est planifiée pour v0.2.

Mesures via `scripts/pmh bench <workload>` sur le même serveur headless, en routant la même charge soit dans notre moteur soit dans vanilla via `EngineHolder.runBypassed`. Latences en nanosecondes.

| Workload              | Iters | Potato ops/s | Vanilla ops/s | Speedup | Potato p50 / p95 / p99 |
|-----------------------|-------|--------------|---------------|---------|------------------------|
| `single_block_update` | 200   | 16 893       | 74 902        | 0.23×   | 32k / 202k / 357k ns   |
| `bulk_random_updates` | 100   | 3 324        | 139 738       | 0.02×   | 249k / 931k / 1 222k ns|
| `full_chunk_relight`  | 50    | 550          | 85 812        | 0.006×  | 777k / 8 084k / 18 724k ns |

**Lecture** : sur ces 3 workloads synthétiques, notre moteur est actuellement plus lent que vanilla (vanilla bénéficie de DEFERRED batching + années d'optims). C'est la baseline avant d'attaquer les chemins chauds : pooling de queues cross-section, batching différé du BFS, élimination des reads de validation, threading des chunks distants. Le harness est en place pour mesurer chaque pas.

Reproduire :

```bash
scripts/pmh start
scripts/pmh cmd 'forceload add -2 -2 2 2'
scripts/pmh bench single_block_update 200 42
scripts/pmh bench bulk_random_updates 100 42
scripts/pmh bench full_chunk_relight 50 42
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

```bash
./gradlew build           # compile + tests
./gradlew test            # uniquement les tests JUnit
./gradlew runServer       # serveur dev headless (avec harness)
./gradlew runClient       # client dev (avec harness)
scripts/test-pmh.sh       # smoke test harness
scripts/test-engine.sh    # validation engine vs vanilla
```

## Specs & plans

- [Design du lighting engine](docs/superpowers/specs/2026-05-11-lighting-engine-design.md)
- [Design du harness](docs/superpowers/specs/2026-05-11-potato-harness-design.md)
- [Plan v0.1 lighting](docs/superpowers/plans/2026-05-11-lighting-engine-v0.1.md)
- [Plan v0 harness](docs/superpowers/plans/2026-05-11-potato-harness-v0.md)

## Licence

À définir. Pour l'instant : tout droits réservés, repo public pour transparence.
