package com.rentrewards.challenge.service;

import com.rentrewards.challenge.model.MemberAccount;
import com.rentrewards.challenge.model.PaymentEvent;
import com.rentrewards.challenge.model.PointsResult;
import com.rentrewards.challenge.model.ProcessingOutcome;

import java.time.YearMonth;

/**
 * Orchestrates the processing of an incoming payment webhook event:
 *
 *  1. Process each eventId at most once; duplicate deliveries (retries,
 *     out-of-order or concurrent) are skipped as DUPLICATE.
 *  2. Calculate base points (with linked-account multiplier).
 *  3. Apply streak bonus if the member is eligible.
 *  4. Enforce the monthly points cap per member.
 *  5. Record the points.
 */
public class RewardsEngine {

    private static final long MONTHLY_POINTS_CAP = 100_000L;

    private final PointsCalculator pointsCalculator;
    private final ProcessedEventStore processedEventStore;

    public RewardsEngine(PointsCalculator pointsCalculator, ProcessedEventStore processedEventStore) {
        this.pointsCalculator = pointsCalculator;
        this.processedEventStore = processedEventStore;
    }

    public PointsResult processPayment(PaymentEvent event, MemberAccount member) {
        return processedEventStore.processOnce(event.getEventId(), () -> award(event, member))
                .orElseGet(() -> new PointsResult(member.getMemberId(), 0, ProcessingOutcome.DUPLICATE));
    }

    private PointsResult award(PaymentEvent event, MemberAccount member) {
        long basePoints = pointsCalculator.calculateBasePoints(event);
        long pointsWithBonus = pointsCalculator.applyStreakBonusIfEligible(
                basePoints, member.getCurrentStreakMonths());

        YearMonth month = YearMonth.from(event.getPaymentDate());
        // Different events for the same member can run concurrently; reading the month's
        // total and adding to it must be one step or the member can overshoot the cap.
        synchronized (member) {
            long alreadyEarnedThisMonth = member.getPointsForMonth(month);
            long remainingCap = Math.max(0, MONTHLY_POINTS_CAP - alreadyEarnedThisMonth);
            long pointsToAward = Math.min(pointsWithBonus, remainingCap);

            ProcessingOutcome outcome = pointsToAward == 0
                    ? ProcessingOutcome.CAPPED
                    : ProcessingOutcome.AWARDED;
            PointsResult result = new PointsResult(member.getMemberId(), pointsToAward, outcome);

            // Crediting must stay the last step: the event is marked processed only after
            // award() returns, so a failure after this line would let a retry credit again.
            member.addPointsForMonth(month, pointsToAward);
            return result;
        }
    }
}
