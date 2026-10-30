package com.rentrewards.challenge.service;

import com.rentrewards.challenge.model.MemberAccount;
import com.rentrewards.challenge.model.PaymentEvent;
import com.rentrewards.challenge.model.PointsResult;
import com.rentrewards.challenge.model.ProcessingOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardsEngineTest {

    private RewardsEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RewardsEngine(new PointsCalculator(), new ProcessedEventStore());
    }

    @Test
    void awardsOnePointPerDollarByDefault() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(1500, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
        assertFalse(result.isSkippedAsDuplicate());
    }

    @Test
    void appliesLinkedAccountMultiplier() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), true, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(3000, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void appliesStreakBonusWhenEligible() {
        MemberAccount member = new MemberAccount("member-1", 6);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("2000"), true, LocalDate.of(2026, 3, 1));

        // base = 2000 * 2 (linked) = 4000; +10% streak bonus = 4400
        PointsResult result = engine.processPayment(event, member);

        assertEquals(4400, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void enforcesMonthlyPointsCap() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("150000"), false, LocalDate.of(2026, 3, 1));

        PointsResult result = engine.processPayment(event, member);

        assertEquals(100_000, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, result.getOutcome());
    }

    @Test
    void reportsCappedWhenALaterEventCannotAwardMorePoints() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent first = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("150000"), false, LocalDate.of(2026, 3, 1));
        PaymentEvent second = new PaymentEvent("evt-2", "member-1",
                new BigDecimal("50"), false, LocalDate.of(2026, 3, 2));

        engine.processPayment(first, member);
        PointsResult result = engine.processPayment(second, member);

        assertEquals(0, result.getPointsAwarded());
        assertEquals(ProcessingOutcome.CAPPED, result.getOutcome());
        assertFalse(result.isSkippedAsDuplicate());
        assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @Test
    void doesNotDoubleAwardPointsWhenSameWebhookEventIsResent() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));

        PointsResult first = engine.processPayment(event, member);
        PointsResult retry = engine.processPayment(event, member);

        assertEquals(1500, first.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, first.getOutcome());
        assertEquals(0, retry.getPointsAwarded());
        assertEquals(ProcessingOutcome.DUPLICATE, retry.getOutcome());
        assertTrue(retry.isSkippedAsDuplicate());
    }

    @Test
    void doesNotDoubleAwardPointsWhenAnOlderEventIsResentOutOfOrder() {
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent eventA = new PaymentEvent("evt-A", "member-1",
                new BigDecimal("1000"), false, LocalDate.of(2026, 3, 1));
        PaymentEvent eventB = new PaymentEvent("evt-B", "member-1",
                new BigDecimal("2000"), false, LocalDate.of(2026, 3, 5));

        // Processing order as it can realistically happen with webhook retries:
        // A arrives, then B arrives, then A is redelivered by the processor.
        engine.processPayment(eventA, member);
        engine.processPayment(eventB, member);
        PointsResult resentA = engine.processPayment(eventA, member);

        assertEquals(0, resentA.getPointsAwarded());
        assertEquals(ProcessingOutcome.DUPLICATE, resentA.getOutcome());
        assertTrue(resentA.isSkippedAsDuplicate());
        assertEquals(3000, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @RepeatedTest(8)
    void awardsPointsAtMostOnceWhenTheSameEventArrivesConcurrently() throws Exception {
        int workerCount = 16;
        RewardsEngine concurrentEngine = new RewardsEngine(
                new PointsCalculator(), new ProcessedEventStore());
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent(
                "evt-concurrent",
                "member-1",
                new BigDecimal("1500"),
                false,
                LocalDate.of(2026, 3, 1));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<PointsResult>> futures = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return concurrentEngine.processPayment(event, member);
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            long awardedResults = 0;
            for (Future<PointsResult> future : futures) {
                if (future.get(2, TimeUnit.SECONDS).getOutcome() == ProcessingOutcome.AWARDED) {
                    awardedResults++;
                }
            }

            assertEquals(1, awardedResults,
                    "at most one worker may award points for the same eventId");
            assertEquals(1500, member.getPointsForMonth(YearMonth.of(2026, 3)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retriesAnEventWhoseEarlierAttemptFailed() {
        AtomicBoolean failNextAttempt = new AtomicBoolean(true);
        RewardsEngine flakyEngine = new RewardsEngine(new PointsCalculator() {
            @Override
            public long calculateBasePoints(PaymentEvent event) {
                if (failNextAttempt.getAndSet(false)) {
                    throw new IllegalStateException("simulated processing failure");
                }
                return super.calculateBasePoints(event);
            }
        }, new ProcessedEventStore());
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));

        assertThrows(IllegalStateException.class, () -> flakyEngine.processPayment(event, member));
        PointsResult redelivery = flakyEngine.processPayment(event, member);

        assertEquals(1500, redelivery.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, redelivery.getOutcome());
        assertEquals(1500, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @Test
    void waitingDuplicateTakesOverWhenTheInFlightAttemptFails() throws Exception {
        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        CountDownLatch failFirstAttempt = new CountDownLatch(1);
        AtomicBoolean isFirstAttempt = new AtomicBoolean(true);
        RewardsEngine slowEngine = new RewardsEngine(new PointsCalculator() {
            @Override
            public long calculateBasePoints(PaymentEvent event) {
                if (isFirstAttempt.getAndSet(false)) {
                    firstAttemptStarted.countDown();
                    await(failFirstAttempt);
                    throw new IllegalStateException("simulated processing failure");
                }
                return super.calculateBasePoints(event);
            }
        }, new ProcessedEventStore());
        MemberAccount member = new MemberAccount("member-1", 0);
        PaymentEvent event = new PaymentEvent("evt-1", "member-1",
                new BigDecimal("1500"), false, LocalDate.of(2026, 3, 1));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // The first delivery is mid-processing when the processor redelivers the event.
            Future<PointsResult> first = executor.submit(() -> slowEngine.processPayment(event, member));
            assertTrue(firstAttemptStarted.await(2, TimeUnit.SECONDS));

            AtomicReference<Thread> secondWorker = new AtomicReference<>();
            Future<PointsResult> second = executor.submit(() -> {
                secondWorker.set(Thread.currentThread());
                return slowEngine.processPayment(event, member);
            });
            awaitBlocked(secondWorker);

            failFirstAttempt.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> first.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            PointsResult redelivery = second.get(2, TimeUnit.SECONDS);
            assertEquals(1500, redelivery.getPointsAwarded());
            assertEquals(ProcessingOutcome.AWARDED, redelivery.getOutcome());
            assertEquals(1500, member.getPointsForMonth(YearMonth.of(2026, 3)));
        } finally {
            executor.shutdownNow();
        }
    }

    @RepeatedTest(8)
    void neverExceedsMonthlyCapWhenDifferentEventsArriveConcurrently() throws Exception {
        int workerCount = 16;
        MemberAccount member = new MemberAccount("member-1", 0);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<PointsResult>> futures = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                PaymentEvent event = new PaymentEvent("evt-" + worker, "member-1",
                        new BigDecimal("10000"), false, LocalDate.of(2026, 3, 1));
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return engine.processPayment(event, member);
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            long awarded = 0;
            long capped = 0;
            for (Future<PointsResult> future : futures) {
                ProcessingOutcome outcome = future.get(2, TimeUnit.SECONDS).getOutcome();
                if (outcome == ProcessingOutcome.AWARDED) {
                    awarded++;
                } else if (outcome == ProcessingOutcome.CAPPED) {
                    capped++;
                }
            }

            assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
            assertEquals(10, awarded);
            assertEquals(6, capped);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitBlocked(AtomicReference<Thread> worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (worker.get() == null || worker.get().getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline,
                    "a concurrent duplicate should wait for the delivery in progress");
            Thread.sleep(1);
        }
    }
}
