# PotatoMC ↔ PotatoMeasure — Split design

**Date :** 2026-05-11
**Statut :** Design — validé

## Motivation

L'utilisateur veut un mod **final léger** sur Modrinth. Actuellement `com.potatomc.harness.*` (~10 KB de classes) est compilé dans `potatomc.jar` même si dead-code en prod (gate `potatomc.dev=true`). Pour les end-users : poids inutile, surface d'attaque inutile, classes Java inutiles dans le classpath.

Second besoin : pouvoir **bencher Starlight / Lithium / Phosphor** plus tard. Le harness doit fonctionner sans `potatomc.jar` installé.

## Architecture cible

```
MinecraftPotato/                          (ce repo, monorepo)
├── potatomc/                             (sous-projet :potatomc)
│   ├── build.gradle                      → produit potatomc.jar (mod final, lean)
│   └── src/main/java/com/potatomc/
│       ├── PotatoMC.java                 entrypoint
│       ├── lighting/                     moteur custom
│       ├── debug/commands/               /potatomc command
│       └── mixin/                        mixins lighting
└── potatomeasure/                        (sous-projet :potatomeasure)
    ├── build.gradle                      → produit potatomeasure.jar (mod dev/CI)
    └── src/main/java/com/potatomeasure/
        ├── PotatoMeasure.java            entrypoint
        ├── harness/                      HTTP server, endpoints
        └── bench/                        Microbench
```

Les deux jars sont **deux mods Fabric distincts**. Chacun a son `fabric.mod.json`.

## Dépendances

- `potatomc.jar` : zéro dépendance sur PotatoMeasure. Le moteur tourne seul.
- `potatomeasure.jar` : dépendance **optionnelle** sur `potatomc` via `fabric.mod.json` "suggests" (non bloquante).
  - Si potatomc est présent → `/light` retourne block engine values, `/stats` montre `sections_tracked`, `/validate` est disponible.
  - Si potatomc est absent → `/light` retourne vanilla only, `/stats` montre `engine_active: false`, `/validate` répond `503 engine not installed`.
- Mécanisme de découverte : `FabricLoader.isModLoaded("potatomc")` au boot, route via réflexion ou via une interface optionnelle.

### Découverte propre via reflection

PotatoMeasure n'importe pas `com.potatomc.PotatoMC` directement (sinon il devient une hard dep). À la place :

```java
public final class PotatoMCBridge {
    private static final Class<?> ENGINE_CLASS;
    private static final Object ENGINE_INSTANCE;
    private static final java.lang.reflect.Method GET_LIGHT_LEVEL;
    static {
        Class<?> c = null; Object inst = null; java.lang.reflect.Method m = null;
        if (FabricLoader.getInstance().isModLoaded("potatomc")) {
            try {
                c = Class.forName("com.potatomc.PotatoMC");
                inst = c.getField("LIGHT_ENGINE").get(null);
                Class<?> api = Class.forName("com.potatomc.lighting.api.LightLevelAPI");
                Class<?> typ = Class.forName("com.potatomc.lighting.api.LightLevelAPI$LightType");
                m = api.getMethod("getLightLevel", BlockPos.class, typ);
            } catch (Exception ignored) {}
        }
        ENGINE_CLASS = c; ENGINE_INSTANCE = inst; GET_LIGHT_LEVEL = m;
    }
    public static boolean isPresent() { return ENGINE_CLASS != null; }
    public static int getBlockLight(BlockPos pos) { /* invoke GET_LIGHT_LEVEL with BLOCK enum */ }
    public static int getSkyLight(BlockPos pos) { /* invoke with SKY enum */ }
}
```

Une fois l'initialisation faite, l'appel reflexif est cher mais cached (chaque méthode est résolue 1×). Acceptable pour un outil de bench.

## Gradle multi-module

Top-level `settings.gradle` :

```gradle
rootProject.name = 'potatomc-monorepo'
include 'potatomc', 'potatomeasure'
```

