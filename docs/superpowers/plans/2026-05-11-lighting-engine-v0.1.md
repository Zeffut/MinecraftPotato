# PotatoMC v0.1 Lighting Engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single-threaded, bit-packed, zero-allocation custom lighting engine for Minecraft 1.21.11 (Fabric), with first-class debug tooling (F3 overlay + differential validator) that proves correctness vs vanilla and measurable performance gains.

**Architecture:** Three layers — (1) custom `PotatoLightEngine` with packed `long[]` storage and pooled BFS propagation, (2) Mixin layer that hijacks vanilla `LightingProvider`, (3) integrated debug toolkit (commands, F3 overlay, differential validator). Threading and full bench harness are deferred to v0.2.

**Tech Stack:** Java 21, Fabric Loader 0.19.2, Fabric API 0.141.3+1.21.11, Yarn mappings 1.21.11+build.5, Fabric Loom 1.8, Mixin (Sponge), JUnit 5, Gradle 8.10.

**Scope of THIS plan:** v0.1 single-threaded path only. Excludes: worker thread pool, comparative bench runner, headless walk bot. Those get a follow-up plan post-v0.1 verification.

---

## File Structure (locked in upfront)

**Core engine:**
- `src/main/java/com/potatomc/lighting/PotatoLightEngine.java` — entrypoint
- `src/main/java/com/potatomc/lighting/storage/MortonIndex.java` — Z-curve indexer (pure math)
- `src/main/java/com/potatomc/lighting/storage/PackedLightStorage.java` — bit-packed `long[]` storage
- `src/main/java/com/potatomc/lighting/propagation/PooledQueue.java` — preallocated int[] queue
- `src/main/java/com/potatomc/lighting/propagation/BFSWorker.java` — flood-fill propagation
- `src/main/java/com/potatomc/lighting/api/LightLevelAPI.java` — vanilla-compatible read API
- `src/main/java/com/potatomc/lighting/CompatGuard.java` — runtime detection of competing lighting mods

**Mixin layer:**
- `src/main/java/com/potatomc/mixin/LightingProviderMixin.java`
- `src/main/java/com/potatomc/mixin/ChunkLightProviderMixin.java`
- `src/main/java/com/potatomc/mixin/WorldChunkMixin.java`

**Debug toolkit:**
- `src/main/java/com/potatomc/debug/commands/PotatoMCCommand.java` — `/potatomc <subcommand>` dispatcher
- `src/main/java/com/potatomc/debug/DifferentialValidator.java` — vs-vanilla diff tool
- `src/main/java/com/potatomc/debug/DebugOverlay.java` — F3 hook (client-only)

**Resources:**
- `src/main/resources/potatomc.mixins.json` (already exists, add mixins to list)
- `src/main/resources/potatomc.client.mixins.json` (already exists)

**Tests:**
- `src/test/java/com/potatomc/lighting/storage/MortonIndexTest.java`
- `src/test/java/com/potatomc/lighting/storage/PackedLightStorageTest.java`
- `src/test/java/com/potatomc/lighting/propagation/PooledQueueTest.java`
- `src/test/java/com/potatomc/lighting/propagation/BFSWorkerTest.java`
- `src/test/resources/worlds/fixture-flat.zip` (vanilla pre-computed reference, generated)

**Existing files modified:**
- `gradle.properties` (already correct)
- `build.gradle` — add JUnit dependencies + test source set
- `src/main/resources/fabric.mod.json` — register `PotatoMCCommand` lifecycle hook

---

## Phase 0 — Build verification

### Task 0.1: Verify Gradle build with corrected versions

**Files:**
- Read: `gradle.properties`, `build.gradle`

- [ ] **Step 1: Run build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. If it fails with "Unsupported unpick version", the yarn build is wrong — verify against `curl -s https://meta.fabricmc.net/v2/versions/yarn/1.21.11 | head -20` and update `gradle.properties`.

- [ ] **Step 2: Generate sources for inspection**

```bash
./gradlew genSources
```

Expected: BUILD SUCCESSFUL. Yarn-named Minecraft sources are now decompiled in the Gradle cache. Required for all subsequent Mixin work.

- [ ] **Step 3: Launch dev client once to smoke-test**

```bash
./gradlew runClient
```

Expected: Minecraft launches, main menu visible, log shows `[PotatoMC] Initialisation — optimisation extrême activée`. Close the client.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: verify build with fabric 1.21.11 toolchain"
```

---

## Phase 1 — Test infrastructure

### Task 1.1: Add JUnit to build.gradle

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add test dependencies**

Append to the `dependencies` block in `build.gradle`:

```gradle
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

Add after `java { ... }` block:

```gradle
test {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'skipped', 'failed'
    }
}
```

- [ ] **Step 2: Verify test task exists**

```bash
./gradlew tasks --all | grep -i test
```

Expected: `test - Runs the test suite.` appears.

- [ ] **Step 3: Create empty test source dir**

```bash
mkdir -p src/test/java/com/potatomc
```

- [ ] **Step 4: Commit**

```bash
git add build.gradle
git commit -m "chore: add JUnit 5 to build"
```

---

## Phase 2 — Storage layer

### Task 2.1: MortonIndex (Z-curve indexer)

**Files:**
- Create: `src/main/java/com/potatomc/lighting/storage/MortonIndex.java`
- Test: `src/test/java/com/potatomc/lighting/storage/MortonIndexTest.java`

