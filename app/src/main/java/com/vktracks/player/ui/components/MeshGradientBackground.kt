package com.vktracks.player.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RuntimeShader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Immutable
data class MeshPalette(
    val color0: Color,
    val color1: Color,
    val color2: Color,
    val color3: Color
) {
    fun averageLuminance(): Float {
        return (color0.luminance() + color1.luminance() + color2.luminance() + color3.luminance()) * 0.25f
    }

    companion object {
        fun defaultForTheme(isDarkTheme: Boolean): MeshPalette {
            return if (isDarkTheme) {
                MeshPalette(
                    color0 = Color(0xFF3A1228),
                    color1 = Color(0xFF1A2342),
                    color2 = Color(0xFF52182C),
                    color3 = Color(0xFF10131C)
                )
            } else {
                MeshPalette(
                    color0 = Color(0xFFF6D5DF),
                    color1 = Color(0xFFD9E4F7),
                    color2 = Color(0xFFFCE2D4),
                    color3 = Color(0xFFE7DCF5)
                )
            }
        }
    }
}

/**
 * Плавный анимированный Mesh-градиент в стиле Apple Music iOS:
 * 4 доминирующих цвета обложки, вращение и warp-искажение слоёв (AGSL RuntimeShader на Android 13+
 * с плавным Canvas-фоллбэком на Android 8–12), 8-битный дитеринг против полосатости и автопауза.
 */
@Composable
fun AppleMusicMeshBackground(
    coverUrl: String?,
    isPlaying: Boolean,
    behindLyrics: Boolean,
    modifier: Modifier = Modifier,
    onLightBackdropChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val fallbackPalette = remember(isDarkTheme) { MeshPalette.defaultForTheme(isDarkTheme) }
    var targetPalette by remember(isDarkTheme) { mutableStateOf(fallbackPalette) }

    LaunchedEffect(coverUrl, isDarkTheme) {
        if (coverUrl.isNullOrBlank()) {
            targetPalette = fallbackPalette
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .size(48)
                    .allowHardware(false)
                    .build()
                val result = Coil.imageLoader(context).execute(request)
                val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    withContext(Dispatchers.Default) {
                        extractMeshColors(bitmap, isDarkTheme)
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
        targetPalette = extracted ?: fallbackPalette
    }

    val c0 by animateColorAsState(targetPalette.color0, animationSpec = tween(750), label = "meshC0")
    val c1 by animateColorAsState(targetPalette.color1, animationSpec = tween(750), label = "meshC1")
    val c2 by animateColorAsState(targetPalette.color2, animationSpec = tween(750), label = "meshC2")
    val c3 by animateColorAsState(targetPalette.color3, animationSpec = tween(750), label = "meshC3")

    val isLightBackdrop = !isDarkTheme && targetPalette.averageLuminance() > 0.56f
    LaunchedEffect(isLightBackdrop) {
        onLightBackdropChanged(isLightBackdrop)
    }

    // Ограничение до ~30 FPS и заморозка фазы при паузе воспроизведения
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastNs = System.nanoTime()
        while (isActive) {
            delay(33L)
            val nowNs = System.nanoTime()
            val dt = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastNs = nowNs
            timeSec = (timeSec + dt) % 3600f
        }
    }

    val scrimColor = if (isLightBackdrop) Color.White else Color.Black
    val scrimAlpha = when {
        isLightBackdrop && behindLyrics -> 0.42f
        isLightBackdrop -> 0.20f
        behindLyrics -> 0.46f
        else -> 0.28f
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslMeshLayer(
                timeSec = timeSec,
                c0 = c0,
                c1 = c1,
                c2 = c2,
                c3 = c3,
                scrimColor = scrimColor,
                scrimAlpha = scrimAlpha
            )
        } else {
            CanvasMeshFallbackLayer(
                timeSec = timeSec,
                c0 = c0,
                c1 = c1,
                c2 = c2,
                c3 = c3,
                scrimColor = scrimColor,
                scrimAlpha = scrimAlpha
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslMeshLayer(
    timeSec: Float,
    c0: Color,
    c1: Color,
    c2: Color,
    c3: Color,
    scrimColor: Color,
    scrimAlpha: Float
) {
    val shader = remember { RuntimeShader(AGSL_MESH_SHADER) }
    val brush = remember(shader) { ShaderBrush(shader) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        shader.setFloatUniform("uResolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uColor0", c0.red, c0.green, c0.blue, 1f)
        shader.setFloatUniform("uColor1", c1.red, c1.green, c1.blue, 1f)
        shader.setFloatUniform("uColor2", c2.red, c2.green, c2.blue, 1f)
        shader.setFloatUniform("uColor3", c3.red, c3.green, c3.blue, 1f)
        shader.setFloatUniform("uScrim", scrimColor.red, scrimColor.green, scrimColor.blue, scrimAlpha)
        drawRect(brush = brush)
    }
}

@Composable
private fun CanvasMeshFallbackLayer(
    timeSec: Float,
    c0: Color,
    c1: Color,
    c2: Color,
    c3: Color,
    scrimColor: Color,
    scrimAlpha: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val radius = max(w, h) * 0.85f

        drawRect(color = c3)

        val p0 = Offset(
            x = w * (0.25f + 0.22f * cos(timeSec * 0.35f)),
            y = h * (0.22f + 0.18f * sin(timeSec * 0.28f))
        )
        val p1 = Offset(
            x = w * (0.78f + 0.20f * sin(timeSec * 0.31f + 1.2f)),
            y = h * (0.34f + 0.22f * cos(timeSec * 0.25f + 0.7f))
        )
        val p2 = Offset(
            x = w * (0.35f + 0.25f * sin(timeSec * 0.27f + 2.5f)),
            y = h * (0.76f + 0.18f * cos(timeSec * 0.33f + 1.9f))
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c0.copy(alpha = 0.92f), Color.Transparent),
                center = p0,
                radius = radius
            ),
            radius = radius,
            center = p0
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c1.copy(alpha = 0.88f), Color.Transparent),
                center = p1,
                radius = radius
            ),
            radius = radius,
            center = p1
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c2.copy(alpha = 0.86f), Color.Transparent),
                center = p2,
                radius = radius
            ),
            radius = radius,
            center = p2
        )
        drawRect(color = scrimColor.copy(alpha = scrimAlpha))
    }
}

