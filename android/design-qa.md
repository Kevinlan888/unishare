# Task 8 Share Home Design QA

## Evidence

- Source visual truth: `C:\Users\kevin\AppData\Local\Temp\codex-clipboard-PsXme7.png`, first phone crop at `(11, 12)`, `372 × 1000` px.
- Rendered implementation: `app/build/reports/task8-home-emulator.png`, captured from `Medium_Phone_API_37.0` at `1080 × 2400` px and `420 dpi` (approximately `411 × 914` dp).
- Normalized comparison: `app/build/reports/task8-home-comparison.png`. The implementation was downsampled to the source crop width (`372 × 827` px) without changing aspect ratio and placed beside the `372 × 1000` source crop.
- State: English, light theme, online local device, two representative recent transfers, and four nearby devices. The shorter Android viewport deliberately shows two recent rows so the nearby section remains visible; all remaining content is vertically scrollable.
- Native interaction evidence: 12 parameterized Compose tests passed on the API 37 emulator, covering English and Simplified Chinese, selected bottom navigation, refresh, FAB/tab/device routes, nested device-ID restoration, loading/error/empty states, grouped-row geometry, 48 dp actions, and 200% font scale.
- Browser console checks: not applicable to this native Android implementation. The connected instrumentation run completed without a process crash or failed test.

## Full-view comparison

The normalized side-by-side image confirms the same major composition: pale blue-gray background, bold Share header with search and overflow actions, rounded white self-device card, Recent and Nearby Devices sections with grouped rows and separators, blue circular FAB, and a rounded white four-item bottom bar. Android system bars remain platform-owned and are not recreated as app content.

## Focused comparison

The normalized `372` px-wide comparison keeps typography, card edges, icons, separators, status dots, FAB, and bottom navigation readable, so separate focused crops were not required. The original `1080 × 2400` capture was also inspected to verify edge quality and text rendering.

## Required fidelity surfaces

- Fonts and typography: Android system typography closely matches the reference's neutral sans-serif. Title, section, device-name, status, and navigation weights preserve the same hierarchy; long content truncates or wraps without hiding actions.
- Spacing and layout rhythm: horizontal margins, large card radii, grouped row rhythm, section gaps, FAB clearance, and bottom-bar placement match the reference direction. Differences in how much content is above the fold come from the source's unusually tall `372 × 1000` crop versus the emulator's approximately `411 × 914` dp viewport.
- Colors and tokens: the existing UniShare Material theme supplies the low-contrast blue-gray background, white surfaces, blue primary actions, slate secondary text, and green online state. The selected Share tab now uses blue icon/text with no default Material indicator pill.
- Image and icon fidelity: this screen needs no raster imagery. Platform and action graphics use the official Material icon library, as required; no emoji, handcrafted vectors, gradients, device chrome, or placeholders are present.
- Copy and content: visible English and Simplified Chinese text is resource-backed. Online state is always accompanied by text; trusted/unpaired status is intentionally more explicit than the visual reference to meet the product and accessibility contract.

## Comparison history

1. Initial capture found a P1 grouped-list defect: sibling rows were overlaid because `Surface` lays out multiple direct children as a box. A failing geometry test reproduced the issue; `GroupedSurface` now places children in a `Column`. The final comparison shows separated rows and dividers.
2. The post-layout capture found a P2 bottom-navigation mismatch: Material's default selected indicator produced a gray pill and slate selected icon. Navigation colors were changed to blue selected icon/text with a transparent indicator. The final comparison shows the corrected selected state.

## Findings

- No actionable P0, P1, or P2 differences remain.
- P3: Material outline platform icons are intentionally simpler than the detailed device illustrations in the mock. This follows the implementation contract to use a platform icon library and avoids raster or handcrafted vector substitutes.

## Implementation checklist

- [x] Group repeated card rows vertically with stable dividers.
- [x] Match blue selected bottom-navigation state.
- [x] Preserve app-owned content only and use Android insets.
- [x] Verify core routes, controls, localization, restoration, large text, and touch targets on an emulator.

## Follow-up polish

- Revisit platform-icon styling only if a future approved icon asset set replaces the required Material icons.

final result: passed
