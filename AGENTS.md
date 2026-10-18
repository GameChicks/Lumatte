# Project instructions

- Android application: Pineframe / Lumix Film Mirror. Use Git; separate source, model-production inputs, documentation and generated artifacts.
- Do not run unit, regression, smoke, device, benchmark or other tests unless explicitly requested. Do not build/package an APK or AAB unless explicitly requested. No automatic test or packaging hooks.
- Put delivered packages and attachments in `dist/`. Keep Windows SDK/JDK/Gradle/AVD and writable emulator data on D: and outside the source tree; local paths belong in ignored configuration.
- Preserve existing application ID and user storage contracts. Do not read/import old app databases, passwords or unrelated signing identities.
- RAW means embedded JPEG extraction only; never demosaic or develop sensor data. Default JPEG dimensions are 100% of effective input; output size and compression quality are separate controls.
- Batch film editing supports independent images or inheriting the first image. The entire batch shares one export frame preset and its settings. Development/reveal animation plays once per batch with a skip action.
- Only CineStill 800T / 400D use the dedicated Fotufilm physical halation path with the JPEG highlight look. Other films preserve their SpektraFilm defaults and bounded sampling. Export frames are added after film rendering; do not add border/watermark detection to halation.
- Camera scope includes connection, browsing and transfer, with playback initialization. Exclude live view, shutter/video control, exposure/white-balance/focus control, parameter copying and remote power.
- Bind camera HTTP to the selected Wi-Fi Network and private/link-local addresses. Internet/LUT clients must enforce HTTPS independently of camera cleartext policy.
- Follow system light/dark mode. Keep UI-independent feature interfaces for frontend work. Returning from film detail preserves carousel position without immediately reopening it.
- Preserve original third-party bytes, source hashes, notices and licenses. User fonts/LUTs and recovered artwork currently have pending redistribution records; keep them and label that status until the maintainer supplies authorization. Do not substitute them without instruction.
- Only report source, compilation, numeric/image/runtime, physical-device and packaging verification at their actual evidence level.
