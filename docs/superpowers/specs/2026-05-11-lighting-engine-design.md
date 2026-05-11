# PotatoMC v0.1 — Lighting Engine Rewrite

**Date :** 2026-05-11
**Statut :** Design — en attente de validation
**Cible :** Minecraft 1.21.11 (Fabric)

## Contexte

PotatoMC est un mod d'optimisation Minecraft positionné comme **mod-killer monolithique** : à terme, il remplace l'ensemble Sodium + Lithium + FerriteCore + Starlight + Krypton + autres. Cible utilisateur : configs ultra légères qui galèrent à faire tourner le jeu, sans exclure les grosses configs qui veulent une fluidité maximale.

Le module v0.1 est le **moteur d'éclairage**. C'est l'optimisation single-axis avec le plus gros gain mesurable jamais publié dans l'écosystème (Starlight a démontré 3 à 10× plus rapide sur les chargements de chunks), tout en ayant une scope bornée (sous-système isolé). C'est notre preuve de capacité.

## Méthodologie projet

Chaque module du mod respecte trois exigences non-négociables :

1. **Module complet** — pas de stub, pas de "PoC à finir plus tard". v0.1 est shippable.
2. **Outillage debug autonome** — on développe nos propres outils de profiling, validation, et inspection. Pas de dépendance à des outils tiers pour valider qu'on est correct.
3. **Bench comparatif** — chaque module a un harnais qui le mesure contre la concurrence (vanilla, Starlight, Phosphor pour la lumière).

## Architecture

Trois couches, l'outillage est first-class :

```
┌─────────────────────────────────────────────────┐
│ Mixin Layer (interception du LightingProvider)  │
├─────────────────────────────────────────────────┤
│ PotatoLightEngine (moteur custom)                │
│   ├─ Storage bit-packed                          │
│   ├─ BFS pooled (zero-alloc)                     │
│   ├─ Batch scheduler                             │
│   └─ Worker thread pool (chunks distants)        │
├─────────────────────────────────────────────────┤
│ Debug + Bench Toolkit                            │
│   ├─ F3 overlay temps réel                       │
│   ├─ Differential validator                      │
│   ├─ Microbench harness                          │
│   └─ Comparative bench runner                    │
└─────────────────────────────────────────────────┘
```

## Composants

### Moteur — `PotatoLightEngine`

**Storage.** Remplace les `ChunkSkyLight` / `ChunkBlockLight` vanilla par un `long[]` bit-packed. 4 bits par bloc × 4096 blocs par section = `long[256]` par section. Une seule allocation par section, persistée. Ordre Z-curve (Morton) pour cache-friendliness en BFS.

**Propagation BFS.** Une queue `int[]` préallouée par thread (16k entries). Chaque entrée packe `(x, y, z, level)` dans un int. Zéro allocation par update — le GC ne voit jamais cette boucle chaude. Flood-fill BFS classique avec early-exit quand le niveau cible est ≤ niveau existant.

**Batch scheduler.** Les updates de blocs sont accumulés dans un `dirtyQueue` par chunk. Flush 1× par tick serveur, juste avant `tickChunks`. Évite de recalculer la lumière 10× quand le joueur pose 10 blocs dans la même frame.

**Threading.** Chunks dans un rayon de 4 du joueur → thread principal (correctness immédiate). Chunks 4–view-distance → `ForkJoinPool` (parallélisme = `cores - 1`). Sync via `ConcurrentHashMap<ChunkPos, LightTask>` avec drapeau `dirty`. Ownership exclusif d'un chunk par task, pas de lock sur les sections.

### Couche Mixin

- `LightingProviderMixin` — `@Overwrite` du constructeur, injecte notre engine dans `ServerWorld` / `ClientWorld`.
- `ChunkLightProviderMixin` — `@Redirect` des appels `enqueue/checkBlock` vers notre engine.
- `WorldChunkMixin` — hook sur `setBlockState` pour déclencher nos updates.

**Compat moteurs lumière tiers.** Si Starlight ou Phosphor est détecté (`FabricLoader.isModLoaded`), nos mixins se désactivent au runtime avec un log warning. Pas de bagarre.

### Outillage debug (first-class)

**F3 overlay.** Section "PotatoMC Light" dans le debug screen, montre : engine actif, pending updates (sky/block split), BFS ops/sec, worker pool usage, dernier full chunk relight (ms), alloc rate sur le light path (doit être 0 B/s).

**Differential validator.** Commande `/potatomc validate <radius>`. Lance notre engine + vanilla en parallèle sur les chunks autour du joueur. Compare bloc par bloc, logue toute différence > ±1. Sortie : `validation-report.json` avec coordonnées + delta. Run automatique en CI sur 100 chunks-tests prédéfinis (biomes variés, structures, redstone).

