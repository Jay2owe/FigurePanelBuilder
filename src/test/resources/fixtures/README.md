# Figure Panel Builder Stage 02 Fixtures

The Stage 02 tests generate deterministic ImageJ TIFF fixtures from
`fpb.io.FixtureImages` so the binary files do not drift when ImageJ writes TIFF
metadata differently across versions.

Fixture contract for later stages:

- `basic/`: 4 groups x 6 subjects x 3 channels, 16-bit, known per-channel means.
- `sections/`: one subject with 3 sections and another with 1 section.
- `uncalibrated.tif`: single-channel 16-bit image without explicit pixel size.
- `eightbit.tif`: single-channel 8-bit image with values 0, 10, 200, 255.
- `puncta.tif`: sparse bright punctum on a dark 16-bit background.
