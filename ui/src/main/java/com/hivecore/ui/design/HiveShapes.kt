package com.hivecore.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** The one place corner shapes live. */
internal val HiveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object HiveShape {
    val card: Shape = HiveShapes.medium
    val hero: Shape = HiveShapes.large
    val field: Shape = HiveShapes.small
    val chunk: Shape = HiveShapes.extraSmall
    val tile: Shape = HiveShapes.small
    val swatch: Shape = RoundedCornerShape(2.dp)
    val pill: Shape = CircleShape
    val circle: Shape = CircleShape
    val composer: Shape = HiveShapes.extraLarge
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    /** A chat bubble from the user: rounded except the bottom-right "tail" corner. */
    val userBubble: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
}
