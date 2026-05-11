package com.potatomc.lighting.propagation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PooledLongQueueTest {

    @Test
    void newQueueIsEmpty() {
        PooledLongQueue q = new PooledLongQueue(16);
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
    }

    @Test
    void enqueueThenDequeueFIFO() {
        PooledLongQueue q = new PooledLongQueue(16);
        q.enqueue(1L);
        q.enqueue(0xDEADBEEFCAFEL);
        q.enqueue(-7L);
        assertEquals(3, q.size());
        assertEquals(1L, q.dequeue());
        assertEquals(0xDEADBEEFCAFEL, q.dequeue());
        assertEquals(-7L, q.dequeue());
        assertTrue(q.isEmpty());
    }

    @Test
    void resetAllowsReuse() {
        PooledLongQueue q = new PooledLongQueue(8);
        q.enqueue(42L);
        q.enqueue(7L);
        q.reset();
        assertTrue(q.isEmpty());
        q.enqueue(99L);
        assertEquals(99L, q.dequeue());
    }

    @Test
    void growsWhenFull() {
        PooledLongQueue q = new PooledLongQueue(4);
        for (long i = 0; i < 1000; i++) q.enqueue(i * 1_000_000L);
        for (long i = 0; i < 1000; i++) assertEquals(i * 1_000_000L, q.dequeue());
    }
}
