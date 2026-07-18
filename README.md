# Twine

[![CI](https://github.com/RicheyWorks/Twine/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/Twine/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

Engine nine of the ecosystem: **crash-atomic multi-key batches**, tied with string. A
journaled intent, an atomic rename as the commit point, and idempotent replay through
SmokeHouse's own last-writer-wins `put`/`delete`:

```java
var twine = Twine.over(store, journalDir, keySer, valSer);   // replays any committed journal
twine.batch()
     .put(orderId, order)
     .put(auditId, entry)
     .delete(staleId)
     .commit();                                              // all of it, or none of it
```

Crash before the rename: the batch never happened. Crash during apply: the next `over`
finishes it — replays are harmless, so the batch lands exactly once in effect. Honest bounds:
atomic **across crashes**, not isolated from concurrent readers (a reader mid-apply sees a
partial batch); cross-key visibility atomicity would need log-format group commit, which
stays a future SmokeHouse phase with its trigger on record.

## Design notes

- **The rename is the commit.** Everything before the ATOMIC_MOVE is intent (torn tmp =
  never happened); everything after is inevitable (replay finishes it). The journal is
  fsynced before the move, CRC-checked whole-file on replay, and a corrupt committed
  journal refuses loudly rather than applying garbage.
- **Replay is idempotent by construction.** Batches compile to the store's own
  last-writer-wins `put`/`delete`, so re-applying an already-applied prefix is a no-op in
  effect — the same argument that makes Renderer's bootstrap and replication's overlap safe.
- **Contract:** construct Twine (which replays) before any other post-reopen writes; one
  Twine, one in-flight batch — the single-writer discipline, one level up.
- **Honest bound:** atomic across crashes, not isolated from concurrent readers. Reader-
  visible atomicity needs log-format group commit — a future SmokeHouse phase, trigger on
  record.

## The ecosystem

Eleven engines, one organism — each in its own repo, composed by nested Gradle
composite builds:

| Engine | Role |
|---|---|
| [CSRBT](https://github.com/RicheyWorks/CSRBT) | the adaptive ordered index — orders the world |
| [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) | the intake tract — profiles, sorts, feeds in O(n) |
| [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) | the log-structured store — durability, tail, watchers, replicas |
| [Carver](https://github.com/RicheyWorks/Carver) | the read planner — decides how to read |
| [Renderer](https://github.com/RicheyWorks/Renderer) | the materialized-view engine — folds the tail into live aggregates |
| [Brine](https://github.com/RicheyWorks/Brine) | the adaptive cache — eviction policy evolved per workload |
| [PitBoss](https://github.com/RicheyWorks/PitBoss) | the fleet conductor — lag watch, re-bootstrap, the promotion runbook |
| [DryAge](https://github.com/RicheyWorks/DryAge) | the time-travel engine — as-of reads over preserved history |
| **Twine** (this repo) | crash-atomic multi-key batches — journaled commit, idempotent replay |
| [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) | the wire — a loopback protocol face for the store |
| [Jerky](https://github.com/RicheyWorks/Jerky) | cold storage — compressed, CRC-verified backup archives |

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.
