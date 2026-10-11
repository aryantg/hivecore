package com.hivecore.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.hivecore.ui.Screen
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's icon set: 24-dp Material-style glyphs drawn from path data (no icon library dependency) plus the brand
 * hexagons. Screens use only these; the design guard rejects `Icons.Filled.*`.
 */
object HiveIcons {
    private fun icon(name: String, vararg paths: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder("Hive.$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero) }
        }.build()

    /** A pointy-top hexagon of radius [r] centred on ([cx], [cy]). */
    private fun PathBuilder.hex(cx: Float, cy: Float, r: Float) {
        for (i in 0 until 6) {
            val a = Math.toRadians(-90.0 + 60.0 * i)
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun hexIcon(name: String, evenOdd: Boolean, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder("Hive.$name", 24.dp, 24.dp, 24f, 24f)
            .addPath(PathBuilder().apply(block).nodes, fill = SolidColor(Color.Black), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
            .build()

    /** The brand mark: a hexagon ring around a filled hexagon. */
    val hexMark: ImageVector by lazy { hexIcon("hexMark", evenOdd = true) { hex(12f, 12f, 10f); hex(12f, 12f, 7.6f); hex(12f, 12f, 4.4f) } }
    /** Three touching hexagons: the swarm. */
    val swarm: ImageVector by lazy { hexIcon("swarm", evenOdd = false) { hex(8.4f, 8.6f, 3.9f); hex(15.6f, 8.6f, 3.9f); hex(12f, 14.9f, 3.9f) } }

    val ask by lazy { icon("ask", "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM7,9h2v2H7V9zM11,9h2v2h-2V9zM15,9h2v2h-2V9z", evenOdd = true) }
    val jobs by lazy { icon("jobs", "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9zM13,13v8h8v-8H13zM19,19h-4v-4h4V19z") }
    val models by lazy { icon("models", "M15,9H9v6h6V9zM13,13h-2v-2h2V13zM21,11V9h-2V7c0,-1.1 -0.9,-2 -2,-2h-2V3h-2v2h-2V3H9v2H7C5.9,5 5,5.9 5,7v2H3v2h2v2H3v2h2v2c0,1.1 0.9,2 2,2h2v2h2v-2h2v2h2v-2h2c1.1,0 2,-0.9 2,-2v-2h2v-2h-2v-2H21zM17,17H7V7h10V17z") }
    val data by lazy { icon("data", "M12,3C7.58,3 4,4.79 4,7v10c0,2.21 3.59,4 8,4s8,-1.79 8,-4V7c0,-2.21 -3.58,-4 -8,-4zM12,5c3.87,0 6,1.5 6,2s-2.13,2 -6,2 -6,-1.5 -6,-2 2.13,-2 6,-2zM18,17c0,0.5 -2.13,2 -6,2s-6,-1.5 -6,-2v-2.23c1.61,0.78 3.72,1.23 6,1.23s4.39,-0.45 6,-1.23V17zM18,12.43c-1.3,0.95 -3.58,1.57 -6,1.57s-4.7,-0.62 -6,-1.57V9.65c1.47,0.83 3.61,1.35 6,1.35s4.53,-0.52 6,-1.35v2.78z") }
    val settings by lazy { icon("settings", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z") }

    val qr by lazy { icon("qr", "M3,11h8V3H3V11zM5,5h4v4H5V5zM3,21h8v-8H3V21zM5,15h4v4H5V15zM13,3v8h8V3H13zM19,9h-4V5h4V9zM19,19h2v2h-2zM13,13h2v2h-2zM15,15h2v2h-2zM13,17h2v2h-2zM15,19h2v2h-2zM17,17h2v2h-2zM17,13h2v2h-2zM19,15h2v2h-2z") }
    val scan by lazy { icon("scan", "M9.5,6.5v3h-3v-3H9.5M11,5H5v6h6V5L11,5zM9.5,14.5v3h-3v-3H9.5M11,13H5v6h6V13L11,13zM17.5,6.5v3h-3v-3H17.5M19,5h-6v6h6V5L19,5zM13,13h1.5v1.5H13V13zM14.5,14.5H16V16h-1.5V14.5zM16,13h1.5v1.5H16V13zM13,16h1.5v1.5H13V16zM14.5,17.5H16V19h-1.5V17.5zM16,16h1.5v1.5H16V16zM17.5,14.5H19V16h-1.5V14.5zM17.5,17.5H19V19h-1.5V17.5zM22,7h-2V4h-3V2h5V7zM22,22v-5h-2v3h-3v2H22zM2,22h5v-2H4v-3H2V22zM2,2v5h2V4h3V2H2z") }
    val link by lazy { icon("link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z") }
    val copy by lazy { icon("copy", "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z") }
    val share by lazy { icon("share", "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z") }
    val play by lazy { icon("play", "M8,5v14l11,-7z") }
    val stop by lazy { icon("stop", "M6,6h12v12H6z") }
    val refresh by lazy { icon("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z") }
    val download by lazy { icon("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val upload by lazy { icon("upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z") }
    /** A slide on an easel: a deck / presentation. */
    val deck by lazy { icon("deck", "M21,3H3C1.9,3 1,3.9 1,5v12c0,1.1 0.9,2 2,2h7v2H8v1h8v-1h-2v-2h7c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12zM6,15h2V9H6v6zM11,15h2V7h-2v8zM16,15h2v-4h-2v4z") }
    /** Open in another app. */
    val openExternal by lazy { icon("openExternal", "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z") }
    val lock by lazy { icon("lock", "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z") }
    val cloud by lazy { icon("cloud", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z") }
    val phone by lazy { icon("phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z") }
    val check by lazy { icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }
    val warning by lazy { icon("warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z") }
    val info by lazy { icon("info", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z") }
    val history by lazy { icon("history", "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z") }
    val tune by lazy { icon("tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z") }
    val bolt by lazy { icon("bolt", "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z") }
    /** Ping nearby: pair with every phone on this Wi-Fi. */
    val ping by lazy { icon("ping", "M12,11c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,13c0,-3.31 -2.69,-6 -6,-6s-6,2.69 -6,6c0,2.22 1.21,4.15 3,5.19l1,-1.74c-1.19,-0.7 -2,-1.97 -2,-3.45 0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,1.48 -0.81,2.75 -2,3.45l1,1.74c1.79,-1.04 3,-2.97 3,-5.19zM12,3C6.48,3 2,7.48 2,13c0,3.7 2.01,6.92 4.99,8.65l1,-1.73C5.61,18.53 4,15.96 4,13c0,-4.42 3.58,-8 8,-8s8,3.58 8,8c0,2.96 -1.61,5.53 -4,6.92l1,1.73c2.99,-1.73 5,-4.95 5,-8.65 0,-5.52 -4.48,-10 -10,-10z") }
    /** Compute points. */
    val star by lazy { icon("star", "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z") }
    val delete by lazy { icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z") }
    val close by lazy { icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z") }
    val more by lazy { icon("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z") }
    val expand by lazy { icon("expand", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z") }
    val collapse by lazy { icon("collapse", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z") }
    val add by lazy { icon("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    val send by lazy { icon("send", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z") }
    val newChat by lazy { icon("newChat", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z") }
    val activity by lazy { icon("activity", "M23,8c0,1.1 -0.9,2 -2,2c-0.18,0 -0.35,-0.02 -0.51,-0.07l-3.56,3.55C16.98,13.64 17,13.82 17,14c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2c0,-0.18 0.02,-0.36 0.07,-0.52l-2.55,-2.55C10.36,10.98 10.18,11 10,11s-0.36,-0.02 -0.52,-0.07l-4.55,4.56C4.98,15.65 5,15.82 5,16c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2s0.9,-2 2,-2c0.18,0 0.35,0.02 0.51,0.07l4.56,-4.55C8.02,9.36 8,9.18 8,9c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,0.18 -0.02,0.36 -0.07,0.52l2.55,2.55C14.64,12.02 14.82,12 15,12s0.36,0.02 0.52,0.07l3.55,-3.56C19.02,8.35 19,8.18 19,8c0,-1.1 0.9,-2 2,-2S23,6.9 23,8z") }
    val photo by lazy { icon("photo", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z") }
    val document by lazy { icon("document", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z") }
    val table by lazy { icon("table", "M3,3v18h18V3H3zM9,19H5v-4h4V19zM9,13H5V9h4V13zM9,7H5V5h4V7zM19,19h-8v-4h8V19zM19,13h-8V9h8V13zM19,7h-8V5h8V7z") }
}

/** The bottom-bar icon of each screen. */
fun Screen.icon(): ImageVector = when (this) {
    Screen.ASK -> HiveIcons.hexMark
    Screen.JOBS -> HiveIcons.jobs
    Screen.SWARM -> HiveIcons.swarm
    Screen.MODELS -> HiveIcons.models
    Screen.DATA -> HiveIcons.data
    Screen.SETTINGS -> HiveIcons.settings
}
