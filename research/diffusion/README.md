# Mobile black-mist diffusion revision — 2026-11-09

Problem reported: OFF / 1/8 / 1/4 / 1/2 appeared visually identical. Source review found grade values in both display/effect recipe keys and the CineStill spatial key; no missing grade-key field was identified. Existing diffusion intercepted the entire image and returned only 55% of that share, so low grades largely dimmed the image instead of creating a clear highlight glow. This is source reasoning, not a measured device result.

Reference reading (no source, preset or asset bytes imported):
- [Shitsukan Diffusion Kit](https://github.com/50asa/shitsukan-diffusion-kit): scene-linear multi-scale bloom, highlight desaturation, dark-region suppression. Its author explicitly calls it educational and not filter calibration.
- [Unity PostProcessing bloom documentation](https://github.com/Unity-Technologies/PostProcessing/blob/v2/Documentation~/Bloom.md): soft knee and adapting highlight thresholds for LDR inputs.

Implementation is independent and continues using the existing bounded three-scale Gaussian helper. Extract the smooth highlight seed before reducing the source, retaining small bright pixels. Keep a small midtone contribution and slight highlight desaturation. Subtract the intercepted seed from direct light, return 96% of it through the weighted PSF, and retain unscattered dark pixels. Grade response is an artistic approximation, not a calibrated branded-filter strength. Lens-field long edge stays at most 512 in ordinary and CineStill paths; tiny preview inputs scale the PSF accordingly. CPU and GLES use the same seed and composition equations. Ordinary emulsion halation and grain parameters are unchanged; diffusion still precedes development. CineStill continues using the dedicated SpektraLab response/halation path.

Validation: this change has not been numerically, visually or physically tested. Native arm64-v8a / x86_64 and application Kotlin compilation passed via the explicit CompileOnly script; this establishes only buildability. Do not infer phone appearance, preview/export equivalence, or measured filter accuracy from compilation.
