# Software alignment

Status: partial. This audit describes version 0.3.0.

The plugin provides a public Java API, recordable macro execution, headless exports,
editable image metadata, cohort-locked display ranges, calibrated outputs and source records.
The scientific/data packages do not import the user interface.

Navigation commits active editors before changing steps. Valid image selections, display
ranges and layout settings survive channel edits. Layout reuses one unchanged completed
preview; changed settings or missing files force rebuilding. The figure opens fitted beside
independently scrollable settings.

PNG and TIFF assembly samples original panel images directly at the final export scale.
Regression checks cover native pixel equality and reduction for both layout routes.

## Verification

Run `python scripts/verify.py --group release` to check ownership boundaries and execute
all Maven tests and packaging. The README Java example is compiled and executed against its
named synthetic fixture. Native Swing tests exercise navigation and Layout preview reuse.
The focused development group is `python scripts/verify.py`.

The local paired benchmark measured Layout revisits at approximately 0.27-0.28 seconds,
compared with 0.83-0.93 seconds before preview reuse, across two synthetic workload sizes.
Other stages varied, so this is not a blanket performance claim. These timings measure the
preloaded native widgets, not full folder-selection startup. Detailed timing evidence and
machine-specific verification receipts are retained by the maintainer.

## Remaining gaps

Whole-session autosave and recovery, unified action/help registries, dark theme support,
and broader independent scientific reference validation remain incomplete. This release
has focused regression and export checks; it does not certify every interactive Fiji dialog.
