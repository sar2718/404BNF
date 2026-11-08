# A17 — Background transcription

## Current implementation

`TranscriptionTaskManager` is lifecycle-independent and persists task transitions in
Room. For one recording, transition validation, Room write, and publication to the UI
are one suspend operation protected by one mutex. The service starts only after
`QUEUED` has been confirmed in Room.

Transitions are monotonic. Terminal states (`COMPLETED`, `FAILED`, `CANCELLED`,
`INTERRUPTED`) reject late callbacks, and callbacks from another attempt are rejected
by `attemptId`. `COMPLETED` is written only after `TranscriptRepository.save()` succeeds.
Room exceptions are not swallowed.
If Foreground Service startup fails, `QUEUED` is explicitly transitioned to and
persisted as `FAILED` for every queued task before the service stops.
The service uses the declared `dataSync` foreground type on Android 14 and
older, and `mediaProcessing` on Android 15 (API 35) and newer. The latter is
not selected on API 34, where it is not a recognized foreground-service type.

## Cancellation and timeout

User cancellation records `CANCELLED`; a Foreground Service timeout records
`INTERRUPTED`. The terminal state is persisted before the active job receives
cancellation, so its `CancellationException` handler cannot replace a timeout with
`CANCELLED`. The result save is guarded by the current attempt and terminal state.
Timeout persistence runs in a service-independent I/O scope; the service is stopped
after that attempt completes, subject to Android process-kill limits. Long result
saves do not hold the transition mutex.
The timeout callback captures `activeTaskId` before cancelling the worker. Worker
cleanup does not stop the service or clear that identifier while timeout handling
is active; timeout handling clears it only after attempting the `INTERRUPTED` write.
System timeout does not wait for the per-recording save lock: it records
`INTERRUPTED` under the transition mutex and cancels the active Job immediately.
If an already-started save later returns, its final attempt check rejects
`COMPLETED`. User cancellation still waits for an in-progress save to preserve
the consistency of a user-visible result.

Android may destroy the process immediately after the timeout callback. Therefore the
application cannot promise an absolute guarantee that a final status is persisted in
that case.

## Queue recovery

On the next application startup, Room is initialized before the queue is inspected.
Persisted `QUEUED` tasks cause the Foreground Service to be started while the app is
available. If Android rejects a background service start, the OS restriction is not
bypassed: the user must open the app and continue the queue.
Queue loading completes before a new enqueue can inspect or replace task state.
Room Flow updates cannot overwrite a newer local transition.

Deletion and enqueue use the same task transition mutex. Deletion reads both the
in-memory and persisted task, performs the file/recording deletion while protected,
and removes the corresponding task row after success.
Result saving and deletion share a per-recording operation lock. Cancellation
waits for an already-started result save; a cancellation before saving prevents
the save. The global transition mutex is not held during the long save itself.

The service creates its ML job lazily, registers it before starting it, and checks
that `PREPARING` was accepted and the attempt is still current before entering
the pipeline.

## Attempts and migration

`attemptId` is a UUID string, not a wall-clock value. `MIGRATION_4_5` changes only the
task table column type and copies existing rows; it is not a destructive database
migration.

## Disagreements & Engineering Decisions

An additional mutex around the previous fire-and-forget writes would not have fixed
the race because validation happened before the write was queued. The implementation
therefore makes the whole transition a suspend operation and does not hide Room
failures behind `runCatching`.
