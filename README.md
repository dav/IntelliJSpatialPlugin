# Spatial — 3D scene tool window for IntelliJ

Spatial is an open-source IntelliJ Platform plugin that adds a 3D tool window
to the IDE and exposes it to the built-in AI agent as an MCP skill. Agents
push entity lists, the plugin renders them with Three.js inside a JCEF
browser, and the user sees the project visualized alongside their code.

<!-- Plugin description -->
Spatial adds a 3D tool window to IntelliJ IDEA. The IDE's built-in AI agent
can drive it over MCP — pushing entities, focusing the camera, and narrating
what it shows — so project structure, call graphs, dependency trees, and
other codebase artifacts become visual instead of textual.

Ships Three.js bundled offline. Requires IntelliJ IDEA 2025.2+ with JCEF.
<!-- Plugin description end -->

## Architecture

```
┌─────────────────┐    MCP     ┌──────────────────┐    state     ┌─────────────────┐
│  IDE AI agent   │ ────────▶  │  SpatialMcpTools │ ───────────▶ │   SceneService  │
└─────────────────┘            └──────────────────┘              └────────┬────────┘
                                                                          │ listener
                                                                          ▼
                                 ┌─────────────────────────────────────────────┐
                                 │  SpatialBrowser (JBCefBrowser)              │
                                 │    ├── index.html                           │
                                 │    ├── three.min.js  (bundled)              │
                                 │    └── scene.js      (window.Spatial API)   │
                                 └─────────────────────────────────────────────┘
```

- `SceneService` is a project-level service that owns scene state.
- `SpatialBrowser` wraps a `JBCefBrowser`, bundles the HTML/JS/library into
  a single `loadHTML` call (no scheme handler required), and subscribes to
  `SceneService` so state changes are pushed into the WebGL view via
  `executeJavaScript`.
- The MCP tools are tiny — they translate calls from the IDE agent into
  method calls on `SceneService`.

## MCP tools

| Tool                    | Purpose                                                       |
| ----------------------- | ------------------------------------------------------------- |
| `spatial_push_entities` | Replace or merge the scene with a list of entities.           |
| `spatial_clear`         | Remove all entities.                                          |
| `spatial_focus`         | Ease the camera toward a target position.                     |
| `spatial_speak`         | Flash a one-line caption over the view.                       |

### Entity schema

```json
{
  "id": "node-42",
  "kind": "sphere",
  "position": { "x": 1.2, "y": 0, "z": -3 },
  "rotation": { "x": 0, "y": 0, "z": 0 },
  "scale":    { "x": 1, "y": 1, "z": 1 },
  "color": "#4a90e2",
  "label": "UserRepository",
  "opacity": 1.0
}
```

Supported kinds: `box`, `sphere`, `cylinder`, `cone`, `plane`. Unknown kinds
fall back to `box`. Unknown fields are ignored by the renderer, so clients
can include arbitrary metadata in `meta` for future passes.

## Building and installing

Requires JDK 21+ and a JetBrains Runtime that includes JCEF.

```bash
./gradlew buildPlugin    # builds distribution zip under build/distributions
./gradlew runIde         # launches a sandbox IDE with the plugin
./gradlew test           # runs the scene-service unit tests
./install.sh             # build, install into the real IDE, relaunch
```

## Running locally against the IDE agent

1. `./install.sh` or `./gradlew runIde` to get the plugin running.
2. Open the **Spatial** tool window on the right.
3. In the IDE AI agent, call one of the MCP tools — for example:
   ```json
   {"entities":[{"id":"a","kind":"sphere","position":{"x":0,"y":0,"z":0},"color":"#4a90e2"}]}
   ```

## Starting from the template

This repository was scaffolded from
[JetBrains/intellij-platform-plugin-template](https://github.com/JetBrains/intellij-platform-plugin-template).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
