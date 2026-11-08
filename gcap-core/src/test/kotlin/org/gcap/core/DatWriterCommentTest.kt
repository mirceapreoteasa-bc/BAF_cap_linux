package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DatWriterCommentTest {

    private fun sample(comment: String) = GroundCall(
        sampleId = "t1",
        comment = comment,
        headerLines = List(149) { "" },
    )

    @Test
    fun clearedCommentOverwritesInheritedHeader() {
        val inherited = sample("OLDCOMMENT")
        assertEquals(
            "oldcomment",
            DatWriter.render(inherited).split("\r\n")[111],
        )
        assertEquals(
            "",
            DatWriter.render(inherited.copy(comment = "")).split("\r\n")[111],
        )
    }

    @Test
    fun commentIsWrittenLowercase() {
        assertEquals(
            "testcmt123",
            DatWriter.render(sample("TESTCMT123")).split("\r\n")[111],
        )
    }
}
