package com.vktracks.player.data.downloader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object Id3Tagger {

    fun tagMp3(file: File, title: String, artist: String, album: String?, coverBytes: ByteArray?) {
        try {
            val framesStream = ByteArrayOutputStream()

            // 1. TIT2: Название трека (UTF-8, флаг 3)
            writeTextFrame(framesStream, "TIT2", title)

            // 2. TPE1: Исполнитель (UTF-8, флаг 3)
            writeTextFrame(framesStream, "TPE1", artist)

            // 3. TALB: Альбом (UTF-8, флаг 3)
            if (!album.isNullOrEmpty()) {
                writeTextFrame(framesStream, "TALB", album)
            }

            // 4. APIC: Обложка (Attached Picture)
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                writePictureFrame(framesStream, coverBytes)
            }

            val framesData = framesStream.toByteArray()
            val header = ByteArray(10)
            header[0] = 'I'.code.toByte()
            header[1] = 'D'.code.toByte()
            header[2] = '3'.code.toByte()
            header[3] = 3 // ID3v2.3
            header[4] = 0 // Revision
            header[5] = 0 // Flags

            // Synchsafe целое число размера тела фреймов (7 бит на байт)
            val synchsafeSize = encodeSynchsafe(framesData.size)
            System.arraycopy(synchsafeSize, 0, header, 6, 4)

            val tempFile = File(file.parentFile, "${file.name}.tagged")
            FileOutputStream(tempFile).use { out ->
                out.write(header)
                out.write(framesData)

                // Считываем оригинальный аудиофайл, пропуская старый ID3 заголовок если он был
                FileInputStream(file).use { input ->
                    val lead = ByteArray(10)
                    val read = input.read(lead)
                    if (read == 10 && lead[0] == 'I'.code.toByte() && lead[1] == 'D'.code.toByte() && lead[2] == '3'.code.toByte()) {
                        val oldSize = decodeSynchsafe(lead, 6)
                        input.skip(oldSize.toLong())
                    } else if (read > 0) {
                        out.write(lead, 0, read)
                    }

                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                file.delete()
                tempFile.renameTo(file)
            }
        } catch (_: Exception) {
            // Если тегирование не удалось, оригинальный файл остаётся целым
        }
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val bodySize = 1 + textBytes.size // 1 байт на кодировку (0x03 = UTF-8)

        out.write(frameId.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(intToByteArray(bodySize))
        out.write(0) // Flags 0
        out.write(0)

        out.write(3) // 0x03 - UTF-8 encoding
        out.write(textBytes)
    }

    private fun writePictureFrame(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val mimeType = "image/jpeg".toByteArray(StandardCharsets.ISO_8859_1)
        val bodySize = 1 + mimeType.size + 1 + 1 + 1 + imageBytes.size

        out.write("APIC".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(intToByteArray(bodySize))
        out.write(0) // Flags 0
        out.write(0)

        out.write(0) // Encoding: ISO-8859-1 for MIME
        out.write(mimeType)
        out.write(0) // Null terminator for MIME
        out.write(3) // Picture type: 3 = Front cover
        out.write(0) // Description: empty string (null terminated)
        out.write(imageBytes)
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun encodeSynchsafe(value: Int): ByteArray {
        var v = value
        val out = ByteArray(4)
        out[3] = (v and 0x7F).toByte()
        v = v shr 7
        out[2] = (v and 0x7F).toByte()
        v = v shr 7
        out[1] = (v and 0x7F).toByte()
        v = v shr 7
        out[0] = (v and 0x7F).toByte()
        return out
    }

    private fun decodeSynchsafe(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 0 until 4) {
            result = (result shl 7) or (bytes[offset + i].toInt() and 0x7F)
        }
        return result
    }
}