internal fun extractMeshColors(bitmap: Bitmap, isDarkTheme: Boolean): MeshPalette {
    val scaled = if (bitmap.width > 24 || bitmap.height > 24) {
        Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    } else {
        bitmap
    }
    val w = scaled.width
    val h = scaled.height
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    if (scaled !== bitmap) scaled.recycle()

    fun sampleQuadrant(xRange: IntRange, yRange: IntRange): Color {
        var bestColor = pixels[0]
        var bestScore = -1f
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        val hsv = FloatArray(3)

        for (y in yRange) {
            for (x in xRange) {
                val px = pixels[y * w + x]
                val r = AndroidColor.red(px)
                val g = AndroidColor.green(px)
                val b = AndroidColor.blue(px)
                rSum += r
                gSum += g
                bSum += b
                count++

                AndroidColor.RGBToHSV(r, g, b, hsv)
                val sat = hsv[1]
                val value = hsv[2]
                // Предпочитаем сочные цвета и избегаем совсем чёрных/белых пикселей
                val brightnessWeight = 1f - kotlin.math.abs(value - 0.58f)
                val score = sat * 2.2f + brightnessWeight
                if (score > bestScore) {
                    bestScore = score
                    bestColor = px
                }
            }
        }

        val chosen = if (bestScore > 0.45f) {
            bestColor
        } else if (count > 0) {
            AndroidColor.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
        } else {
            bestColor
        }

        AndroidColor.colorToHSV(chosen, hsv)
        if (isDarkTheme) {
            hsv[1] = min(1f, hsv[1] * 1.15f)
            hsv[2] = hsv[2].coerceIn(0.22f, 0.78f)
        } else {
            hsv[1] = min(1f, hsv[1] * 1.05f)
            hsv[2] = hsv[2].coerceIn(0.26f, 0.90f)
        }
        return Color(AndroidColor.HSVToColor(hsv))
    }

    val midX = (w / 2).coerceAtLeast(1)
    val midY = (h / 2).coerceAtLeast(1)
    return MeshPalette(
        color0 = sampleQuadrant(0 until midX, 0 until midY),
        color1 = sampleQuadrant(midX until w, 0 until midY),
        color2 = sampleQuadrant(0 until midX, midY until h),
        color3 = sampleQuadrant(midX until w, midY until h)
    )
}

private const val AGSL_MESH_SHADER = """
uniform float2 uResolution;
uniform float uTime;
uniform float4 uColor0;
uniform float4 uColor1;
uniform float4 uColor2;
uniform float4 uColor3;
uniform float4 uScrim;

float2 rotate2d(float2 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return float2(p.x * c - p.y * s, p.x * s + p.y * c);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    float2 p = uv - 0.5;
    p.x *= aspect;

    // Warp / pinch искажение сетки по мотивам meshbgx Apple Music
    float2 warp = float2(
        sin(p.y * 3.1 + uTime * 0.42) * 0.18 + cos(p.x * 2.3 - uTime * 0.27) * 0.12,
        cos(p.x * 2.8 - uTime * 0.36) * 0.18 + sin(p.y * 2.5 + uTime * 0.24) * 0.12
    );
    float2 q = p + warp;

    // 3 вращающиеся орбитальные точки цветовых пятен
    float2 c0Pos = rotate2d(float2(-0.28, -0.30), uTime * 0.22);
    float2 c1Pos = rotate2d(float2(0.32, -0.18), -uTime * 0.18 + 1.1);
    float2 c2Pos = rotate2d(float2(-0.20, 0.34), uTime * 0.15 + 2.4);
    float2 c3Pos = rotate2d(float2(0.28, 0.28), -uTime * 0.20 + 4.0);

    float w0 = exp(-dot(q - c0Pos, q - c0Pos) * 2.6);
    float w1 = exp(-dot(q - c1Pos, q - c1Pos) * 2.4);
    float w2 = exp(-dot(q - c2Pos, q - c2Pos) * 2.5);
    float w3 = exp(-dot(q - c3Pos, q - c3Pos) * 2.3) + 0.08;

    float wSum = w0 + w1 + w2 + w3;
    float3 col = (uColor0.rgb * w0 + uColor1.rgb * w1 + uColor2.rgb * w2 + uColor3.rgb * w3) / wSum;

    // Лёгкое усиление насыщенности (Rec.709)
    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(luma), col, 1.14);

    // Скрим для читаемости текста/контролов
    col = mix(col, uScrim.rgb, uScrim.a);

    // Interleaved gradient noise (анти-бэндинг для 8-бит)
    float noise = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715))));
    col = clamp(col + (noise - 0.5) / 255.0, 0.0, 1.0);

    return half4(half3(col), 1.0);
}
"""