Morton encoding interleaves the bits of x/y/z so spatially close blocks have close indices. This makes BFS cache-friendly. For a 16×16×16 section (4 bits per axis), the Morton index is 12 bits (0..4095).

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/potatomc/lighting/storage/MortonIndexTest.java
package com.potatomc.lighting.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MortonIndexTest {

    @Test
    void encodeOrigin() {
        assertEquals(0, MortonIndex.encode(0, 0, 0));
    }

    @Test
    void encodeUnitsInterleave() {
        // x=1 → bit 0, y=1 → bit 1, z=1 → bit 2
        assertEquals(0b001, MortonIndex.encode(1, 0, 0));
        assertEquals(0b010, MortonIndex.encode(0, 1, 0));
        assertEquals(0b100, MortonIndex.encode(0, 0, 1));
        assertEquals(0b111, MortonIndex.encode(1, 1, 1));
    }

    @Test
    void encodeMaxFitsIn12Bits() {
        int idx = MortonIndex.encode(15, 15, 15);
        assertTrue(idx >= 0 && idx < 4096, "idx=" + idx);
    }

    @Test
    void roundTripAllPositions() {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int idx = MortonIndex.encode(x, y, z);
                    assertEquals(x, MortonIndex.decodeX(idx), "x");
                    assertEquals(y, MortonIndex.decodeY(idx), "y");
                    assertEquals(z, MortonIndex.decodeZ(idx), "z");
                }
            }
        }
    }

    @Test
    void uniquenessAcrossSection() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    assertTrue(seen.add(MortonIndex.encode(x, y, z)));
        assertEquals(4096, seen.size());
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

```bash
./gradlew test --tests MortonIndexTest
```

