package com.autoexpensetracker.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * An amount that is always shown in full when it can be, instead of being cut
 * off with "...". The text is measured against the width it actually has:
 *  1. at the normal size if it fits;
 *  2. otherwise shrunk a point at a time, down to [minSizeSp];
 *  3. only if even that does not fit, the [compact] form ("₹1.54 L") at the
 *     normal size - never a truncated number, which could be misread.
 */
@Composable
internal fun FitAmountText(
    full: String,
    compact: String,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    minSizeSp: Float = 12f
) {
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val baseSize = style.fontSize.value

        fun widthAt(text: String, sizeSp: Float): Int =
            measurer.measure(
                text = text,
                style = style.copy(fontSize = sizeSp.sp, fontWeight = fontWeight),
                maxLines = 1,
                softWrap = false
            ).size.width

        var size = baseSize
        while (size > minSizeSp && widthAt(full, size) > maxPx) size -= 1f
        val fits = widthAt(full, size) <= maxPx
        Text(
            text = if (fits) full else compact,
            style = style.copy(fontSize = (if (fits) size else baseSize).sp),
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
