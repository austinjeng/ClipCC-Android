---
name: clipcc-android-review-discipline
description: "How this user reviews specs/plans — demands empirical verification, plan-before-build"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 329a9c79-36a3-45fd-bc5f-b2c2f0d40359
---

On the clipCC-Android work the user ran rigorous, repeated adversarial review of the spec and
plan (P1/P2 findings, cited sources). They caught an assertion I made from a research agent's
source-code claim (SigLIP2 tokenizer lowercasing) that conflicted with the shipped artifact.

**Why:** they value correctness-by-evidence over confident prose; an unverified claim that
later breaks parity is worse than an explicit "UNRESOLVED → spike".

**How to apply:** for this user, (1) verify load-bearing technical claims against live/primary
sources before asserting; when you can't, label UNRESOLVED and convert to a de-risking spike;
(2) plan spikes/unknowns into an explicit Phase 0 gate before building; (3) state-of-the-art
APIs move fast — confirm versions/method names rather than trusting the model's priors. They
respond well to "here's the evidence / here's where I was wrong" and dislike performative
agreement. See [[clipcc-android-port]].
