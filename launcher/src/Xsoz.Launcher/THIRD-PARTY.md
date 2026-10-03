# Third-party components

XsozClient ships two font files and nothing else. Both are under the SIL Open Font License 1.1,
which permits bundling and redistribution provided the licence text travels with the font. Both
licence texts are in `Licensing/` and are copied to the output directory on publish.

## Fonts

### Monocraft

- **Role:** headline / wordmark / the PLAY button / version names / module names / badge glyphs
- **Version:** v4.2.1 (`Monocraft.ttf` plus the Light, Regular, SemiBold and Bold weights)
- **Copyright:** Copyright (c) 2022, Idrees Hassan (https://github.com/IdreesInc/Monocraft)
- **Licence:** SIL Open Font License 1.1 — full text in `Licensing/OFL-Monocraft.txt`
- **Source:** https://github.com/IdreesInc/Monocraft/releases/tag/v4.2.1
- **Modified:** No. The files are bundled byte-for-byte as published. Monocraft is not renamed.

### Inter

- **Role:** body copy, descriptions, the log pane
- **Version:** 4.1 (`Inter-Regular`, `Inter-Medium`, `Inter-SemiBold`, `Inter-Bold`)
- **Copyright:** Copyright (c) 2016 The Inter Project Authors (https://github.com/rsms/inter)
- **Licence:** SIL Open Font License 1.1 — full text in `Licensing/OFL-Inter.txt`
- **Source:** https://github.com/rsms/inter/releases/tag/v4.1
- **Modified:** No.

## Runtime

The published executable is self-contained: it bundles the .NET 10 runtime and the WPF
(WindowsDesktop) runtime, both MIT-licensed, from the .NET SDK. No other native component is
redistributed.

## What is deliberately absent

**No Mojang or Microsoft asset is present in this repository, in any build output, or in any
release artifact.** Specifically: no Minecraft client jar, no assets or asset index, no textures,
no sounds, no fonts or typeface, and no launcher code. Those are EULA/MUG-governed, and the
governing rule for this project is *use only, never distribute*. The launcher downloads game files
from Mojang's own hosts onto the user's own disk when an instance is created; it does not mirror,
rehost, or bundle them.

**No third-party mod is bundled.** Dependencies that are not permissively licensed — or that carry
a non-compete, noncommercial, sublicensing, or source-offer condition — are fetched at install time
from a signed manifest that this project hosts, so that the *user* is the party downloading them.
The launcher presents a module list, never a mods folder or a jar name.

**No font or typeface derived from Mojang's Minecraft is used, redistributed, or referenced.**
