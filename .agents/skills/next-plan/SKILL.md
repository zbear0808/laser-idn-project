---
name: next-plan
description: Survey every plan in plans/, check each against git and the code, and recommend the next best plan, phase or step to work on, with what it needs first. Use when the user asks "what's next", "what should I work on", "which plan next", or wants the open plans ranked.
---

# Pick the next plan

Read-only. Don't edit plans, code or git state. If you find a plan header that is wrong or stale, report it; fix it only if the user asks.

An optional focus from the user (an area, a time budget like "half a day", "no hardware", "UX only") narrows the candidates and shifts the weights below. Say how you applied it.

## 1. Inventory

1. `ls plans/` and note each folder or top-level file. Dated names (`YYYY-MM-DD-topic`) give age; newer usually supersedes older.
2. For each folder, read its entry doc (`00-overview.md`, `00-README.md` or `README.md`): the `**Status:**` header, `## Start here`, the phase/doc table (its *Size*, *Needs* and *Depends on* columns) and any "Next is …" sentence. Then read only the `**Status:**` header (the first ~6 lines) of each phase doc. Read deeper only for candidates that survive step 2.
3. Also mine the latest `*-improvement-review/00-overview.md` ranked-priority table for rows marked **Open** or **Partly**, and its "Next" or carry-over sections. These are candidates even when no plan folder covers them yet.
4. With many folders, give each folder to a parallel `explore` subagent ("quick") that returns the per-item fields below, so the main context stays small.

Record one row per **work item** (a phase or step, not a whole folder):

| Field | Where it comes from |
|---|---|
| Plan / item | path and phase id (e.g. `2026-11-20-state-integrity/02`, commit 5) |
| State | see step 2 |
| Blocked on | open decision ids (`D4`, `W1–W6`, `Q-Z1`…), another item, real hardware, or nothing |
| ★ defaults? | whether each open decision has a recommended option (★). "Answer only where you disagree" means it's a soft block: one reply from the user unblocks it |
| Size | the plan's own estimate |
| Severity / impact | the plan's own H/M/L, finding ids, or "data loss" / "safety" wording |
| Unblocks | items whose *Needs* or *Depends on* name this one |
| Last verified against | the commit in the header |

## 2. Classify, and check against reality

Classify each item: **Done**, **In progress**, **Ready**, **Soft-blocked** (open questions, all with ★), **Blocked** (questions with no default, or an unfinished dependency), **Hardware-only** (needs a real DAC or projector, e.g. "still owed: the check on a real projector"), **Design only** (research or options, no plan yet; the planning guide says it needs a plan before work), **Future / not scheduled**, **Superseded**. Drop Done, Superseded and Future unless the user asks about them.

Don't trust headers alone. Check cheaply:

- **Done claims:** `git log --oneline -1 <sha>` for the cited commits; `git branch --no-merged master` for work that is built but not merged (an item "done on branch X" isn't done on `master`).
- **Open claims:** grep `git log --oneline` and the other plans for the item's id or topic. Plans cross-reference each other (e.g. a step in one folder is done by another), and the newer header wins.
- **Drift:** `git rev-list --count <last-verified>..HEAD` and `git diff --stat <last-verified>..HEAD -- <files the item cites>`. Heavy churn in cited files means a re-check is owed before starting; add that to the item's cost.
- **Conflicts:** unmerged branches or uncommitted changes (`git status --short`) touching the same files.

## 3. Rank

Rank Ready and Soft-blocked items first; mention Blocked ones only for what would unblock them. Weigh, in roughly this order:

1. **Safety and data loss.** This app drives lasers: anything about unsafe output, safety zones failing open, or losing the user's work comes first. Then correctness bugs with evidence (probes, High findings), then UX, then performance claims with evidence, then polish.
2. **The plan's own pointer.** A "Next is …" in a current `Start here` is a strong signal, provided step 2 confirmed it's still true.
3. **Readiness.** Ready beats soft-blocked beats blocked. A soft block costs one message to the user, so a high-impact soft-blocked item can still win.
4. **Unblocks.** Items that other phases depend on rank up.
5. **Size and freshness.** Prefer the smaller item, and one whose citations are still current, when the above are close. Respect the user's time budget.
6. **No collisions.** Rank down items that overlap in-flight branches or uncommitted work.

Hardware-only items never win unless the user says hardware is available; list them in one line.

## 4. Report

Keep it short. Use this shape:

```markdown
## Next: <plan path> — <item> (<size>)
Why: 2–4 bullets, each tied to evidence (severity, "Next is" pointer, what it unblocks, probe ids).
Before starting: the decisions to answer (ids and the ★ option, so the user can reply "all ★"), any re-check owed (commits behind, files changed), the branch to start from.
First step: the first commit or test the plan names.

## Runners-up
| # | Item | Size | State | Why not first |

## Blocked
| Item | Waiting on | Unblocked by |

## Parked
One line each: hardware-only items, design-only folders, future work.

## Plan hygiene (only if found)
Headers that disagree with git or with each other, stale "Next is" pointers, done plans that rule 11 of `.agents/rules/planning-guide.md` says to delete.
```

Cite every claim with a plan path and section (`02-direct-state-writes.md` §10) or a commit. End by asking whether to start the top pick, answer its open questions, or pick another.
