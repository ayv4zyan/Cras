# Material Design 3 on React Native (2026)

**Status (2026-08-18):** historical. Android is Jetpack Compose + Material 3, not React Native — [Which client stack do we lock for web and Android?](https://github.com/ayv4zyan/Cras/issues/12), `docs/adr/0001-polyglot-clients.md`. Google’s official MD3 column is Compose; that is the path we took. This note is why Expo UI / Paper were not the plan.

Ticket: [How do you ship Material Design 3 on React Native?](https://github.com/ayv4zyan/Cras/issues/7)  
Researched: 2026-08-17 against official Material, Android, React Native, Expo, React Navigation, React Native Paper, and shadcn docs.

## Verdict (for the rejected RN path)

Google does **not** ship a Material 3 component library for React Native. Official MD3 code lives on Android Views, Jetpack Compose, Flutter, and the Web. On React Native in 2026 there are two first-party-supported ways to get MD3 (not MD2):

1. **Native Material 3 widgets** via Expo `@expo/ui` Jetpack Compose (`npx expo install @expo/ui`). Buttons, FABs, text fields, navigation bars, and the Material 3 / Material You palette are real Compose Material3, hosted inside a `<Host>`. Included in Expo Go.
2. **JS-rendered Material You (MD3)** via Callstack [React Native Paper](https://oss.callstack.com/react-native-paper/) v5+. `PaperProvider` applies the MD3 theme unless you pass `theme` / `version: 2`. Expo-compatible (vector icons already in the Expo package).

React Native itself only points at Material Design as a design resource and exposes platform color tokens (`PlatformColor`). It does not ship MD3 components.

If the web app is shadcn + Tailwind and Android is MD3, **share a contract, not UI**. That still holds after the polyglot lock (schema between Kotlin and TypeScript, not TanStack Query on Android). shadcn’s official install targets Next, Vite, TanStack Start, Laravel, React Router, and Astro — not Expo or React Native.

## What Google actually implements

Material 3’s [Develop](https://m3.material.io/develop) index is “code and developer documentation for Android Views (MDC-Android), Flutter, Jetpack Compose, and the Web.” There is no React Native column.

Official implementations:

| Surface | Owner | Notes |
| --- | --- | --- |
| Jetpack Compose Material 3 | Android | [`androidx.compose.material3`](https://developer.android.com/develop/ui/compose/designsystems/material3) — Material You + M3 Expressive; dynamic color on Android 12+ via `dynamicLightColorScheme` / `dynamicDarkColorScheme` |
| Android Views (MDC-Android) | Android | Listed on [m3.material.io/develop](https://m3.material.io/develop) |
| Flutter | Flutter | [m3.material.io/develop/flutter](https://m3.material.io/develop/flutter) |
| Material Web | Google | [m3.material.io/develop/web](https://m3.material.io/develop/web) — “component library for building applications that work in any web framework” |

Dynamic / Material You color is specified by Material as wallpaper-derived schemes on Android 12+ ([user-generated source](https://m3.material.io/styles/color/dynamic/user-generated-source), [dynamic-color overview](https://m3.material.io/styles/color/dynamic-color/overview)). Compose’s implementation docs list **Android Views, Compose, and Flutter** as the available implementation resources for that color system — again, not React Native.

React Native’s own [Improving UX](https://reactnative.dev/docs/improvingux) page treats Material Design as a *guideline*, not a kit: “Material Design and Human Interface Guidelines are great resources for learning more about designing for mobile platforms.” The only built-in Material-adjacent primitive is ripple via `TouchableNativeFeedback` on Android API 21+.

## Path A — Native MD3: Expo UI + Jetpack Compose

Expo’s first-party kit is [`@expo/ui`](https://docs.expo.dev/versions/latest/sdk/ui/) (`npx expo install @expo/ui`). Platforms: Android, iOS, tvOS, Expo Go.

- **Jetpack Compose tree** (`@expo/ui/jetpack-compose`): “fully native Android interfaces using Jetpack Compose from React Native.” Every Compose subtree must sit in a [`Host`](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/host/).
- **Buttons** are documented as “native Material3 buttons” and “match the official Jetpack Compose [Button API](https://developer.android.com/develop/ui/compose/components/button)”: filled, tonal, outlined, elevated, text. Icon spacing follows [M3 button guidelines](https://m3.material.io/components/buttons/guidelines) ([Expo Button](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/button/)).
- **FAB** is “following Material Design 3.” **NavigationBar** is “a Jetpack Compose NavigationBar component for Material 3 bottom navigation” matching Compose [`NavigationBar`](https://developer.android.com/develop/ui/compose/components/navigation-bar) ([Expo NavigationBar](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/navigationbar/)).
- **Material Colors** ([docs](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/colors/)):
  - Default on Android 12+ with no `seedColor`: wallpaper-derived [Material 3 Dynamic Colors](https://m3.material.io/styles/color/dynamic-color/overview) (Material You).
  - Fallback on Android 11 and below: static Material 3 baseline.
  - `seedColor` on `<Host>`: `SchemeTonalSpot` (same algorithm Material You uses); works on every API level.
  - `useMaterialColors()` / `getMaterialColors()` / `isDynamicColorAvailable`.
- Official Expo agent skills treat this as *the* MD3 path: example prompt “Use Material Design 3 components with Jetpack Compose” maps to the `expo-ui` skill ([Expo Skills](https://docs.expo.dev/skills/)).

**Universal** `@expo/ui` ([docs](https://docs.expo.dev/versions/latest/sdk/ui/universal/)) is one API that delegates to Compose on Android, SwiftUI on iOS, and JS on web. That is *not* MD3 on every platform — on Android it is native MD3; on iOS it is HIG/SwiftUI. Cras is Android-only for mobile, so `@expo/ui/jetpack-compose` is the tighter MD3 surface.

Constraints that matter:

- Compose children only render inside `<Host>`. Mixing RN `View`/`Text` into a Compose tree needs `RNHostView`.
- `matchContents` cannot share an axis with a scrollable Compose child.
- This is a native module (`@expo/ui`). Expo Go currently includes it; a bare RN app must install Expo modules first.

## Path B — JS MD3: React Native Paper v5+

[React Native Paper](https://oss.callstack.com/react-native-paper/) is Callstack’s “cross-platform Material Design for React Native.” README: production-ready components that “by default are following and respecting the Google’s Material Design guidelines,” linking [m3.material.io/get-started](https://m3.material.io/get-started/).

**MD3 is the default.** Theming guide: “By default React Native Paper will apply the Material You theme (MD3) if no `theme` or `version` prop is passed to the `PaperProvider`.” Version `3` = Material You (MD3); version `2` = previous Material Design (MD2). MD2 remains supported in 5.x via `{ version: 2 }` or `MD2LightTheme` / `MD2DarkTheme` ([Theming](https://oss.callstack.com/react-native-paper/docs/guides/theming)).

Getting started ([docs](https://oss.callstack.com/react-native-paper/docs/guides/getting-started)):

- `npm install react-native-paper react-native-safe-area-context`
- Vanilla RN: also `@react-native-vector-icons/material-design-icons` (some components use that pack internally).
- **Expo:** “you don’t need to install vector icons — those are the part of the expo package.” Keep `babel-preset-expo`. Production babel plugin: `react-native-paper/babel`.
- Wrap the tree in `PaperProvider`. Customize from `MD3LightTheme` / `MD3DarkTheme`.

Paper’s color roles match MD3 (`primary` / `onPrimary` / `primaryContainer` / surfaces / elevation levels 0–5 / inverse / error). Docs include a seed-color scheme generator that “follow[s] the Material Design 3 color system.” For wallpaper sync they point at third-party `@pchmn/expo-material3-theme` (not Expo first-party). Expo now owns equivalent first-party APIs (`Color.android.dynamic.*` and `@expo/ui` `useMaterialColors`); prefer those over the community package if the app is Expo.

`adaptNavigationTheme` maps a React Navigation theme onto Paper’s MD3 colors (and, on React Navigation 7, remaps `fonts` onto MD3 type roles). Pass the result to `NavigationContainer` so headers/tabs share Paper’s scheme.

Paper is JS views (plus icons / safe area). It is **not** Compose Material3. It is the supported way to get an MD3 *look* on RN `View` trees, including React Native Web.

## Expo theming (dynamic color / Material You)

Independent of which component kit you pick:

| Mechanism | What it is | Source |
| --- | --- | --- |
| `Color` from `expo-router` | Type-safe `PlatformColor` wrapper. `Color.android.material.*` = static MD3 light/dark roles. `Color.android.dynamic.*` = wallpaper Material You on Android 12+ (API 31+). Same role set as [M3 color roles](https://m3.material.io/styles/color/roles). Call `useColorScheme()` so theme changes re-render (required under React Compiler). | [Expo Router Color](https://docs.expo.dev/router/reference/color/) |
| `@expo/ui` palette | Wallpaper / seed / baseline palettes as `#RRGGBBAA` strings; themes Compose `<Host>` children. | [Material Colors](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/colors/) |
| `useColorScheme` / `Appearance` | Light vs dark. Expo `userInterfaceStyle` (default `automatic` on new templates). Dev builds need `expo-system-ui` or Android ignores the config. | [Color themes](https://docs.expo.dev/develop/user-interface/color-themes/) |
| `PlatformColor` | RN primitive for `?attr/` and `@android:color/` (and iOS UIColors). | [PlatformColor](https://reactnative.dev/docs/platformcolor) |

System chrome (not MD3 widgets, but part of an Android MD3 app):

- [`expo-status-bar`](https://docs.expo.dev/develop/user-interface/system-bars/) — status bar style; template default `auto` follows color scheme.
- [`expo-navigation-bar`](https://docs.expo.dev/versions/latest/sdk/navigation-bar/) — Android system nav/gesture bar visibility and button style (`auto` / `light` / `dark`). Distinct from Compose `NavigationBar`.
- Edge-to-edge: content draws behind system bars; use safe areas ([System bars](https://docs.expo.dev/develop/user-interface/system-bars/)).

Themed adaptive icons (Android 13+) are configured on the splash/icon guide; that is OS chrome, not in-app MD3.

## Navigation chrome

Several layers, none of which is “Google’s RN navigator”:

**1. Expo Router Native Tabs** (alpha, SDK 54+) — [docs](https://docs.expo.dev/router/advanced/native-tabs/). Uses the *system* tab bar. On Android, icons via `md` = Material Symbols. Known limitation: “On Android, there is a limitation of having a maximum of 5 tabs in the tab bar. This restriction comes from the platform's Material Tabs component.” Not a drop-in for JS tabs; nest a Stack inside a tab for headers. Cannot nest native tabs. All tab screens mount eagerly.

**2. Expo UI Compose `NavigationBar`** — Material 3 destination bar as a *widget*. Selection is React state (`selected` + `onClick`). It is not Expo Router / React Navigation. You wire it to routing yourself ([NavigationBar](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/navigationbar/)).

**3. React Navigation 7** — [Upgrade notes](https://reactnavigation.org/docs/upgrading-from-6.x/): “Various UI elements now follow Material Design 3 guidelines” (non-iOS header, drawer, material top tabs). Themes are a small token set (`primary`, `background`, `card`, `text`, `border`, `notification` + `fonts`), not the full MD3 role table ([Themes](https://reactnavigation.org/docs/themes/)). `@react-navigation/material-bottom-tabs` **moved to Paper**: import `createMaterialBottomTabNavigator` from `react-native-paper/react-navigation`. Paper 5.14+ **deprecates** that helper in favor of `@react-navigation/bottom-tabs` 7.x + `BottomNavigation.Bar` ([Paper bottom navigation](https://oss.callstack.com/react-native-paper/docs/guides/bottom-navigation), [BottomNavigation.Bar](https://oss.callstack.com/react-native-paper/docs/components/BottomNavigation/BottomNavigationBar)).

**4. JS Bottom Tabs `tabBarVariant: 'material'`** — Material styling only when `tabBarPosition` is `left` or `right` (rail/sidebar), not the bottom phone bar ([React Navigation bottom tabs](https://reactnavigation.org/docs/bottom-tab-navigator/), [Expo JS tabs](https://docs.expo.dev/router/advanced/tabs/)).

**5. Material Top Tabs** — still first-party React Navigation; “material-design themed tab bar on the top.” RN 7 no longer requires a separate `react-native-tab-view` install ([upgrade guide](https://reactnavigation.org/docs/upgrading-from-6.x/)).

Practical Android MD3 chrome for an Expo Router app: NativeTabs (system Material Tabs, ≤5 destinations) *or* JS tabs + Paper `BottomNavigation.Bar`; `adaptNavigationTheme` if Paper owns color; `expo-status-bar` + `expo-navigation-bar` for system bars; Compose `NavigationBar` only if the tab strip itself is an `@expo/ui` widget.

## What you give up: shadcn/Tailwind web vs MD3 mobile

shadcn/ui is a **web** component copy system. Official [installation](https://ui.shadcn.com/docs/installation) scaffolds Next, Vite, TanStack Start, React Router, and Astro (`npx shadcn@latest init -t [framework]`); Laravel is `laravel new` then `init`. There is no Expo or React Native template.

Implications that follow from those owners’ docs, not from blogs:

- shadcn components assume React DOM + Tailwind (and typically Radix/Base UI). React Native views are not DOM nodes and are not styled with that CSS pipeline. Expo’s first-party Tailwind path ([`expo-tailwind-setup`](https://docs.expo.dev/skills/)) is NativeWind/react-native-css for *styling RN views*, not a shadcn port.
- Paper’s own pitch is “aligned across iOS, Android, and React Native Web from one codebase” — that is Paper-on-web, the inverse of shadcn-on-native.
- Expo Universal `@expo/ui` shares a *native-control* API across Android/iOS/web. On web those controls are JS (`react-dom` / `react-native-web`), not shadcn, and on iOS they are SwiftUI. Using it as the “one UI kit” would replace shadcn on web, not reuse it.
- Expo DOM components run web code in a WebView on native ([`expo-dom`](https://docs.expo.dev/skills/)). That is incremental *web-inside-native*, not MD3, and not a shared design system.

**Shareable on the rejected RN path:** TypeScript domain models, Effect programs, validation, API clients, TanStack Query keys/functions, and other non-view utilities. **Current Cras does not share this TypeScript code with Kotlin Android; it shares the canonical JSON Schema contract and golden examples.**

**Not shareable as components on the rejected RN path:** shadcn primitives, Tailwind class strings, Paper components, and Compose `@expo/ui` trees. In current Cras, the entire UI and client implementation fork by platform; only the domain contract is shared.

**You give up:** one visual language, one component library, one theming token file that drives both surfaces, pixel-identical screens, and “write the Inbox row once.” You keep a brand *seed color* only if you generate two palettes (Material Theme Builder / Paper scheme JSON / Compose `seedColor` vs shadcn CSS variables) and maintain them separately.

## How to choose (facts, not a build)

| Need | Supported choice |
| --- | --- |
| Closest to official Android MD3 / Material You widgets | `@expo/ui/jetpack-compose` inside `<Host>` |
| MD3 on ordinary RN screens + Paper/RN Navigation | `react-native-paper` v5+ (`PaperProvider`, default MD3) |
| Wallpaper colors on RN `View`s without a kit | `Color.android.dynamic.*` (Expo Router) + `useColorScheme()` |
| System tab bar that is actually Material Tabs | Expo Router `NativeTabs` (`md` icons; max 5 on Android; alpha) |
| JS tab bar that looks MD3 | RN 7 bottom tabs + Paper `BottomNavigation.Bar` |
| MD2 by mistake | Paper `{ version: 2 }` or pre-v5 Paper — do not use if the requirement is MD3 |
| One component tree for web shadcn + Android MD3 | Not offered by any first-party kit above |

Cras uses shadcn + Tailwind on web and Jetpack Compose + Material Design 3 on Android. The docs above support **two client/UI stacks** over a **shared JSON contract**, not shared TypeScript code or a universal component layer.

## Sources

- [Material Design 3](https://m3.material.io/)
- [Develop with Material Design 3](https://m3.material.io/develop)
- [Material Web](https://m3.material.io/develop/web)
- [Dynamic color (user-generated source)](https://m3.material.io/styles/color/dynamic/user-generated-source)
- [Color roles](https://m3.material.io/styles/color/roles)
- [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) (updated 2026-08-14)
- [React Native Improving UX](https://reactnative.dev/docs/improvingux)
- [React Native PlatformColor](https://reactnative.dev/docs/platformcolor)
- [Expo UI](https://docs.expo.dev/versions/latest/sdk/ui/)
- [Expo UI Jetpack Compose](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/)
- [Expo UI Host](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/host/)
- [Expo UI Button](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/button/)
- [Expo UI NavigationBar](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/navigationbar/)
- [Expo UI Material Colors](https://docs.expo.dev/versions/latest/sdk/ui/jetpack-compose/colors/)
- [Expo UI Universal](https://docs.expo.dev/versions/latest/sdk/ui/universal/)
- [Expo Router Color](https://docs.expo.dev/router/reference/color/)
- [Expo Router Native tabs](https://docs.expo.dev/router/advanced/native-tabs/)
- [Expo Router JavaScript tabs](https://docs.expo.dev/router/advanced/tabs/)
- [Expo color themes](https://docs.expo.dev/develop/user-interface/color-themes/)
- [Expo system bars](https://docs.expo.dev/develop/user-interface/system-bars/)
- [expo-navigation-bar](https://docs.expo.dev/versions/latest/sdk/navigation-bar/)
- [expo-system-ui](https://docs.expo.dev/versions/latest/sdk/system-ui/)
- [Expo Skills](https://docs.expo.dev/skills/)
- [React Native Paper](https://oss.callstack.com/react-native-paper/)
- [Paper Getting Started](https://oss.callstack.com/react-native-paper/docs/guides/getting-started)
- [Paper Theming](https://oss.callstack.com/react-native-paper/docs/guides/theming)
- [Paper + React Navigation bottom nav](https://oss.callstack.com/react-native-paper/docs/guides/bottom-navigation)
- [Paper BottomNavigation.Bar](https://oss.callstack.com/react-native-paper/docs/components/BottomNavigation/BottomNavigationBar)
- [callstack/react-native-paper](https://github.com/callstack/react-native-paper)
- [React Navigation 7 upgrade](https://reactnavigation.org/docs/upgrading-from-6.x/)
- [React Navigation Themes](https://reactnavigation.org/docs/themes/)
- [React Navigation Bottom Tabs](https://reactnavigation.org/docs/bottom-tab-navigator/)
- [shadcn/ui Installation](https://ui.shadcn.com/docs/installation)
