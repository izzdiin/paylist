#!/bin/bash
# Build Paylist di laptopgrt (Ubuntu, user warganet, TANPA sudo):
# JDK 17 (Temurin tarball), Android cmdline-tools, Gradle 8.9 — semua di $HOME.
set -e
cd ~

extract_zip() { # $1=zip $2=dest
  mkdir -p "$2"
  if command -v unzip >/dev/null 2>&1; then unzip -q -o "$1" -d "$2";
  else python3 -m zipfile -e "$1" "$2"; fi
}

if [ ! -x ~/jdk17/bin/java ]; then
  echo "== unduh JDK 17 =="
  curl -sL -o /tmp/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p ~/jdk17
  tar xzf /tmp/jdk17.tar.gz -C ~/jdk17 --strip-components=1
fi
export JAVA_HOME=~/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
java -version

if [ ! -x ~/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "== unduh Android cmdline-tools =="
  curl -sL -o /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf /tmp/cmdtools-x
  extract_zip /tmp/cmdtools.zip /tmp/cmdtools-x
  mkdir -p ~/android-sdk/cmdline-tools/latest
  cp -r /tmp/cmdtools-x/cmdline-tools/. ~/android-sdk/cmdline-tools/latest/
fi
export ANDROID_HOME=~/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "== sdkmanager: lisensi + komponen =="
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

if [ ! -x ~/gradle-8.9/bin/gradle ]; then
  echo "== unduh Gradle 8.9 =="
  curl -sL -o /tmp/gradle89.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
  extract_zip /tmp/gradle89.zip ~
fi

echo "== build assembleDebug =="
cd ~/paylist-build/paylist
# RAM laptopgrt hanya 2 GB: batasi heap Gradle/Kotlin agar tidak swap berat.
~/gradle-8.9/bin/gradle assembleDebug --no-daemon --console=plain -Dorg.gradle.jvmargs="-Xmx1024m" -Dkotlin.daemon.jvm.options="-Xmx512m"
echo "== hasil =="
ls -la app/build/outputs/apk/debug/