Expected: COMPILATION FAILURE (class MortonIndex doesn't exist).

- [ ] **Step 3: Implement MortonIndex**

```java
// src/main/java/com/potatomc/lighting/storage/MortonIndex.java
package com.potatomc.lighting.storage;

public final class MortonIndex {

    private MortonIndex() {}

    private static int splitBy3(int v) {
        v = (v | (v << 8)) & 0x0300F00F;
        v = (v | (v << 4)) & 0x030C30C3;
        v = (v | (v << 2)) & 0x09249249;
        return v;
    }

    private static int compactBy3(int v) {
        v &= 0x09249249;
        v = (v | (v >> 2)) & 0x030C30C3;
        v = (v | (v >> 4)) & 0x0300F00F;
        v = (v | (v >> 8)) & 0xFF0000FF;
        v = (v | (v >> 16)) & 0x000003FF;
        return v;
    }

    public static int encode(int x, int y, int z) {
        return splitBy3(x) | (splitBy3(y) << 1) | (splitBy3(z) << 2);
    }

    public static int decodeX(int idx) { return compactBy3(idx); }
    public static int decodeY(int idx) { return compactBy3(idx >> 1); }
    public static int decodeZ(int idx) { return compactBy3(idx >> 2); }
}
```

- [ ] **Step 4: Run test, verify it passes**

```bash
./gradlew test --tests MortonIndexTest
```

Expected: 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/potatomc/lighting/storage/MortonIndex.java src/test/java/com/potatomc/lighting/storage/MortonIndexTest.java
git commit -m "feat(lighting): MortonIndex (Z-curve indexer)"
```

---

### Task 2.2: PackedLightStorage (bit-packed long[])

**Files:**
- Create: `src/main/java/com/potatomc/lighting/storage/PackedLightStorage.java`
- Test: `src/test/java/com/potatomc/lighting/storage/PackedLightStorageTest.java`

4 bits per block × 4096 blocks per section = 16384 bits = 256 `long`s. Block-light AND sky-light each get their own storage, so a section that stores both = 512 longs (4 KiB), vs vanilla's ~8 KiB+ with overhead. Single `long[256]` allocated once at section creation, reused for the section lifetime.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/potatomc/lighting/storage/PackedLightStorageTest.java
package com.potatomc.lighting.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackedLightStorageTest {

    @Test
    void newStorageIsZero() {
        PackedLightStorage s = new PackedLightStorage();
        for (int i = 0; i < 4096; i++) {
            assertEquals(0, s.get(i));
        }
    }

    @Test
    void setAndGetSingle() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(0, 15);
        assertEquals(15, s.get(0));
    }

    @Test
    void setAndGetIndependent() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(0, 15);
        s.set(1, 7);
        s.set(2, 3);
        assertEquals(15, s.get(0));
        assertEquals(7, s.get(1));
        assertEquals(3, s.get(2));
    }

    @Test
    void setMasksTo4Bits() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(10, 0xFF);
        assertEquals(15, s.get(10));
    }

    @Test
    void overwriteResetsValue() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(100, 15);
        s.set(100, 4);
        assertEquals(4, s.get(100));
    }

    @Test
    void boundaryIndices() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(0, 1);
        s.set(4095, 14);
        assertEquals(1, s.get(0));
        assertEquals(14, s.get(4095));
    }

    @Test
    void fillEvery16thWriteIsolated() {
        PackedLightStorage s = new PackedLightStorage();
        for (int i = 0; i < 4096; i += 16) s.set(i, 5);
        for (int i = 0; i < 4096; i++) {
            assertEquals(i % 16 == 0 ? 5 : 0, s.get(i));
        }
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

```bash
./gradlew test --tests PackedLightStorageTest
```

Expected: compilation failure.

- [ ] **Step 3: Implement PackedLightStorage**

```java
// src/main/java/com/potatomc/lighting/storage/PackedLightStorage.java
package com.potatomc.lighting.storage;

/**
 * 4 bits per block × 4096 blocks per section = 256 longs.
 * Indexed by Morton-encoded position (use {@link MortonIndex}).
 */
public final class PackedLightStorage {

    public static final int SECTION_SIZE = 4096;
    private static final int LONGS = 256;
    private static final int BITS_PER_VALUE = 4;
    private static final int VALUES_PER_LONG = 16;
    private static final long MASK = 0xFL;

    private final long[] data = new long[LONGS];

    public int get(int index) {
        int longIdx = index >>> 4;          // / 16
        int bitOffset = (index & 0xF) << 2; // (% 16) * 4
        return (int) ((data[longIdx] >>> bitOffset) & MASK);
    }

    public void set(int index, int value) {
        int longIdx = index >>> 4;
        int bitOffset = (index & 0xF) << 2;
        long cleared = data[longIdx] & ~(MASK << bitOffset);
        data[longIdx] = cleared | ((value & MASK) << bitOffset);
    }

    public void fill(int value) {
        long v = value & MASK;
        long packed = 0L;
        for (int i = 0; i < VALUES_PER_LONG; i++) packed |= v << (i * BITS_PER_VALUE);
        java.util.Arrays.fill(data, packed);
    }

    public long[] rawData() { return data; }
}
```

- [ ] **Step 4: Run test, verify it passes**

```bash
./gradlew test --tests PackedLightStorageTest
```

Expected: 7 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/potatomc/lighting/storage/PackedLightStorage.java src/test/java/com/potatomc/lighting/storage/PackedLightStorageTest.java
git commit -m "feat(lighting): PackedLightStorage (4-bit packed long[256] per section)"
```

---

## Phase 3 — Propagation layer

### Task 3.1: PooledQueue (zero-alloc int queue)

**Files:**
- Create: `src/main/java/com/potatomc/lighting/propagation/PooledQueue.java`
- Test: `src/test/java/com/potatomc/lighting/propagation/PooledQueueTest.java`

Preallocated `int[]` (default capacity 16384, ~16 KiB per thread). Auto-grows by doubling if a real-world workload exceeds it (rare, logged as a warning so we can tune the default).

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/potatomc/lighting/propagation/PooledQueueTest.java
package com.potatomc.lighting.propagation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PooledQueueTest {

    @Test
    void newQueueIsEmpty() {
        PooledQueue q = new PooledQueue(16);
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
    }

    @Test
    void enqueueThenDequeueFIFO() {
        PooledQueue q = new PooledQueue(16);
        q.enqueue(1);
        q.enqueue(2);
        q.enqueue(3);
        assertEquals(3, q.size());
        assertEquals(1, q.dequeue());
        assertEquals(2, q.dequeue());
        assertEquals(3, q.dequeue());
        assertTrue(q.isEmpty());
    }

    @Test
    void resetAllowsReuse() {
        PooledQueue q = new PooledQueue(8);
        q.enqueue(42);
        q.enqueue(7);
        q.reset();
        assertTrue(q.isEmpty());
        q.enqueue(99);
        assertEquals(99, q.dequeue());
    }

    @Test
    void growsWhenFull() {
        PooledQueue q = new PooledQueue(4);
        for (int i = 0; i < 100; i++) q.enqueue(i);
        for (int i = 0; i < 100; i++) assertEquals(i, q.dequeue());
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

```bash
./gradlew test --tests PooledQueueTest
```

Expected: compilation failure.

- [ ] **Step 3: Implement PooledQueue**

```java
// src/main/java/com/potatomc/lighting/propagation/PooledQueue.java
package com.potatomc.lighting.propagation;

/**
 * Preallocated FIFO int queue. Designed to be reset and reused across BFS
 * propagation passes without allocating.
 */
public final class PooledQueue {

    private int[] data;
    private int head;
    private int tail;

    public PooledQueue(int initialCapacity) {
        if (initialCapacity < 2) initialCapacity = 2;
        this.data = new int[initialCapacity];
    }

    public boolean isEmpty() { return head == tail; }
    public int size() { return tail - head; }

    public void enqueue(int value) {
        if (tail == data.length) grow();
        data[tail++] = value;
    }

    public int dequeue() {
        return data[head++];
    }

    public void reset() {
        head = 0;
        tail = 0;
    }

    private void grow() {
        int newLen = data.length * 2;
        int[] bigger = new int[newLen];
        System.arraycopy(data, head, bigger, 0, tail - head);
        tail -= head;
        head = 0;
        data = bigger;
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

```bash
./gradlew test --tests PooledQueueTest
```

Expected: 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/potatomc/lighting/propagation/PooledQueue.java src/test/java/com/potatomc/lighting/propagation/PooledQueueTest.java
git commit -m "feat(lighting): PooledQueue (zero-alloc FIFO for BFS)"
```

---

### Task 3.2: BFSWorker (flood-fill propagation, section-local)

**Files:**
- Create: `src/main/java/com/potatomc/lighting/propagation/BFSWorker.java`
- Test: `src/test/java/com/potatomc/lighting/propagation/BFSWorkerTest.java`

For v0.1, the worker operates **within a single 16×16×16 section**. Cross-section propagation is handled at the engine layer (v0.1 punts it: edge updates flagged dirty for neighbor sections, processed in next tick). This keeps the worker pure and trivially testable.

Queue entry packs `(idx12, level4)` into 16 bits — `(MortonIdx << 4) | level`.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/potatomc/lighting/propagation/BFSWorkerTest.java
package com.potatomc.lighting.propagation;

import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BFSWorkerTest {

    private static int idx(int x, int y, int z) { return MortonIndex.encode(x, y, z); }

    @Test
    void singleSourcePropagatesFalloffByOnePerStep() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        // No opacity (all blocks transparent): seed level 15 at center
        boolean[] opaque = new boolean[4096]; // all false
        w.seed(idx(8, 8, 8), 15);
        w.propagate(s, opaque);

        assertEquals(15, s.get(idx(8, 8, 8)));
        assertEquals(14, s.get(idx(9, 8, 8))); // 1 step away
        assertEquals(13, s.get(idx(10, 8, 8))); // 2 steps
        assertEquals(13, s.get(idx(8, 9, 9))); // 2 steps (manhattan)
        assertEquals(0, s.get(idx(0, 0, 0))); // distance 24, capped to 0
    }

    @Test
    void opaqueBlocksLight() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        opaque[idx(9, 8, 8)] = true; // wall east of source

        w.seed(idx(8, 8, 8), 15);
        w.propagate(s, opaque);

        assertEquals(15, s.get(idx(8, 8, 8)));
        assertEquals(0, s.get(idx(9, 8, 8))); // blocked
        assertEquals(0, s.get(idx(10, 8, 8))); // beyond wall
        assertEquals(14, s.get(idx(8, 9, 8))); // other directions unaffected
    }

    @Test
    void earlyExitWhenExistingLevelHigher() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(idx(8, 8, 8), 12); // pre-existing brighter light
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];

        w.seed(idx(8, 8, 8), 5); // lower-level source
        w.propagate(s, opaque);

        assertEquals(12, s.get(idx(8, 8, 8))); // unchanged
        assertEquals(0, s.get(idx(9, 8, 8))); // didn't propagate
    }

    @Test
    void multipleSourcesTakeMax() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        w.seed(idx(0, 8, 8), 15);
        w.seed(idx(15, 8, 8), 15);
        w.propagate(s, opaque);

        // Midpoint x=7 or x=8: distance 7 or 8 from one source, 7 or 8 from other → level 8
        assertEquals(8, s.get(idx(7, 8, 8)));
        assertEquals(8, s.get(idx(8, 8, 8)));
    }

    @Test
    void zeroLevelSeedIsNoop() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        w.seed(idx(8, 8, 8), 0);
        w.propagate(s, opaque);

        assertEquals(0, s.get(idx(8, 8, 8)));
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

