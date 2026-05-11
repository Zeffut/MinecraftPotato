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
