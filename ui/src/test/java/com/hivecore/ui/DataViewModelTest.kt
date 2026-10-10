package com.hivecore.ui

import com.hivecore.core.NodeRole
import com.hivecore.core.SchemaSource
import com.hivecore.core.fakes.FakeDataAdmin
import com.hivecore.core.fakes.FakeSchemaSource
import com.hivecore.ui.ports.ItemKind
import com.hivecore.ui.ports.Release
import com.hivecore.ui.ports.fakes.FakeDataPort
import com.hivecore.ui.vm.DataViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataViewModelTest {
    private val now = 1_700_000_000_000L
    private val schema = FakeSchemaSource(NodeRole.HR)
    private val admin = FakeDataAdmin()
    private val port = FakeDataPort()

    private fun TestScope.vm(src: SchemaSource = schema) = DataViewModel(src, admin, port, backgroundScope, StandardTestDispatcher(testScheduler), clock = { now })

    @Test fun refreshListsTablesDocsAndMediaWithTheirState() = runTest {
        val vm = vm()
        vm.refresh(); settle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("employees"), s.tables.map { it.name })
        assertEquals("61 rows · 6 columns · confidential", s.tables[0].detail)
        assertEquals(setOf("salary", "commission"), s.tables[0].confidentialColumns)
        assertEquals("shared · schema only, no values", s.tables[0].stateLine(now))
        assertEquals(listOf("onboarding_checklist.docx"), s.docs.map { it.name })
        assertEquals(listOf("site_photos_2026-10", "standup_2026-10-09.m4a", "warehouse_cam_03.mp4"), s.media.map { it.name })
        assertEquals("video · 869.8 MB", s.media[2].detail)
        assertEquals(0, s.privateCount)
    }

    @Test fun refreshErrorIsShown() = runTest {
        val vm = vm(object : SchemaSource { override fun schema() = throw IllegalStateException("database is locked") })
        vm.refresh(); settle()
        assertEquals("Could not read the data registry: database is locked", vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun privateReleaseAndHideGoThroughThePortAndPersist() = runTest {
        var changed = 0
        val vm = vm().also { it.onDataChanged = { changed++ } }
        vm.refresh(); settle()
        val t = vm.state.value.tables[0]
        vm.setPrivate(t, true)
        assertTrue("optimistic", vm.state.value.tables[0].private)
        settle()
        assertEquals("employees stays on this phone", vm.state.value.message)
        assertEquals("private · never leaves this phone", vm.state.value.tables[0].stateLine(now))
        assertTrue(port.marks[ItemKind.TABLE to "employees"]!!.private)
        assertEquals(1, vm.state.value.privateCount)

        val doc = vm.state.value.docs[0]
        vm.releaseForJob(doc, "job-12"); settle()
        assertEquals(Release.ForJob("job-12"), port.marks[ItemKind.DOC to "doc_onboarding"]!!.release)
        assertEquals("released for job job-12", vm.state.value.docs[0].stateLine(now))
        vm.releaseForJob(doc, " "); settle()
        assertEquals("Pick a job to release onboarding_checklist.docx for", vm.state.value.message)

        val media = vm.state.value.media[0]
        vm.releaseForMonth(media); settle()
        assertEquals(Release.Until(now + DataViewModel.MONTH_MS), port.marks[ItemKind.MEDIA to "m1"]!!.release)
        assertEquals("released until ${Format.dateTime(now + DataViewModel.MONTH_MS)}", vm.state.value.media[0].stateLine(now))
        assertEquals("release expired", vm.state.value.media[0].stateLine(now + DataViewModel.MONTH_MS + 1))
        vm.clearRelease(vm.state.value.media[0]); settle()
        assertNull(port.marks[ItemKind.MEDIA to "m1"]!!.release)

        vm.hideTable(vm.state.value.tables[0], true); settle()
        assertTrue(port.marks[ItemKind.TABLE to "employees"]!!.hidden)
        assertEquals("hidden from the catalog", vm.state.value.tables[0].stateLine(now))
        assertTrue(changed >= 5)

        val fresh = vm()
        fresh.refresh(); settle()
        assertTrue(fresh.state.value.tables[0].private)
        assertTrue(fresh.state.value.tables[0].hidden)
    }

    @Test fun confidentialColumnsAreEditedPerTable() = runTest {
        val vm = vm()
        vm.refresh(); settle()
        val t = vm.state.value.tables[0]
        vm.editColumns(t.id)
        assertEquals("employees", vm.state.value.editingColumns)
        vm.setConfidentialColumns(t, setOf("salary", "commission", "joined")); settle()
        assertNull(vm.state.value.editingColumns)
        assertEquals(setOf("salary", "commission", "joined"), port.marks[ItemKind.TABLE to "employees"]!!.confidentialColumns)
        assertEquals(setOf("salary", "commission", "joined"), vm.state.value.tables[0].confidentialColumns)
        assertEquals("employees: confidential commission, joined, salary", vm.state.value.message)
    }

    @Test fun deleteGoesThroughAdminAndFailuresAreReported() = runTest {
        val vm = vm()
        vm.refresh(); settle()
        vm.deleteTable("employees"); settle()
        assertEquals(listOf("employees"), admin.deletedTables)
        assertEquals("Deleted table employees", vm.state.value.message)
        vm.deleteDoc(vm.state.value.docs[0].doc!!); settle()
        assertEquals(listOf("doc_onboarding"), admin.deletedDocs)
        port.failNext = IllegalStateException("disk full")
        vm.setPrivate(vm.state.value.media[0], true); settle()
        assertEquals("Failed: disk full", vm.state.value.message)
        assertFalse("re-read after the failure", vm.state.value.media[0].private)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }
}