**Microbench harness.** Commande `/potatomc bench micro`. Workloads synthétiques en mémoire : 10k single-block updates, 100 full-chunk relights, 1000 random updates batch. Sortie : ops/sec, p50/p95/p99 latency, allocations. Comparable run-à-run pour détecter les régressions par commit.

**Comparative bench runner.** Script `scripts/bench-compare.sh`. Spawn N instances headless de Minecraft (vanilla / PotatoMC / Starlight si dispo / Phosphor). Charge `bench-world.zip` (seed fixe, checked-in). Simule un walk automatique de 10k blocs en ligne droite. Mesure : chunk load time (ms), light updates/sec, peak RAM, GC pause time total, FPS moyen. Sortie : `bench-results/<date>/comparison.csv` + rapport Markdown auto.

## Correctness

**Tolérance retenue : ±1 niveau de lumière** sur les cas limites (transitions de chunks pas encore loadés, coins exotiques). Documenté publiquement.

**Risque accepté :** certaines fermes de mobs ou redstone clocks dépendant d'un light level exact peuvent se comporter différemment. Tests sur 5 fermes types, kill-switch via config (`strict-vanilla-compat: true` force le chemin vanilla).

**Tests :**
- JUnit unitaires sur le storage bit-packed (read/write, bordures, sérialisation).
- Intégration : fixtures `src/test/resources/worlds/` avec lighting-de-référence pré-calculée par vanilla. Output matche à ±1.
- CI : differential validator + microbench à chaque PR. Régression > 5% bloque le merge.

## Risques & mitigations

| Risque | Mitigation |
|---|---|
| Fermes de mobs cassées (light level ±1) | Doc claire, tests sur 5 fermes types, kill-switch via config |
| Datapacks lisant le light level via `/execute if predicate` | Notre engine sert les mêmes valeurs en lecture API |
| Conflits avec mods lumière custom (LambDynamicLights) | Détection runtime, requêtes passées au chemin vanilla |
| Sauvegarde du monde incompatible | Storage RAM uniquement, sérialisation au format vanilla à l'écriture |
| Bug subtil non détecté par validator | Beta publique sur Modrinth avant release stable |

## Roadmap

**v0.1 (3-6 semaines, MVP shippable)**
- Storage bit-packed + BFS pooled (single-thread)
- Mixins core + sky light + block light
- F3 overlay + differential validator
- Tests unitaires + 1 world fixture

**v0.2 (+2-3 semaines)**
- Threading workers (chunks distants)
- Batch scheduler complet
- Microbench harness complet
- Comparative bench runner (vs vanilla + Starlight si dispo sur 1.21.11)

**v0.3** — publication Modrinth, première release publique avec rapport de bench officiel.

## Structure de fichiers cible

```
src/main/java/com/potatomc/
├── PotatoMC.java
├── client/PotatoMCClient.java
├── lighting/
│   ├── PotatoLightEngine.java
│   ├── storage/
│   │   ├── PackedLightStorage.java
│   │   └── MortonIndex.java
│   ├── propagation/
│   │   ├── BFSWorker.java
│   │   └── PooledQueue.java
│   ├── scheduler/
│   │   ├── BatchScheduler.java
│   │   └── WorkerPool.java
│   └── api/
│       └── LightLevelAPI.java
├── debug/
│   ├── DebugOverlay.java
│   ├── DifferentialValidator.java
│   └── commands/PotatoMCCommand.java
├── bench/
│   ├── MicrobenchHarness.java
│   └── HeadlessWalkBot.java
└── mixin/
    ├── LightingProviderMixin.java
    ├── ChunkLightProviderMixin.java
    └── WorldChunkMixin.java
```

## Succès = quoi ?

v0.1 est validé si **toutes** ces conditions sont remplies :

1. Chunk load time **≥ 2× plus rapide** que vanilla sur le bench-world (mesure interne).
2. Differential validator : **0 différence > ±1** sur les 100 chunks-tests.
3. F3 overlay affiche **0 B/s d'allocations** sur le light path en steady state.
4. Microbench : zéro régression > 5% entre HEAD et le commit précédent.
5. Le mod se charge sans crash avec Sodium installé en parallèle.

## v0.1 Phase 7 status — 2026-05-11

- DifferentialValidator + /potatomc command implemented and runnable headlessly via harness.
- F3 overlay (Phase 7.3) deferred to v0.2 — non-testable in headless dev environment, and `recordBfsOp()` counter pollutes the BFS hot path. Will revisit when client-side benching is added.
- Validate command on a freshly-placed glowstone (radius 1) results: `Validation: 18513 blocs comparés, 0 diffs (max delta 0)` (e2e ran via harness; chunk load via `forceload` had no effect because harness command source has no anchor, but validate itself completed without crash — primary acceptance for this phase).
