package com.vktracks.player.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
                    color0 = Color(0xFF8C1D48),
                    color1 = Color(0xFF1D356B),
                    color2 = Color(0xFFB3382C),
                    color3 = Color(0xFF111629)
                )
            } else {
                MeshPalette(
                    color0 = Color(0xFFF6C2D4),
                    color1 = Color(0xFFC5DAF9),
                    color2 = Color(0xFFFAD3BC),
                    color3 = Color(0xFFDFCCF5)
                )
            }
        }
    }
}

private data class ProcessedArtworkMesh(
    val blurredBitmap: Bitmap,
    val palette: MeshPalette
)

/**
 * Прямой порт логики Apple Music / meshbgx:
 * размытая миниатюра обложки сэмплируется через 3 вращающихся и warp/pinch-искажённых слоя
 * в AGSL RuntimeShader (Android 13+), с плавным кроссфейдом между треками, 8-битным дитерингом
 * против полосатости и автопаузой при остановке воспроизведения. На Android 8–12 — яркие
 * перетекающие радиальные пятна на Canvas по диверсифицированной палитре обложки.
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
    val fallbackBitmap = remember(fallbackPalette) { createPaletteMeshBitmap(fallbackPalette) }

    var currentBitmap by remember(fallbackBitmap) { mutableStateOf(fallbackBitmap) }
    var prevBitmap by remember(fallbackBitmap) { mutableStateOf(fallbackBitmap) }
    var targetPalette by remember(fallbackPalette) { mutableStateOf(fallbackPalette) }
    val transitionAnim = remember { Animatable(1f) }

    LaunchedEffect(coverUrl, isDarkTheme) {
        val processed = if (coverUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(coverUrl)
                        .size(96)
                        .allowHardware(false)
                        .build()
                    val result = Coil.imageLoader(context).execute(request)
                    val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null && !bitmap.isRecycled) {
                        withContext(Dispatchers.Default) {
                            buildArtworkMesh(bitmap, isDarkTheme)
                        }
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        val nextBitmap = processed?.blurredBitmap ?: fallbackBitmap
        val nextPalette = processed?.palette ?: fallbackPalette
        targetPalette = nextPalette

        if (nextBitmap !== currentBitmap) {
            prevBitmap = currentBitmap
            currentBitmap = nextBitmap
            transitionAnim.snapTo(0f)
            transitionAnim.animateTo(1f, animationSpec = tween(durationMillis = 800))
        }
    }

    val c0 by animateColorAsState(targetPalette.color0, animationSpec = tween(750), label = "meshC0")
    val c1 by animateColorAsState(targetPalette.color1, animationSpec = tween(750), label = "meshC1")
    val c2 by animateColorAsState(targetPalette.color2, animationSpec = tween(750), label = "meshC2")
    val c3 by animateColorAsState(targetPalette.color3, animationSpec = tween(750), label = "meshC3")

    val isLightBackdrop = !isDarkTheme && targetPalette.averageLuminance() > 0.60f
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
        isLightBackdrop -> 0.18f
        behindLyrics -> 0.48f
        else -> 0.24f
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslArtworkMeshLayer(
                prevBitmap = prevBitmap,
                currentBitmap = currentBitmap,
                transitionMix = transitionAnim.value,
                timeSec = timeSec,
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
private fun AgslArtworkMeshLayer(
    prevBitmap: Bitmap,
    currentBitmap: Bitmap,
    transitionMix: Float,
    timeSec: Float,
    scrimColor: Color,
    scrimAlpha: Float
) {
    val shader = remember { RuntimeShader(AGSL_ARTWORK_MESH_SHADER) }
    val paint = remember { Paint().apply { isFilterBitmap = true } }
    val prevShader = remember(prevBitmap) {
        BitmapShader(prevBitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
    }
    val currShader = remember(currentBitmap) {
        BitmapShader(currentBitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        shader.setFloatUniform("uResolution", w, h)
        shader.setFloatUniform("uPrevSize", prevBitmap.width.toFloat().coerceAtLeast(1f), prevBitmap.height.toFloat().coerceAtLeast(1f))
        shader.setFloatUniform("uCurrSize", currentBitmap.width.toFloat().coerceAtLeast(1f), currentBitmap.height.toFloat().coerceAtLeast(1f))
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uTransition", transitionMix.coerceIn(0f, 1f))
        shader.setFloatUniform("uScrim", scrimColor.red, scrimColor.green, scrimColor.blue, scrimAlpha)
        shader.setInputShader("uPrevArtwork", prevShader)
        shader.setInputShader("uCurrArtwork", currShader)

        drawIntoCanvas { canvas ->
            paint.shader = shader
            canvas.nativeCanvas.drawRect(0f, 0f, w, h, paint)
        }
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
        val blobRadius = min(w, h) * 0.88f

        drawRect(color = c3)

        val p0 = Offset(
            x = w * (0.22f + 0.28f * cos(timeSec * 0.35f)),
            y = h * (0.18f + 0.22f * sin(timeSec * 0.28f))
        )
        val p1 = Offset(
            x = w * (0.78f + 0.24f * sin(timeSec * 0.31f + 1.2f)),
            y = h * (0.36f + 0.26f * cos(timeSec * 0.25f + 0.7f))
        )
        val p2 = Offset(
            x = w * (0.38f + 0.30f * sin(timeSec * 0.27f + 2.5f)),
            y = h * (0.78f + 0.20f * cos(timeSec * 0.33f + 1.9f))
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c0, c0.copy(alpha = 0.45f), Color.Transparent),
                center = p0,
                radius = blobRadius
            ),
            radius = blobRadius,
            center = p0
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c1, c1.copy(alpha = 0.42f), Color.Transparent),
                center = p1,
                radius = blobRadius * 0.95f
            ),
            radius = blobRadius * 0.95f,
            center = p1
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c2, c2.copy(alpha = 0.40f), Color.Transparent),
                center = p2,
                radius = blobRadius * 1.05f
            ),
            radius = blobRadius * 1.05f,
            center = p2
        )
        drawRect(color = scrimColor.copy(alpha = scrimAlpha))
    }
}

private fun createPaletteMeshBitmap(palette: MeshPalette): Bitmap {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(palette.color3.toArgb())

    paint.shader = android.graphics.RadialGradient(
        size * 0.2f, size * 0.25f, size * 0.7f,
        palette.color0.toArgb(), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP
    )
    canvas.drawCircle(size * 0.2f, size * 0.25f, size * 0.7f, paint)

    paint.shader = android.graphics.RadialGradient(
        size * 0.8f, size * 0.35f, size * 0.65f,
        palette.color1.toArgb(), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP
    )
    canvas.drawCircle(size * 0.8f, size * 0.35f, size * 0.65f, paint)

    paint.shader = android.graphics.RadialGradient(
        size * 0.4f, size * 0.85f, size * 0.7f,
        palette.color2.toArgb(), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP
    )
    canvas.drawCircle(size * 0.4f, size * 0.85f, size * 0.7f, paint)
    return bmp
}

private fun buildArtworkMesh(source: Bitmap, isDarkTheme: Boolean): ProcessedArtworkMesh {
    val size = 72
    val scaled = Bitmap.createScaledBitmap(source, size, size, true)
    val w = scaled.width
    val h = scaled.height
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    if (scaled !== source) scaled.recycle()

    val palette = extractDiversePalette(pixels, w, h, isDarkTheme)
    fastBoxBlur(pixels, w, h, radius = 9, passes = 3)

    val blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    blurred.setPixels(pixels, 0, w, 0, 0, w, h)
    return ProcessedArtworkMesh(blurredBitmap = blurred, palette = palette)
}

internal fun extractDiversePalette(
    pixels: IntArray,
    width: Int,
    height: Int,
    isDarkTheme: Boolean
): MeshPalette {
    val step = 2
    val candidates = ArrayList<Int>(pixels.size / (step * step) + 1)
    val hsv = FloatArray(3)
    for (y in 0 until height step step) {
        for (x in 0 until width step step) {
            candidates.add(pixels[y * width + x])
        }
    }
    if (candidates.isEmpty()) return MeshPalette.defaultForTheme(isDarkTheme)

    fun colorDist(cA: Int, cB: Int): Float {
        val dr = (AndroidColor.red(cA) - AndroidColor.red(cB)) / 255f
        val dg = (AndroidColor.green(cA) - AndroidColor.green(cB)) / 255f
        val db = (AndroidColor.blue(cA) - AndroidColor.blue(cB)) / 255f
        return sqrt(dr * dr + dg * dg + db * db)
    }

    val picked = mutableListOf<Int>()

    // 1. Самый сочный акцентный цвет
    var accent = candidates[0]
    var bestAccentScore = -1f
    for (c in candidates) {
        AndroidColor.colorToHSV(c, hsv)
        val score = hsv[1] * 2.5f + (1f - abs(hsv[2] - 0.55f))
        if (score > bestAccentScore) {
            bestAccentScore = score
            accent = c
        }
    }
    picked.add(accent)

    // 2. Ещё 3 максимально контрастных/разных цвета обложки (светлый, тёмный, вторичный тон)
    repeat(3) { round ->
        var best = candidates[0]
        var bestScore = -1f
        for (c in candidates) {
            AndroidColor.colorToHSV(c, hsv)
            val minDist = picked.minOf { colorDist(c, it) }
            val targetTone = when (round) {
                0 -> 0.22f // глубокий теневой слой
                1 -> 0.80f // светлый акцент
                else -> 0.52f
            }
            val toneScore = 1f - abs(hsv[2] - targetTone)
            val score = minDist * 2.8f + toneScore + hsv[1] * 0.7f
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        picked.add(best)
    }

    fun normalizeColor(argb: Int, isShadowSlot: Boolean): Color {
        AndroidColor.colorToHSV(argb, hsv)
        if (isDarkTheme) {
            hsv[1] = min(1f, hsv[1] * 1.18f)
            hsv[2] = if (isShadowSlot) hsv[2].coerceIn(0.10f, 0.35f) else hsv[2].coerceIn(0.24f, 0.82f)
        } else {
            hsv[1] = min(1f, hsv[1] * 1.10f)
            hsv[2] = if (isShadowSlot) hsv[2].coerceIn(0.16f, 0.50f) else hsv[2].coerceIn(0.28f, 0.90f)
        }
        return Color(AndroidColor.HSVToColor(hsv))
    }

    return MeshPalette(
        color0 = normalizeColor(picked[0], isShadowSlot = false),
        color1 = normalizeColor(picked[2], isShadowSlot = false),
        color2 = normalizeColor(picked[3], isShadowSlot = false),
        color3 = normalizeColor(picked[1], isShadowSlot = true)
    )
}

/**
 * Быстрый 3-проходный разделимый Box Blur в памяти (эквивалент Gaussian Blur без выделения лишних массивов).
 */
