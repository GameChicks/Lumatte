# JPEG-only RAW preview bridge

The application stages an input copy in its private import directory. The JNI entry opens that copy with LibRaw, enumerates at most eight advertised previews, filters `LIBRAW_INTERNAL_THUMBNAIL_JPEG` **before** `unpack_thumb_ex`, then writes compressed JPEG bytes in 64 KB chunks. It never calls `unpack()`, `raw2image()`, `dcraw_process()` or a bitmap preview loader. No bitmap-to-JPEG conversion or pixel recompression occurs here.

Fresh LibRaw instances isolate candidates after parser errors. There is one compressed thumbnail in native memory at a time; each instance is destroyed before the next candidate. Compressed preview bytes are limited to 64 MiB and embedded profile parsing to 16 MiB. These are read limits, not proof of a memory ceiling for every parser or Android decoding. At most eight candidate files may temporarily exist; Kotlin selects by actual Android JPEG dimensions and deletes redundant files. RAW sensor resolution does not become the export resolution.

The callback checks the Kotlin import/transfer job. Checks occur around native stages, in LibRaw progress callbacks when provided and between write chunks. A single LibRaw metadata read or thumbnail read can still delay cancellation; immediate interruptibility is not claimed. The import's failure path removes only its own private staging directory.

CMake uses fixed upstream source paths; it does not fetch dependencies or run upstream examples. Optional JPEG decoding, OpenMP, RawSpeed, DNG SDK, external color management and optional demosaic packs are not enabled. The original library contains decoder implementations, but the only JNI surface is the JPEG preview operation.

NDK `28.2.13676358`, CMake `3.22.1`, C++20, `arm64-v8a` and `x86_64` are declared in Gradle. The shared JNI target requests 16 KiB ELF alignment; libc++ comes from the selected NDK. Source configuration is not a successful build, APK alignment proof or device validation. See [integration record](../../docs/engineering/raw-preview.md).