```bash
./gradlew test --tests BFSWorkerTest
```

Expected: compilation failure.

- [ ] **Step 3: Implement BFSWorker**

```java
// src/main/java/com/potatomc/lighting/propagation/BFSWorker.java
package com.potatomc.lighting.propagation;

import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;

/**
 * Section-local flood-fill propagation. One instance per thread; reused
 * across calls via reset().
 */
public final class BFSWorker {

    private static final int LEVEL_BITS = 4;
    private static final int LEVEL_MASK = 0xF;

    private final PooledQueue queue = new PooledQueue(16384);

    public void seed(int mortonIdx, int level) {
        if (level <= 0) return;
        queue.enqueue((mortonIdx << LEVEL_BITS) | (level & LEVEL_MASK));
    }

    public void propagate(PackedLightStorage storage, boolean[] opaque) {
        while (!queue.isEmpty()) {
            int packed = queue.dequeue();
            int idx = packed >>> LEVEL_BITS;
            int level = packed & LEVEL_MASK;

            if (opaque[idx]) continue;
            if (storage.get(idx) >= level) continue;
            storage.set(idx, level);

            if (level <= 1) continue;
            int nextLevel = level - 1;

            int x = MortonIndex.decodeX(idx);
            int y = MortonIndex.decodeY(idx);
            int z = MortonIndex.decodeZ(idx);

            if (x > 0)  enqueueNeighbor(MortonIndex.encode(x - 1, y, z), nextLevel, storage);
            if (x < 15) enqueueNeighbor(MortonIndex.encode(x + 1, y, z), nextLevel, storage);
            if (y > 0)  enqueueNeighbor(MortonIndex.encode(x, y - 1, z), nextLevel, storage);
            if (y < 15) enqueueNeighbor(MortonIndex.encode(x, y + 1, z), nextLevel, storage);
            if (z > 0)  enqueueNeighbor(MortonIndex.encode(x, y, z - 1), nextLevel, storage);
            if (z < 15) enqueueNeighbor(MortonIndex.encode(x, y, z + 1), nextLevel, storage);
        }
        queue.reset();
    }

    private void enqueueNeighbor(int idx, int level, PackedLightStorage storage) {
        if (storage.get(idx) >= level) return;
        queue.enqueue((idx << LEVEL_BITS) | level);
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

```bash
./gradlew test --tests BFSWorkerTest
```

Expected: 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/potatomc/lighting/propagation/BFSWorker.java src/test/java/com/potatomc/lighting/propagation/BFSWorkerTest.java
git commit -m "feat(lighting): BFSWorker (section-local flood-fill)"
```

---

## Phase 4 — Engine API

### Task 4.1: LightLevelAPI (read interface)

**Files:**
- Create: `src/main/java/com/potatomc/lighting/api/LightLevelAPI.java`

Interface that mirrors what vanilla code expects (`getLightLevel(BlockPos, LightType)`). The Mixin layer redirects vanilla read calls through this. No tests yet — pure interface, exercised at integration level.

- [ ] **Step 1: Create interface**

```java
// src/main/java/com/potatomc/lighting/api/LightLevelAPI.java
package com.potatomc.lighting.api;

import net.minecraft.util.math.BlockPos;

public interface LightLevelAPI {

    enum LightType { SKY, BLOCK }

    /**
     * Returns the light level at {@code pos} for {@code type}, in [0, 15].
     * Returns 0 if the position's section is unloaded.
     */
    int getLightLevel(BlockPos pos, LightType type);
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/potatomc/lighting/api/LightLevelAPI.java
git commit -m "feat(lighting): LightLevelAPI read interface"
```

---

### Task 4.2: PotatoLightEngine (entrypoint)

**Files:**
- Create: `src/main/java/com/potatomc/lighting/PotatoLightEngine.java`

The engine holds:
- A `ConcurrentHashMap<SectionKey, SectionLightData>` where `SectionLightData` bundles the block-light + sky-light `PackedLightStorage` for a section.
- A `ThreadLocal<BFSWorker>` for re-entrant BFS without sharing state.
- An `update(BlockPos)` entrypoint called by `WorldChunkMixin` when a block changes.

For v0.1, cross-section propagation is **deferred to the next tick** by marking neighbor sections dirty. The engine tracks dirty sections in a `Set<SectionKey>` flushed on `tick()`.

- [ ] **Step 1: Define SectionKey & SectionLightData (inner classes for now)**

```java
// src/main/java/com/potatomc/lighting/PotatoLightEngine.java
package com.potatomc.lighting;

import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.propagation.BFSWorker;
import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;
import net.minecraft.util.math.BlockPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PotatoLightEngine implements LightLevelAPI {

    public record SectionKey(int sx, int sy, int sz) {}

    public static final class SectionLightData {
        public final PackedLightStorage block = new PackedLightStorage();
        public final PackedLightStorage sky = new PackedLightStorage();
        public final boolean[] opaque = new boolean[PackedLightStorage.SECTION_SIZE];
    }

    private final ConcurrentHashMap<SectionKey, SectionLightData> sections = new ConcurrentHashMap<>();
    private final Set<SectionKey> dirty = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<BFSWorker> workers = ThreadLocal.withInitial(BFSWorker::new);

    public SectionLightData getOrCreate(SectionKey key) {
        return sections.computeIfAbsent(key, k -> new SectionLightData());
    }

    public void markDirty(SectionKey key) { dirty.add(key); }

    @Override
    public int getLightLevel(BlockPos pos, LightType type) {
        SectionKey k = keyOf(pos);
        SectionLightData data = sections.get(k);
        if (data == null) return 0;
        int idx = MortonIndex.encode(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        return type == LightType.BLOCK ? data.block.get(idx) : data.sky.get(idx);
    }

    public void onBlockChanged(BlockPos pos, int emittedLight, boolean opaque) {
        SectionKey k = keyOf(pos);
        SectionLightData data = getOrCreate(k);
        int idx = MortonIndex.encode(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        data.opaque[idx] = opaque;
        BFSWorker w = workers.get();
        w.seed(idx, emittedLight);
        w.propagate(data.block, data.opaque);
        markDirty(k);
    }

    public void tick() {
        // v0.1: simple flush — re-propagate dirty sections. Cross-section handled in v0.2.
        dirty.clear();
    }

    private static SectionKey keyOf(BlockPos pos) {
        return new SectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/potatomc/lighting/PotatoLightEngine.java
git commit -m "feat(lighting): PotatoLightEngine entrypoint (single-threaded, intra-section)"
```

