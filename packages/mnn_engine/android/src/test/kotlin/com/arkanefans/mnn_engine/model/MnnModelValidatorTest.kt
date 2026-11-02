package com.arkanefans.mnn_engine.model

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MnnModelValidatorTest {
    private val validator = MnnModelValidator()

    @Test
    fun validatesCompleteTextModelDirectory() {
        val root = createTempDirectory("mnn-model").toFile()
        try {
            File(root, "llm.mnn").writeBytes(byteArrayOf(1))
            File(root, "llm.mnn.weight").writeBytes(byteArrayOf(2))
            File(root, "config.json").writeText(
                """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","backend_type":"cpu"}""",
            )

            val result = validator.validate(root)

            assertEquals(emptyList(), result.warnings)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsReferencedPathEscapingModelDirectory() {
        val parent = createTempDirectory("mnn-parent").toFile()
        val root = File(parent, "model").apply { mkdir() }
        try {
            File(parent, "outside.mnn").writeBytes(byteArrayOf(1))
            File(root, "config.json").writeText("""{"llm_model":"../outside.mnn"}""")

            assertFailsWith<IllegalArgumentException> {
                validator.validate(root)
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun detectsVisualAndToolCapabilitiesAndRequiresVisualWeight() {
        val root = createTempDirectory("mnn-visual-model").toFile()
        try {
            File(root, "llm.mnn").writeBytes(byteArrayOf(1))
            File(root, "llm.mnn.weight").writeBytes(byteArrayOf(2))
            File(root, "visual.mnn").writeBytes(byteArrayOf(3))
            File(root, "visual.mnn.weight").writeBytes(byteArrayOf(4))
            File(root, "config.json").writeText(
                """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","is_visual":true}""",
            )
            File(root, "llm_config.json").writeText(
                """{"jinja":{"chat_template":"tools <tool_call> <tool_response>"}}""",
            )

            val result = validator.validate(root)

            assertEquals(true, result.supportsVision)
            assertEquals(true, result.supportsToolCalling)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsVisualModelWithoutWeight() {
        val root = createTempDirectory("mnn-missing-visual-weight").toFile()
        try {
            File(root, "llm.mnn").writeBytes(byteArrayOf(1))
            File(root, "llm.mnn.weight").writeBytes(byteArrayOf(2))
            File(root, "visual.mnn").writeBytes(byteArrayOf(3))
            File(root, "config.json").writeText(
                """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","is_visual":true}""",
            )

            assertFailsWith<IllegalArgumentException> { validator.validate(root) }
        } finally {
            root.deleteRecursively()
        }
    }
}
