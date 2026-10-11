# The UI template

Every screen and every new feature lands in this template. `DesignGuardTest` (`ui/src/test`) enforces the rules marked
**guarded**; a build that breaks them fails.

## Product shape

- **Home is the chat.** Ask shows as "Home" in the bottom bar: a greeting and suggestion cards when empty, then the
  conversation (user bubbles on the right, the swarm's turns on the left with the plan card, the LOCAL / CLOUD choice,
  live status and answers inline) and the composer pinned at the bottom.
- **Every other page shows a capability.** Jobs, Swarm, Models, Data and Settings open with a `CapabilityHeader`: an
  icon, a one-sentence pitch, one live number and, where it makes sense, "Try it in chat" (fills the Home composer).
- A new feature is either a turn in the chat (a new `ChatTurn` kind rendered from kit components) or a section of a
  capability page. A new top-level page needs a new `Screen` entry and a spec change.

## Screen anatomy

```
XxxRoute(vm)                      collects the view model, wires callbacks; no layout
XxxScreen(state, callbacks...)    stateless; one HiveScreen; previews
  HiveScreen(
    screen = Screen.XXX,          title (shown in the shell's top bar)
    capability = Capability(...), required on every page except Home        (guarded)
    subtitle, statusPill,         at most one line and one pill under the header
    actions = { HeaderAction(...) },   icon actions in the top bar
    message, onDismissMessage,    transient results, pinned above the list
    primaryAction = HiveAction(...),   one docked action at most (Run, Pair a phone)
    bottomBar = { Composer(...) },     Home only
  ) { section(...); listOrEmpty(...); item { HiveCard { ... } } }
```

- View models are plain classes over ports with an injected scope (spec section 18); they never import Compose.
- Navigation goes through `LocalHiveNav.current?.go(Screen.X)`; never keep a local tab state.
- Things only the app can do (camera, clipboard, share sheet, system pages) are integration lambdas on the view model
  (`openScanner`, `copyLink`, `copyText`, ...) or a port (`DemoPort`), set by `:app`.

## Which component for which job

| Need | Use |
| --- | --- |
| Any card | `HiveCard(tone = ...)`; never Material's `Card` (guarded) |
| The one result or live object on a page | `HeroCard` (big headline, caption, pills, body, actions) |
| A state (online, loaded, private, cloud) | `StatusPill(text, Tone)`; pills show state, never actions |
| A number worth reading from across the room | `StatTile` / `StatRow` |
| Buttons | `ActionRow(primary, secondaries)`: one filled primary, at most two tonal, the rest in the overflow menu |
| A single button | `HiveButton(action, ButtonKind)` |
| A row with an avatar or icon | `ListRow` + `Avatar` |
| Steps, figures, timelines | `StepList` |
| One choice of a few | `SegmentedChoice` |
| Settings | `PreferenceGroup` + `SwitchRow` / `KeyValue` / `PreferenceDivider` |
| Text input | `HiveTextField`, `IntField`, `DoubleField`, `SaveableTextField` |
| Chat | `UserBubble`, `SwarmTurn`, `TypingIndicator`, `SuggestionCard`, `Composer` |
| Notices | `InlineNotice`, `MessageBar`, `EmptyState`, `LoadingRow`, `ErrorState` |
| A refusal or partial result | `RefusalCard` (spec section 17, refusals in plain words) |

## States every list handles

Loading (`loading(...)`), error with retry (`error(...)`), empty with what to do next (`listOrEmpty(...)`), and
populated. Transient results go to the message bar; refusals go through `RefusalCard`.

## Tokens only (guarded)

Outside `com.hivecore.ui.design` no file may contain:

| Pattern | Use instead |
| --- | --- |
| `Color(0x...)` | `MaterialTheme.colorScheme.*`, `HiveTheme.colors.*` |
| `12.sp`, `fontSize =`, `fontFamily =`, `letterSpacing =` | `MaterialTheme.typography.*`, `HiveTextStyles.*` |
| `RoundedCornerShape(`, `CutCornerShape(`, `CircleShape` | `HiveShape.*` |
| a dp literal other than 0, 1 or 2 | `HiveSpacing.*` |
| `Icons.Filled.*` and other library icons | `HiveIcons.*` |
| `Card(`, `ElevatedCard(`, `OutlinedCard(` | `HiveCard` |
| `lightColorScheme(`, `darkColorScheme(`, `MaterialTheme(` | `HiveCoreTheme` (applied by the activities only) |
| `android.R.drawable.*` (app) | the app's own drawables |

Need a new colour, size, shape or icon? Add it to the design package (`HivePalette` + `HiveColors`, `HiveSpacing`,
`HiveShape`, `HiveTextStyles`, `HiveIcons`); that is the only reason to edit it. Icons are drawn from path data, so no
icon library is needed.

## Accessibility

Headings carry `heading()` (the kit's section and header do). Selectable cards set `stateDescription` through
`HiveCard(semantics = ...)`. Switches, checkboxes and chunk cells have a `contentDescription`. Tiles merge their
descendants into one description. Every icon-only action has a label (`HeaderAction(icon, label)`).

## Required with every screen change

- `@HivePreviews` previews (light and dark) in the screen file (guarded).
- A JVM test for the view model or the pure mapping behind the change (`ui/src/test`).
- `tools/gradle.sh :ui:testDebugUnitTest :app:testDebugUnitTest` green, `DesignGuardTest` included.