---

## Phase 5 — Compatibility detection

### Task 5.1: CompatGuard (detect competing lighting mods)

**Files:**
- Create: `src/main/java/com/potatomc/lighting/CompatGuard.java`
- Modify: `src/main/java/com/potatomc/PotatoMC.java`

If Starlight or Phosphor is detected, the engine is disabled. The Mixin layer checks `CompatGuard.isActive()` before redirecting.

- [ ] **Step 1: Implement CompatGuard**

```java
// src/main/java/com/potatomc/lighting/CompatGuard.java
package com.potatomc.lighting;

import com.potatomc.PotatoMC;
import net.fabricmc.loader.api.FabricLoader;

public final class CompatGuard {

    private static final String[] CONFLICTING_MODS = {"starlight", "phosphor"};
    private static volatile boolean active = true;
    private static boolean evaluated = false;

    private CompatGuard() {}

    public static void evaluate() {
        if (evaluated) return;
        FabricLoader fl = FabricLoader.getInstance();
        for (String id : CONFLICTING_MODS) {
            if (fl.isModLoaded(id)) {
                active = false;
                PotatoMC.LOGGER.warn("[PotatoMC] Mod '{}' détecté — moteur lumière custom désactivé (compat)", id);
            }
        }
        if (active) PotatoMC.LOGGER.info("[PotatoMC] Moteur lumière custom: actif");
        evaluated = true;
    }

    public static boolean isActive() { return active; }
}
```

- [ ] **Step 2: Wire into PotatoMC.onInitialize**

Modify `src/main/java/com/potatomc/PotatoMC.java`:

```java
package com.potatomc;

import com.potatomc.lighting.CompatGuard;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PotatoMC implements ModInitializer {
    public static final String MOD_ID = "potatomc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[PotatoMC] Initialisation — optimisation extrême activée");
        CompatGuard.evaluate();
    }
}
```

- [ ] **Step 3: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/potatomc/lighting/CompatGuard.java src/main/java/com/potatomc/PotatoMC.java
git commit -m "feat(lighting): CompatGuard for Starlight/Phosphor coexistence"
```

---

## Phase 6 — Mixin layer

> **IMPORTANT:** This phase requires inspecting the actual decompiled Minecraft 1.21.11 sources (after `./gradlew genSources`). Yarn method names can shift between MC versions. **Before writing each Mixin, open the target class in your IDE and verify the method signature matches what's shown below.** Where there's a discrepancy, prefer the actual yarn-mapped signature.

### Task 6.1: WorldChunkMixin (hook setBlockState)

**Files:**
- Create: `src/main/java/com/potatomc/mixin/WorldChunkMixin.java`
- Modify: `src/main/resources/potatomc.mixins.json`

Hooks `WorldChunk.setBlockState` to call our engine's `onBlockChanged`. Vanilla 1.21 yarn signature (verify): `public BlockState setBlockState(BlockPos pos, BlockState state, boolean moved)`.

- [ ] **Step 1: Implement WorldChunkMixin**

```java
// src/main/java/com/potatomc/mixin/WorldChunkMixin.java
package com.potatomc.mixin;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void potatomc$onSetBlockState(
            BlockPos pos, BlockState state, boolean moved,
            CallbackInfoReturnable<BlockState> cir) {
        if (!CompatGuard.isActive()) return;
        PotatoLightEngine engine = EngineHolder.get();
        if (engine == null) return;
        int emitted = state.getLuminance();
        boolean opaque = state.isOpaqueFullCube();
        engine.onBlockChanged(pos, emitted, opaque);
    }
}
```

You'll also need a holder for the engine (since Mixins can't easily store state). Create:

```java
// src/main/java/com/potatomc/mixin/EngineHolder.java
package com.potatomc.mixin;

import com.potatomc.lighting.PotatoLightEngine;

public final class EngineHolder {

    private static volatile PotatoLightEngine engine;

    private EngineHolder() {}

    public static void set(PotatoLightEngine e) { engine = e; }
    public static PotatoLightEngine get() { return engine; }
}
```

- [ ] **Step 2: Register the mixin**

Edit `src/main/resources/potatomc.mixins.json`, replace empty `"mixins": []` with:

```json
"mixins": [
    "WorldChunkMixin"
],
```

- [ ] **Step 3: Initialize engine in PotatoMC.onInitialize**

Modify `src/main/java/com/potatomc/PotatoMC.java`:

```java
package com.potatomc;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import com.potatomc.mixin.EngineHolder;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PotatoMC implements ModInitializer {
    public static final String MOD_ID = "potatomc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final PotatoLightEngine LIGHT_ENGINE = new PotatoLightEngine();

    @Override
    public void onInitialize() {
        LOGGER.info("[PotatoMC] Initialisation — optimisation extrême activée");
        CompatGuard.evaluate();
        if (CompatGuard.isActive()) EngineHolder.set(LIGHT_ENGINE);
    }
}
```

- [ ] **Step 4: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Smoke test in dev client**

```bash
./gradlew runClient
```

Create a new world, place a glowstone, place stone next to it, check the log shows no exception. Close client.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/potatomc/mixin/ src/main/resources/potatomc.mixins.json src/main/java/com/potatomc/PotatoMC.java
git commit -m "feat(mixin): WorldChunk setBlockState hook → PotatoLightEngine"
```

---

### Task 6.2: ChunkLightProviderMixin (redirect read calls)

