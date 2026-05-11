# PotatoMC v0.2 — Memory Module Design

**Date :** 2026-05-11
**Statut :** Design — autonome
**Cible :** Minecraft 1.21.11 (Fabric)

## Contexte

Le module **lighting** de v0.1 est shippable (correctness bit-exact, taggé `v0.1.0-beta`). Le perf vs vanilla plafonne à 0.08-0.36× sur les workloads synthétiques — vanilla 1.21 est très optimisé sur ce chemin. Pour livrer un gain user-visible significatif sur la cible "config légère", le prochain module attaque la **RAM**.

## Pourquoi memory

Sur une patate à 4 GB de RAM totale (8 GB partagés avec l'OS = ~4 GB pour Minecraft) :
- Un monde généré bouffe 1.5-3 GB de heap
- 30% de ce heap est du `BlockState` property bookkeeping (HashMaps multipartites)
- FerriteCore prouve depuis 2020 qu'on peut récupérer ces 30%

Gain visible : moins de GC pauses, plus de chunks chargeables avant OOM, framerate plus stable sur configs serrées. **C'est l'optim qui change littéralement la jouabilité** sur la cible.

## Concurrent

**FerriteCore 8.2.0-fabric** disponible sur 1.21.11. **C'est notre concurrent direct**, donc on bench frontalement dès le premier jet. Selon la philosophie du projet : on ne se mesure à un concurrent que quand on est en état de concurrencer — pour memory, c'est dès le jour 1 puisque l'algorithme est connu et bornée.

Optimisations FerriteCore connues :
1. **Property map dedup** sur les BlockState — remplace les `Map<Property<?>, Comparable<?>>` par une structure compacte partagée
2. **Multipart predicate dedup** — modèles de blocs qui partagent des predicates
3. **Empty nibble array reuse** — sections vides ne stockent pas 2048 bytes de zéros
4. **Predicate compactor** — predicates de items modèles dédupliqués

Total gain claim : 30-50% RAM sur monde généré.

## Notre approche

**Ne pas se contenter d'égaler FerriteCore.** Le faire mieux. Axes différenciants possibles :

1. **BlockPos / Vec3 pooling** — vanilla alloue énormément de BlockPos sur le chemin chaud. Un pool thread-local évite l'alloc + GC pressure. Mesurable via JFR allocation profiler. FerriteCore ne touche pas ça.
2. **String interning agressif sur les NBT tag keys** — les NBT compounds créent des strings courtes redondantes. Internalize via un cache faible.
3. **Compact PalettedContainer** — vanilla utilise 4 bits par bloc en moyenne mais sur chunks majoritairement air, on pourrait avoir 1-bit-mode. Risqué (affecte rendering + IO), reporté à v0.3.

Le **MVP du module memory** se concentre sur 1+2 (property map dedup + nibble dedup), qui matche directement FerriteCore. Si ça marche, on rajoute pooling.

## Architecture

Suit la même structure modulaire que le lighting :

```
potatomc/src/main/java/com/potatomc/memory/
├── PotatoMemory.java                  initialisation du module
├── dedup/
│   ├── PropertyMapInterner.java       intern table pour Map<Property, Comparable>
│   ├── NibbleArrayInterner.java       intern table pour les nibble arrays vides
│   └── CompactBlockState.java         structure compacte alternative (si nécessaire)
├── pool/
│   ├── BlockPosPool.java              thread-local pool
│   └── Vec3Pool.java                  idem
└── api/
    └── MemoryStats.java               expose les compteurs (entries interned, bytes saved)
```

Mixins :
```
potatomc/src/main/java/com/potatomc/mixin/memory/
├── StateMixin.java                    intercepte BlockState constructor → intern map
├── ChunkNibbleArrayMixin.java         intercepte allocation → intern empty
└── (autres ciblés)
```

## Mesure

**Outillage à ajouter dans potatomeasure :**

`/memory` endpoint qui retourne :
```json
{
  "potato_present": true,
  "heap_used_mb": 1245,
  "heap_committed_mb": 2048,
  "interned_property_maps": 18432,
  "estimated_bytes_saved": 47185920,
  "gc_pause_total_ms": 1234,
  "block_state_count": 8420
}
```

**Bench worldgen RAM** : `scripts/test-memory.sh` qui :
1. Boot un serveur en mode constrained (4 GB heap simulé)
2. Génère ou charge un monde fixe (1024×1024 blocs)
3. Mesure heap usage via JMX après stabilisation
4. Compare 3 modes : `vanilla` / `vanilla + FerriteCore` / `vanilla + PotatoMC`
5. Sortie : table CSV + Markdown report

## Correctness

**Tolérance : zéro divergence.** L'internalization des property maps est sémantiquement neutre — si deux BlockStates ont les mêmes propriétés, ils peuvent partager la map. Cas pathologiques :
- Mutable maps qu'on aurait par erreur dédupliquées → bug très grave
- Race conditions sur l'intern table → propriétés mélangées

Mitigation :
- L'intern table utilise `ConcurrentHashMap` + clés immutable
- Tests : créer 1000 BlockStates avec propriétés identiques, vérifier qu'ils partagent la même map référentiellement
- Validate via comparaison de jeu : charger un monde-fixture, vérifier que `getStateForNeighborUpdate`, `mirror`, `rotate` retournent les mêmes BlockStates

## Roadmap

**v0.2.0-alpha (1-2 semaines)** :
- PropertyMapInterner + Mixin
- NibbleArrayInterner pour arrays vides
- `/memory` endpoint + stats
- Tests unitaires sur l'interner

**v0.2.0-beta (+1 semaine)** :
- BlockPosPool + Vec3Pool
- Bench `scripts/test-memory.sh` vs FerriteCore + vanilla
- README updates

**v0.2.0** :
- Si on bat FerriteCore en RAM saved, c'est la release. Sinon on documente l'écart et on identifie le gap.

## Succès = quoi ?

v0.2 est validé si :
1. RAM heap usage sur world-fixture **≥ FerriteCore + 10%** (on les bat de 10% ou plus)
2. Zéro régression de gameplay (no crash, no visual bug, all tests pass)
3. Co-installation avec FerriteCore propre (détection runtime + cession)
4. Mod jar prod reste ≤ 100 KB
