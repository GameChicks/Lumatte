# Local integration changes

2026-10-11. Originals under `upstream/` remain unmodified.

- `native/film/CMakeLists.txt` declares a separate Android shared library and Vulkan shader compilation for a future user-authorized build. No upstream build or test scripts are invoked.
- Build-directory copies derive the pipeline RNG initializer from a stable per-photo seed and add an atomic task cancellation check at pipeline node boundaries. Pinned source anchors must exist; original files are retained.
- Android JNI converts mutable JPEG bitmaps between encoded sRGB bytes and linear RGB input, invokes the physical pipeline, then copies its RGBA16 result into the owned bitmap. Lens diffusion is a separate local implementation.
- The application requests the existing striped executor with calculated rows for large images; it does not change original physical profile data or silently lower final JPEG dimensions.
- A derived display-only film catalogue is extracted from profile metadata by `tools/generate_film_catalogue.py`, credited to Andrea Volpato and licensed CC BY-SA 4.0. Numerical profile arrays are read only by the native engine.

None of these adaptations have been compiled, rendered, measured or tested. No numerical parity or mobile performance claim is made.
