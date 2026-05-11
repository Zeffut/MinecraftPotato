# PotatoHarness — Headless Minecraft Control Plane

**Date :** 2026-05-11
**Statut :** Design — validé
**Type :** Outil de dev interne (non distribué aux end-users)

## Contexte & motivation

PotatoMC est développé via l'outillage de subagents autonomes. Pour que les subagents et le contrôleur puissent **tester eux-mêmes** les optimisations (placer un bloc, lire un light level, valider contre vanilla, bench), il faut un canal de contrôle programmatique sur une instance Minecraft headless. Sans ça, aucune itération autonome possible — chaque vérification exige un humain dans la boucle.

## Architecture

```
┌──────────────────────────────────────────┐
│ Subagent / contrôleur / CI                │
│   $ scripts/pmh place glowstone 0 4 0     │
│   $ scripts/pmh light 1 4 0 --json        │
│   $ scripts/pmh validate --radius 4       │
└──────────────────┬───────────────────────┘
                   │ HTTP localhost:25585
                   ▼
┌──────────────────────────────────────────┐
│ Minecraft Dedicated Server (headless)     │
│ Bootée par ./gradlew runServer            │
│   ┌────────────────────────────────────┐ │
│   │ PotatoMC mod                        │ │
│   │   └─ HarnessServer (HTTP, dev only) │ │
│   │       └─ enregistre endpoints       │ │
│   │       └─ exécute via server.execute │ │
│   └────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

**Serveur HTTP** : `com.sun.net.httpserver.HttpServer` (JDK built-in, zéro dépendance externe). Bind `127.0.0.1:25585`. Démarré uniquement si propriété système `potatomc.dev=true` (set par Loom dans tous les `runX` tasks).

**Sécurité** : pas d'auth (loopback only, dev-only). En build prod (`./gradlew build`), le flag est absent donc le `HarnessServer.start()` est no-op.

**Exécution thread-safe** : Minecraft est strict sur les accès au monde — seul le main server thread peut lire/écrire. Toutes les actions qui touchent au monde sont **soumises à `server.execute(Runnable)`** et le résultat est récupéré via `CompletableFuture` avec timeout.

## Endpoints HTTP

| Méthode | Path | Body / Query | Réponse |
|---|---|---|---|
| `GET` | `/health` | — | `{"status":"ok","mc_version":"1.21.11","mod_version":"0.1.0"}` |
| `POST` | `/cmd` | `{"command":"setblock 0 4 0 glowstone"}` | `{"success":true,"output":"..."}`  (exécute comme console) |
| `GET` | `/light/{x}/{y}/{z}` | — | `{"vanilla_block":14,"vanilla_sky":15,"potato_block":14,"potato_sky":15,"match":true}` |
| `GET` | `/stats` | — | `{"bfs_ops":N,"sections_tracked":N,"allocations":N,"engine_active":true}` |
| `POST` | `/validate` | `{"center":[0,4,0],"radius":4}` | `{"total_blocks":N,"diff_count":N,"max_delta":N,"pass":bool}` |
| `POST` | `/world/load` | `{"name":"fixture-flat"}` | `{"loaded":true}` |
| `POST` | `/world/new` | `{"type":"flat","seed":0,"name":"test"}` | `{"created":true}` |
| `POST` | `/bench/micro` | `{"workload":"single_block","iterations":10000}` | `{"ops_per_sec":N,"p50_ns":N,"p95_ns":N,"p99_ns":N}` |
| `POST` | `/shutdown` | — | `{"shutdown":true}` (puis kill server graceful) |

Erreurs : HTTP 4xx/5xx avec body `{"error":"..."}`. Tout endpoint qui touche au monde sans qu'un monde soit chargé → `409 Conflict {"error":"no world loaded"}`.

## CLI wrapper — `scripts/pmh`

Bash script. Sous-commandes mirror des endpoints :

```bash
scripts/pmh start [--world NAME]      # boot serveur en background, attend /health OK
scripts/pmh stop                       # POST /shutdown
scripts/pmh cmd "setblock 0 4 0 glowstone"
scripts/pmh place glowstone 0 4 0     # syntactic sugar pour /cmd setblock
scripts/pmh light 1 4 0 [--json]
scripts/pmh stats [--json]
scripts/pmh validate --radius 4
scripts/pmh world load fixture-flat
scripts/pmh world new --type flat --seed 0
scripts/pmh bench micro --workload single_block --iterations 10000
scripts/pmh logs [-f]                  # tail le log du serveur
```

Output : par défaut pretty-printed humain, `--json` pour subagents.

Le start gère :
- Acceptation auto de l'EULA (`eula.txt` écrit avec `eula=true`)
- Lancement de `./gradlew runServer --no-daemon` en background
- Polling de `GET /health` jusqu'à 200 OK ou timeout 60s
- PID stocké dans `.pmh/server.pid`
- Logs dans `.pmh/server.log`

## Tests & validation

**Tests unitaires Java** : parsing JSON des requests, sérialisation des responses, routing.

**Tests d'intégration shell** (`scripts/test-pmh.sh`) :
1. `pmh start --world test` → vérifier `/health` répond
2. `pmh cmd "setblock 0 4 0 glowstone"` → vérifier success
3. `pmh light 0 4 0` → vérifier `vanilla_block == 15`
4. `pmh stats` → vérifier `engine_active`
5. `pmh stop` → vérifier process tué, port libéré

Run en CI. Chaque PR doit faire passer ce script.

## Risques & mitigations

| Risque | Mitigation |
|---|---|
| HTTP server actif en prod (faille de sécu) | Gate strict sur prop système `potatomc.dev`, vérifié par test unitaire |
| Thread-safety violations sur le monde | Toutes les actions monde passent par `server.execute()` |
| Serveur ne boot pas (EULA, port pris) | CLI vérifie EULA, log clair, port configurable via env `PMH_PORT` |
| Fuite de ports en CI | `pmh stop` toujours appelé via trap, `.pmh/server.pid` cleanup |
| Subagent attend indéfiniment | Tous les appels HTTP côté CLI ont timeout 30s |

## Roadmap

**v0** (1er sprint) : `/health`, `/cmd`, `/light`, CLI `start/stop/cmd/light`. Suffit pour qu'un subagent boote le serveur et place/lit des blocs.

**v1** (2è sprint) : `/stats`, `/validate`, `/world/*`. Suffit pour valider le lighting engine v0.1.

**v2** (3è sprint) : `/bench/micro`, scripts CI, métriques de comparaison vs vanilla.

## Définition de done v0

- `scripts/pmh start --world test` lance un serveur, retourne quand `/health` répond, en moins de 60s.
- `scripts/pmh cmd "say hello"` retourne success.
- `scripts/pmh light 0 100 0` retourne du JSON valide avec les 4 champs.
- `scripts/pmh stop` tue le process et libère le port.
- `scripts/test-pmh.sh` passe en bash propre sans intervention humaine.
