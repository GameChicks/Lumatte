# Kodak 5207 curve correction reference

Original profile bytes from Fotufilm commit
[`079844387659045a39ef4e5005191b0d77b81606`](https://github.com/DhaliwalX/fotufilm-engine/commit/079844387659045a39ef4e5005191b0d77b81606).
Copyright MUAStudio Inc.; profiles and derived data are licensed
[CC BY-SA 4.0](../licenses/CC-BY-SA-4.0.txt).
Original [licensing](../licenses/LICENSING.md) and [notice](../licenses/NOTICE) remain preserved.
`source-index.json` records the original SHA-256 digests. The earlier snapshot is unchanged.

Lumatte uses the normal-development curves only. The source files retain upstream push/pull
conditions for attribution and provenance; an exposure slider is not a chemical push/pull control.

- 400D: adopt both characteristic-curve groups, retain the original public-pack film LUT's
  density coordinates, and solve new printer midpoints for a neutral 18% reference.
- 250D: its new records describe formed dye amounts above a separate base. Project their
  spectral density onto the existing SpektraFilm dye basis using nonnegative least squares,
  using only wavelengths measured by both profiles. Keep the existing capture spectra,
  local DIR chemistry, 2383 receiver and spatial model; solve the receiver balance again.
  This conversion is an approximation (spectral-density RMS residual is recorded in the model),
  not a claim to reproduce upstream's Status M accuracy or its complete engine.

The reproducible adapter is [film_curve_correction.py](../../../tools/film_curve_correction.py).
