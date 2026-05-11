package com.potatomc.lighting.propagation;

/**
 * Preallocated FIFO long queue. Designed to be reset and reused across BFS
 * propagation passes without allocating. Mirrors {@link PooledQueue} but
 * holds {@code long} entries (used by the world-space BFS to pack
 * x/y/z/level into a single 64-bit token).
 */
public final class PooledLongQueue {

    private long[] data;
    private int head;
    private int tail;

    public PooledLongQueue(int initialCapacity) {
        if (initialCapacity < 2) initialCapacity = 2;
        this.data = new long[initialCapacity];
    }

    public boolean isEmpty() { return head == tail; }
    public int size() { return tail - head; }

    public void enqueue(long value) {
        if (tail == data.length) grow();
        data[tail++] = value;
    }

    public long dequeue() {
        return data[head++];
    }

    public void reset() {
        head = 0;
        tail = 0;
    }

    private void grow() {
        int newLen = data.length * 2;
        long[] bigger = new long[newLen];
        System.arraycopy(data, head, bigger, 0, tail - head);
        tail -= head;
        head = 0;
        data = bigger;
    }
}
