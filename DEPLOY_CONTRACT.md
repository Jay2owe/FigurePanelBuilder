# Figure Panel Builder deploy contract

<!-- deploy-required-channels: ["local-fiji", "github-release", "zenodo", "imagej-update-site"] -->

Bare `deploy` means a local Fiji candidate install only. It does not push Git
or publish any public channel. `publish` completes every channel below in order.

## Identity and artifact

- Read the version from `pom.xml` at deployment time; `CITATION.cff`, the
  README install line and `CHANGELOG.md` must name the same version.
- Record the exact source commit and the selected jar SHA-256.
- Select exactly one `target/FigurePanelBuilder-*.jar`; exclude sources, tests,
  `original-*`, and non-plugin jars.

## Channels, in publication order

1. `local-fiji`: the maintainer's working Fiji `plugins` folder. Remove only
   `^FigurePanelBuilder-.*\.jar$`; verify exactly one live jar with a matching
   SHA-256, then restart Fiji.
2. `github-release`: annotated tag `vX.Y.Z` on the release commit and a GitHub
   release with `FigurePanelBuilder-X.Y.Z.jar` attached. Verify the asset hash.
3. `zenodo`: archived automatically from the GitHub release. Cite the version
   DOI in `CITATION.cff` and the README afterwards; the README badge keeps the
   concept DOI `10.5281/zenodo.21933265`. Before tagging, drop the previous
   version's DOI from `CITATION.cff` so the archive does not cite another version.
4. `imagej-update-site`: `FigurePanelBuilder` at
   `https://sites.imagej.net/FigurePanelBuilder/`, published only through
   `.github/workflows/fiji-update-site-upload.yml`. Point its `ARTIFACT_GLOB`,
   `RELEASE_ASSET_URL` and `RELEASE_ASSET_SHA256` at the GitHub release asset,
   run it with `dry_run=true`, then live with `confirm_live_upload`.
   Verify the site's `db.xml.gz` lists the new jar and checksum.

No shared lab distribution folder is declared. Do not invent one.

## Gates

- `mvnw clean package` with all tests passing on the release commit.
- One Fiji check that runs Figure Panel Builder on a small input inside Fiji.
- Update-site dry run passes before the live upload.

## Secrets

- Update-site credentials are workflow secrets and must never be stored here.
