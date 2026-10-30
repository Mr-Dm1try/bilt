package com.rentrewards.challenge.service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Tracks which webhook events have already been processed, so that the
 * RewardsEngine can ignore duplicate deliveries from the payment processor.
 *
 * Every processed eventId is remembered (not just the latest one), and each
 * eventId has its own lock so concurrent deliveries of the same event are
 * processed one at a time while different events never block each other.
 */
public class ProcessedEventStore {

    private final ConcurrentMap<String, EventSlot> slots = new ConcurrentHashMap<>();

    private static final class EventSlot {
        private boolean processed; // guarded by synchronized (this)
    }

    /**
     * Runs work for eventId unless an earlier delivery already completed it.
     * Concurrent deliveries of the same event wait for the one in progress;
     * if that attempt throws, the event stays unprocessed and the next one retries.
     *
     * @return the work's result, or empty if the event was already processed.
     */
    public <T> Optional<T> processOnce(String eventId, Supplier<T> work) {
        EventSlot slot = slots.computeIfAbsent(eventId, id -> new EventSlot());
        // Concurrent duplicates wait rather than returning DUPLICATE immediately: the sender
        // stops retrying once any delivery is acked, so acking early could lose a failed event.
        synchronized (slot) {
            if (slot.processed) {
                return Optional.empty();
            }
            T result = work.get(); // throws -> processed stays false, next delivery retries
            // Marked only after work succeeds, so work must not fail after its side effect,
            // or a retry would repeat it.
            slot.processed = true;
            return Optional.of(result);
        }
    }
}
