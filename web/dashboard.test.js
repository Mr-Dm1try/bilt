import test from "node:test";
import assert from "node:assert/strict";

import { buildViewModel } from "./dashboard.js";

const member = {
  pointsThisMonth: 1_500,
  monthlyCap: 100_000,
  streakMonths: 6,
};

test("shows awarded points as a successful payment", () => {
  const view = buildViewModel(
    { pointsAwarded: 1_500, outcome: "AWARDED" },
    member,
  );

  assert.equal(view.title, "1,500 points credited");
  assert.equal(view.tone, "success");
  assert.equal(view.progressPercent, 1.5);
});

test("shows a duplicate event as skipped rather than credited", () => {
  const view = buildViewModel(
    { pointsAwarded: 0, outcome: "DUPLICATE" },
    member,
  );

  assert.equal(view.title, "Duplicate event skipped");
  assert.equal(view.tone, "neutral");
});

test("shows when the member has reached the monthly cap", () => {
  const view = buildViewModel(
    { pointsAwarded: 0, outcome: "CAPPED" },
    { ...member, pointsThisMonth: 100_000 },
  );

  assert.equal(view.title, "Monthly cap reached");
  assert.equal(view.tone, "warning");
  assert.equal(view.progressPercent, 100);
});

test("does not describe skipped or capped payments as credited", () => {
  for (const outcome of ["DUPLICATE", "CAPPED"]) {
    const view = buildViewModel({ pointsAwarded: 0, outcome }, member);

    assert.doesNotMatch(view.title, /credited/i);
    assert.doesNotMatch(view.description, /credited|successfully/i);
  }
});

test("explains the cap using the member's monthly limit", () => {
  const view = buildViewModel(
    { pointsAwarded: 0, outcome: "CAPPED" },
    { ...member, pointsThisMonth: 100_000 },
  );

  assert.match(view.description, /100,000-point monthly maximum/);
});

test("does not guess success for an unknown outcome", () => {
  const view = buildViewModel({ pointsAwarded: 0, outcome: "PENDING" }, member);

  assert.equal(view.title, "Payment status unavailable");
  assert.equal(view.tone, "neutral");
});