**Files:**
- Create: `src/main/java/com/potatomc/mixin/ChunkLightProviderMixin.java`
- Modify: `src/main/resources/potatomc.mixins.json`

Vanilla reads light via `LightingProvider.getLight(BlockPos, int)` and `ChunkLightProvider.getLightLevel(BlockPos)`. We redirect reads to our engine so renderer/AI/etc. see our values.

> **Verify the exact yarn name** for the read method on 1.21.11. As of 1.21.x it's typically `ChunkLightProvider#getLightLevel(BlockPos pos)` returning `int`. Open `ChunkLightProvider.class` in IDE first.

- [ ] **Step 1: Implement ChunkLightProviderMixin**

```java
// src/main/java/com/potatomc/mixin/ChunkLightProviderMixin.java
package com.potatomc.mixin;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import com.potatomc.lighting.api.LightLevelAPI;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkLightProvider.class)
public abstract class ChunkLightProviderMixin {

    @Inject(method = "getLightLevel(Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void potatomc$getLightLevel(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!CompatGuard.isActive()) return;
        PotatoLightEngine engine = EngineHolder.get();
        if (engine == null) return;
        // Block light path — sky handled by a separate subclass mixin in v0.2
        cir.setReturnValue(engine.getLightLevel(pos, LightLevelAPI.LightType.BLOCK));
    }
}
```

`require = 0` means it's OK if the descriptor doesn't match (some MC versions vary) — we'll see a warning instead of a build break, giving us a chance to adjust.

- [ ] **Step 2: Register the mixin**

Edit `src/main/resources/potatomc.mixins.json`:

```json
"mixins": [
    "WorldChunkMixin",
    "ChunkLightProviderMixin"
],
```

- [ ] **Step 3: Smoke test**

```bash
./gradlew runClient
```

In-world: F3 should still show light levels (you'll likely see 0 everywhere for now because the engine hasn't been seeded with sky light — that's expected, sky comes later). Critically: no crash.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/potatomc/mixin/ChunkLightProviderMixin.java src/main/resources/potatomc.mixins.json
git commit -m "feat(mixin): ChunkLightProvider read redirect"
```

---

### Task 6.3: LightingProviderMixin (top-level read API)

**Files:**
- Create: `src/main/java/com/potatomc/mixin/LightingProviderMixin.java`
- Modify: `src/main/resources/potatomc.mixins.json`

`LightingProvider.getLight(BlockPos, int ambientDarkness)` is the call most external systems use. We redirect this to combine our block+sky values.

- [ ] **Step 1: Implement LightingProviderMixin**

```java
// src/main/java/com/potatomc/mixin/LightingProviderMixin.java
package com.potatomc.mixin;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import com.potatomc.lighting.api.LightLevelAPI;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightingProvider.class)
public abstract class LightingProviderMixin {

    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;I)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void potatomc$getLight(BlockPos pos, int ambientDarkness, CallbackInfoReturnable<Integer> cir) {
        if (!CompatGuard.isActive()) return;
        PotatoLightEngine engine = EngineHolder.get();
        if (engine == null) return;
        int block = engine.getLightLevel(pos, LightLevelAPI.LightType.BLOCK);
        int sky = engine.getLightLevel(pos, LightLevelAPI.LightType.SKY) - ambientDarkness;
        cir.setReturnValue(Math.max(block, Math.max(0, sky)));
    }
}
```

- [ ] **Step 2: Register the mixin**

Edit `src/main/resources/potatomc.mixins.json`:

```json
"mixins": [
    "WorldChunkMixin",
    "ChunkLightProviderMixin",
    "LightingProviderMixin"
],
```

- [ ] **Step 3: Smoke test**

```bash
./gradlew runClient
```

Create a flat world, place glowstone in a dark room — verify it illuminates (light level > 0 nearby) by reading F3. **At this stage there will be visual bugs** (sky light is broken, propagation across sections is broken). That's expected — we'll fix in subsequent tasks. The goal here is that the *plumbing* works without crash.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/potatomc/mixin/LightingProviderMixin.java src/main/resources/potatomc.mixins.json
git commit -m "feat(mixin): LightingProvider getLight() redirect"
```

---

## Phase 7 — Debug toolkit

### Task 7.1: PotatoMCCommand dispatcher

**Files:**
- Create: `src/main/java/com/potatomc/debug/commands/PotatoMCCommand.java`
- Modify: `src/main/java/com/potatomc/PotatoMC.java`

Registers `/potatomc` with subcommands `status`, `validate`. (`bench` deferred to v0.2.)

- [ ] **Step 1: Implement command**

```java
// src/main/java/com/potatomc/debug/commands/PotatoMCCommand.java
package com.potatomc.debug.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.potatomc.PotatoMC;
import com.potatomc.lighting.CompatGuard;
import com.potatomc.debug.DifferentialValidator;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class PotatoMCCommand {

    private PotatoMCCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("potatomc")
            .then(CommandManager.literal("status").executes(ctx -> {
                ServerCommandSource src = ctx.getSource();
                src.sendFeedback(() -> Text.literal(
                    "PotatoMC — moteur lumière: " + (CompatGuard.isActive() ? "ACTIF" : "DÉSACTIVÉ (compat)")
                ), false);
                return 1;
            }))
            .then(CommandManager.literal("validate")
                .then(CommandManager.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                    .executes(ctx -> {
                        int radius = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius");
                        ServerCommandSource src = ctx.getSource();
                        DifferentialValidator.Report r = DifferentialValidator.runAround(src.getWorld(), src.getPosition(), radius);
                        src.sendFeedback(() -> Text.literal(String.format(
                            "Validation: %d blocs comparés, %d diffs (max delta %d)",
                            r.totalBlocks, r.diffCount, r.maxDelta)), false);
                        return 1;
                    })))
        );
        PotatoMC.LOGGER.info("[PotatoMC] /potatomc enregistré");
    }
}
```

- [ ] **Step 2: Wire registration in PotatoMC.onInitialize**

Modify `PotatoMC.java`, add inside `onInitialize`:

```java
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, env) -> com.potatomc.debug.commands.PotatoMCCommand.register(dispatcher)
        );
```

