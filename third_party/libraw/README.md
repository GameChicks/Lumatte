# LibRaw source dependency

Pinned upstream: [LibRaw 0.22.2](https://github.com/LibRaw/LibRaw/tree/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f), commit `b93f6e45c194f5df9b02a43b1af9a54b4f41f33f`. `upstream/` contains original `src/`, `internal/`, `libraw/`, copyright, both license texts, README and source-list Makefile. No upstream files have been modified.

This project selects the LGPL 2.1 licensing option offered in upstream COPYRIGHT. Preserve `upstream/COPYRIGHT`, `LICENSE.LGPL`, `LICENSE.CDDL` and embedded source notices. The library's other embedded copyright notices remain in the original sources. This dependency is separate from recovered APK assets and application code.

`source-manifest.json` records source paths, SHA-256 and commit. `sources.cmake` copies the library source list from pinned `Makefile.am`; examples, benchmark programs, upstream build scripts and nested Git metadata are not run or included in the Android build. `tools/vendor_libraw.py` reproduces the copy from a clean matching checkout at `tools/.cache/libraw-0.22.2` and refuses changed destination files.

Android integration is in `native/raw-preview/`. It builds the original library and a JNI bridge exposing only JPEG preview extraction. Keeping upstream decoder sources for link dependencies is not authorization to call sensor unpacking or RAW processing. No library sample, test, compiler, CMake configure or Gradle command has been executed as part of this integration.
