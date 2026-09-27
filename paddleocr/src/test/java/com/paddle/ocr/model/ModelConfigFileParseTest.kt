package com.paddle.ocr.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelConfigFileParseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesValidYamlFileWithCharacterDict() {
        val yamlContent = """
            Global:
              model_name: test_rec
            PreProcess:
              transform_ops:
              - DecodeImage:
                  img_mode: BGR
              - RecResizeImg:
                  image_shape: [3, 48, 320]
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '0'
              - '1'
              - 'A'
              - 'b'
              - "c"
              - ' '
        """.trimIndent()

        val yamlFile = tempFolder.newFile("inference.yml")
        yamlFile.writeText(yamlContent)

        val config = ModelConfig.parse(yamlFile)
        assertTrue(config.characterList.contains("0"))
        assertTrue(config.characterList.contains("1"))
        assertTrue(config.characterList.contains("A"))
        assertTrue(config.characterList.contains("b"))
        assertTrue(config.characterList.contains("c"))
        assertEquals("Last token should be space", " ", config.characterList.last())
    }

    @Test
    fun automaticallyAppendsSpaceIfMissingFromDict() {
        val yamlContent = """
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - 'x'
              - 'y'
        """.trimIndent()

        val yamlFile = tempFolder.newFile("no_space.yml")
        yamlFile.writeText(yamlContent)

        val config = ModelConfig.parse(yamlFile)
        assertEquals(listOf("x", "y", " "), config.characterList)
    }

    @Test
    fun throwsConfigParseFailedWhenPostProcessIsMissing() {
        val yamlContent = """
            Global:
              model_name: broken
        """.trimIndent()

        val yamlFile = tempFolder.newFile("missing_postprocess.yml")
        yamlFile.writeText(yamlContent)

        try {
            ModelConfig.parse(yamlFile)
            fail("Expected OCRError.ConfigParseFailed")
        } catch (e: OCRError.ConfigParseFailed) {
            assertTrue(e.message!!.contains("Missing PostProcess"))
        }
    }

    @Test
    fun throwsConfigParseFailedWhenCharacterDictIsMissing() {
        val yamlContent = """
            PostProcess:
              name: CTCLabelDecode
        """.trimIndent()

        val yamlFile = tempFolder.newFile("missing_dict.yml")
        yamlFile.writeText(yamlContent)

        try {
            ModelConfig.parse(yamlFile)
            fail("Expected OCRError.ConfigParseFailed")
        } catch (e: OCRError.ConfigParseFailed) {
            assertTrue(e.message!!.contains("Missing character_dict"))
        }
    }
}
