# Regenerating the GUI screenshots

`FpbShots` drives the real wizard classes (no mock-ups) and photographs each
window with `java.awt.Robot`. `DemoData` writes the synthetic 3-channel dataset
it walks through: two groups (WT, KO), three animals each, two sections each.

The wizard windows appear on screen while it runs, so leave the machine alone
for the two or three minutes it takes.

```sh
CP="../../../target/classes;$HOME/.m2/repository/net/imagej/ij/1.54p/ij-1.54p.jar"
javac -cp "$CP" -d . FpbShots.java DemoData.java
java -Xmx4g -cp "$CP;." FpbShots ./demo-images ./out ./demo-export
```

Prefer a short absolute path for the first and third arguments: both appear in
the captured screenshots, and a long one makes the folder fields unreadable.

Arguments are: demo image folder, screenshot output folder, export output
folder. All three are created if missing. The final step runs a real export, so
the last screenshot shows genuine written output rather than a mocked summary.

Two knobs worth knowing:

- `WIZARD_W` / `WIZARD_H` set the captured window size (clamped to the screen).
- `SHADOW_LOGICAL_PX` trims the invisible Windows resize border, which would
  otherwise leave a strip of desktop down the edges of every shot.

The step 3 loading screen is only captured if the cohort is still loading 140 ms
after the step opens; with a warm file cache it is skipped rather than faked.
