# Figure Panel Builder

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21933265.svg)](https://doi.org/10.5281/zenodo.21933265)

Figure Panel Builder is an ImageJ/Fiji plugin for building publication figure panels from a folder
of multi-channel microscopy images. It applies one locked display range per channel across all
compared images, supports representative section selection from editable group/subject/section
metadata, and writes the files needed to reproduce the figure.

The guided workflow ranks animals with either the built-in full-resolution brightest-1% mean
or an exact numeric column from a CSV. A Figure Panel Builder metadata export can be extended with
that numeric column and reused directly, while the final pick is one individual section per group.
Quick Grid is a separate express route: it loads in the
background, derives one cohort range per channel, exports every discovered image, and does not claim
representative-section selection.

Choose Images also plots all channels and groups in one section-level comparison. Each x-axis tick is
a channel, values are z-normalized independently within that channel, and every dot is one section.
Groups retain distinct colours across channels, coloured bars show group means, and the dashed line
shows the overall z-score mean of zero. The chosen section is also shown at section level in its row
mini-plot. Guided exports include the same comparison as `Supporting files/group_quantification.png`
and an auditable `Supporting files/group_quantification.csv` containing raw section values, z scores,
group mean, SD, SEM, minimum and
maximum values. These summaries are descriptive and do not add an inferential test. Animal-level
averaging is still used only for representative-ranking suggestions.

For scale bars, embedded micron calibration is verified per image. The Layout step also accepts
per-image X/Y pixel sizes in µm/px; explicit values override embedded metadata and are recorded as
`user-entered` in `manifest.csv`. Java and macro callers key these values by the relative
`SourceImageId` shown in metadata and manifest files.

LIF containers are expanded into one logical image per Bio-Formats series. In the guided metadata
step, group, subject, and section labels are inferred from each individual series name; an explicit
condition token is preferred, otherwise the animal-name prefix becomes the group. The token roles
remain editable. Metadata and manifest IDs append `#series=N` so two series from the same LIF remain
distinct, and full-resolution export reopens the selected series explicitly.
Other multi-series container formats currently retain first-series behaviour.

Recursive input discovery reserves `Figure Panels/` and `.fpb-export-*` directories for generated
output and skips them, so a recorded macro can replay safely when its output root is inside the
input folder. Cancellation is polled between channels, Z slices, and pixel-processing blocks;
an ImageJ or Bio-Formats open call already in progress must return before cancellation can finish.

## What It Exports

- `figure.png`, `figure.tif`, and `figure.svg`
- Full-resolution per-panel PNGs and one calibrated, channel-only TIFF hyperstack per selected image in `Supporting files/panels/`
- `Supporting files/manifest.csv` with source, calibration, display range, clipping, selection, and output fields
- `Supporting files/selection.csv` with per-subject selection evidence
- `Supporting files/group_quantification.png` and `Supporting files/group_quantification.csv` for cross-group comparison
- Optional `Supporting files/All project images/` exports every logical source image at full resolution as lossless, display-adjusted channel and merge PNGs and/or one calibrated RGB TIFF channel stack per image
- `Supporting files/metadata.csv` with the exact edited labels used for deterministic macro replay
- `Supporting files/methods.txt` and `Supporting files/README.txt` for the output folder

## Use In Fiji

Figure Panel Builder requires [Fiji](https://fiji.sc/) with Bio-Formats. Download
`FigurePanelBuilder-0.1.0.jar` from the
[latest GitHub release](https://github.com/Jay2owe/FigurePanelBuilder/releases/latest), copy it into
Fiji's `plugins` folder, restart Fiji, then run
`Plugins > Figure Panel Builder`. The guided wizard supports folder loading, metadata labelling,
channel setup, representative selection, layout, annotation, and export.

For automatic installation and updates, open `Help > Update...`, choose `Manage update sites`,
add an unlisted site named `FigurePanelBuilder` with URL
`https://sites.imagej.net/FigurePanelBuilder/`, enable it, apply the changes, and restart Fiji.
Each Choose Images row has four compact icon controls for 90-degree left/right rotation and
horizontal/vertical flipping. The same controls appear over an image while it is hovered in the
Layout canvas. Orientation is stored per logical image and is applied consistently to previews,
individual panels, SVG assets, full-project PNGs and calibrated TIFF stacks.
Layout's external-label editor uses a large full-figure canvas. Click any group title, column label,
or row label to replace its displayed text; drag column and row labels to adjust their distance from
the image grid, or drag the selected label's yellow handle to resize that label type. Visibility,
horizontal/rotated orientation, and numeric size/distance controls remain available at the side.
Group titles can also be renamed directly in the main Layout controls and aligned left, middle, or
right within each group block; the same alignment control remains available in the external-label editor.
The Layout preview is rendered at 2x quality and opens at 100% with scrollbars; Fit, 150%, and
200% zoom modes are available for whole-figure or detail inspection. Ctrl+mouse-wheel zooms
continuously around the pointer, dragging pans the canvas, and Shift+wheel scrolls horizontally.

For scripted use, run the plugin with explicit macro options, including one min and max range for
each channel and one pick for each group. Headless operation is available with `hide_display`.

## Java API

```java
FPBParameters params = FPBParameters.builder(folder)
        .channel(1, "DAPI", ChannelColour.BLUE, 120, 4200)
        .pick("Control", "S3")
        .calibration("Control_S3.tif", 0.42, 0.42)
        .outputFolder(output)
        .build();

FPBResult result = FPB.run(params);
FPB.write(result);
```

The API opens no dialogs, requires no active ImageJ image, and writes files only through explicit
export calls.

## Build

```sh
./mvnw clean package
```

The project targets Java 8 bytecode for Fiji compatibility. The only compile-scope dependency is
`net.imagej:ij`; JUnit is test-scoped.

## License and Citation

Figure Panel Builder is released under the [BSD 3-Clause License](LICENSE). Citation metadata is
provided in [CITATION.cff](CITATION.cff).

> Malcolm, J. (2026). *Figure Panel Builder* (Version 0.1.0)
> [Computer software]. Zenodo. https://doi.org/10.5281/zenodo.21933266
