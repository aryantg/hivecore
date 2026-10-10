package com.hivecore.ui.ports.fakes

import com.hivecore.ui.ports.DataPort
import com.hivecore.ui.ports.ItemKind
import com.hivecore.ui.ports.MediaItem
import com.hivecore.ui.ports.Release
import com.hivecore.ui.ports.Visibility

/** An in-memory [DataPort]: marks live in [marks], keyed by kind and id; every write is recorded in [log]. */
class FakeDataPort(media: List<MediaItem> = SAMPLE_MEDIA) : DataPort {
    var mediaItems: List<MediaItem> = media
    val marks = LinkedHashMap<Pair<ItemKind, String>, Visibility>()
    val log = mutableListOf<String>()
    var failNext: Throwable? = null

    private fun check() { val f = failNext; if (f != null) { failNext = null; throw f } }
    private fun edit(kind: ItemKind, id: String, f: (Visibility) -> Visibility) {
        check()
        marks[kind to id] = f(marks[kind to id] ?: Visibility(kind, id))
    }

    override fun media(): List<MediaItem> { check(); return mediaItems }
    override fun visibility(): List<Visibility> { check(); return marks.values.toList() }
    override fun setPrivate(kind: ItemKind, id: String, on: Boolean) { log += "private $kind $id $on"; edit(kind, id) { it.copy(private = on) } }
    override fun release(kind: ItemKind, id: String, release: Release?) { log += "release $kind $id $release"; edit(kind, id) { it.copy(release = release) } }
    override fun setConfidentialColumns(table: String, columns: Set<String>) { log += "confidential $table $columns"; edit(ItemKind.TABLE, table) { it.copy(confidentialColumns = columns) } }
    override fun hideTable(table: String, hidden: Boolean) { log += "hide $table $hidden"; edit(ItemKind.TABLE, table) { it.copy(hidden = hidden) } }

    companion object {
        val SAMPLE_MEDIA = listOf(
            MediaItem("m1", "site_photos_2026-10", "image", 184_000_000L),
            MediaItem("m2", "standup_2026-10-09.m4a", "audio", 21_000_000L),
            MediaItem("m3", "warehouse_cam_03.mp4", "video", 912_000_000L),
        )
    }
}