private fun fastBoxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int) {
    val temp = IntArray(pixels.size)
    val div = radius * 2 + 1
    repeat(passes) {
        // Горизонтальный проход
        for (y in 0 until height) {
            val rowOffset = y * width
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -radius..radius) {
                val px = pixels[rowOffset + k.coerceIn(0, width - 1)]
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
            }
            for (x in 0 until width) {
                temp[rowOffset + x] = (0xFF shl 24) or
                    ((rSum / div) shl 16) or
                    ((gSum / div) shl 8) or
                    (bSum / div)
                val outX = (x - radius).coerceIn(0, width - 1)
                val inX = (x + radius + 1).coerceIn(0, width - 1)
                val outPx = pixels[rowOffset + outX]
                val inPx = pixels[rowOffset + inX]
                rSum += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                gSum += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                bSum += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
        // Вертикальный проход
        for (x in 0 until width) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -radius..radius) {
                val py = temp[k.coerceIn(0, height - 1) * width + x]
                rSum += (py shr 16) and 0xFF
                gSum += (py shr 8) and 0xFF
                bSum += py and 0xFF
            }
            for (y in 0 until height) {
                pixels[y * width + x] = (0xFF shl 24) or
                    ((rSum / div) shl 16) or
                    ((gSum / div) shl 8) or
                    (bSum / div)
                val outY = (y - radius).coerceIn(0, height - 1)
                val inY = (y + radius + 1).coerceIn(0, height - 1)
                val outPx = temp[outY * width + x]
                val inPx = temp[inY * width + x]
                rSum += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                gSum += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                bSum += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
    }
}

private const val AGSL_ARTWORK_MESH_SHADER = """
uniform shader uPrevArtwork;
uniform shader uCurrArtwork;
uniform float2 uResolution;
uniform float2 uPrevSize;
uniform float2 uCurrSize;
uniform float uTime;
uniform float uTransition;
uniform float4 uScrim;

float2 rotate2d(float2 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return float2(p.x * c - p.y * s, p.x * s + p.y * c);
}

float3 sampleArt(float2 uv) {
    float3 cCurr = uCurrArtwork.eval(uv * uCurrSize).rgb;
    if (uTransition >= 0.999) {
        return cCurr;
    }
    float3 cPrev = uPrevArtwork.eval(uv * uPrevSize).rgb;
    return mix(cPrev, cCurr, uTransition);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    float2 p = (uv - 0.5) * float2(aspect, 1.0);

    // Pinch / warp деформация сетки (порт meshbgx iOS Apple Music)
    float2 warpA = float2(
        sin(p.y * 3.2 + uTime * 0.38) * 0.22 + cos(p.x * 2.4 - uTime * 0.24) * 0.14,
        cos(p.x * 2.9 - uTime * 0.34) * 0.22 + sin(p.y * 2.6 + uTime * 0.21) * 0.14
    );
    float2 warpB = float2(
        cos(p.y * 2.7 - uTime * 0.29 + 1.3) * 0.24,
        sin(p.x * 3.1 + uTime * 0.31 - 0.8) * 0.24
    );

    // 3 вращающихся слоя размытой обложки с разным масштабом и смещением
    float2 uv0 = rotate2d(p * 0.72 + warpA, uTime * 0.18) + float2(0.5, 0.45);
    float2 uv1 = rotate2d(p * 0.88 + warpB, -uTime * 0.23 + 1.8) + float2(0.45, 0.55);
    float2 uv2 = rotate2d(p * 0.58 - warpA * 0.7, uTime * 0.13 + 3.4) + float2(0.55, 0.50);

    float3 col0 = sampleArt(uv0);
    float3 col1 = sampleArt(uv1);
    float3 col2 = sampleArt(uv2);

    // Смешивание слоёв как в meshbgx: основной поток + глубинный контрастный слой + световой блик
    float3 col = mix(col0, col1, 0.48);
    col = mix(col, col2, 0.36);

    // Усиление насыщенности и мягкий S-контраст (Rec.709)
    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(luma), col, 1.36);
    col = (col - 0.5) * 1.12 + 0.5;

    // Скрим для читаемости текста и контролов
    col = mix(clamp(col, 0.0, 1.0), uScrim.rgb, uScrim.a);

    // Interleaved gradient noise (анти-бэндинг 8-бит)
    float noise = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715))));
    col = clamp(col + (noise - 0.5) / 255.0, 0.0, 1.0);

    return half4(half3(col), 1.0);
}
"""
