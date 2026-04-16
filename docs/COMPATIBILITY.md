# ForgeBook Compatibility Matrix

**Last verified:** YYYY-MM-DD on Minecraft 1.20.1 + Forge 47.4.18
**Tester:** (fill in when row is verified)
**Method:** see §Testing Protocol below

ForgeBook is a client+server mod; incompatibility most commonly shows up as overlapping GUI widgets in the inventory screen, where ForgeBook injects a toggle button at the right edge of the inventory frame. The mods below are community-popular and commonly installed alongside ForgeBook; this matrix records whether the "Ask ForgeBook" button and ChatScreen render cleanly in each combination.

## Matrix

| Mod | Version tested | GUI scale 1 | GUI scale 2 | Notes |
|-----|----------------|-------------|-------------|-------|
| Just Enough Items (JEI) | jei-1.20.1-forge-15.20.0.106 | [ ] pending | [ ] pending | — |
| Roughly Enough Items (REI) | RoughlyEnoughItems-12.0.687 | [ ] pending | [ ] pending | — |
| Sodium (Embeddium fork) | embeddium-0.3.30+mc1.20.1 | [ ] pending | [ ] pending | — |
| Iris (Oculus fork) | oculus-mc1.20.1-1.7.0 | [ ] pending | [ ] pending | — |
| Jade | Jade-1.20.1-forge-11.12.1 | [ ] pending | [ ] pending | — |
| Mouse Tweaks | MouseTweaks-forge-mc1.20-2.25 | [ ] pending | [ ] pending | — |
| Quark | Quark-4.0-460 | [ ] pending | [ ] pending | — |
| Inventory HUD+ | InventoryHUD.Forge-1.20.1-3.4.25 | [ ] pending | [ ] pending | — |

Legend: `✓` pass · `✗` fail (see Notes) · `[ ] pending` not yet tested

## Testing Protocol

For each mod in the matrix:

1. Fresh `./gradlew runClient` with a clean `run/mods/` folder (delete any stale jars first).
2. Drop the ForgeBook jar (`build/libs/forgebook-1.0.0.jar`) **and** the one compat target into `run/mods/`.
3. Launch.
4. Open the inventory — verify the "Ask ForgeBook" button appears to the right of the inventory at the `leftPos+imageWidth+4` offset.
5. Verify no vanilla or compat-mod widgets overlap the button.
6. Click the button — verify the ChatScreen opens with the inventory still visible beneath.
7. Change GUI scale via Options → Video Settings → GUI Scale to 1, repeat steps 4-6. Then to 2, repeat.
8. Close Minecraft.
9. Record result in the matrix above. Any overlap or failure becomes a "note" entry linking to a GitHub issue.

## Re-run Triggers

Re-run this protocol when:

- ForgeBook ships a UI-affecting change (new widget, panel tier, inventory-injected surface).
- Any compat target releases a major version bump.
- Users report a compatibility regression via GitHub issues.

## Contributing Matrix Updates

1. Run the protocol above for the affected mod(s).
2. Update the matrix row with the tested version string and result (`✓` or `✗`).
3. If failing, add a Notes entry describing the symptom and linking to a GitHub issue.
4. Update the `**Last verified:**` date header to the current date.
5. Submit a PR with the updated `docs/COMPATIBILITY.md`.
