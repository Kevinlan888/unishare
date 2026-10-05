# Windows UI Design QA

- Visual source: `.superpowers/sdd/2026-10-05-windows-ui/windows-ui-reference.png`
- Implementation: `windows/src/UniShare.App`
- Primary evidence: `.superpowers/sdd/2026-10-05-windows-ui/task-9-light-en-1536.png`
- Additional evidence: `task-9-dark-zh-1536.png`, `task-9-light-en-960.png`, `task-9-offer-960.png`, and `task-9-failed-960.png` in the same evidence directory
- Capture method: the running WinUI client tree rendered with `RenderTargetBitmap`; each sidecar records `nativeDllLoaded=False`

## Compared states

| Evidence | Viewport / density | Theme / locale | State |
| --- | --- | --- | --- |
| Reference | 1536 x 1024 source bitmap | Light / English | Selected online device, populated activity flow |
| `task-9-light-en-1536.png` | 1520 x 1015 client tree, 96 DPI, scale 1.0 | Light / English | Selected online device, populated activity flow |
| `task-9-dark-zh-1536.png` | 1520 x 1015 client tree, 96 DPI, scale 1.0 | Dark / Chinese | Selected online device, populated activity flow |
| `task-9-light-en-960.png` | 944 x 631 client tree, 96 DPI, scale 1.0 | Light / English | Minimum-window compact layout |
| `task-9-offer-960.png` | 944 x 631 client tree, 96 DPI, scale 1.0 | Light / English | Incoming file approval |
| `task-9-failed-960.png` | 944 x 631 client tree, 96 DPI, scale 1.0 | Light / English | Failed outgoing file with retry |

The implementation captures exclude the OS border and caption buttons, so outer-window rounding and native title-bar chrome were not compared. Full-frame and focused evidence are the wide shell capture plus the two compact decision-state captures.

## Iteration history

1. Initial implementation established the real three-pane WinUI shell, deterministic demo data, responsive compact rail, bilingual resources, light/dark resources, and actual client-tree capture.
2. Visual comparison identified P2 differences in wide-pane proportions, transfer-card height, device online indication, recent-row timestamps, and overly prominent completed-state labels.
3. The implementation was revised to 320 / flexible center / 320 panes, an approximately 118 px transfer card with inline Cancel, a green hero status dot, right-aligned recent timestamps, and accessible-but-visually-suppressed terminal labels.
4. Final comparison found no remaining actionable P0, P1, or P2 visual-fidelity issue. At 960 x 640, tall activity content continues in the timeline ScrollViewer while the composer and decision controls remain visible.
5. Independent code review found and closed retained card subscriptions, restricted clipboard-origin presentation to local outgoing items, and bound offline row visuals and accessible status to device presence. These changes did not alter the approved layout.

## Findings

- P0: none.
- P1: none.
- P2: none remaining after iteration 2.
- P3: the implementation uses Segoe Fluent device glyphs instead of the reference's custom phone artwork; this is intentional until brand art is separately approved.
- P3: folder, link-preview, and emoji affordances from the source image are intentionally omitted because they are outside the approved product scope.
- Verification blocker: the Windows native automation pipe was unavailable after retry/reset (`os error 2`). Real keyboard traversal and focus rings, screen-reader announcements, Windows high-contrast mode, and 150% / 200% OS scaling were therefore not observed. Static resource/policy tests and 100% light/dark captures do not substitute for those checks.

## Result

Visual fidelity at the captured sizes and states is accepted. The mandatory physical accessibility and scaling smoke must be completed in the Task 11 Windows matrix before release acceptance.

final result: blocked
