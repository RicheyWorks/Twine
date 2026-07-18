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

## The ecosystem

Engines 1–6: [CSRBT](https://github.com/RicheyWorks/CSRBT) (index) · [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) (intake) · [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) (store) · [Carver](https://github.com/RicheyWorks/Carver) (read planner) · [Renderer](https://github.com/RicheyWorks/Renderer) (materialized views) · [Brine](https://github.com/RicheyWorks/Brine) (adaptive cache).
Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.
