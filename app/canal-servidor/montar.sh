#!/bin/bash
# Monta o servidor do canal (.jar com classes.dex) para o celular, igual ao build_without_gradle.sh do scrcpy.
# Roda no GitHub Actions (lá existe $ANDROID_HOME). Aqui no ambiente do Claude não há Android SDK.
#
# Uso: bash montar.sh <pasta-dos-fontes> <saida.jar>
#   ex.: bash app/canal-servidor/montar.sh app/canal-servidor/src app/src/main/assets/canal-servidor.jar
set -euo pipefail
SRC="$1"
SAIDA="$2"
: "${ANDROID_HOME:?falta ANDROID_HOME}"
# A mesma plataforma do app (compileSdk 34) se existir; senão a mais nova (precisa >= 31).
if [ -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then PLATAFORMA=android-34
else PLATAFORMA=$(ls "$ANDROID_HOME/platforms" | sort -V | tail -1); fi
FERRAMENTAS=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)         # ex.: 35.0.0
ANDROID_JAR="$ANDROID_HOME/platforms/$PLATAFORMA/android.jar"
D8="$ANDROID_HOME/build-tools/$FERRAMENTAS/d8"
LAMBDA="$ANDROID_HOME/build-tools/$FERRAMENTAS/core-lambda-stubs.jar"
TMP=$(mktemp -d)
mkdir -p "$TMP/classes"
echo "servidor do canal: $PLATAFORMA, build-tools $FERRAMENTAS"
javac -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -cp "$LAMBDA" -source 1.8 -target 1.8 -nowarn \
    -d "$TMP/classes" $(find "$SRC" -name "*.java")
(cd "$TMP/classes" && "$D8" --lib "$ANDROID_JAR" --min-api 31 --output "$TMP/classes.zip" $(find . -name "*.class"))
mkdir -p "$(dirname "$SAIDA")"
mv "$TMP/classes.zip" "$SAIDA"      # zip com classes.dex: o app_process aceita como CLASSPATH
rm -rf "$TMP"
ls -l "$SAIDA"
