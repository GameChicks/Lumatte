#!/bin/sh
set -e
# Film models live in the Android module. Reuse the exact same CC-BY-SA / GPL
# assets instead of duplicating them in Git. A local checkout can point
# LUMATTE_FILM_ASSETS at a staged copy (see ios/README.md).
SOURCE="${LUMATTE_FILM_ASSETS:-$SRCROOT/../core/media/src/main/assets/film}"
if [ ! -d "$SOURCE" ]; then
  SOURCE="$SRCROOT/../.film-assets/film"
fi
if [ ! -d "$SOURCE" ]; then
  echo "error: film assets not found. Set LUMATTE_FILM_ASSETS or place them in .film-assets/film" >&2
  exit 1
fi
DEST="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/Films"
mkdir -p "$DEST"
rsync -a --delete \
  --include 'catalogue.json' \
  --include 'index.json' \
  --include 'model.json' \
  --include '*.f32' \
  --include '*.txt' \
  --include '*/' \
  --exclude '*' \
  "$SOURCE/" "$DEST/"
