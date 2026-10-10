package com.hivecore.engine.journal

import com.hivecore.core.SLog

/**
 * The resume entry point for `JobRunner.resume(jobId)` (§13.3, §13.4): asks the deputy for rows newer than this
 * journal's (when the job recorded one and [pull] is wired), loads the snapshot and runs the RECLAIM round. Returns
 * null when the journal does not know the job.
 */
class JobResume(
    private val journal: JobJournal,
    private val coordinator: ResumeCoordinator,
    private val pull: JournalPull? = null,
    private val deputyOf: (JobRow) -> String? = ResumeCoordinator::deputyOf,
) {
    suspend fun resume(jobId: String): ResumePlan? {
        val first = journal.load(jobId) ?: return null
        val deputy = deputyOf(first.job)
        if (deputy != null && pull != null) {
            val n = pull.pull(deputy, jobId)
            SLog.d("Resume", "deputy ${deputy.take(8)}: ${n?.let { "$it newer rows" } ?: "no answer"}")
        }
        val snap = journal.load(jobId) ?: return null
        return coordinator.resume(snap, extra = listOfNotNull(deputy))
    }
}
