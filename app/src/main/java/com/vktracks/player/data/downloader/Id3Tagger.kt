package com.vktracks.player.data.downloader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object Id3Tagger {

    fun tagMp3(
        file: File,
        title: String,
        artist: String,
        album: String?,
        coverBytes: ByteArray?,
        lyricsText: String? = null
    ) {
        val tempFile = File(file.parentFile, "${file.name}.${System.nanoTime()}.tagged")
        try {
            val framesStream = ByteArrayOutputStream()

            // ID3v2.3 поддерживает ISO-8859-1 (0x00) и UTF-16LE с BOM (0x01)
            writeTextFrame(framesStream, "TIT2", title)
            writeTextFrame(framesStream, "TPE1", artist)
            if (!album.isNullOrEmpty()) {
                writeTextFrame(framesStream, "TALB", album)
            }
            if (!lyricsText.isNullOrBlank()) {
                writeUnsyncedLyricsFrame(framesStream, lyricsText)
            }
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

            val synchsafeSize = encodeSynchsafe(framesData.size)
            System.arraycopy(synchsafeSize, 0, header, 6, 4)

            FileOutputStream(tempFile).use { out ->
                out.write(header)
                out.write(framesData)

                // Считываем оригинальный аудиофайл, пропуская старый ID3-заголовок
                FileInputStream(file).use { input ->
                    val lead = ByteArray(10)
                    val read = input.read(lead)
                    if (read == 10 && lead[0] == 'I'.code.toByte() && lead[1] == 'D'.code.toByte() && lead[2] == '3'.code.toByte()) {
                        val oldSize = decodeSynchsafe(lead, 6)
                        skipFully(input, oldSize.toLong())
                    } else if (read > 0) {
                        out.write(lead, 0, read)
                    }

                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                    out.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() > framesData.size) {
                if (!tempFile.renameTo(file)) {
                    tempFile.copyTo(file, overwrite = true)
                }
            }
        } catch (_: Exception) {
            // Если тегирование не удалось, оригинальный файл остаётся целым
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun skipFully(input: FileInputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(4096)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(skipBuf, 0, minOf(remaining, skipBuf.size.toLong()).toInt())
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val utf16Bytes = text.toByteArray(StandardCharsets.UTF_16LE)
        val bodySize = 1 + 2 + utf16Bytes.size // 0x01 (UTF-16 с BOM) + FF FE + текст

        out.write(frameId.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(intToByteArray(bodySize))
        out.write(0) // Flags 0
        out.write(0)

        out.write(1) // 0x01 - UTF-16 с BOM (официальный стандарт ID3v2.3)
        out.write(0xFF)
        out.write(0xFE)
        out.write(utf16Bytes)
    }

    private fun writeUnsyncedLyricsFrame(out: ByteArrayOutputStream, lyricsText: String) {
        val normalized = lyricsText.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return

        val utf16Bytes = normalized.toByteArray(StandardCharsets.UTF_16LE)
        // 1 (encoding 0x01) + 3 (lang "und") + 4 (BOM + пустой дескриптор) + 2 (BOM текста) + текст
        val bodySize = 1 + 3 + 4 + 2 + utf16Bytes.size

        out.write("USLT".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(intToByteArray(bodySize))
        out.write(0) // Flags 0
        out.write(0)

        out.write(1) // 0x01 - UTF-16LE с BOM
        out.write("und".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(0xFF)
        out.write(0xFE)
        out.write(0x00)
        out.write(0x00)
        out.write(0xFF)
        out.write(0xFE)
        out.write(utf16Bytes)
    }

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return "image/png"
        }
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()) {
            return "image/webp"
        }
        return "image/jpeg"
    }

    private fun writePictureFrame(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val mimeTypeStr = detectMimeType(imageBytes)
        val mimeType = mimeTypeStr.toByteArray(StandardCharsets.ISO_8859_1)
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
