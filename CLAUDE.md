# Twine — working notes for agents

Engine 9: crash-atomic batches by composition. One class (`Twine` + inner `Batch`):
journal to tmp → fsync → ATOMIC_MOVE (the commit point) → apply → delete; `over()` discards
torn tmp, replays committed journal (CRC-checked, whole-file read).

## Invariants (do not break)
- **The rename IS the commit.** Nothing may apply before the move; everything after the
  move must be completable by replay alone.
- **Replay must stay idempotent** — only `put`/`delete` through the store (last-writer-
  wins). Any op that isn't replay-safe can't join a batch.
- **Construct Twine before any other post-reopen writes**; one Twine, one in-flight batch.
- Corrupt committed journal = refuse loudly, apply nothing. Crash-window tests in
  `TwineTest` cover all three windows — keep them green and add a window with any change.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.
