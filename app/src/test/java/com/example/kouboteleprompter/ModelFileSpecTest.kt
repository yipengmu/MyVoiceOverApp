package com.example.kouboteleprompter

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileSpecTest {
    @Test
    fun `accepts a file only when both size and digest match`() {
        val directory = createTempDirectory(prefix = "koubo-model-spec-").toFile()
        try {
            val file = File(directory, "model.bin").apply { writeText("model") }
            val spec = ModelFileSpec(
                name = "model.bin",
                size = 5,
                sha256 = "9372c470eeadd5ecd9c3c74c2b3cb633f8e2f2fad799250a0f70d652b6b825e4"
            )

            assertTrue(spec.matches(file))
            file.appendText("-corrupt")
            assertFalse(spec.matches(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects a missing file`() {
        val spec = ModelFileSpec("missing", 0, "")
        assertFalse(spec.matches(File("definitely-not-a-real-koubo-model")))
    }
}
