package com.mf.musicfree.ui.wave

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.mf.musicfree.player.AudioSpectrum
import kotlin.math.cos
import kotlin.math.sin

private const val PLASMA_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float iBass;
uniform float iMid;
uniform float iHigh;
uniform float iActive;

float2 warp(float2 p, float t, float bass) {
    float2 q = float2(
        sin(p.y * 2.1 + t * 0.9) + cos(p.x * 1.7 - t * 0.7),
        cos(p.x * 2.3 + t * 0.8) - sin(p.y * 1.9 + t * 0.6)
    );
    return p + q * (0.38 + bass * 0.28);
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * iResolution) / min(iResolution.x, iResolution.y);
    float dist = length(uv);

    float t = iTime;
    float2 p = uv * (2.2 - iBass * 0.25);

    // Двойной domain warping для маслянистого течения
    float2 w1 = warp(p, t, iBass);
    float2 w2 = warp(w1 * 1.15, -t * 0.85, iMid);

    // Чёткие неоновые гребни (ridge interference)
    float waveA = sin(w2.x * 3.1 + w2.y * 2.4 + t * 1.1);
    float waveB = cos(w2.x * 2.3 - w2.y * 3.2 - t * 0.9);
    float ridge1 = pow(1.0 - abs(waveA), 3.2);
    float ridge2 = pow(1.0 - abs(waveB), 3.6);
    float slick = smoothstep(0.15, 0.95, ridge1 * 0.65 + ridge2 * 0.65);

    // Палитра полностью управляется спектром звука:
    // Бас -> глубокий фиолет/маджента, Середина -> электрический синий/циан, Верха -> тёплое золото
    float3 deepBg   = float3(0.055, 0.060, 0.095);
    float3 bassCol  = mix(float3(0.32, 0.14, 0.68), float3(0.76, 0.16, 0.58), clamp(iBass * 1.3, 0.0, 1.0));
    float3 midCol   = float3(0.08, 0.62, 0.88);
    float3 highCol  = float3(0.98, 0.78, 0.34);

    // Смешиваем слои в зависимости от текущей громкости полос
    float3 oil = bassCol * (0.45 + iBass * 0.85) * ridge1;
    oil += midCol * (0.30 + iMid * 0.95) * ridge2;
    oil += highCol * (0.12 + iHigh * 1.15) * pow(ridge1 * ridge2, 0.75);

    // Лёгкий перелив бензиновой плёнки на гребнях
    float irid = 0.5 + 0.5 * sin(w2.x * 4.0 - w2.y * 3.0 + t * 1.5 + iHigh * 3.0);
    oil = mix(oil, oil * float3(0.85 + 0.3 * irid, 0.95, 1.15 - 0.25 * irid), 0.35);

    // В покое приглушаем в спокойный монохромный оттенок темы
    float3 idleCol = float3(0.24, 0.27, 0.42) * slick * 0.42;
    float3 activeCol = deepBg + oil * (0.65 + slick * 0.55);
    float3 col = mix(deepBg + idleCol, activeCol, iActive);

    // Мягкая тёмная виньетка по краям + лёгкое приглушение самого центра под кнопки
    float edgeVignette = smoothstep(1.25, 0.22, dist);
    float centerReadability = mix(0.74, 1.0, smoothstep(0.05, 0.42, dist));
    col = mix(deepBg, col, edgeVignette) * centerReadability;

    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""

@Composable
fun WavePlasmaBackground(
    isWavePlaying: Boolean,
    isWaveActive: Boolean,
    spectrumProvider: () -> AudioSpectrum,
    modifier: Modifier = Modifier
) {
    var time by remember { mutableFloatStateOf(0f) }
    var smoothBass by remember { mutableFloatStateOf(0f) }
    var smoothMid by remember { mutableFloatStateOf(0f) }
    var smoothHigh by remember { mutableFloatStateOf(0f) }
    var activeMix by remember { mutableFloatStateOf(if (isWaveActive) 1f else 0f) }

    LaunchedEffect(isWavePlaying, isWaveActive) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now

            val spec = if (isWavePlaying) spectrumProvider() else AudioSpectrum()
            smoothBass += (spec.bass - smoothBass) * (dt * 12f).coerceAtMost(1f)
            smoothMid += (spec.mid - smoothMid) * (dt * 10f).coerceAtMost(1f)
            smoothHigh += (spec.high - smoothHigh) * (dt * 10f).coerceAtMost(1f)

            val targetActive = if (isWaveActive) 1f else 0f
            activeMix += (targetActive - activeMix) * (dt * 3.5f).coerceAtMost(1f)

            // На паузе плазма еле дышит; при бите — ускоряется
            val speed = if (isWavePlaying) {
                0.55f + smoothBass * 1.45f + smoothMid * 0.45f
            } else {
                0.14f
            }
            time = (time + dt * speed) % 10_000f
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(PLASMA_AGSL) }
        Canvas(modifier = modifier.fillMaxSize()) {
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iTime", time)
            shader.setFloatUniform("iBass", smoothBass)
            shader.setFloatUniform("iMid", smoothMid)
            shader.setFloatUniform("iHigh", smoothHigh)
            shader.setFloatUniform("iActive", activeMix)
            drawRect(brush = ShaderBrush(shader))
        }
    } else {
        // Фолбэк для Android < 13
        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(Color(0xFF0E1018))
            val cx = size.width * 0.5f + cos(time * 0.7f) * size.width * 0.16f
            val cy = size.height * 0.48f + sin(time * 0.9f) * size.height * 0.14f
            val radius = size.minDimension * (0.65f + smoothBass * 0.25f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF6B2CA8).copy(alpha = 0.25f + activeMix * (0.3f + smoothBass * 0.35f)),
                        Color(0xFF149EE0).copy(alpha = 0.15f + activeMix * smoothMid * 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                center = Offset(cx, cy),
                radius = radius
            )
        }
    }
}