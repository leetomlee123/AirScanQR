package top.css.airscanqr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TransferSessionTest {

    @Test
    fun reassemblesOutOfOrderSlices() {
        val file = "hello airscan".toByteArray()
        val session = TransferSession()
        framesFor(file, chunk = 4).reversed().forEach { session.ingest(it) }
        assertTrue(session.isComplete)
        assertArrayEquals(file, session.assemble())
        assertEquals("note.txt", session.fileName)
    }

    @Test
    fun ignoresDuplicatesAndShortFrames() {
        val session = TransferSession()
        val frames = framesFor("abc".toByteArray(), chunk = 8)
        assertEquals(TransferSession.IngestResult.Accepted, session.ingest(frames[0]))
        assertEquals(TransferSession.IngestResult.Duplicate, session.ingest(frames[0]))
        assertEquals(TransferSession.IngestResult.Ignored, session.ingest("only|three|parts"))
        assertEquals(TransferSession.IngestResult.Ignored, session.ingest("ABC|note.txt|1|5|xxxx"))
        assertEquals(1, session.receivedCount)
    }

    @Test
    fun keepsPipesInsideThePayload() {
        val session = TransferSession()
        session.ingest("ABC|name|has|pipe.bin|1|0|YQ==|extra")
        assertTrue(session.isComplete)
        assertEquals("name|has|pipe.bin", session.fileName)
        assertArrayEquals(byteArrayOf('a'.code.toByte()), session.assemble())
    }

    @Test
    fun blankNameFallsBack() {
        val session = TransferSession()
        session.ingest("ABC||1|0|YQ==")
        assertEquals("unnamed_file.txt", session.fileName)
    }

    @Test
    fun foreignTaskDoesNotResetProgress() {
        val session = TransferSession()
        session.ingest("ABC|a.bin|2|0|YQ==")
        assertTrue(session.isForeignTask("XYZ|b.bin|1|0|YQ=="))
        assertEquals(TransferSession.IngestResult.ForeignTask, session.ingest("XYZ|b.bin|1|0|YQ=="))
        assertEquals(1, session.receivedCount)
        assertEquals("ABC", session.taskId)
    }

    @Test
    fun rejectsOversizedTransfer() {
        val session = TransferSession()
        val tooMany = TransferSession.MAX_FRAMES + 1
        assertEquals(
            TransferSession.IngestResult.Ignored,
            session.ingest("ABC|a.bin|$tooMany|0|YQ==")
        )
        assertFalse(session.isComplete)
    }

    @Test
    fun mismatchedTotalIsIgnored() {
        val session = TransferSession()
        session.ingest("ABC|a.bin|2|0|YQ==")
        assertEquals(
            TransferSession.IngestResult.Ignored,
            session.ingest("ABC|a.bin|3|1|Yg==")
        )
        assertEquals(1, session.receivedCount)
    }

    private fun framesFor(bytes: ByteArray, chunk: Int, name: String = "note.txt"): List<String> {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val slices = encoded.chunked(chunk)
        return slices.mapIndexed { index, slice ->
            listOf("ABC", name, slices.size, index, slice).joinToString("|")
        }
    }
}
