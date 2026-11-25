package com.vktracks.player.data.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.vktracks.player.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object UpdateSecurity {

    private val TRUSTED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "raw.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "ntvampire.github.io"
    )

    fun isTrustedUpdateUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in TRUSTED_HOSTS ||
            host.endsWith(".githubusercontent.com") ||
            host.endsWith(".github.io")
    }

    fun sanitizeVersionName(versionName: String?): String {
        val cleaned = (versionName ?: "")
            .replace(Regex("[^0-9A-Za-z._-]"), "_")
            .replace("..", "_")
            .trim('.', '_', '-')
        return cleaned.ifEmpty { "update" }
    }

    fun getPrivateUpdateDir(context: Context): File {
        return File(context.cacheDir, "updates").apply { mkdirs() }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyApkIntegrity(
        context: Context,
        apkFile: File,
        expectedSha256: String?
    ): Result<Unit> {
        if (!apkFile.exists() || apkFile.length() <= 1024 * 1024) {
            return Result.failure(SecurityException("Файл обновления отсутствует или повреждён"))
        }

        if (!expectedSha256.isNullOrBlank()) {
            val actualHash = try {
                computeSha256(apkFile)
            } catch (e: Exception) {
                return Result.failure(SecurityException("Не удалось проверить SHA-256 файла", e))
            }
            if (!actualHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                return Result.failure(SecurityException("Контрольная сумма SHA-256 не совпадает"))
            }
        }

        val sigResult = verifyApkSignatureMatchesInstalled(context, apkFile)
        if (sigResult.isFailure) {
            if (BuildConfig.DEBUG) {
                Log.w("UpdateSecurity", "Signature mismatch ignored in DEBUG build", sigResult.exceptionOrNull())
            } else {
                return sigResult
            }
        }

        return Result.success(Unit)
    }

    private fun verifyApkSignatureMatchesInstalled(context: Context, apkFile: File): Result<Unit> {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val installedInfo = pm.getPackageInfo(context.packageName, flags)
            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return Result.failure(SecurityException("Не удалось прочитать метаданные APK"))

            if (archiveInfo.packageName != context.packageName) {
                return Result.failure(SecurityException("Неверный applicationId пакета обновления: ${archiveInfo.packageName}"))
            }

            val installedCerts = extractCertDigests(installedInfo)
            val archiveCerts = extractCertDigests(archiveInfo)

            if (installedCerts.isEmpty() || archiveCerts.isEmpty()) {
                return Result.failure(SecurityException("Отсутствует цифровая подпись APK"))
            }

            if (installedCerts.intersect(archiveCerts).isEmpty()) {
                return Result.failure(SecurityException("Цифровая подпись APK не совпадает с установленным приложением"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(SecurityException("Ошибка проверки подписи APK", e))
        }
    }

    private fun extractCertDigests(pkgInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
