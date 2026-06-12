# Racecraft — Blog Series (prologue + 5 parts)

A complete narrative series on building Racecraft, written in story-spine order
(setup → problem → escalation → climb → resolution) with the technical depth and
the real problems we hit. **Each post is Blogger-ready HTML you can paste directly.**

Publish order: the **prologue first**, then parts 1–5.

| # | File | Theme |
| :- | :--- | :--- |
| 0 | `00-the-origin-story.html` | **Prologue / origin story** — read first; the motivation (the happy-hour conversation, the first trustable-AI blueprint) + a map of the series · **[LIVE](https://rkrants.blogspot.com/2026/06/racecraft-project-koru-prologue-origin.html)** |
| 1 | `01-the-800ms-problem.html` | Origin & the trust thesis — "feedback 800 ms late is worse than silence" |
| 2 | `02-reading-the-driver.html` | The coaching paradigm — DriverModel, skill adaptation, personas |
| 3 | `03-splitting-the-brain.html` | Latency & the Split-Brain engine — HOT/COLD/FEEDFORWARD, the 5 ms safety path |
| 4 | `04-gemma-in-the-cockpit.html` | On-device Gemma 4 E2B, LiteRT-LM, and the honest NPU/benchmark saga |
| 5 | `05-earning-trust-at-speed.html` | Determinism, on-device validation (16/16 on Pixel 10), and what generalizes |

## Published URLs (fill in as each goes live, then wire cross-links)

- **0 · Prologue:** https://rkrants.blogspot.com/2026/06/racecraft-project-koru-prologue-origin.html ✅
- 1 · The 800-Millisecond Problem: _(add URL)_
- 2 · Reading the Driver: _(add URL)_
- 3 · Splitting the Brain: _(add URL)_
- 4 · Gemma in the Cockpit: _(add URL)_
- 5 · Earning Trust at Speed: _(add URL)_

Each part already back-links to the live prologue (in the eyebrow line). The prologue's
forward links to parts 1–5, and the parts' "next/previous" links, use `#` placeholders
until those URLs exist — paste them above and they can be wired in one pass.

## How to publish on Blogger

1. New post → switch the editor to **HTML view** (the `<>` button).
2. Open the post's `.html` file and copy everything between the
   `<!-- ===== BEGIN BLOGGER PASTE ===== -->` / `END` markers.
3. Paste into the HTML view, then switch to Compose to confirm. The **inline SVG
   figures render as-is** — no image hosting needed.
4. Where a post shows a **dashed placeholder box**, click it in Compose view and
   upload the named real screenshot.
5. Suggested labels are in each file's header comment (e.g. `gemma4, edge, android`).

## Figures

Standalone copies of the diagrams live in `docs/blog/assets/` (also embedded inline
in the posts):

- `fig-live-cockpit.svg`, `fig-split-brain.svg`, `fig-latency-budget.svg`,
  `fig-coaching-paradigm.svg`
- Benchmark chart: `submission-artifacts/benchmarks/accelerator-comparison-report.svg`

## Real device screenshots & recording

Capture fresh Pixel screenshots and the interaction recording (phone unlocked):

```bash
npm run artifacts:pixel:capture
# writes to submission-artifacts/media/:
#   pixel-setup-screen.png, pixel-diagnostics-screen.png,
#   pixel-paddock-screen.png, koru-demo-interaction.mp4
```

> The three earlier drafts (`01-building-the-koru-field-lab.md` etc.) are superseded
> by this HTML series and kept only for reference.
