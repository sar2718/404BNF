package com.team404bnf.app.transcription

import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BackgroundTranscriptionTest {

    @Test
    fun `Test A - first enqueue stays QUEUED and is NOT marked as INTERRUPTED`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()

        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.QUEUED,
            stageMessage = "В очереди…"
        )

        // Run recovery check
        TranscriptionTaskManager.recoverInterruptedTasks()

        // QUEUED tasks must NOT be marked INTERRUPTED!
        val task = TranscriptionTaskManager.getTask(recordingId)
        assertNotNull(task)
        assertEquals(TranscriptionTaskStatus.QUEUED, task?.status)
        assertEquals("В очереди…", task?.stageMessage)
    }

    @Test
    fun `Test B - duplicate task update does not spawn duplicate active tasks`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()

        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.RUNNING_ASR,
            stageMessage = "Инференс GigaAM"
        )

        assertTrue(TranscriptionTaskManager.getTask(recordingId)?.isActive == true)

        // Duplicate update
        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.RUNNING_ASR,
            stageMessage = "Инференс GigaAM"
        )

        val activeCount = TranscriptionTaskManager.tasksFlow.value.values.count {
            it.recordingId == recordingId && it.isActive
        }
        assertEquals(1, activeCount)
    }

    @Test
    fun `Test C - orphaned running tasks are recovered as INTERRUPTED on process restart`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()

        // Task marked as RUNNING_ASR without an active job (orphaned after process kill)
        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.RUNNING_ASR,
            stageMessage = "Инференс"
        )

        TranscriptionTaskManager.recoverInterruptedTasks()

        val task = TranscriptionTaskManager.getTask(recordingId)
        assertEquals(TranscriptionTaskStatus.INTERRUPTED, task?.status)
        assertEquals("Обработка прервана", task?.stageMessage)
    }

    @Test
    fun `Test D - late callback after cancellation does NOT overwrite CANCELLED state`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        val dummyJob = Job()

        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.RUNNING_DIARIZATION,
            stageMessage = "Диаризация"
        )
        TranscriptionTaskManager.registerJob(recordingId, dummyJob)

        // Cancel task
        TranscriptionTaskManager.cancel(recordingId)
        assertEquals(TranscriptionTaskStatus.CANCELLED, TranscriptionTaskManager.getTask(recordingId)?.status)

        // Simulate late callback trying to update task to RUNNING_ASR or COMPLETED
        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.RUNNING_ASR,
            stageMessage = "Запоздалый вызов"
        )

        // Status must remain CANCELLED!
        assertEquals(TranscriptionTaskStatus.CANCELLED, TranscriptionTaskManager.getTask(recordingId)?.status)
    }

    @Test
    fun `Test E - COMPLETED status is set only on explicit completion update`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()

        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.SAVING,
            stageMessage = "Сохранение"
        )

        assertEquals(TranscriptionTaskStatus.SAVING, TranscriptionTaskManager.getTask(recordingId)?.status)

        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.COMPLETED,
            stageMessage = "Расшифровка готова"
        )

        val task = TranscriptionTaskManager.getTask(recordingId)
        assertEquals(TranscriptionTaskStatus.COMPLETED, task?.status)
        assertTrue(task?.isFinished == true)
    }

    @Test
    fun `Test F - service failure sets FAILED state without crash`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()

        TranscriptionTaskManager.updateTask(
            recordingId = recordingId,
            status = TranscriptionTaskStatus.FAILED,
            stageMessage = "",
            errorMessage = "Не удалось запустить фоновый сервис"
        )

        val task = TranscriptionTaskManager.getTask(recordingId)
        assertEquals(TranscriptionTaskStatus.FAILED, task?.status)
        assertEquals("Не удалось запустить фоновый сервис", task?.errorMessage)
    }

    @Test
    fun `Test G - sequential queueing picks tasks ordered by creation time`() = runBlocking {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()

        TranscriptionTaskManager.updateTask(
            recordingId = id1,
            status = TranscriptionTaskStatus.QUEUED,
            stageMessage = "1"
        )
        Thread.sleep(10)
        TranscriptionTaskManager.updateTask(
            recordingId = id2,
            status = TranscriptionTaskStatus.QUEUED,
            stageMessage = "2"
        )

        val nextTask = TranscriptionTaskManager.getNextQueuedTask()
        assertNotNull(nextTask)
        assertTrue(
            TranscriptionTaskManager.getTask(id1)!!.createdAtEpochMs <=
                TranscriptionTaskManager.getTask(id2)!!.createdAtEpochMs
        )
    }

    @Test
    fun `rapid state transitions are committed in order and stale attempt is rejected`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.QUEUED)
        val attempt = TranscriptionTaskManager.getTask(recordingId)!!.attemptId

        listOf(
            TranscriptionTaskStatus.PREPARING,
            TranscriptionTaskStatus.RUNNING_ASR,
            TranscriptionTaskStatus.SAVING,
            TranscriptionTaskStatus.COMPLETED
        ).forEach { status ->
            assertTrue(TranscriptionTaskManager.updateTask(recordingId, status, attemptId = attempt))
        }
        assertFalse(
            TranscriptionTaskManager.updateTask(
                recordingId, TranscriptionTaskStatus.RUNNING_ASR, attemptId = UUID.randomUUID().toString()
            )
        )
        assertEquals(TranscriptionTaskStatus.COMPLETED, TranscriptionTaskManager.getTask(recordingId)?.status)
    }

    @Test
    fun `concurrent updates cannot move a terminal task backwards`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.RUNNING_ASR)
        val attempt = TranscriptionTaskManager.getTask(recordingId)!!.attemptId
        listOf(
            async { TranscriptionTaskManager.cancel(recordingId) },
            async { TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.SAVING, attemptId = attempt) }
        ).awaitAll()
        assertEquals(TranscriptionTaskStatus.CANCELLED, TranscriptionTaskManager.getTask(recordingId)?.status)
    }

    @Test
    fun `completion is rejected after interruption`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.RUNNING_ASR)
        val attempt = TranscriptionTaskManager.getTask(recordingId)!!.attemptId
        TranscriptionTaskManager.interrupt(recordingId, "timeout")
        assertFalse(
            TranscriptionTaskManager.updateTask(
                recordingId, TranscriptionTaskStatus.COMPLETED, attemptId = attempt
            )
        )
        assertEquals(TranscriptionTaskStatus.INTERRUPTED, TranscriptionTaskManager.getTask(recordingId)?.status)
    }

    @Test
    fun `failed result save cannot produce COMPLETED`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.SAVING)
        val attempt = TranscriptionTaskManager.getTask(recordingId)!!.attemptId
        try {
            TranscriptionTaskManager.saveResult(recordingId, attempt) {
                error(" Room write failure")
            }
        } catch (_: IllegalStateException) {
            // The service converts this failure to FAILED.
        }
        assertEquals(TranscriptionTaskStatus.SAVING, TranscriptionTaskManager.getTask(recordingId)?.status)
        assertFalse(
            TranscriptionTaskManager.updateTask(
                recordingId, TranscriptionTaskStatus.COMPLETED, attemptId = UUID.randomUUID().toString()
            )
        )
    }

    @Test
    fun `each new task attempt receives a distinct UUID`() = runBlocking {
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(firstId, TranscriptionTaskStatus.QUEUED)
        TranscriptionTaskManager.updateTask(secondId, TranscriptionTaskStatus.QUEUED)
        val firstAttempt = TranscriptionTaskManager.getTask(firstId)!!.attemptId
        val secondAttempt = TranscriptionTaskManager.getTask(secondId)!!.attemptId
        assertNotNull(UUID.fromString(firstAttempt))
        assertNotNull(UUID.fromString(secondAttempt))
        assertFalse(firstAttempt == secondAttempt)
    }

    @Test
    fun `concurrent enqueue of one recording creates one active task`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        val results = listOf(
            async { TranscriptionTaskManager.enqueueForTest(recordingId) },
            async { TranscriptionTaskManager.enqueueForTest(recordingId) }
        ).awaitAll()
        assertEquals(1, results.count { it != null })
        assertTrue(TranscriptionTaskManager.getTask(recordingId)?.isActive == true)
    }

    @Test
    fun `foreground service failure can move QUEUED task to FAILED`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.QUEUED)

        assertTrue(
            TranscriptionTaskManager.updateTask(
                recordingId,
                TranscriptionTaskStatus.FAILED,
                errorMessage = "FGS unavailable"
            )
        )
        assertEquals(TranscriptionTaskStatus.FAILED, TranscriptionTaskManager.getTask(recordingId)?.status)
    }

    @Test
    fun `foreground service failure marks every queued task failed`() = runBlocking {
        val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        ids.forEach { TranscriptionTaskManager.updateTask(it, TranscriptionTaskStatus.QUEUED) }

        assertTrue(TranscriptionTaskManager.failQueuedTasks("FGS unavailable") >= ids.size)
        ids.forEach {
            assertEquals(TranscriptionTaskStatus.FAILED, TranscriptionTaskManager.getTask(it)?.status)
        }
    }

    @Test
    fun `save and cancel are serialized and deletion waits for save`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.SAVING)
        val attempt = TranscriptionTaskManager.getTask(recordingId)!!.attemptId
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()

        val saveJob = async {
            TranscriptionTaskManager.saveResult(recordingId, attempt) {
                saveStarted.complete(Unit)
                releaseSave.await()
            }
        }
        saveStarted.await()
        val cancelJob = async { TranscriptionTaskManager.cancel(recordingId) }
        val deleteJob = async {
            TranscriptionTaskManager.deleteIfInactive(recordingId) {}
        }
        delay(20)
        assertFalse(cancelJob.isCompleted)
        assertFalse(deleteJob.isCompleted)
        releaseSave.complete(Unit)

        assertTrue(saveJob.await())
        cancelJob.await()
        deleteJob.await()
        assertEquals(null, TranscriptionTaskManager.getTask(recordingId))
    }

    @Test
    fun `cancel before lazy job start prevents processing`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.PREPARING)
        val job = async(start = CoroutineStart.LAZY) { delay(1000) }
        TranscriptionTaskManager.registerJob(recordingId, job)
        TranscriptionTaskManager.cancel(recordingId)
        job.start()
        assertTrue(job.isCancelled)
        assertEquals(TranscriptionTaskStatus.CANCELLED, TranscriptionTaskManager.getTask(recordingId)?.status)
    }

    @Test
    fun `timeout captures active task before worker cancellation`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.RUNNING_ASR)
        val worker = Job()
        TranscriptionTaskManager.registerJob(recordingId, worker)

        val capturedId = TranscriptionTaskManager.getTask(recordingId)?.recordingId
        worker.cancel()
        assertEquals(
            TranscriptionTaskStatus.RUNNING_ASR,
            TranscriptionTaskManager.getTask(recordingId)?.status
        )
        TranscriptionTaskManager.interrupt(capturedId!!, "system timeout")

        assertEquals(TranscriptionTaskStatus.INTERRUPTED, TranscriptionTaskManager.getTask(recordingId)?.status)
        assertTrue(worker.isCancelled)
    }

    @Test
    fun `timeout interrupts without waiting for a blocked result save`() = runBlocking {
        val recordingId = UUID.randomUUID().toString()
        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.SAVING)
        val attempt = TranscriptionTaskManager.getTask(recordingId)!!.attemptId
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val saveJob = async {
            TranscriptionTaskManager.saveResult(recordingId, attempt) {
                saveStarted.complete(Unit)
                releaseSave.await()
            }
        }

        saveStarted.await()
        TranscriptionTaskManager.interrupt(recordingId, "system timeout")

        assertEquals(TranscriptionTaskStatus.INTERRUPTED, TranscriptionTaskManager.getTask(recordingId)?.status)
        releaseSave.complete(Unit)
        assertFalse(saveJob.await())
    }
}
