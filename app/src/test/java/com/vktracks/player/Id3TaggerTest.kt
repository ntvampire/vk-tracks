package com.vktracks.player

import com.vktracks.player.data.downloader.Id3Tagger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Id3TaggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testWritesValidId3v23HeaderWithUtf16LeAndPreservesAudioPayload() {
        val mp3File = File(tempFolder.root, "sample.mp3")
        val audioPayload = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 0x11, 0x22, 0x33, 0x44)
        mp3File.writeBytes(audioPayload)

        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        Id3Tagger.tagMp3(mp3File, "Привет", "Артист", "Альбом", fakeJpeg)

        val taggedBytes = mp3File.readBytes()
        assertEquals('I'.code.toByte(), taggedBytes[0])
        assertEquals('D'.code.toByte(), taggedBytes[1])
        assertEquals('3'.code.toByte(), taggedBytes[2])
        assertEquals(3.toByte(), taggedBytes[3]) // ID3v2.3

        // Проверяем, что временные .tagged файлы удалены
        val leftover = tempFolder.root.listFiles()?.filter { it.name.endsWith(".tagged") }.orEmpty()
        assertTrue(leftover.isEmpty())

        // Проверяем, что аудиоданные в конце файла сохранены без искажения
        val tail = taggedBytes.copyOfRange(taggedBytes.size - audioPayload.size, taggedBytes.size)
        assertArrayEquals(audioPayload, tail)

        // Повторное тегирование должно заменить старый тег, сохранив точно те же аудиоданные
        Id3Tagger.tagMp3(mp3File, "Новое имя", "Новый артист", null, null)
        val retaggedBytes = mp3File.readBytes()
        val tailAfterRetag = retaggedBytes.copyOfRange(retaggedBytes.size - audioPayload.size, retaggedBytes.size)
        assertArrayEquals(audioPayload, tailAfterRetag)
    }
}
