# Spatial — 3D scene tool window for IntelliJ

Spatial is an open-source IntelliJ Platform plugin that adds a 3D tool window
to the IDE and exposes it to the built-in AI agent as an MCP skill. Agents can
push raw geometry, semantic architecture maps, and churn landscapes; the plugin
renders them with Three.js inside a JCEF browser so the user sees the project
visualized alongside their code.

<!-- Plugin description -->
Spatial adds a 3D tool window to IntelliJ IDEA. The IDE's built-in AI agent
can drive it over MCP — pushing entities, SaRF maps, links, churn landscapes,
project-structure platters, feed-forward neural networks, camera moves, and narration — so project structure, call graphs, dependency
trees, and other codebase artifacts become visual instead of textual.

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
- The MCP tools are intentionally small — they translate calls from the IDE
  agent into method calls on `SceneService`, with a few domain-specific
  helpers such as SaRF maps and repository churn.

## MCP tools

| Tool                           | Purpose                                                                 |
| ------------------------------ | ----------------------------------------------------------------------- |
| `spatial_push_entities`        | Replace or merge the scene with raw 3D entities.                        |
| `spatial_clear`                | Remove all entities.                                                    |
| `spatial_focus`                | Ease the camera toward a world-space target.                            |
| `spatial_focus_entity`         | Ease the camera onto an entity already in the scene.                    |
| `spatial_speak`                | Flash a one-line caption over the view.                                 |
| `spatial_narrate`              | Speak a sentence aloud with optional on-screen caption.                 |
| `spatial_highlight`            | Pulse one or more entities to draw attention.                           |
| `spatial_play_tour`            | Play a synchronized multi-stop tour with speech, focus, and highlight.  |
| `spatial_push_links`           | Render edges between entity ids for dependency and architecture views.  |
| `spatial_clear_links`          | Remove all links while leaving entities and landscapes intact.          |
| `spatial_push_project_structure` | Render the project as layered folder platters with file blocks.       |
| `spatial_push_feed_forward_network` | Render a canonical feed-forward neural network from layers and weights. |
| `spatial_push_churn_landscape` | Render a treemap-like churn landscape from per-file timeline data.      |
| `spatial_clear_landscape`      | Remove the active churn landscape.                                      |
| `spatial_push_repo_churn`      | Analyze a git repo and push a churn landscape in one call.              |
| `spatial_push_sarf_map`        | Render a canonical SaRF map from semantic structure instead of geometry. |

## Default Project View

For a general-purpose project visualization, prefer
`spatial_push_project_structure`. The plugin scans the current project tree (or
an explicit root path) and renders:

- each folder as a platter
- each file in that folder as a block on top of the platter
- each subfolder as a smaller platter elevated above its parent

File blocks carry path metadata so they can open in the IDE on click. Folder
platters carry folder metadata so current-file focus can still land on the
nearest container when needed.

## Canonical Feed-Forward Network Contract

For weighted neural-network diagrams, prefer
`spatial_push_feed_forward_network`. Agents should describe the network
semantically and let the plugin compute a layered layout.

The canonical contract is defined in
[`src/main/kotlin/dev/spatial/neural/FeedForwardNetwork.kt`](src/main/kotlin/dev/spatial/neural/FeedForwardNetwork.kt)
and has these top-level fields:

- `layers`: ordered input-to-output layers with `nodeCount` and optional `nodeLabels`
- `connections`: weighted node-to-node connections identified by layer id and node index
- `styles`: optional spacing, color, and link-thickness overrides

The plugin materializes that contract into:

- layer platters
- node spheres
- signed weighted links where negative weights are red and positive weights are green
- input/output node labels
- a default focus target on the center layer

Minimal example:

```json
{
  "layers": [
    { "id": "inputs", "label": "Inputs", "nodeCount": 3, "nodeLabels": ["bias", "distance", "velocity"] },
    { "id": "hidden-1", "label": "Hidden 1", "nodeCount": 4 },
    { "id": "outputs", "label": "Outputs", "nodeCount": 2, "nodeLabels": ["turn", "thrust"] }
  ],
  "connections": [
    { "fromLayerId": "inputs", "fromNodeIndex": 0, "toLayerId": "hidden-1", "toNodeIndex": 0, "weight": 0.82 },
    { "fromLayerId": "inputs", "fromNodeIndex": 1, "toLayerId": "hidden-1", "toNodeIndex": 0, "weight": -0.35 },
    { "fromLayerId": "hidden-1", "fromNodeIndex": 2, "toLayerId": "outputs", "toNodeIndex": 1, "weight": 0.57 }
  ]
}
```

## Canonical SaRF Contract

For architecture maps, the preferred entry point is `spatial_push_sarf_map`.
Agents should describe the project semantically and let the plugin compute a
readable default layout.

The canonical SaRF contract is defined in
[`src/main/kotlin/dev/spatial/sarf/SarfMap.kt`](src/main/kotlin/dev/spatial/sarf/SarfMap.kt)
and has these top-level fields:

- `levels`: ordered lanes such as `experience`, `domain`, `infrastructure`
- `clusters`: hierarchical feature or subsystem containers placed within levels
- `modules`: concrete nodes that belong to clusters
- `dependencies`: links between clusters and/or modules
- `tourStops`: optional guided-tour stops with narration and timing metadata
- `styles`: optional layout and appearance overrides

The plugin materializes that contract into:

- level lanes
- cluster boxes
- module nodes
- hierarchy links
- dependency links
- a default focus target

If you want narrated playback, either:

- call `spatial_push_sarf_map` with `playTour: true`, or
- call `spatial_play_tour` with explicit stops after the scene is present

Each `tourStop` may include:

- `focusDurationMs`
- `highlightDurationMs`
- `preDelayMs`
- `postDelayMs`
- `minHoldMs`
- `voice`
- `rate`
- `caption`
- `waitForSpeech`

### Canonical Example

A complete checked-in example lives at
[`src/main/resources/examples/canonical-sarf-map.json`](src/main/resources/examples/canonical-sarf-map.json).

Minimal example:

```json
{
  "levels": [
    { "id": "experience", "label": "Experience" },
    { "id": "domain", "label": "Domain" },
    { "id": "infrastructure", "label": "Infrastructure" }
  ],
  "clusters": [
    { "id": "catalog", "label": "Catalog", "levelId": "domain" },
    { "id": "catalog-ui", "label": "Catalog UI", "levelId": "experience", "parentId": "catalog" }
  ],
  "modules": [
    { "id": "catalog-page", "label": "CatalogPage", "clusterId": "catalog-ui", "kind": "box" },
    { "id": "catalog-service", "label": "CatalogService", "clusterId": "catalog" }
  ],
  "dependencies": [
    { "fromId": "catalog-page", "toId": "catalog-service", "label": "queries" }
  ]
}
```

### Entity schema

Use `spatial_push_entities` when you need full manual control over geometry.
For architecture maps, prefer `spatial_push_sarf_map`; for file-history views,
prefer `spatial_push_churn_landscape` or `spatial_push_repo_churn`.

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
4. For a semantic architecture map, prefer `spatial_push_sarf_map` with a
   canonical SaRF payload instead of hand-placed entities.

## Starting from the template

This repository was scaffolded from
[JetBrains/intellij-platform-plugin-template](https://github.com/JetBrains/intellij-platform-plugin-template).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
