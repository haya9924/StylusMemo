package com.stylusmemo.app

import android.content.Context
import android.content.ContextWrapper
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stylusmemo.app.data.InkUtil
import com.stylusmemo.app.data.NoteRepository
import com.stylusmemo.app.data.StrokeCodec
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NoteRepositoryIncrementalSaveTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: NoteRepository
    private val note = Note(title = "incremental-save", pages = List(3) { PageData() })

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.cacheDir, "incremental-save-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        context = object : ContextWrapper(app) {
            override fun getExternalFilesDir(type: String?): File = root
            override fun getFilesDir(): File = root
        }
        repository = NoteRepository(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun page(index: Int): File {
        val dir = File(root, "notes/notes/${note.title}")
        val suffixed = File(dir, "page-$index.bin.bin")
        return if (suffixed.exists()) suffixed else File(dir, "page-$index.bin")
    }

    private fun stroke(x: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
        inputs.add(InputToolType.STYLUS, x, 1f, 0L)
        inputs.add(InputToolType.STYLUS, x + 1f, 2f, 10L)
        return Stroke(InkUtil.penBrush(0xff000000.toInt(), 1f), inputs.toImmutable())
    }

    private fun mark(index: Int): Long {
        val timestamp = 1_000_000_000_000L
        assertTrue(page(index).setLastModified(timestamp))
        return page(index).lastModified()
    }

    private fun assertPage(index: Int, strokes: List<Stroke>) {
        assertArrayEquals(StrokeCodec.toBytes(strokes), page(index).readBytes())
    }

    @Test
    fun unchangedPagesAreSkippedAndCallerMutationCannotChangeBaseline() = runBlocking {
        val original = stroke(1f)
        val mutable = mutableListOf(original)
        repository.saveNote(note, listOf(mutable, emptyList(), emptyList()))
        val first = mark(0)
        val second = mark(1)
        repository.saveNote(note, listOf(listOf(original), emptyList(), emptyList()))
        assertEquals(first, page(0).lastModified())
        assertEquals(second, page(1).lastModified())
        mutable.clear()
        repository.saveNote(note, listOf(mutable, emptyList(), emptyList()))
        assertPage(0, emptyList())
        assertEquals(second, page(1).lastModified())
    }

    @Test
    fun insertionReorderingAndDeletionCompareByDiskIndex() = runBlocking {
        val a = listOf(stroke(1f))
        val b = listOf(stroke(4f))
        val c = listOf(stroke(7f))
        repository.saveNote(note.copy(pages = note.pages.take(2)), listOf(a, b))
        repository.saveNote(note, listOf(c, a, b))
        assertPage(0, c)
        assertPage(1, a)
        assertPage(2, b)
        repository.saveNote(note, listOf(b, c, a))
        assertPage(0, b)
        assertPage(1, c)
        assertPage(2, a)
        repository.saveNote(note.copy(pages = note.pages.take(2)), listOf(b, a))
        assertPage(0, b)
        assertPage(1, a)
        repository.saveNote(note, listOf(b, a, emptyList()))
        assertPage(2, emptyList())
    }

    @Test
    fun missingAndUntrustedLoadedPagesAreWritten() = runBlocking {
        val empty = List(3) { emptyList<Stroke>() }
        repository.saveNote(note, empty)
        assertTrue(page(1).delete())
        repository.saveNote(note, empty)
        assertPage(1, emptyList())
        page(0).writeBytes(byteArrayOf(1))
        assertTrue(page(2).delete())
        repository = NoteRepository(context)
        assertTrue(repository.loadAllStrokes(note.id, 3).all { it.isEmpty() })
        repository.saveNote(note, empty)
        (0..2).forEach { assertPage(it, emptyList()) }
    }

    @Test
    fun failedPartialWriteCannotPreserveOrAdvanceBaseline() = runBlocking {
        val a = listOf(stroke(1f))
        val b = listOf(stroke(4f))
        repository.saveNote(note, listOf(a, a, a))
        assertTrue(page(1).delete())
        assertTrue(page(1).mkdir())
        try {
            repository.saveNote(note, listOf(b, b, b))
            fail("Expected page write failure")
        } catch (_: java.io.IOException) {
        }
        assertTrue(page(1).delete())
        repository.saveNote(note, listOf(a, a, a))
        (0..2).forEach { assertPage(it, a) }
    }

    @Test
    fun rootChangesInvalidatePreviouslyPersistedPages() = runBlocking {
        val strokes = List(3) { emptyList<Stroke>() }
        repository.saveNote(note, strokes)
        val timestamp = mark(0)
        repository.setRootUri("content://test/root")
        repository.setRootUri(null)
        repository.saveNote(note, strokes)
        assertTrue(page(0).lastModified() != timestamp)
        assertPage(0, emptyList())
    }

    @Test
    fun refreshSeesExternalRenameAndLegacyPageReplacement() = runBlocking {
        val original = listOf(stroke(1f))
        val replacement = listOf(stroke(8f))
        repository.saveNote(note, List(3) { original })
        val moved = File(page(0).parentFile!!.parentFile, "external-name")
        assertTrue(page(0).parentFile!!.renameTo(moved))
        File(moved, "page-0.bin.bin").writeBytes(StrokeCodec.toBytes(replacement))
        assertTrue(repository.listNotes().any { it.id == note.id })
        val loaded = repository.loadAllStrokes(note.id, 3)[0]
        assertEquals(1, loaded.size)
        assertEquals(8f, loaded[0].inputs.get(0).x, 0.001f)
        repository.saveNote(note, List(3) { original })
        assertArrayEquals(StrokeCodec.toBytes(original), File(moved, "page-0.bin.bin").readBytes())
        assertTrue(!page(0).parentFile!!.exists())
    }

    @Test
    fun unloadedPagesAreNotRewrittenOrOverwritten() = runBlocking {
        val a = listOf(stroke(1f))
        val b = listOf(stroke(4f))
        repository.saveNote(note, listOf(a, b, a))
        val untouched = mark(1)
        // Simulate a lazy-load save where page 1 was never loaded (passed as empty).
        repository.saveNote(note, listOf(a, emptyList(), a), unloadedPages = setOf(1))
        assertEquals(untouched, page(1).lastModified())
        assertPage(1, b)
        // Once loaded, the real content is written again.
        repository.saveNote(note, listOf(a, b, a))
        assertPage(1, b)
    }

    @Test
    fun appendingPagesKeepsUnloadedPagesIntact() = runBlocking {
        val a = listOf(stroke(1f))
        val b = listOf(stroke(4f))
        repository.saveNote(note, listOf(a, b, a))
        val untouched = mark(1)
        val extended = note.copy(pages = note.pages + PageData())
        repository.saveNote(extended, listOf(a, emptyList(), a, emptyList()), unloadedPages = setOf(1))
        assertEquals(untouched, page(1).lastModified())
        assertPage(1, b)
        assertPage(3, emptyList())
    }

    @Test
    fun equalMigrationDestinationIsRejected() = runBlocking {
        repository.saveNote(note, List(3) { emptyList() })
        try {
            repository.migrate(null, "")
            fail("Expected equal destination rejection")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(page(0).isFile)
    }

    @Test
    fun recreatedStorageAtSamePathMustWriteMissingPages() = runBlocking {
        val strokes = List(3) { listOf(stroke(it.toFloat())) }
        repository.saveNote(note, strokes)
        assertTrue(page(0).parentFile!!.deleteRecursively())
        repository.saveNote(note, strokes)
        (0..2).forEach { assertPage(it, strokes[it]) }
    }
}
