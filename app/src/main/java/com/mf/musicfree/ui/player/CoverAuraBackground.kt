package com.mf.musicfree.ui.player

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val SWIRL_SMOKE_AGSL = """
uniform float2 resolution;
uniform float time;
uniform float4 c0;
uniform float4 c1;
uniform float4 c2;
uniform float4 c3;
uniform float4 c4;
uniform float4 c5;
uniform float4 bgColor;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i + float2(0.0, 0.0)), hash(i + float2(1.0, 0.0)), u.x),
        mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x),
        u.y
    );
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = rot * p * 2.05;
        a *= 0.5;
    }
    return v;
}

float3 sampleRingColor(float t) {
    float s = fract(t) * 6.0;
    int idx = int(floor(s));
    float f = smoothstep(0.0, 1.0, fract(s));
    if (idx == 0) return mix(c0.rgb, c1.rgb, f);
    if (idx == 1) return mix(c1.rgb, c2.rgb, f);
    if (idx == 2) return mix(c2.rgb, c3.rgb, f);
    if (idx == 3) return mix(c3.rgb, c4.rgb, f);
    if (idx == 4) return mix(c4.rgb, c5.rgb, f);
    return mix(c5.rgb, c0.rgb, f);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / max(resolution.y, 1.0);

    // Центр вихря — в области обложки (верхняя треть экрана)
    float2 center = float2(0.5, 0.34);
    float2 p = uv - center;
    p.x *= aspect;

    float r = length(p);
    float angle = atan(p.y, p.x);

    // Дымная турбулентность, медленно плывущая в пространстве
    float2 flowUv = p * 2.6;
    float n1 = fbm(flowUv + float2(time * 0.14, -time * 0.11));
    float n2 = fbm(flowUv * 1.6 - float2(time * 0.10, time * 0.16) + n1 * 1.2);

    // Спиралевидное закручивание лучей от краёв обложки
    float swirlAngle = angle + r * 2.4 - time * 0.25 + (n2 - 0.5) * 1.9;
    float ringT = swirlAngle / 6.2831853 + 0.5;

    float3 auraColor = sampleRingColor(ringT);

    // Делаем текстуру «дымной» за счёт модуляции яркости шумом
    float smokeMask = smoothstep(0.18, 0.88, n2);
    float radialFade = exp(-r * 2.15) * (0.72 + 0.45 * smokeMask);

    // Плавное затухание к низу экрана (под кнопки и ползунок)
    float bottomFade = 1.0 - smoothstep(0.46, 0.88, uv.y);

    float intensity = clamp(radialFade * bottomFade * 0.92, 0.0, 0.92);
    float3 finalRgb = mix(bgColor.rgb, auraColor, intensity);

    return half4(finalRgb, 1.0);
}
"""

