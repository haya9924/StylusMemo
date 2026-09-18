# stylus-memo CLI

Desktop exporter for stylus-memo notes. Reads a notes directory (see the Android
app for the on-device format), renders pages to PDF or JPEG, and can generate
sample notes for testing.

The program name is `stylus-export`. It is written in Kotlin/JVM (Java 17+) and
packages cleanly for both Windows and Linux — see [Distribution](#distribution).

## Usage

```
stylus-export sample <target-dir> [--dir-name <name>] [--title <title>]
stylus-export list   <notes-dir> [--json]
stylus-export export <notes-dir> <note-id> --pdf|--jpeg --out <file> [--dpi <dpi>] [--page <n>]
```

### sample

Creates a new sample note (two pages: grid + ruled, a text box, an image, and
several handwritten-style strokes) under `target-dir`.

```
stylus-export sample ~/notes
```

### list

Lists note ids and titles found under `notes-dir`.

```
stylus-export list ~/notes
```

### export

Renders one note to a single multi-page PDF, or one page to JPEG.

```
stylus-export export ~/notes n-sample-12345678 --pdf --out note.pdf
stylus-export export ~/notes n-sample-12345678 --jpeg --out page-0.jpg --page 0
```

`--dpi` controls raster resolution (default 300). For JPEG export, `--page`
selects which page to render (default 0).

## Distribution

Four packaging options are provided. All run the same code; pick whichever fits
the target machine. Everything is built with the repository's Gradle wrapper
using a JDK 17+:

```
JAVA_HOME=/path/to/jdk-17 ./gradlew :cli:build :cli:test
```

### 1. Portable zip (Windows + Linux) — no installation

```
./gradlew :cli:distZip
```

Produces `cli/build/distributions/stylus-export.zip` containing the app plus
launcher scripts for both platforms:

- Linux:   `stylus-export/bin/stylus-export`
- Windows: `stylus-export/bin/stylus-export.bat`

Requires a JRE 17+ on the target machine.

### 2. Single-file jar (Windows + Linux)

```
./gradlew :cli:shadowJar
```

Produces `cli/build/libs/stylus-export-all.jar`, runnable on any machine with a
JRE 17+:

```
java -jar stylus-export-all.jar sample ~/notes
```

### 3. Self-contained native app (Linux) — no Java needed

```
./gradlew :cli:jpackageImage        # build/dist/stylus-export/ (launcher folder)
./gradlew :cli:jpackageDeb          # build/dist/stylus-export_1.0.0_amd64.deb
```

jpackage ships a trimmed JRE inside the app, so end users do not need Java.
Install the `.deb` with `sudo apt install ./stylus-export_1.0.0_amd64.deb`
(binary at `/opt/stylus-export/bin/stylus-export`).

### 4. Windows MSI / EXE installer

jpackage must run on the target OS, so build the Windows installer **on a
Windows machine** with a JDK 17+:

```
gradlew :cli:jpackageMsi     # build/dist/stylus-export-1.0.0.msi
gradlew :cli:jpackageExe     # build/dist/stylus-export-1.0.0.exe
```

The resulting installer is self-contained (no Java needed on the target).

### Console encoding

All launchers set `-Dfile.encoding=UTF-8`, so Japanese note titles render
correctly on Windows consoles too.

## Format

Each note is a directory named by its id:

```
<notes-dir>/<note-id>/
  note.json     # Note, PageData, TextBox, ImageBox, BackgroundSpec serialized
  page-0.bin    # gzip-compressed CodedStrokeInputBatch per page
  page-1.bin
  assets/       # images referenced by ImageBox.assetName
```

### note.json

Kotlinx-serialization JSON of the app's `Note` model. Stroke data lives in the
`page-N.bin` files (the app keeps `PageData.strokes` empty to avoid duplicating
it).

### page-N.bin

Header is a little-endian `Int` stroke count, then per stroke:
`Int colorArgb`, `Float sizeMm`, `Float epsilon`, `Int payloadLength`,
`payload`. Each payload is a gzip-compressed `CodedStrokeInputBatch` protobuf
matching the Android Ink encoder: numeric runs (field 1 = x, 2 = y, 3 = t,
4 = pressure) each carrying packed/unpacked zig-zag `sint32` deltas plus
optional `float` scale/offset.
