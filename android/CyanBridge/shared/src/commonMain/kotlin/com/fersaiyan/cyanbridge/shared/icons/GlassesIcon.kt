package com.fersaiyan.cyanbridge.shared.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outlined eyeglasses (Material has no glasses glyph): two lenses, a bridge and temples. */
internal val GlassesOutlined: ImageVector by lazy {
    ImageVector.Builder(
        name = "GlassesOutlined",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // Left lens
        moveTo(2.5f, 12.5f)
        horizontalLineTo(10f)
        verticalLineTo(14.5f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7f, y1 = 17.5f)
        horizontalLineTo(5.5f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 2.5f, y1 = 14.5f)
        close()
        // Right lens
        moveTo(14f, 12.5f)
        horizontalLineTo(21.5f)
        verticalLineTo(14.5f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18.5f, y1 = 17.5f)
        horizontalLineTo(17f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 14f, y1 = 14.5f)
        close()
        // Bridge
        moveTo(10f, 13.2f)
        curveTo(11.2f, 12.2f, 12.8f, 12.2f, 14f, 13.2f)
        // Temples
        moveTo(2.5f, 12.5f)
        lineTo(4f, 7.5f)
        moveTo(21.5f, 12.5f)
        lineTo(20f, 7.5f)
    }.build()
}
