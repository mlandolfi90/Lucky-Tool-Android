package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClipboardExtensionsTest {
    private val clipData = mockk<ClipData>()
    private val clipboard = mockk<Clipboard>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } returns clipData
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ClipData::class)
    }

    @Test
    fun `setPlainText sets a ClipEntry wrapping ClipData newPlainText with the plain text label`() =
        runTest {
            // Arrange
            val entry = slot<ClipEntry>()
            coEvery { clipboard.setClipEntry(capture(entry)) } just Runs

            // Act
            clipboard.setPlainText("hello")

            // Assert
            coVerify(exactly = 1) { clipboard.setClipEntry(any()) }
            verify(exactly = 1) { ClipData.newPlainText("plain text", "hello") }
            assertSame(clipData, entry.captured.clipData)
        }

    @Test
    fun `setPlainText passes the text unchanged`() =
        runTest {
            // Arrange
            val multiLine = "first line\nsecond line\n"

            // Act
            clipboard.setPlainText(multiLine)
            clipboard.setPlainText("")

            // Assert
            verify(exactly = 1) { ClipData.newPlainText("plain text", multiLine) }
            verify(exactly = 1) { ClipData.newPlainText("plain text", "") }
        }
}
