package raf.console.quran7hours.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Подъём по-прежнему выразительный; возвращение теперь идёт равномерно до самого конца. */
internal object PageTurnEasing {
    val lift = CubicBezierEasing(0.12f, 0.0f, 0.20f, 1.0f)
    val unfold = CubicBezierEasing(0.42f, 0.03f, 0.62f, 0.97f)
    val settle = CubicBezierEasing(0.30f, 0.0f, 0.65f, 1.0f)
}

/** Массивы переиспользуются: ни новых bitmap, ни новых буферов на кадр. */
private class PaperMeshBuffer {
    private val columns = BookTurnMath.COLUMNS
    private val rows = BookTurnMath.ROWS
    val vertices = FloatArray((columns + 1) * (rows + 1) * 2)
    val vertexColors = IntArray((columns + 1) * (rows + 1))
    val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun update(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        progress: Float,
        turnFromLeft: Boolean,
        unfolding: Boolean,
        shadeFold: Boolean,
        themeTransition: Boolean
    ) {
        val p = BookTurnMath.safe(progress)
        val shape = BookTurnMath.profile(p, unfolding, columns)

        // ВАЖНО: в обоих случаях arc -> 0 при p -> 0.
        // Старый cos(PI*p/2) при p=0 давал arc=1, отчего расправленная
        // страница внезапно прыгала вверх при замене сетки на Image.
        // Во время смены темы обе стороны встречаются при одинаковом подъёме
        // у ребра (p=1), чтобы не было разрыва посередине анимации.
        val arc = if (themeTransition) {
            sin(Math.PI * p * 0.5).toFloat()
        } else {
            sin(Math.PI * p).toFloat().coerceAtLeast(0f)
        }

        for (x in 0..columns) {
            val u = if (turnFromLeft) x else columns - x
            val xx = if (turnFromLeft) {
                left + width * shape.x[u]
            } else {
                left + width * (1f - shape.x[u])
            }
            // 0 у корешка, 1 у свободного края.
            val t = u.toFloat() / columns
            val lift = arc * height * 0.30f * (t * t)
            val perspective = 1f + arc * 0.10f * t
            val ripple = arc * sin(Math.PI * t).toFloat() * 0.003f * height
            val shade = if (shadeFold) {
                val bend = abs(sin(shape.tangent[u].toDouble())).toFloat()
                (1f - bend * 0.10f).coerceIn(0.85f, 1f)
            } else 1f
            val gray = (255f * shade).toInt().coerceIn(0, 255)
            val color = android.graphics.Color.rgb(gray, gray, gray)
            for (y in 0..rows) {
                val v = y.toFloat() / rows
                val idx = y * (columns + 1) + x
                vertices[idx * 2] = xx
                vertices[idx * 2 + 1] =
                    top + height * (0.5f + (v - 0.5f) * perspective) + ripple - lift
                vertexColors[idx] = color
            }
        }
    }
}

/** Волновая сетка 72×16 сохранена, bitmap нигде не зеркалится. */
@Composable
internal fun CurvedMushafPage(
    bitmap: Bitmap,
    progress: Float,
    turnFromLeft: Boolean,
    unfolding: Boolean,
    shadeFold: Boolean,
    modifier: Modifier = Modifier.fillMaxSize(),
    themeTransition: Boolean = false
) {
    val mesh = remember { PaperMeshBuffer() }
    Canvas(modifier) {
        if (bitmap.width == 0 || bitmap.height == 0 || size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }
        val fit = min(size.width / bitmap.width, size.height / bitmap.height)
        val width = bitmap.width * fit
        val height = bitmap.height * fit
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        mesh.update(
            left, top, width, height, progress, turnFromLeft,
            unfolding, shadeFold, themeTransition
        )
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmapMesh(
                bitmap, BookTurnMath.COLUMNS, BookTurnMath.ROWS,
                mesh.vertices, 0,
                if (shadeFold) mesh.vertexColors else null, 0,
                mesh.filter
            )
        }
    }
}

/**
 * Оба варианта темы опираются на один корешок. Ни WebView, ни загрузок,
 * ни падающей тени; только оригинальный WebP и волна, доходящая до нуля.
 */
@Composable
internal fun BookThemeTurnOverlay(
    bitmap: Bitmap,
    backdrop: Color,
    fraction: Float,
    newFace: Boolean
) {
    Box(Modifier.fillMaxSize().background(backdrop)) {
        val p = BookTurnMath.safe(fraction)
        if (p <= 0f) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            CurvedMushafPage(
                bitmap = bitmap,
                progress = p,
                turnFromLeft = false,
                unfolding = newFace,
                shadeFold = false,
                themeTransition = true
            )
        }
    }
}

/**
 * Один лист над вторым. При медленном свайпе последний участок дорисовывает
 * BookLeafGestureState вместо резкого удаления слоя при смене settledPage.
 */
@Composable
internal fun BookLeafTurnOverlay(
    outgoing: Bitmap,
    incoming: Bitmap,
    progress: Float,
    forward: Boolean,
    backdrop: Color,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val p = BookTurnMath.safe(progress)
    // Направление пользователя сохранено без каких-либо инверсий.
    val hingeAtLeft = !forward
    Box(modifier.background(backdrop)) {
        Image(
            bitmap = incoming.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        CurvedMushafPage(
            bitmap = outgoing,
            progress = p,
            turnFromLeft = hingeAtLeft,
            unfolding = false,
            shadeFold = true
        )
        if (p > 0.015f && p < 0.97f) {
            Canvas(Modifier.fillMaxSize()) {
                val fit = min(size.width / outgoing.width, size.height / outgoing.height)
                val pageWidth = outgoing.width * fit
                val pageHeight = outgoing.height * fit
                val left = (size.width - pageWidth) / 2f
                val top = (size.height - pageHeight) / 2f
                val bent = BookTurnMath.profile(p).x.last().coerceIn(0f, 1f)
                val freeX = if (hingeAtLeft) left + pageWidth * bent
                    else left + pageWidth * (1f - bent)
                val arc = sin(Math.PI * p).toFloat()
                val freeLift = arc * pageHeight * 0.30f
                val freePersp = 1f + arc * 0.10f
                val topY = top + pageHeight * (0.5f - 0.5f * freePersp) - freeLift
                val bottomY = top + pageHeight * (0.5f + 0.5f * freePersp) - freeLift
                val opacity = (0.12f * arc).coerceIn(0f, 0.12f)
                drawLine(
                    color = if (backdrop.red + backdrop.green + backdrop.blue < 1.2f)
                        Color.White.copy(alpha = opacity) else Color.Black.copy(alpha = opacity),
                    start = Offset(freeX, topY),
                    end = Offset(freeX, bottomY),
                    strokeWidth = 1.5f
                )
            }
        }
    }
}
