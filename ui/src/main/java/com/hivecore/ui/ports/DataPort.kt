package com.hivecore.ui.ports

// Spec section 17 (Data) and section 7 (privacy, release): the per-item private / released state the swarm catalog
// honours. Tables and documents come from `core.SchemaSource`; media and the marks come from [DataPort]. The state
// track implements it over the data store's `_private` marks and the blob store's release records; [FakeDataPort]
// drives the screen and the JVM tests until then.

enum class ItemKind { TABLE, DOC, MEDIA }

/** How long a released item may leave this phone (spec section 13.5: blobs are deleted after countersign). */
sealed interface Release {
    /** Released for one job only. */
    data class ForJob(val jobId: String) : Release
    /** Released until [untilTs] (ms since epoch); "release for a month". */
    data class Until(val untilTs: Long) : Release
}

/** A photo, audio or video file visible to the swarm catalog. */
data class MediaItem(val id: String, val name: String, val kind: String, val sizeBytes: Long)

/** The visibility of one item. Private wins over any release. */
data class Visibility(
    val kind: ItemKind,
    val id: String,
    val private: Boolean = false,
    val release: Release? = null,
    /** tables only: hidden from the swarm catalog */
    val hidden: Boolean = false,
    /** tables only: the confidential column names */
    val confidentialColumns: Set<String> = emptySet(),
)

interface DataPort {
    fun media(): List<MediaItem>
    /** Every mark this phone holds; items without a mark are shared, unreleased and visible. */
    fun visibility(): List<Visibility>
    fun setPrivate(kind: ItemKind, id: String, on: Boolean)
    /** null clears the release. */
    fun release(kind: ItemKind, id: String, release: Release?)
    fun setConfidentialColumns(table: String, columns: Set<String>)
    fun hideTable(table: String, hidden: Boolean)
}
