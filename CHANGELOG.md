# Changelog

All notable changes to Figure Panel Builder will be documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/) and this project adheres to
[Semantic Versioning](https://semver.org/).

## [0.4.0] - 2026-11-18

### Added

- Save project and Open project in the wizard footer save and restore a `.fpb.xml` snapshot of
  image inclusion, metadata, channels, locked ranges, picks, calibration/orientation overrides,
  layout and export settings. Moved image folders can be relinked after a content check.
- Java API for host plugins: `FPBParameters.Builder.image(source, group, subject, section)` lists
  images with explicit labels instead of scanning a folder, and `imageOpener(...)` supplies
  already-prepared images (Z subsets, deconvolved or LIF-series data) for previews and export.
  ImageJ's `Grays` LUT name is accepted as grey.

### Fixed

- Channels: RGB colour pictures (the Echo Revolution's `_Merge.tif`, ImageJ `_RGB.tif`) are
  recognised, including tiled TIFFs. Copies saved beside real multichannel images are left out
  automatically, so the channel count comes from the channel data instead of reading 3; when
  only RGB pictures are present the step explains that their channels were blended on saving.
  Mixed channel counts and channels saved as Z planes or time points are reported. Online-only
  (cloud) `_RGB.tif`/`_Merge.tif` copies are judged by name so screening never waits on a download.
- Assembled PNG/TIFF figures shrink each panel into its cell by area averaging, so sparse puncta
  and fine processes no longer alias or vanish at large reductions.
- Previews average instead of keeping block maxima, so the contrast set on screen matches the
  exported PNG and SVG panels rather than looking brighter than any export.

## [0.3.0] - 2026-10-14

### Fixed

- Back and header navigation commit active editors and preserve image picks, contrast ranges,
  orientations and layout where source identities remain valid.
- Assembled PNG and TIFF exports sample original panel images directly at final export scale,
  avoiding premature reduction and maximum-pixel pooling.
- Group-title edits survive switching groups before the delayed preview update.

### Changed

- Reuse unchanged Layout previews on revisits; settings changes and missing files trigger a rebuild.
- Fit the whole figure by default and scroll settings independently beside it.
- Add executable project checks, a software alignment audit and reproducible paired speed measurements.

## [0.2.0] - 2026-10-09

### Added

- Images: a Use tick box per image, with Tick/Untick selected, leaves images out without moving files.
- Channels: arrow buttons reorder channels, which sets the panel column order.
- Quick grid can be started from Channels and uses the chosen channels, names and colours.

### Fixed

- Channel colours now start from the LUTs saved in ImageJ TIFFs (or Bio-Formats metadata for other
  formats) instead of a fixed Blue/Magenta/Green order, so a channel is no longer shown in another
  channel's colour.
- Quick grid no longer skips channel setup; Back from its layout returns to Channels.

## [0.1.1] - 2026-08-19

### Fixed

- Drop-down values are no longer replaced by an ellipsis under Windows display scaling. Every combo
  box now reserves room for its widest entry, so "Export scale" reads `1x` rather than `...`, and
  "Magenta", "Maximum projection" and "Bottom right" are readable in full.

## [0.1.0] - 2026-08-06

### Added

- Initial ImageJ/Fiji plugin scaffold.
- Maven build, wrapper, and GitHub Actions workflow.
- Plugin menu registration and ImageJ entry point.
- Vendored Swing toggle and focused CSV/filesystem utilities.
- Full v0.1.0 test suite covering the nine extraction-defect regressions, fixture end-to-end export,
  release-file checks, dependency scope, and public-release safety checks.
- Standalone ImageJ/Fiji plugin chassis for Figure Panel Builder.
- Folder loading, raw plane caching, max-binned previews, and full-resolution histograms.
- Locked-range direct-raster rendering with per-channel clipping records.
- Editable group/subject/section metadata strategies and subject-level representative suggestions.
- PNG, TIFF, SVG, manifest, selection, methods, Quick Grid, macro, Java API, batch, and headless
  routes.
- Release-readiness audit and regression tests for the full extraction defect ledger.
- Per-image user-entered calibration with GUI, Java API, macro replay, scale-bar, and manifest
  provenance support.
- An unskipped SciJava Maven/CI gate with complete project contributor metadata.
- LIF container expansion with per-series loading and stable `#series=N` source IDs; automatic
  label inference uses individual series names while retaining the LIF container name as the source.
- Cross-group section-level quantification in one z-normalized chart spanning all channels, with
  distinct group colours, group means, the overall zero mean, and auditable CSV/PNG exports.
- Individual section selection in Choose Images while retaining animal-level ranking and
  cross-group quantification.
- Editable external group/column/row label typography, orientation, and distance from the image
  grid, applied consistently to previews and raster/SVG exports.
- Supersampled Layout previews with high-quality progressive resizing, responsive Fit mode, and
  100%/150%/200% inspection zoom.
- Canvas-style Layout navigation with pointer-anchored Ctrl+wheel zoom, drag panning, and
  Shift+wheel horizontal scrolling.

[0.4.0]: https://github.com/Jay2owe/FigurePanelBuilder/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/Jay2owe/FigurePanelBuilder/compare/v0.2.0...v0.3.0
