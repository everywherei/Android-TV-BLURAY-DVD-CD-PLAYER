#!/bin/sh
# Compila Kotlin/JVM sin Gradle usando el compilador que viene dentro de la distribución de Gradle.
# Uso: tools/kotlinc.sh <dir_salida> <fuentes...>
L=${GRADLE_LIB:-/opt/gradle/lib}
CP="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
OUT=$1; shift
exec java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$L/kotlin-stdlib-2.0.21.jar" -d "$OUT" "$@"
