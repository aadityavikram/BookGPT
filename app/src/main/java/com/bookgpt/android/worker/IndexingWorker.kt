package com.bookgpt.android.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookgpt.android.MainActivity
import com.bookgpt.android.R
import com.bookgpt.android.data.library.IndexProgress
import com.bookgpt.android.data.library.LibraryRepository
import com.bookgpt.android.data.openai.OperationFailure
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlin.math.pow

@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        if (bookId < 0) return Result.failure()
        val initial = IndexProgress("Book", "Preparing book…", 0)
        setForeground(IndexingNotifications.foregroundInfo(applicationContext, bookId, initial))
        return try {
            var lastProgress = initial
            libraryRepository.indexBook(bookId) { progress ->
                lastProgress = progress
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_STAGE to progress.stage,
                        KEY_PROGRESS_PERCENT to (progress.percent ?: -1),
                    ),
                )
                setForeground(
                    IndexingNotifications.foregroundInfo(
                        applicationContext,
                        bookId,
                        progress,
                    ),
                )
            }
            IndexingNotifications.finished(
                context = applicationContext,
                bookId = bookId,
                title = lastProgress.bookTitle,
                message = "Indexing complete",
                isError = false,
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (failure: OperationFailure) {
            handleFailure(bookId, failure)
        } catch (_: Exception) {
            val message = "BookGPT could not process this book."
            libraryRepository.markIndexingFailed(bookId, message)
            IndexingNotifications.finished(
                applicationContext,
                bookId,
                "Book",
                message,
                isError = true,
            )
            Result.failure(workDataOf(KEY_FAILURE_REASON to message))
        }
    }

    private suspend fun handleFailure(bookId: Long, failure: OperationFailure): Result {
        val completedAttempts = runAttemptCount + 1
        val reason = failure.message ?: "Indexing failed."
        if (failure.retryable && completedAttempts < MAX_ATTEMPTS) {
            val nextAttempt = completedAttempts + 1
            val delaySeconds = retryDelaySeconds(completedAttempts)
            val message = "$reason Retry $nextAttempt of $MAX_ATTEMPTS in about ${formatDelay(delaySeconds)}."
            libraryRepository.markIndexingRetry(bookId, message)
            IndexingNotifications.finished(
                applicationContext,
                bookId,
                "Indexing paused",
                message,
                isError = false,
            )
            return Result.retry()
        }

        val message = if (failure.retryable) {
            "$reason Indexing stopped after $MAX_ATTEMPTS attempts."
        } else {
            reason
        }
        libraryRepository.markIndexingFailed(bookId, message)
        IndexingNotifications.finished(
            applicationContext,
            bookId,
            "Indexing failed",
            message,
            isError = true,
        )
        return Result.failure(
            workDataOf(
                KEY_FAILURE_REASON to message,
                KEY_FAILURE_CATEGORY to failure.category.name,
            ),
        )
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val KEY_FAILURE_CATEGORY = "failure_category"
        const val MAX_ATTEMPTS = 5

        internal fun retryDelaySeconds(completedAttempts: Int): Long =
            (30.0 * 2.0.pow((completedAttempts - 1).coerceAtLeast(0))).toLong()

        private fun formatDelay(seconds: Long): String =
            if (seconds < 60) "$seconds seconds" else "${seconds / 60} minutes"
    }
}

object IndexingNotifications {
    const val CHANNEL_ID = "book_indexing"
    private const val COMPLETION_ID_OFFSET = 1_000_000

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Book indexing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress and results while BookGPT indexes books"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, bookId: Long, progress: IndexProgress): ForegroundInfo {
        createChannel(context)
        val builder = baseBuilder(context, progress.bookTitle)
            .setContentText(progress.stage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val percent = progress.percent
        if (percent == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }
        return ForegroundInfo(
            notificationId(bookId),
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    fun finished(
        context: Context,
        bookId: Long,
        title: String,
        message: String,
        isError: Boolean,
    ) {
        createChannel(context)
        val notification = baseBuilder(context, title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(if (isError) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                notificationId(bookId) + COMPLETION_ID_OFFSET,
                notification,
            )
        } catch (_: SecurityException) {
            // Indexing still runs when notification permission is denied.
        }
    }

    private fun baseBuilder(context: Context, title: String): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun notificationId(bookId: Long): Int =
        (bookId % 900_000L).toInt() + 10_000
}
