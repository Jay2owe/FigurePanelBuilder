# Changelog

All notable changes to Figure Panel Builder will be documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/) and this project adheres to
[Semantic Versioning](https://semver.org/).

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