- [ ] **Step 3: Stub DifferentialValidator (full impl in next task)**

```java
// src/main/java/com/potatomc/debug/DifferentialValidator.java
package com.potatomc.debug;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

public final class DifferentialValidator {

    public record Report(int totalBlocks, int diffCount, int maxDelta) {}

    private DifferentialValidator() {}

    public static Report runAround(ServerWorld world, Vec3d center, int radius) {
        // Stub for compilation. Real implementation in Task 7.2.
        return new Report(0, 0, 0);
    }
}
```

- [ ] **Step 4: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Smoke test command**

```bash
./gradlew runClient
```

In-world: open chat, type `/potatomc status`. Expected output: "PotatoMC — moteur lumière: ACTIF".

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/potatomc/debug/ src/main/java/com/potatomc/PotatoMC.java
git commit -m "feat(debug): /potatomc command dispatcher + status subcommand"
```

---

### Task 7.2: DifferentialValidator (vs-vanilla diff)

**Files:**
- Modify: `src/main/java/com/potatomc/debug/DifferentialValidator.java`

Iterates blocks within `radius` chunks of center. For each block: read our light value (via `PotatoMC.LIGHT_ENGINE`) and vanilla light value (call `world.getLightingProvider()` via reflection-free direct path — but we have to bypass our own Mixin to get vanilla). We solve this with a thread-local "validation mode" flag that makes our Mixins fall through to vanilla.

- [ ] **Step 1: Add validation-mode flag to EngineHolder**

Modify `src/main/java/com/potatomc/mixin/EngineHolder.java`:

```java
package com.potatomc.mixin;

import com.potatomc.lighting.PotatoLightEngine;

public final class EngineHolder {

    private static volatile PotatoLightEngine engine;
    private static final ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> false);

    private EngineHolder() {}

    public static void set(PotatoLightEngine e) { engine = e; }
    public static PotatoLightEngine get() { return bypass.get() ? null : engine; }

    public static void runBypassed(Runnable task) {
        bypass.set(true);
        try { task.run(); } finally { bypass.set(false); }
    }
}
```

- [ ] **Step 2: Implement validator**

Replace stub `DifferentialValidator.java` with:

```java
// src/main/java/com/potatomc/debug/DifferentialValidator.java
package com.potatomc.debug;

import com.potatomc.PotatoMC;
import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.mixin.EngineHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

public final class DifferentialValidator {

    public record Report(int totalBlocks, int diffCount, int maxDelta) {}

    private DifferentialValidator() {}

    public static Report runAround(ServerWorld world, Vec3d center, int radius) {
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int range = radius * 16;

        int total = 0;
        int diffs = 0;
        int maxDelta = 0;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    int ours = PotatoMC.LIGHT_ENGINE.getLightLevel(pos, LightLevelAPI.LightType.BLOCK);
                    int vanilla = readVanilla(world, pos);
                    total++;
                    int delta = Math.abs(ours - vanilla);
                    if (delta > 0) {
                        diffs++;
                        if (delta > maxDelta) maxDelta = delta;
                        if (delta > 1) {
                            PotatoMC.LOGGER.warn(
                                "[validator] diff>1 @ {} : potato={} vanilla={}",
                                pos, ours, vanilla);
                        }
                    }
                }
            }
        }
        return new Report(total, diffs, maxDelta);
    }

    private static int readVanilla(ServerWorld world, BlockPos pos) {
        int[] result = new int[1];
        EngineHolder.runBypassed(() ->
            result[0] = world.getLightLevel(LightType.BLOCK, pos)
        );
        return result[0];
    }
}
```

- [ ] **Step 3: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: In-world test**

```bash
./gradlew runClient
```

Create a flat world, place 5 glowstones randomly. Run `/potatomc validate 2`. Expected: a Report printed; diffs may be non-zero (we know v0.1 has sky-light gaps and cross-section issues — that's the point of measuring), but **max delta should be ≤ 15** (sanity). Log any `diff>1` warnings to track what we still need to fix.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/potatomc/debug/DifferentialValidator.java src/main/java/com/potatomc/mixin/EngineHolder.java
git commit -m "feat(debug): DifferentialValidator (vs-vanilla bypass + diff report)"
```

---

### Task 7.3: DebugOverlay (F3 hook, client-only)

**Files:**
- Create: `src/main/java/com/potatomc/debug/DebugOverlay.java`
- Modify: `src/main/java/com/potatomc/client/PotatoMCClient.java`
- Modify: `src/main/resources/potatomc.client.mixins.json`
- Create: `src/main/java/com/potatomc/mixin/client/DebugHudMixin.java`

Fabric provides `HudRenderCallback` but F3 debug strings are vanilla. We use a Mixin on `DebugHud.getRightText()` to append our lines.

- [ ] **Step 1: Implement DebugOverlay (data holder)**

```java
// src/main/java/com/potatomc/debug/DebugOverlay.java
package com.potatomc.debug;

import com.potatomc.PotatoMC;
import com.potatomc.lighting.CompatGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class DebugOverlay {

    private static final AtomicLong bfsOps = new AtomicLong();
    private static final AtomicLong allocations = new AtomicLong();

    private DebugOverlay() {}

    public static void recordBfsOp() { bfsOps.incrementAndGet(); }
    public static void recordAllocation() { allocations.incrementAndGet(); }

    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("");
        out.add("§ePotatoMC Light:");
        out.add("§7 engine: " + (CompatGuard.isActive() ? "§aPotatoLightEngine" : "§cdisabled (compat)"));
        out.add("§7 BFS ops total: §f" + bfsOps.get());
        out.add("§7 alloc events: §f" + allocations.get());
        out.add("§7 sections tracked: §f" + PotatoMC.LIGHT_ENGINE.trackedSectionsCount());
        return out;
    }
}
```

- [ ] **Step 2: Add `trackedSectionsCount()` to PotatoLightEngine**

Append to `PotatoLightEngine`:

```java
    public int trackedSectionsCount() { return sections.size(); }
```

- [ ] **Step 3: Increment BFS counter in BFSWorker.propagate**

