# Figure Panel Builder

Figure Panel Builder is an ImageJ/Fiji plugin for building publication figure panels from a folder
of multi-channel microscopy images. It applies one locked display range per channel across all
compared images, supports representative subject selection from editable group/subject/section
metadata, and writes the files needed to reproduce the figure.

## What It Exports

- `figure.png`, `figure.tif`, and `figure.svg`
- Per-panel PNG files in `panels/`
- `manifest.csv` with source, calibration, display range, clipping, selection, and output fields
- `selection.csv` with per-subject selection evidence
- `methods.txt` and `README.txt` for the output folder

## Use In Fiji

Install the release JAR into Fiji's `plugins` folder, restart Fiji, then run
`Plugins > Figure Panel Builder`. The guided wizard supports folder loading, metadata labelling,
channel setup, representative selection, layout, annotation, and export.

For scripted use, run the plugin with explicit macro options, including one min and max range for
each channel and one pick for each group. Headless operation is available with `hide_display`.

## Java API

```java
FPBParameters params = FPBParameters.builder(folder)
        .channel(1, "DAPI", ChannelColour.BLUE, 120, 4200)
        .pick("Control", "S3")
        .outputFolder(output)
        .build();

FPBResult result = FPB.run(params);
FPB.write(result);
```

The API opens no dialogs, requires no active ImageJ image, and writes files only through explicit
export calls.

## Build

```sh
./mvnw clean package -Denforcer.skip=true
```

The project targets Java 8 bytecode for Fiji compatibility. The only compile-scope dependency is
`net.imagej:ij`; JUnit is test-scoped.
