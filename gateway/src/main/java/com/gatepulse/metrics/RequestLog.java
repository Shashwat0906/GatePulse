package com.gatepulse.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A fixed-size, lock-free ring buffer of the most recent requests.
 *
 * <p>Writers grab a unique sequence number with {@code getAndIncrement} and write into slot
 * {@code seq % capacity}. Readers ask for "everything after sequence N", which is how the SSE
 * broadcaster sends each dashboard only the new entries since its last tick. Old entries are
 * simply overwritten, so memory never grows.
 *
 * <p>A writer may have taken a sequence number but not yet stored its record when a reader
 * looks. The reader stops at that gap and picks up from there on the next read, so entries are
 * not skipped. Entries that were overwritten before being read (more than {@code capacity}
 * requests between two reads) are skipped; that is acceptable for a live view.
 */
public final class RequestLog {

    private final int capacity;
    private final int mask;
    private final AtomicReferenceArray<RequestRecord> slots;
    private final AtomicLong nextSeq = new AtomicLong(1);

    /** @param capacity rounded up to a power of two */
    public RequestLog(int capacity) {
        int size = Integer.highestOneBit(Math.max(2, capacity - 1)) << 1;
        this.capacity = size;
        this.mask = size - 1;
        this.slots = new AtomicReferenceArray<>(size);
    }

    public void add(RequestRecord record) {
        long seq = nextSeq.getAndIncrement();
        slots.set((int) (seq & mask), record.withSeq(seq));
    }

    /** Sequence number of the most recently started write (0 if none). */
    public long lastSeq() {
        return nextSeq.get() - 1;
    }

    /**
     * Records with sequence greater than {@code afterSeq}, oldest first, at most {@code max}
     * (the newest ones if there are more).
     */
    public List<RequestRecord> since(long afterSeq, int max) {
        long last = lastSeq();
        long from = Math.max(afterSeq + 1, Math.max(last - capacity + 1, last - max + 1));
        List<RequestRecord> result = new ArrayList<>((int) Math.max(0, Math.min(max, last - from + 1)));
        for (long seq = Math.max(from, 1); seq <= last; seq++) {
            RequestRecord record = slots.get((int) (seq & mask));
            if (record == null || record.seq() != seq) {
                break; // not written yet (or already overwritten): stop at the gap
            }
            result.add(record);
        }
        return result;
    }

    public List<RequestRecord> latest(int max) {
        return since(lastSeq() - max, max);
    }

    public int capacity() {
        return capacity;
    }
}
