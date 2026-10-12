#!/usr/bin/env bash
# Builds dist/PoingSaveManager.jar and dist/PoingSaveManager.exe
# Requires: JDK 11+ (javac, jar) and mingw-w64 (x86_64-w64-mingw32-gcc / -windres), e.g. on Ubuntu/WSL:
#   sudo apt install openjdk-21-jdk-headless gcc-mingw-w64-x86-64
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out dist && mkdir -p out/classes dist

# 1) Java program -> jar
javac -encoding UTF-8 --release 11 -d out/classes src/PoingSaveManager.java
cp assets/icon.png out/classes/icon.png               # the one app icon (window, taskbar, tray)
cp -r lang out/classes/lang                           # translations (lang/*.properties + languages.txt)
printf 'Main-Class: PoingSaveManager\n' > out/manifest.txt
jar cfm dist/PoingSaveManager.jar out/manifest.txt -C out/classes .

# 2) Windows launcher (hotkeys, native file dialogs, exe icon)
( cd launcher
  x86_64-w64-mingw32-windres app.rc -O coff -o ../out/app.res.o
  x86_64-w64-mingw32-gcc -O2 -s -mwindows -static launcher.c ../out/app.res.o -o ../out/launcher.exe -lole32 -luuid -lshell32 -ladvapi32 )

# 3) exe = launcher + appended jar (single file)
cat out/launcher.exe dist/PoingSaveManager.jar > dist/PoingSaveManager.exe
echo "Done: dist/PoingSaveManager.exe, dist/PoingSaveManager.jar"
