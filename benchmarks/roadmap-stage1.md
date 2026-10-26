# Stage 1: convolution and implicit lighting grids

Baseline: published `a898ad7b63bbb35b36f26a77eb384e03db8038b4`, tree `471017b2838c7a604ed2bab32bece64867cd5ad6`.

## Implementation

- `feConvolveMatrix`: arbitrary rectangular kernels, SVG 180-degree index reversal, divisor/bias, targets, duplicate/wrap/none boundary extension, premultiplied filtering and preserveAlpha straight-color filtering. Invalid numeric/enum values fail before source reparenting. Valid wrong kernel counts pass through; zero divisor falls back to sum or one; nonpositive spacing uses the default.
- Positive nondefault `kernelUnitLength` in convolution and diffuse/specular lighting now performs an actual input resample → implicit-grid operation → output resample. Bilinear interpolation is a deliberate permitted choice, not a browser pixel-parity claim. Grid origin is the source footprint's top-left edge; centers occur at half the requested spacing. Partial final cells preserve exact spacing and duplicate raster-edge samples.
- Actual cropped input boundaries are extended before convolution resampling, using its selected edgeMode. Coincident cropped lighting edges use duplicated height samples before grid Sobel evaluation.
- Default one-pixel lighting keeps the previous direct path. No buffer or work ceilings were increased. Implicit grid axes are capped at 4096; two intermediate grids and resampling are charged to the existing per-view budget. Convolution kernels are capped at 65536 cells, with actual operations still bounded by the existing 200 million operation cap.

## Verification scope

New coverage includes the W3C asymmetric 2D example, target offsets, three edge modes, cropped/nonaligned input, alpha preservation/bias/clamping, malformed parameters and oversized work rejection; independent explicit-grid lighting oracles, exact identity behavior, cropped-flat lighting, point-light center coordinates, premultiplied specular interpolation and buffer accounting; actual rendering, repeated 1x–2x scaling, budget failure and recovery, and actual editor invalid-edit recovery.

Sample: `src/test/resources/svg/convolve-lighting-filters.svg`.
Generated test screenshots: `target/convolve-lighting-filters-1x.png` and `target/convolve-lighting-filters-editor-preview.png`.

Tests formerly using feConvolveMatrix as an unsupported primitive now use feTile. That fixture must change when stage 3 implements feTile; diagnostic strictness and partial-preview behavior remain tested.

Independent code review reported no remaining high/medium blockers. Full aggregate test result is recorded in the stage verification logs, not inferred from focused passes. Validation is Linux software rendering only; no Windows/GPU or browser raster equivalence claim. No push, PR, merge or release.

## Primary references

- https://www.w3.org/TR/filter-effects-1/#feConvolveMatrixElement
- https://www.w3.org/TR/filter-effects-1/#feDiffuseLightingElement
- https://www.w3.org/TR/filter-effects-1/#feSpecularLightingElement