Modify `BFSWorker.java`, inside the `while (!queue.isEmpty())` loop, after `int packed = queue.dequeue();` add:

```java
            com.potatomc.debug.DebugOverlay.recordBfsOp();
```

(Yes this couples debug into hot path. The atomic increment is ~5 ns; acceptable cost for the visibility. A no-op `IS_DEBUG_BUILD` flag in v0.2 can compile it out.)

- [ ] **Step 4: Create the F3 mixin**

```java
// src/main/java/com/potatomc/mixin/client/DebugHudMixin.java
package com.potatomc.mixin.client;

import com.potatomc.debug.DebugOverlay;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugHud.class)
public abstract class DebugHudMixin {

    @Inject(method = "getRightText", at = @At("RETURN"), require = 0)
    private void potatomc$appendRightText(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();
        if (lines != null) lines.addAll(DebugOverlay.lines());
    }
}
```

- [ ] **Step 5: Register the client mixin**

Edit `src/main/resources/potatomc.client.mixins.json`, replace `"client": []` with:

```json
"client": [
    "client.DebugHudMixin"
],
```

- [ ] **Step 6: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Smoke test in client**

```bash
./gradlew runClient
```

In-world, press F3. Right column should show "PotatoMC Light:" section with engine name, BFS ops, alloc events, sections tracked. The BFS ops count should INCREASE as you place/break blocks.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/potatomc/debug/DebugOverlay.java src/main/java/com/potatomc/mixin/client/DebugHudMixin.java src/main/resources/potatomc.client.mixins.json src/main/java/com/potatomc/lighting/PotatoLightEngine.java src/main/java/com/potatomc/lighting/propagation/BFSWorker.java
git commit -m "feat(debug): F3 overlay with live engine stats"
```

---

## Phase 8 — Integration test fixture

### Task 8.1: Flat-world fixture for differential validation

**Files:**
- Create: `src/test/resources/worlds/README.md` documenting fixture creation
- Create (via gradle/manual): `src/test/resources/worlds/fixture-flat/` (a saved vanilla flat world with known blocks)

We checkout a small flat world built by hand once. Anyone reproducing the build can load it via dev client, run `/potatomc validate 4`, and confirm diff count + max delta against expected values stored in `expected.json`.

- [ ] **Step 1: Document fixture protocol**

```markdown
<!-- src/test/resources/worlds/README.md -->
# Test World Fixtures

These are saved Minecraft worlds used as reproducible inputs for the differential validator.

## fixture-flat

- Type: superflat
- Seed: 0
- Player spawn: 0,4,0
- Placed blocks (built by hand once, committed verbatim):
  - Glowstone at (5, 4, 0)
  - Glowstone at (-5, 4, 0)
  - Torch at (0, 5, 5)
  - Solid wall of stone from (3,4,-3) to (3,8,3)
- Expected validation report after `/potatomc validate 4`:
  - total_blocks: ≥ 100000
  - diffs: ≤ 5% of total
  - max_delta: ≤ 1 (per design tolerance)

## How to regenerate

1. `./gradlew runClient`
2. Create world: name "fixture-flat", superflat, seed 0, creative, cheats on.
3. Place blocks listed above.
4. Quit to title.
5. Copy `run/saves/fixture-flat/` to `src/test/resources/worlds/fixture-flat/`.
6. Commit.
```

- [ ] **Step 2: Generate the fixture**

```bash
./gradlew runClient
```

Follow the protocol in the README above. When done, quit the game.

- [ ] **Step 3: Copy fixture into repo**

```bash
mkdir -p src/test/resources/worlds/
cp -R run/saves/fixture-flat src/test/resources/worlds/
```

- [ ] **Step 4: Add fixture to .gitignore exclusions if needed**

Check that `.gitignore` does NOT exclude `src/test/resources/**`. Current `.gitignore` excludes only `run/`, so the copied fixture under `src/test/resources/` is fine.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/worlds/
git commit -m "test(lighting): flat-world fixture for differential validation"
```

---

## Phase 9 — v0.1 acceptance check

### Task 9.1: Run full validation against fixture

**Files:** (read-only)
- Read: `src/test/resources/worlds/README.md`

- [ ] **Step 1: Copy fixture to runtime location**

```bash
mkdir -p run/saves
cp -R src/test/resources/worlds/fixture-flat run/saves/
```

- [ ] **Step 2: Launch and validate**

```bash
./gradlew runClient
```

In-game: Singleplayer → load "fixture-flat" → `/potatomc validate 4`.

Expected output in chat:
```
Validation: ≥100000 blocs comparés, X diffs (max delta ≤ 1)
```

- [ ] **Step 3: Check F3 stats**

Press F3. Verify "PotatoMC Light" section visible. BFS ops > 0. Sections tracked > 0.

- [ ] **Step 4: Document the result**

Append to `docs/superpowers/specs/2026-05-11-lighting-engine-design.md`:

```markdown

## v0.1 Acceptance Results — <date>

- Fixture: `fixture-flat`
- Total blocks compared: <N>
- Diffs: <D> (<percent>%)
- Max delta: <delta>
- BFS ops during validation: <ops>
- Pass criteria: max_delta ≤ 1 → <PASS|FAIL>
```

- [ ] **Step 5: Commit results**

```bash
git add docs/superpowers/specs/2026-05-11-lighting-engine-design.md
git commit -m "docs: v0.1 lighting engine acceptance results"
```

---

## End of v0.1 plan

**What's deferred to v0.2 (separate plan):**
- Sky-light propagation (currently block-light only)
- Cross-section propagation (currently each section is independent → diff>1 at section boundaries)
- Worker thread pool for chunks beyond radius 4
- Batch scheduler (currently propagate-on-write)
- Microbench harness command (`/potatomc bench`)
- Comparative bench runner script + headless walk bot
- World save serialization (currently in-memory only — light recomputes on world reload)

**Definition of done for v0.1:**
- All Phase 0–9 tasks committed.
- `./gradlew test` passes (all unit tests green).
- `./gradlew build` passes.
- `/potatomc validate 4` on fixture-flat reports max_delta ≤ 1.
- F3 overlay shows live engine stats with non-zero BFS ops after gameplay.