@Composable
fun CoverAuraBackground(
    coverUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bgColor = MaterialTheme.colorScheme.background
    val defaultColors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    )

    var targetColors by remember { mutableStateOf(defaultColors) }

    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrBlank()) {
            targetColors = defaultColors
            return@LaunchedEffect
        }
        val extracted = extractEdgeColors(context, coverUrl)
        targetColors = if (extracted.size == 6) extracted else defaultColors
    }

    // Плавный переход цветов при смене трека
    val c0 by animateColorAsState(targetColors[0], tween(900), label = "aura0")
    val c1 by animateColorAsState(targetColors[1], tween(900), label = "aura1")
    val c2 by animateColorAsState(targetColors[2], tween(900), label = "aura2")
    val c3 by animateColorAsState(targetColors[3], tween(900), label = "aura3")
    val c4 by animateColorAsState(targetColors[4], tween(900), label = "aura4")
    val c5 by animateColorAsState(targetColors[5], tween(900), label = "aura5")
    val animatedColors = listOf(c0, c1, c2, c3, c4, c5)

    // Непрерывное плавное время для живого дыма (если пауза — скорость ниже)
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        var lastFrameNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (lastFrameNs != 0L) {
                    val dt = (frameNs - lastFrameNs) / 1_000_000_000f
                    val speed = if (isPlaying) 1.0f else 0.25f
                    timeSec = (timeSec + dt * speed) % 600f
                }
                lastFrameNs = frameNs
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslSwirlSmokeCanvas(
                timeSec = timeSec,
                colors = animatedColors,
                bgColor = bgColor,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            FallbackSwirlCanvas(
                colors = animatedColors,
                bgColor = bgColor,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslSwirlSmokeCanvas(
    timeSec: Float,
    colors: List<Color>,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    val shader = remember { RuntimeShader(SWIRL_SMOKE_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }

    Canvas(modifier = modifier) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", timeSec)
        shader.setFloatUniform("c0", colors[0].red, colors[0].green, colors[0].blue, 1f)
        shader.setFloatUniform("c1", colors[1].red, colors[1].green, colors[1].blue, 1f)
        shader.setFloatUniform("c2", colors[2].red, colors[2].green, colors[2].blue, 1f)
        shader.setFloatUniform("c3", colors[3].red, colors[3].green, colors[3].blue, 1f)
        shader.setFloatUniform("c4", colors[4].red, colors[4].green, colors[4].blue, 1f)
        shader.setFloatUniform("c5", colors[5].red, colors[5].green, colors[5].blue, 1f)
        shader.setFloatUniform("bgColor", bgColor.red, bgColor.green, bgColor.blue, 1f)

        drawRect(brush = brush)
    }
}

@Composable
private fun FallbackSwirlCanvas(
    colors: List<Color>,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "fallbackAura")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auraAngle"
    )

    Canvas(modifier = modifier) {
        drawRect(color = bgColor)
        val cx = size.width * 0.5f
        val cy = size.height * 0.34f
        val orbit = size.minDimension * 0.24f
        val blobRadius = size.minDimension * 0.62f

        colors.forEachIndexed { idx, color ->
            val a = angle + idx * (2.0 * PI / colors.size).toFloat()
            val bx = cx + cos(a) * orbit
            val by = cy + sin(a) * orbit * 0.85f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.48f), Color.Transparent),
                    center = Offset(bx, by),
                    radius = blobRadius
                ),
                radius = blobRadius,
                center = Offset(bx, by)
            )
        }
    }
}

/**
 * Загружает уменьшенную обложку (48x48) и берёт 6 цветов по периметру краёв обложки.
 */
private suspend fun extractEdgeColors(
    context: android.content.Context,
    coverUrl: String
): List<Color> = withContext(Dispatchers.IO) {
    try {
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(coverUrl)
            .allowHardware(false)
            .size(Size(48, 48))
            .build()
        val result = loader.execute(request) as? SuccessResult ?: return@withContext emptyList()
        val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext emptyList()
        sampleSixEdgeColors(bmp)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun sampleSixEdgeColors(bmp: Bitmap): List<Color> {
    val w = bmp.width.coerceAtLeast(1)
    val h = bmp.height.coerceAtLeast(1)
    val mX = (w * 0.12f).toInt().coerceIn(0, w - 1)
    val mY = (h * 0.12f).toInt().coerceIn(0, h - 1)

    // 6 точек по кругу вдоль краёв обложки
    val coords = listOf(
        w / 2 to mY,           // верх
        w - 1 - mX to h / 3,   // право-верх
        w - 1 - mX to 2 * h / 3, // право-низ
        w / 2 to h - 1 - mY,   // низ
        mX to 2 * h / 3,       // лево-низ
        mX to h / 3            // лево-верх
    )

    return coords.map { (x, y) ->
        val pixel = bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
        boostColorForAura(pixel)
    }
}

private fun boostColorForAura(argb: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[1] = (hsv[1] * 1.25f).coerceIn(0.22f, 0.90f)
    hsv[2] = hsv[2].coerceIn(0.24f, 0.62f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}