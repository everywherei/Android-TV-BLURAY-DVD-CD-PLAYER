#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2025-2026 DiscMount contributors
# Compila el núcleo + tests sin Gradle, genera imágenes de prueba y ejecuta SelfTest.
set -e
cd "$(dirname "$0")/.."
W=${1:-$(mktemp -d)}
mkdir -p "$W/classes" "$W/images"
tools/kotlinc.sh "$W/classes" core/src/main/kotlin core/src/test/kotlin
python3 tools/gen_test_images.py "$W/images" >/dev/null
java -cp "$W/classes:/opt/gradle/lib/kotlin-stdlib-2.0.21.jar" dev.discmount.core.SelfTestKt "$W/images"