Top-level `build.gradle` est minimal (juste les repos communs).

Chaque sous-projet a son propre `build.gradle` avec Loom et sa propre version. Loom 1.16.1 supporte le multi-module sans difficulté.

`potatomeasure/build.gradle` a une `compileOnly project(':potatomc')` pour accéder aux types au compile (pas au runtime, on passe par reflection).

Le wrapper Gradle reste au top-level.

## Migration des fichiers

| Fichier actuel | Destination |
|---|---|
| `src/main/java/com/potatomc/harness/**` | `potatomeasure/src/main/java/com/potatomeasure/harness/**` (renommé package) |
| `src/main/java/com/potatomc/bench/**` | `potatomeasure/src/main/java/com/potatomeasure/bench/**` |
| `src/main/java/com/potatomc/PotatoMC.java` | `potatomc/src/main/java/com/potatomc/PotatoMC.java` (purgé du tick hook batching ? NON, on garde — fait partie de l'engine) |
| `src/main/java/com/potatomc/debug/**` | `potatomc/src/main/java/com/potatomc/debug/**` (commands restent dans le mod principal car ils s'adressent à l'utilisateur final) |
| Tout `lighting/`, `mixin/` | `potatomc/src/main/java/com/potatomc/...` |
| `scripts/pmh`, `scripts/test-*.sh` | racine, inchangé — fonctionnent en parlant à HarnessServer dans potatomeasure.jar |
| `src/main/resources/fabric.mod.json` | scindé en `potatomc/.../fabric.mod.json` (mod principal, entrypoint `com.potatomc.PotatoMC`) et `potatomeasure/.../fabric.mod.json` (mod test, entrypoint `com.potatomeasure.PotatoMeasure`) |

## Loom runs

Le `runServer` task doit charger LES DEUX mods (potatomc + potatomeasure) en dev pour que les tests marchent. Loom multi-module : `runServer` est défini dans le projet root et inclut les deux source sets via `mods { ... }`.

Document de référence : https://docs.fabricmc.net/develop/loom

## Conséquences

- Fichier `potatomc.jar` final ne contiendra **aucune** classe `com.potatomc.harness.*` ou `com.potatomeasure.*`.
- Les contributeurs peuvent forker uniquement `potatomeasure/` s'ils veulent bencher d'autres mods d'optim.
- Le repo monorepo reste simple à cloner / builder.

## Plan d'exécution (pour le subagent)

1. Créer le squelette multi-module (`settings.gradle`, `potatomc/build.gradle`, `potatomeasure/build.gradle`).
2. Déplacer les sources Java entre les deux modules avec `git mv` pour préserver l'historique.
3. Renommer les packages : `com.potatomc.harness` → `com.potatomeasure.harness`, `com.potatomc.bench` → `com.potatomeasure.bench`.
4. Écrire `PotatoMCBridge.java` (reflexion) côté `potatomeasure`.
5. Écrire `PotatoMeasure.java` entrypoint qui démarre `HarnessServer`.
6. Créer `potatomeasure/.../fabric.mod.json` séparé.
7. Purger l'entrypoint client de `potatomc` (`PotatoMCClient.java` n'a pas d'usage harness, peut rester ou être supprimé).
8. Vérifier que `./gradlew build` produit deux jars distincts dans `potatomc/build/libs/` et `potatomeasure/build/libs/`.
9. Vérifier que `./gradlew runServer` charge les deux mods.
10. Re-tester `scripts/test-pmh.sh` et `scripts/test-engine.sh`.
11. Update README pour expliquer la structure monorepo.
12. Commit + push.

## Non-objectifs

- Pas de publication séparée vers Modrinth dans cette itération (juste le split en interne).
- Pas de support "charger PotatoMeasure avec un autre mod d'optim que PotatoMC" dans cette itération — le bridge reflexif est neutre par construction mais les commandes `/validate`/`/stats` resteront non fonctionnelles sans potatomc. Ce support viendra avec le ticket "bench vs Starlight".
