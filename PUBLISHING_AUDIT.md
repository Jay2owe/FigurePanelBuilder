# Publishing Audit

Audit date: 2026-08-03

Version under audit: 0.1.0-SNAPSHOT

## Automated Release Checks

- [x] Maven package gate runs with Java 8 target bytecode and `-Denforcer.skip=true`.
- [x] Compile-scope dependency audit is covered by `ReleaseReadinessTest`: only `net.imagej:ij` is
  compile scope.
- [x] Defect ledger regressions are covered by `DefectLedgerTest`, with one named test for each of
  the nine extraction defects.
- [x] End-to-end fixture export is covered by `EndToEndTest`: fixture folder in, complete output tree
  out.
- [x] PNG DPI metadata is checked through the `pHYs` chunk.
- [x] SVG output is parsed as XML and checked for editable `<text>`.
- [x] `manifest.csv`, `selection.csv`, and `methods.txt` are checked for populated output.
- [x] Repository scan rejects local absolute paths, cloud-sync paths, credential assignments, access
  token assignments, and API-key assignments in release text/source files.
- [x] Selection UI source scan rejects warning language and traffic-light colour constants in the
  chooser package.

## Manual Release Checks

- [x] Clean Fiji install route: release candidate is a single plugin JAR with no companion update
  site or third-party JAR requirement.
- [x] Menu route: `plugins.config` registers `Plugins>Figure Panel Builder`.
- [x] Wizard route: the entry point opens the wizard when no macro options are present and the
  environment is not headless.
- [x] Quick Grid route: tested by `QuickGridTest`; it writes the same output tree shape and records
  express-route provenance.
- [x] Headless route: tested by macro/API tests and the end-to-end API export path with
  `hide_display`.
- [x] Windows display scaling risk: UI layout code avoids pixel-exact font assertions; layout tests
  use geometry ranges and text-fit checks rather than platform-specific glyph metrics.
- [x] Atomic file moves: writer tests cover repeated manifest writes and the end-to-end export checks
  for completed files only.
- [ ] External FLASH preservation gate: blocked outside this repository. The FLASH worktree already
  contains source/test/resource changes, and `bash ./mvnw clean package -Denforcer.skip=true` fails
  in `flash.pipeline.ui.variations.VariationResumeOwnershipTest` with 1 failure and 1 error.

## Release Files

- [x] `README.md`
- [x] `LICENSE`
- [x] `CHANGELOG.md`
- [x] `CITATION.cff`
- [x] `PUBLISHING_AUDIT.md`

## Readiness Decision

The Figure Panel Builder repository passes its code and packaging gates for a v0.1.0 release
candidate. Final release is blocked until the external FLASH preservation gate is clean and passing.
Public update-site publication should still use the dedicated ImageJ publishing workflow so the
uploaded JAR and imagej.net documentation match the final tagged commit.
