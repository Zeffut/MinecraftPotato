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
