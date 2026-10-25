# Stock-specific film edge reference

Pinned source: [Fotufilm e15b09815a83b0e00ba9381b65d31d253667a1b1](https://github.com/DhaliwalX/fotufilm-engine/tree/e15b09815a83b0e00ba9381b65d31d253667a1b1).
`PrintFrame.swift` and `print-frame-model.md` retain the original source bytes.
`source-index.json` records original profile and source SHA-256 digests. The
unaltered profiles are retained in the ignored `tools/.cache/film-edges/profiles/`
cache and are reproducibly read from the pinned Git objects by
[`produce_film_edge_metadata.py`](../../../tools/produce_film_edge_metadata.py).

The bundled extracted records, source attribution and original Apache-2.0 / CC
BY-SA 4.0 licences are in `core/media/src/main/assets/frames/stock-edges/`.
Profile records are by Fotufilm / MUAStudio Inc. The Kotlin DX encoder adapts
`FilmEdgePrinting` and `DXEdgeCode` from the preserved Apache-2.0 source.

Lumatte presents a 135-style strip around the completed colour photograph.
The full photograph is preserved at its requested size and aspect. Rails and
holes use a consistent physical scale; portrait rails turn with the picture,
and wide pictures extend the strip without stretching holes or lettering.
This differs deliberately from upstream's aperture crop and spectral negative
delivery. Rebate colours, vector lettering and BH hole outlines are graphical
approximations, not a measured scan of an individual roll.

Known stock records only: no DX is borrowed between Portra speeds, Velvia
speeds or Vision3 variants. Undocumented codes remain absent. Representative
frame numbers belong to this simulated strip, not the original camera exposure.
