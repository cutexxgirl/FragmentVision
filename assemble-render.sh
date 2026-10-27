#!/bin/sh
set -eu
cd "$(dirname "$0")"
cat mods/render-parts/FragmentRender.zip.00* > FragmentRender.zip
unzip -o FragmentRender.zip -d mods
