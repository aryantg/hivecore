package com.hivecore.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hivecore.ui.Screen
import com.hivecore.ui.ScreenRegistry
import com.hivecore.ui.design.HiveIcons
import com.hivecore.ui.design.HiveShape
import com.hivecore.ui.design.HiveSpacing
import com.hivecore.ui.design.HiveTextStyles
import com.hivecore.ui.design.LocalHiveTopBar
import com.hivecore.ui.design.LocalTryInChat
import com.hivecore.ui.design.StatusPill
import com.hivecore.ui.design.Tone
import com.hivecore.ui.design.TopBarSlots
import com.hivecore.ui.design.icon

/**
 * What the top bar reports. Null fields are hidden: the app passes only what its flags really wire, so a fake
 * swarm never shows up in the chip.
 */
@Immutable
data class ShellStatus(
    val phonesOnline: Int? = null,
    val phonesTotal: Int? = null,
    val bigModel: String? = null,
    val jobRunning: Boolean = false,
    val demo: Boolean = false,
    val unreadEvents: Int = 0,
    val lastEvent: String? = null,
) {
    /** "3/3 phones" or null. */
    val chipText: String? get() = phonesTotal?.let { total -> "${phonesOnline ?: 0}/$total ${if (total == 1) "phone" else "phones"}" }
}

/**
 * The app frame (spec section 17: one activity, a bottom bar). It does not apply the theme: the activity wraps it in
 * `HiveCoreTheme` so sheets and banners drawn beside it are themed too.
 *
 * @param banner rows under the top bar (the pending-approval card, the permissions card); they push content down
 * @param onTryInChat what "Try it in chat" does (the app fills the Home composer); null hides the button
 * @param onOpenFeed opens the live activity feed; null hides the icon
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiveCoreShell(
    registry: ScreenRegistry = ScreenRegistry(),
    nav: HiveNav = rememberHiveNav(),
    status: ShellStatus = ShellStatus(),
    banner: @Composable () -> Unit = {},
    onTryInChat: ((String) -> Unit)? = null,
    onOpenFeed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val slots = remember { TopBarSlots() }
    val tryInChat: ((String) -> Unit)? = onTryInChat?.let { f -> { prompt: String -> f(prompt); nav.go(Screen.ASK) } }
    CompositionLocalProvider(LocalHiveNav provides nav, LocalHiveTopBar provides slots, LocalTryInChat provides tryInChat) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        Icon(HiveIcons.hexMark, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = HiveSpacing.lg, end = HiveSpacing.xs).size(HiveSpacing.iconLg))
                    },
                    title = { Text(if (nav.current == Screen.ASK) "HiveCore" else nav.current.title, style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        if (status.demo) { StatusPill("DEMO", Tone.Warn); Spacer(Modifier.width(HiveSpacing.xs)) }
                        status.chipText?.let { chip ->
                            StatusPill(chip, if ((status.phonesOnline ?: 0) > 0) Tone.Ok else Tone.Neutral, dot = true,
                                modifier = Modifier.clip(HiveShape.pill).clickable { nav.go(Screen.SWARM) }.semantics { contentDescription = "$chip online. Open Swarm" })
                        }
                        if (onOpenFeed != null) {
                            IconButton(onClick = onOpenFeed) {
                                BadgedBox(badge = { if (status.unreadEvents > 0) Badge { Text(minOf(status.unreadEvents, MAX_BADGE).toString(), style = HiveTextStyles.count) } }) {
                                    Icon(HiveIcons.activity, contentDescription = "Activity")
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { slots.actions(this) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Screen.entries.forEach { s ->
                        NavigationBarItem(
                            selected = nav.current == s,
                            onClick = { nav.go(s) },
                            icon = { Icon(s.icon(), contentDescription = null) },
                            label = { Text(s.navLabel, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                banner()
                Box(Modifier.weight(1f)) { registry.content(nav.current) }
            }
        }
    }
}

private const val MAX_BADGE = 99
