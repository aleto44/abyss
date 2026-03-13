# Debug tools

## Done
- Added a 3D first-person debug viewer: `FirstPersonWorldDebugApp`
- Added desktop launcher: `FirstPersonWorldDebugLauncher`
- Added free-fly input controller: `FirstPersonDebugController`
- Switched `Main` to launch the 3D viewer by default
- Updated `README.md` with 3D viewer details
- Added controller tests for the new input summary and pitch clamp

## Current behavior
- Click to capture mouse, move with `WASD`, `Space`, `Ctrl`, `Shift`, `Esc`
- Streams chunks around camera using existing `AbyssWorld` pipeline
- Renders chunk meshes in 3D with simple directional lighting
- Draws on-screen debug info for position, direction, chunk counts, and FPS

## Next steps
- Add frustum culling so far-off loaded chunks are skipped at render time
- Split opaque and transparent rendering into separate passes if transparency matters
- Tune start position / stream radii once world scale feels better in practice
- Consider a small on-screen toggle or launcher switch between 2D and 3D debug clients

## Concerns
- Transparent meshes are rendered in a simple pass with no sorting yet
- Fast movement may still show chunk pop-in while async loading catches up
- Viewer is intentionally no-collision / no-physics for now
