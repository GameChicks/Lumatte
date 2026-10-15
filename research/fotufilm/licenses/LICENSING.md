# Licensing

Project-authored source, documentation, synthetic stock examples, print receiver
measurements, and artwork are governed by [LICENSE](LICENSE). Third-party components
retain their respective terms, recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
and accompanying file notices. Project-authored material is licensed under
Apache-2.0, except the 46 film profiles and the editor glyphs described below.

## Film profiles

The 46 runtime film profiles listed in the [release manifest](licenses/FILM-PROFILES.json)
in `Sources/FotufilmCore/Stocks/` are licensed under CC BY-SA 4.0. See the
[profile notice](licenses/FILM-PROFILES.txt) and [full licence](licenses/CC-BY-SA-4.0.txt).
You may use, modify, and redistribute them, including commercially, with attribution.
Credit the profiles to **Fotufilm** and link to **https://fotufilm.com**;
retain the MUAStudio Inc. copyright notice and identify any changes.
Distributed adaptations must use CC BY-SA 4.0 or an officially compatible licence.

This licence covers the listed profiles, including their rendering parameters and
spectral samples. Synthetic examples and engine code remain Apache-2.0. Using a
profile to render a photograph or video does not impose the profile licence or an
attribution requirement on that rendered work.

## Glyphs

The editor glyphs in `shared/Glyphs/sources/`, and the symbol templates and SVGs generated
from them in `shared/Glyphs/Glyphs.xcassets/` and `web/public/glyphs/`, are licensed under
CC BY-SA 4.0. See the [glyph notice](licenses/GLYPHS.txt) and [full licence](licenses/CC-BY-SA-4.0.txt).
You may use, modify, and redistribute them, including commercially, with attribution.
Credit the glyphs to **Fotufilm** and link to **https://fotufilm.com**; retain the
MUAStudio Inc. copyright notice and identify any changes. Distributed adaptations must use
CC BY-SA 4.0 or an officially compatible licence.

ShareAlike applies to changed glyphs. Showing the glyphs unchanged in an app, website or
document does not bring that work under the glyph licence. The generator
(`tools/build-glyphs.py`) and the manifest (`shared/Glyphs/glyphs.json`) remain Apache-2.0.

## Measured spectral data

The print media in `PrintPaperTables.swift` and the `*PaperSpectra.swift` /
`*PrintSpectra.swift` files are digitised by this project from the manufacturers'
publicly published datasheets: Kodak E-7020, E-4070, H-1-2383 and the 2393 curve
sheets, and Fujifilm AF3-0250U2 and the ETERNA-CP 3513DI brochure. The digitised
coordinates and the code that reads them are project-authored and Apache-2.0.

The underlying publications remain the property of their publishers and are not
redistributed here. Product names are the trademarks of their respective owners and
are used only to identify which material each measurement describes; see
**Trademarks** below. Nothing here is endorsed by or affiliated with Kodak or
Fujifilm.

One table is not ours: the xenon projector spectrum in `Illuminant.swift` is
transcribed from the colour-science library and carries that project's BSD 3-Clause
terms. Its copyright notice, conditions and disclaimer are reproduced in full in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Third-party material

Preserve upstream licence texts, copyright notices, and attribution when working
with third-party code or data. Check the applicable terms when distributing a
binary or a derived dataset. `SOURCE_ASSETS.json` records asset provenance and hashes.

## User-created packs

Users retain their rights in stock packs they author. Including a pack in a build
does not transfer those rights to Fotufilm.

## Trademarks

“Fotufilm” and “MUA Studio”, their logos, and the project's branding identify
official builds. No trademark permission is granted by the source or data licences.
Third-party names remain the trademarks of their respective owners.

## Contributions

Code contributions are provided under Apache-2.0. Contributions to the 46 film
profiles and to the glyphs are provided under CC BY-SA 4.0 unless explicitly agreed otherwise. Include only material
whose provenance and redistribution rights can be verified, and preserve all
required notices.
