/*
 * Spatial scene renderer. Kept intentionally small:
 *   - `window.Spatial.setScene({entities})` replaces the scene
 *   - `window.Spatial.focus({target, distance, durationMs})` eases the camera
 *   - `window.Spatial.speak(text)` flashes a message at the top
 *
 * The Kotlin side calls these three functions directly via CEF's
 * executeJavaScript. Add new entity kinds inside `createMesh`.
 */
(function () {
  const runtimeConfig = window.__spatialConfig || {};
  const stage = document.getElementById('stage');
  const hudCount = document.getElementById('hud-count');
  const emptyState = document.getElementById('empty');
  const speech = document.getElementById('speech');
  const notice = document.getElementById('notice');
  const controlsEl = document.getElementById('controls');
  const controlsTitle = document.getElementById('controls-title');
  const controlsValue = document.getElementById('controls-value');
  const controlsTurnLeft = document.getElementById('controls-turn-left');
  const controlsTurnRight = document.getElementById('controls-turn-right');
  const controlsForward = document.getElementById('controls-forward');
  const controlsBackward = document.getElementById('controls-backward');
  const controlsClose = document.getElementById('controls-close');
  const immersiveButton = document.getElementById('btn-immersive');
  const connectionStatus = document.getElementById('connection-status');

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x1e1f22);

  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 5000);
  camera.position.set(6, 6, 10);
  camera.lookAt(0, 0, 0);

  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.xr.enabled = true;
  renderer.setPixelRatio(window.devicePixelRatio || 1);
  renderer.domElement.style.display = 'block';
  renderer.domElement.style.width = '100%';
  renderer.domElement.style.height = '100%';
  stage.appendChild(renderer.domElement);

  const ambient = new THREE.AmbientLight(0xffffff, 0.55);
  const key = new THREE.DirectionalLight(0xffffff, 0.85);
  key.position.set(8, 14, 6);
  scene.add(ambient, key);

  const grid = new THREE.GridHelper(40, 40, 0x3a3d42, 0x2a2d32);
  grid.position.y = -0.01;
  const xrRoot = new THREE.Group();
  scene.add(xrRoot);
  const worldRoot = new THREE.Group();
  xrRoot.add(worldRoot);
  worldRoot.add(grid);

  const entityRoot = new THREE.Group();
  worldRoot.add(entityRoot);
  const labelRoot = new THREE.Group();
  worldRoot.add(labelRoot);
  const interactionRoot = new THREE.Group();
  worldRoot.add(interactionRoot);
  const byId = new Map();
  const entityDefs = new Map();
  const raycaster = new THREE.Raycaster();
  const pointerNdc = new THREE.Vector2();
  const singleClickDelayMs = 300;
  let pointerDown = null;
  let singleClickTimer = null;

  const linkRoot = new THREE.Group();
  worldRoot.add(linkRoot);
  const linkDefs = new Map();    // id → raw link def {id, fromId, toId, color, label, arrow, opacity}
  const linkObjects = new Map(); // id → Object3D currently in linkRoot

  const landscapeRoot = new THREE.Group();
  worldRoot.add(landscapeRoot);
  // Per-cell state for the active landscape, keyed by file path.
  const landscapeCells = new Map(); // path → { mesh, baseColor, heights:[], colors:[], currentHeight }
  let landscapeTimeline = null;
  let landscapeFrameIndex = 0;
  let landscapeMaxHeight = 6;
  const scrubEl = document.getElementById('scrub');
  const scrubRange = document.getElementById('scrub-range');
  const scrubLabel = document.getElementById('scrub-label');
  let interactionConfig = null;
  const controlDefs = new Map();
  const controlStateById = new Map();
  const controlIdByEntityId = new Map();
  const valueStateById = new Map();
  let selectedControlId = null;
  const interactionLines = new Map();

  function parseScaleValue(rawValue, fallbackValue) {
    const parsed = Number(rawValue);
    if (!Number.isFinite(parsed) || parsed <= 0) return fallbackValue;
    return Math.max(0.01, Math.min(100, parsed));
  }

  const urlParams = new URLSearchParams(window.location.search);
  let worldScale = parseScaleValue(urlParams.get('scale'), parseScaleValue(runtimeConfig.worldScale, 1));
  let immersiveEntryDepthMultiplier = parseScaleValue(
    urlParams.get('depth') || urlParams.get('entryDepth') || urlParams.get('entryDepthMultiplier'),
    parseScaleValue(runtimeConfig.immersiveEntryDepthMultiplier, 1),
  );
  worldRoot.scale.setScalar(worldScale);

  function localPointToWorld(point) {
    return worldRoot.localToWorld(point.clone());
  }

  function worldPointToLocal(point) {
    return worldRoot.worldToLocal(point.clone());
  }

  function worldDistanceToLocal(distance) {
    return worldScale === 0 ? distance : distance / worldScale;
  }

  function setWorldScale(nextScale) {
    const resolved = parseScaleValue(nextScale, worldScale);
    if (resolved === worldScale) return;
    worldScale = resolved;
    worldRoot.scale.setScalar(worldScale);
    rebuildAllLinks();
    if (interactionConfig) recomputeInteractiveBindings();
  }

  function setImmersiveEntryDepthMultiplier(nextMultiplier) {
    const resolved = parseScaleValue(nextMultiplier, immersiveEntryDepthMultiplier);
    if (resolved === immersiveEntryDepthMultiplier) return;
    immersiveEntryDepthMultiplier = resolved;
    if (isImmersivePresenting()) {
      placeSceneInFrontForImmersiveStart();
    }
  }

  function isImmersivePresenting() {
    return !!immersiveSession && !!renderer.xr && renderer.xr.isPresenting;
  }

  function currentXrCamera() {
    return isImmersivePresenting() ? renderer.xr.getCamera(camera) : null;
  }

  function resetXrRig() {
    xrRoot.position.set(0, 0, 0);
    xrRoot.quaternion.identity();
  }

  function authoredDistanceToImmersive(distance, fallbackDistance) {
    const authoredDistance = Number.isFinite(distance) ? distance : fallbackDistance;
    return Math.max(0.35, Math.abs(authoredDistance) * worldScale);
  }

  function immersiveViewerPosition() {
    const xrCamera = currentXrCamera();
    return xrCamera
      ? xrCamera.getWorldPosition(new THREE.Vector3())
      : camera.getWorldPosition(new THREE.Vector3());
  }

  function immersiveForwardVector() {
    const xrCamera = currentXrCamera();
    const forward = xrCamera
      ? xrCamera.getWorldDirection(new THREE.Vector3())
      : camera.getWorldDirection(new THREE.Vector3());
    const planarLength = Math.hypot(forward.x, forward.z);
    if (planarLength > 1e-4) {
      forward.y = 0;
      forward.normalize();
      return forward;
    }
    return new THREE.Vector3(0, 0, -1);
  }

  function desiredImmersiveTargetPosition(targetWorld, distanceMeters) {
    const viewer = immersiveViewerPosition();
    const forward = immersiveForwardVector();
    const desired = viewer.clone().addScaledVector(forward, Math.max(0.35, distanceMeters));
    desired.y = Math.max(0.2, viewer.y - 0.3);
    if (targetWorld && Number.isFinite(targetWorld.y)) {
      desired.y = Math.min(desired.y, Math.max(0.1, targetWorld.y + 0.25));
    }
    return desired;
  }

  function resize() {
    const w = stage.clientWidth || window.innerWidth;
    const h = stage.clientHeight || window.innerHeight;
    if (w < 1 || h < 1) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', resize);
  // JCEF tool windows resize without firing window.resize, so observe the stage too.
  if (typeof ResizeObserver !== 'undefined') {
    new ResizeObserver(resize).observe(stage);
  }
  resize();

  // Minimal orbit controls (spherical around target).
  const target = new THREE.Vector3(0, 0, 0);
  const spherical = new THREE.Spherical().setFromVector3(camera.position.clone().sub(target));
  // Snapshot the initial camera pose so the Recenter button can restore it.
  const defaultPose = {
    target: target.clone(),
    radius: spherical.radius,
    theta: spherical.theta,
    phi: spherical.phi,
  };
  let dragMode = null; // 'orbit' | 'pan' | null
  let lastX = 0;
  let lastY = 0;
  let suppressInteractionDispatch = false;
  let immersiveSession = null;
  const panRight = new THREE.Vector3();
  const panUp = new THREE.Vector3();
  renderer.domElement.addEventListener('contextmenu', (e) => e.preventDefault());
  renderer.domElement.addEventListener('mousedown', (e) => {
    pointerDown = { x: e.clientX, y: e.clientY, button: e.button, moved: false };
    // Right-drag, middle-drag, or shift+left-drag pans; plain left-drag orbits.
    if (e.button === 2 || e.button === 1 || (e.button === 0 && e.shiftKey)) {
      dragMode = 'pan';
    } else if (e.button === 0) {
      dragMode = 'orbit';
    } else {
      return;
    }
    lastX = e.clientX;
    lastY = e.clientY;
    e.preventDefault();
  });
  window.addEventListener('mouseup', (e) => {
    const shouldHandleClick = pointerDown
      && pointerDown.button === 0
      && !pointerDown.moved
      && e.clientX != null
      && e.clientY != null;
    dragMode = null;
    if (shouldHandleClick) scheduleSingleClickAtClientPoint(e.clientX, e.clientY);
    pointerDown = null;
  });
  window.addEventListener('mousemove', (e) => {
    if (!dragMode) return;
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
    if (pointerDown && Math.hypot(e.clientX - pointerDown.x, e.clientY - pointerDown.y) > 4) {
      pointerDown.moved = true;
    }
    lastX = e.clientX; lastY = e.clientY;
    if (dragMode === 'orbit') {
      spherical.theta -= dx / 200;
      spherical.phi = Math.max(0.05, Math.min(Math.PI - 0.05, spherical.phi - dy / 200));
      updateCamera();
    } else if (dragMode === 'pan') {
      // Pixels → world units: scale by view height at the target's distance.
      const h = renderer.domElement.clientHeight || 1;
      const worldPerPixel = (2 * Math.tan((camera.fov * Math.PI) / 360) * spherical.radius) / h;
      camera.matrixWorld.extractBasis(panRight, panUp, new THREE.Vector3());
      const offset = panRight.multiplyScalar(-dx * worldPerPixel)
        .add(panUp.multiplyScalar(dy * worldPerPixel));
      target.add(offset);
      updateCamera();
    }
  });
  renderer.domElement.addEventListener('mousemove', (e) => {
    if (dragMode) return;
    renderer.domElement.style.cursor = pickSpatialObjectAtClientPoint(e.clientX, e.clientY) ? 'pointer' : 'default';
  });
  renderer.domElement.addEventListener('dblclick', (e) => {
    if (e.button !== 0) return;
    cancelPendingSingleClick();
    focusAtClientPoint(e.clientX, e.clientY);
  });
  renderer.domElement.addEventListener('wheel', (e) => {
    e.preventDefault();
    spherical.radius = Math.max(1.5, Math.min(400, spherical.radius * (1 + e.deltaY * 0.0015)));
    updateCamera();
  }, { passive: false });

  function updateCamera() {
    const offset = new THREE.Vector3().setFromSpherical(spherical);
    camera.position.copy(target).add(offset);
    camera.lookAt(target);
  }

  function renderFrame() {
    renderer.render(scene, camera);
  }
  renderer.setAnimationLoop(renderFrame);

  function createMesh(entity) {
    let geometry;
    switch ((entity.kind || 'box').toLowerCase()) {
      case 'sphere':
        geometry = new THREE.SphereGeometry(0.5, 32, 24);
        break;
      case 'cylinder':
        geometry = new THREE.CylinderGeometry(0.5, 0.5, 1, 24);
        break;
      case 'cone':
        geometry = new THREE.ConeGeometry(0.5, 1, 24);
        break;
      case 'plane':
        geometry = new THREE.PlaneGeometry(1, 1);
        break;
      case 'box':
      default:
        geometry = new THREE.BoxGeometry(1, 1, 1);
    }
    const material = new THREE.MeshStandardMaterial({
      color: entity.color || '#9aa0a6',
      transparent: (entity.opacity ?? 1) < 1,
      opacity: entity.opacity ?? 1,
      metalness: 0.1,
      roughness: 0.6,
    });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.userData.spatialId = entity.id;
    mesh.userData.baseColor = new THREE.Color(entity.color || '#9aa0a6');
    mesh.userData.spatialMeta = entity.meta || {};
    return mesh;
  }

  function openPathFromMeta(meta) {
    if (!meta || typeof meta !== 'object') return null;
    return meta.path || meta.filePath || meta.spatialPath || null;
  }

  function openLineFromMeta(meta) {
    if (!meta || typeof meta !== 'object') return null;
    const value = Number(meta.line);
    return Number.isFinite(value) ? value : null;
  }

  function openColumnFromMeta(meta) {
    if (!meta || typeof meta !== 'object') return null;
    const value = Number(meta.column);
    return Number.isFinite(value) ? value : null;
  }

  function normalizeAngleDeg(angle) {
    let normalized = angle % 360;
    if (normalized <= -180) normalized += 360;
    if (normalized > 180) normalized -= 360;
    return normalized;
  }

  function currentEntityPosition(entityId) {
    const controlId = controlIdByEntityId.get(entityId);
    if (controlId) {
      const state = controlStateById.get(controlId);
      const entity = entityDefs.get(entityId);
      if (state && entity) {
        return {
          x: state.x,
          y: (entity.position && entity.position.y) || 0,
          z: state.z,
        };
      }
    }
    const entity = entityDefs.get(entityId);
    return entity && entity.position ? entity.position : { x: 0, y: 0, z: 0 };
  }

  function currentEntityHeadingDeg(entityId) {
    const controlId = controlIdByEntityId.get(entityId);
    if (controlId) {
      const state = controlStateById.get(controlId);
      if (state) return state.headingDeg;
    }
    const entity = entityDefs.get(entityId);
    const radians = entity && entity.rotation ? (entity.rotation.y || 0) : 0;
    return THREE.MathUtils.radToDeg(radians);
  }

  function boundValueNodeIds() {
    const ids = new Set();
    if (!interactionConfig) return ids;
    (interactionConfig.raySensors || []).forEach((binding) => {
      (binding.valueNodeEntityIds || []).forEach((id) => ids.add(id));
    });
    (interactionConfig.bearingSensors || []).forEach((binding) => {
      (binding.valueNodeEntityIds || []).forEach((id) => ids.add(id));
      if (binding.distanceNodeEntityId) ids.add(binding.distanceNodeEntityId);
    });
    return ids;
  }

  function updateControlPanel() {
    const control = selectedControlId ? controlDefs.get(selectedControlId) : null;
    const state = selectedControlId ? controlStateById.get(selectedControlId) : null;
    if (!control || !state || control.showUi === false) {
      controlsEl.classList.remove('visible');
      return;
    }
    controlsTitle.textContent = control.label || control.entityId;
    controlsValue.textContent = `x ${state.x.toFixed(2)} · z ${state.z.toFixed(2)} · heading ${Math.round(normalizeAngleDeg(state.headingDeg))}°`;
    controlsEl.classList.add('visible');
  }

  function selectControl(controlId) {
    selectedControlId = controlId && controlDefs.has(controlId) ? controlId : null;
    updateControlPanel();
    sendInteractionState();
  }

  function makeInteractionSnapshot() {
    return {
      selectedControlId,
      controls: Array.from(controlStateById.values()).map((state) => ({
        id: state.id,
        entityId: state.entityId,
        x: state.x,
        z: state.z,
        headingDeg: state.headingDeg,
      })),
      values: Array.from(valueStateById.entries())
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([id, value]) => ({ id, value })),
    };
  }

  function sendInteractionState() {
    if (suppressInteractionDispatch) return;
    if (typeof window.__spatialSend !== 'function') return;
    window.__spatialSend(JSON.stringify({
      type: 'interaction-state',
      interactionState: makeInteractionSnapshot(),
    }));
  }

  function updateOpenBinding(mesh, entity) {
    const meta = entity.meta || {};
    mesh.userData.spatialMeta = meta;
    mesh.userData.spatialPath = openPathFromMeta(meta);
    mesh.userData.spatialLine = openLineFromMeta(meta);
    mesh.userData.spatialColumn = openColumnFromMeta(meta);
  }

  function findSpatialObject(obj) {
    let cursor = obj;
    while (cursor) {
      if (cursor.userData && (cursor.userData.spatialId || cursor.userData.spatialPath)) return cursor;
      cursor = cursor.parent || null;
    }
    return null;
  }

  function sendOpenRequest(target) {
    if (!target || !target.userData || !target.userData.spatialPath) return false;
    if (typeof window.__spatialSend !== 'function') return false;
    const payload = {
      type: 'open-file',
      entityId: target.userData.spatialId || null,
      path: target.userData.spatialPath,
      line: target.userData.spatialLine || null,
      column: target.userData.spatialColumn || null,
    };
    window.__spatialSend(JSON.stringify(payload));
    return true;
  }

  function pickSpatialObjectAtClientPoint(clientX, clientY) {
    const rect = renderer.domElement.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    pointerNdc.x = ((clientX - rect.left) / rect.width) * 2 - 1;
    pointerNdc.y = -((clientY - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(pointerNdc, camera);
    const hits = raycaster.intersectObjects([...entityRoot.children, ...labelRoot.children, ...landscapeRoot.children], true);
    for (const hit of hits) {
      const spatial = findSpatialObject(hit.object);
      if (spatial) return spatial;
    }
    return null;
  }

  function cancelPendingSingleClick() {
    if (!singleClickTimer) return;
    clearTimeout(singleClickTimer);
    singleClickTimer = null;
  }

  function showNotice(text, durationMs) {
    if (!notice) return;
    notice.textContent = text || '';
    notice.classList.add('visible');
    if (notice.hideTimer) clearTimeout(notice.hideTimer);
    notice.hideTimer = setTimeout(() => {
      notice.classList.remove('visible');
    }, durationMs != null ? durationMs : 1400);
  }

  function focusObject(target, durationMs) {
    if (!target) return Promise.resolve(false);
    const focusTarget = target.userData && target.userData.spatialFocusObject
      ? target.userData.spatialFocusObject
      : target;
    const box = new THREE.Box3().setFromObject(focusTarget);
    if (focusTarget.userData && focusTarget.userData.label && focusTarget.userData.label.sprite) {
      box.union(new THREE.Box3().setFromObject(focusTarget.userData.label.sprite));
    }
    if (box.isEmpty()) {
      const worldPos = new THREE.Vector3();
      focusTarget.getWorldPosition(worldPos);
      if (isImmersivePresenting()) {
        return easeImmersiveFocus(worldPos, authoredDistanceToImmersive(4, 4), durationMs != null ? durationMs : 450);
      }
      return easeCamera(worldPos, 4, null, null, durationMs != null ? durationMs : 450);
    }
    const sphere = new THREE.Sphere();
    box.getBoundingSphere(sphere);
    if (isImmersivePresenting()) {
      return easeImmersiveFocus(sphere.center.clone(), Math.max(0.45, sphere.radius * 3.2), durationMs != null ? durationMs : 450);
    }
    const distance = Math.max(3.5, sphere.radius * 3.2);
    return easeCamera(sphere.center.clone(), distance, null, null, durationMs != null ? durationMs : 450);
  }

  function handleSingleClickAtClientPoint(clientX, clientY) {
    const target = pickSpatialObjectAtClientPoint(clientX, clientY);
    if (!target) return;
    const spatialId = target.userData ? target.userData.spatialId : null;
    const controlId = spatialId ? controlIdByEntityId.get(spatialId) : null;
    if (controlId) {
      selectControl(controlId);
      if (!(target.userData && target.userData.spatialPath)) return;
    }
    if (target.userData && target.userData.spatialPath) {
      sendOpenRequest(target);
      return;
    }
    showNotice('No associated path');
  }

  function scheduleSingleClickAtClientPoint(clientX, clientY) {
    cancelPendingSingleClick();
    singleClickTimer = setTimeout(() => {
      singleClickTimer = null;
      handleSingleClickAtClientPoint(clientX, clientY);
    }, singleClickDelayMs);
  }

  function focusAtClientPoint(clientX, clientY) {
    const target = pickSpatialObjectAtClientPoint(clientX, clientY);
    if (!target) return;
    focusObject(target, 450);
  }

  function splitLabelSegment(ctx, segment, maxWidthPx) {
    if (ctx.measureText(segment).width <= maxWidthPx) return [segment];
    const parts = [];
    let current = '';
    for (const ch of segment) {
      const candidate = current + ch;
      if (current && ctx.measureText(candidate).width > maxWidthPx) {
        parts.push(current);
        current = ch;
      } else {
        current = candidate;
      }
    }
    if (current) parts.push(current);
    return parts;
  }

  function labelSegments(text) {
    return String(text || '')
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/([./_\\\\-]+)/g, ' $1 ')
      .trim()
      .split(/\s+/)
      .filter(Boolean);
  }

  function wrapLabelLines(ctx, text, maxWidthPx) {
    const segments = labelSegments(text);
    if (segments.length === 0) return [''];
    const lines = [];
    let current = '';
    segments.forEach((segment) => {
      splitLabelSegment(ctx, segment, maxWidthPx).forEach((part) => {
        const candidate = current ? `${current} ${part}` : part;
        if (current && ctx.measureText(candidate).width > maxWidthPx) {
          lines.push(current);
          current = part;
        } else {
          current = candidate;
        }
      });
    });
    if (current) lines.push(current);
    return lines;
  }

  function computeLabelWorldScale(logicalWidth, logicalHeight, lineCount, options) {
    const minWorldWidth = options.minWorldWidth ?? 1.2;
    const maxWorldWidth = options.maxWorldWidth ?? 3.4;
    const baseWorldHeight = options.baseWorldHeight ?? 0.45;
    const extraLineHeight = options.extraLineHeight ?? 0.14;
    const worldHeight = baseWorldHeight + Math.max(0, lineCount - 1) * extraLineHeight;
    const aspectWidth = worldHeight * (logicalWidth / Math.max(1, logicalHeight));
    return {
      width: Math.max(minWorldWidth, Math.min(maxWorldWidth, aspectWidth)),
      height: worldHeight,
    };
  }

  function makeLabelSprite(text, options = {}) {
    const fontSizePx = options.fontSizePx ?? 56;
    const maxTextWidthPx = options.maxTextWidthPx ?? 700;
    const paddingX = options.paddingX ?? 28;
    const paddingY = options.paddingY ?? 18;
    const lineHeightPx = options.lineHeightPx ?? Math.ceil(fontSizePx * 1.15);
    const radiusPx = options.radiusPx ?? 12;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);

    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    ctx.font = `bold ${fontSizePx}px -apple-system, "Segoe UI", sans-serif`;

    const lines = wrapLabelLines(ctx, text, maxTextWidthPx);
    const textWidthPx = Math.max(1, ...lines.map((line) => ctx.measureText(line).width));
    const logicalWidth = Math.ceil(textWidthPx + paddingX * 2);
    const logicalHeight = Math.ceil(lines.length * lineHeightPx + paddingY * 2);

    canvas.width = Math.ceil(logicalWidth * dpr);
    canvas.height = Math.ceil(logicalHeight * dpr);

    ctx.scale(dpr, dpr);
    ctx.font = `bold ${fontSizePx}px -apple-system, "Segoe UI", sans-serif`;
    ctx.fillStyle = 'rgba(20, 21, 24, 0.82)';
    ctx.beginPath();
    ctx.roundRect(0, 0, logicalWidth, logicalHeight, radiusPx);
    ctx.fill();

    ctx.fillStyle = '#f1f3f5';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    lines.forEach((line, index) => {
      const y = paddingY + lineHeightPx * index + lineHeightPx / 2;
      ctx.fillText(line, logicalWidth / 2, y);
    });

    const texture = new THREE.CanvasTexture(canvas);
    texture.minFilter = THREE.LinearFilter;
    const material = new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false });
    const sprite = new THREE.Sprite(material);
    const worldScale = computeLabelWorldScale(logicalWidth, logicalHeight, lines.length, options);
    sprite.renderOrder = 999;
    sprite.scale.set(worldScale.width, worldScale.height, 1);
    sprite.userData.labelWorldScale = worldScale;
    return sprite;
  }

  function entityLabelOptions(entity) {
    const meta = entity.meta || {};
    if (meta.networkType === 'feedForwardLayer') {
      return {
        fontSizePx: 44,
        maxTextWidthPx: 420,
        minWorldWidth: 1.5,
        maxWorldWidth: 2.8,
        baseWorldHeight: 0.34,
        extraLineHeight: 0.11,
        paddingX: 18,
        paddingY: 10,
      };
    }
    if (meta.networkType === 'feedForwardNode') {
      return {
        fontSizePx: 40,
        maxTextWidthPx: 340,
        minWorldWidth: 0.75,
        maxWorldWidth: 1.7,
        baseWorldHeight: 0.22,
        extraLineHeight: 0.08,
        paddingX: 16,
        paddingY: 8,
      };
    }
    return {
      maxTextWidthPx: 640,
      minWorldWidth: 1.3,
      maxWorldWidth: 3.8,
      baseWorldHeight: 0.45,
      extraLineHeight: 0.16,
    };
  }

  function entityTopY(entity, overridePosition) {
    const p = overridePosition || entity.position || { x: 0, y: 0, z: 0 };
    const s = entity.scale || { x: 1, y: 1, z: 1 };
    switch ((entity.kind || 'box').toLowerCase()) {
      case 'sphere':
      case 'box':
      case 'cylinder':
      case 'cone':
        return (p.y || 0) + (s.y || 1) / 2;
      case 'plane':
        return p.y || 0;
      default:
        return (p.y || 0) + (s.y || 1) / 2;
    }
  }

  function syncLabel(mesh, entity, overridePosition) {
    const labelText = mesh.userData.dynamicLabelText != null ? mesh.userData.dynamicLabelText : entity.label;
    const existing = mesh.userData.label;
    if (existing && existing.text === (labelText || '')) {
      const p = overridePosition || entity.position || { x: 0, y: 0, z: 0 };
      const labelScale = existing.sprite.userData.labelWorldScale || { width: 1.8, height: 0.45 };
      existing.sprite.position.set(
        p.x || 0,
        entityTopY(entity, overridePosition) + labelScale.height / 2 + 0.14,
        p.z || 0,
      );
      return;
    }
    if (existing) {
      labelRoot.remove(existing.sprite);
      existing.sprite.material.map.dispose();
      existing.sprite.material.dispose();
      mesh.userData.label = null;
    }
    if (!labelText) return;
    const sprite = makeLabelSprite(labelText, entityLabelOptions(entity));
    const p = overridePosition || entity.position || { x: 0, y: 0, z: 0 };
    const labelScale = sprite.userData.labelWorldScale || { width: 1.8, height: 0.45 };
    sprite.position.set(
      p.x || 0,
      entityTopY(entity, overridePosition) + labelScale.height / 2 + 0.14,
      p.z || 0,
    );
    sprite.userData.spatialId = entity.id;
    sprite.userData.spatialMeta = entity.meta || {};
    sprite.userData.spatialPath = openPathFromMeta(entity.meta || {});
    sprite.userData.spatialLine = openLineFromMeta(entity.meta || {});
    sprite.userData.spatialColumn = openColumnFromMeta(entity.meta || {});
    sprite.userData.spatialFocusObject = mesh;
    labelRoot.add(sprite);
    mesh.userData.label = { text: labelText, sprite };
  }

  function applyTransform(mesh, entity) {
    const p = entity.position || { x: 0, y: 0, z: 0 };
    const r = entity.rotation || { x: 0, y: 0, z: 0 };
    const s = entity.scale || { x: 1, y: 1, z: 1 };
    mesh.position.set(p.x || 0, p.y || 0, p.z || 0);
    mesh.rotation.set(r.x || 0, r.y || 0, r.z || 0);
    mesh.scale.set(s.x || 1, s.y || 1, s.z || 1);
  }

  function setDynamicLabelText(entityId, text) {
    const mesh = byId.get(entityId);
    const entity = entityDefs.get(entityId);
    if (!mesh || !entity) return;
    mesh.userData.dynamicLabelText = text;
    const controlId = controlIdByEntityId.get(entityId);
    const state = controlId ? controlStateById.get(controlId) : null;
    const overridePosition = state ? { x: state.x, y: entity.position?.y || 0, z: state.z } : null;
    syncLabel(mesh, entity, overridePosition);
  }

  function clearDynamicLabelText(entityId) {
    const mesh = byId.get(entityId);
    const entity = entityDefs.get(entityId);
    if (!mesh || !entity) return;
    delete mesh.userData.dynamicLabelText;
    const controlId = controlIdByEntityId.get(entityId);
    const state = controlId ? controlStateById.get(controlId) : null;
    const overridePosition = state ? { x: state.x, y: entity.position?.y || 0, z: state.z } : null;
    syncLabel(mesh, entity, overridePosition);
  }

  function applyControlPose(controlId) {
    const control = controlDefs.get(controlId);
    const state = controlStateById.get(controlId);
    const mesh = control ? byId.get(control.entityId) : null;
    const entity = control ? entityDefs.get(control.entityId) : null;
    if (!control || !state || !mesh || !entity) return;
    const p = entity.position || { x: 0, y: 0, z: 0 };
    const r = entity.rotation || { x: 0, y: 0, z: 0 };
    const s = entity.scale || { x: 1, y: 1, z: 1 };
    mesh.position.set(state.x, p.y || 0, state.z);
    mesh.rotation.set(r.x || 0, THREE.MathUtils.degToRad(state.headingDeg), r.z || 0);
    mesh.scale.set(s.x || 1, s.y || 1, s.z || 1);
    syncLabel(mesh, entity, { x: state.x, y: p.y || 0, z: state.z });
  }

  function clearInteractionLines() {
    interactionLines.forEach((line) => {
      if (line.geometry) line.geometry.dispose();
      if (line.material) line.material.dispose();
      interactionRoot.remove(line);
    });
    interactionLines.clear();
  }

  function resetValueNodeVisual(entityId) {
    const mesh = byId.get(entityId);
    const entity = entityDefs.get(entityId);
    if (!mesh || !entity) return;
    const baseScale = entity.scale || { x: 1, y: 1, z: 1 };
    mesh.scale.set(baseScale.x || 1, baseScale.y || 1, baseScale.z || 1);
    mesh.material.color.copy(mesh.userData.baseColor || new THREE.Color(entity.color || '#9aa0a6'));
    clearDynamicLabelText(entityId);
  }

  function applyValueNodeVisual(entityId, value) {
    const mesh = byId.get(entityId);
    const entity = entityDefs.get(entityId);
    if (!mesh || !entity) return;
    const clamped = Math.max(0, Math.min(1, value));
    const baseScale = entity.scale || { x: 1, y: 1, z: 1 };
    const scaleMultiplier = 0.78 + clamped * 0.82;
    mesh.scale.set(
      (baseScale.x || 1) * scaleMultiplier,
      (baseScale.y || 1) * scaleMultiplier,
      (baseScale.z || 1) * scaleMultiplier,
    );
    const cold = new THREE.Color('#4c5561');
    const hot = new THREE.Color('#ffd166');
    mesh.material.color.copy(cold.lerp(new THREE.Color(mesh.userData.baseColor || entity.color || '#9aa0a6'), 0.35).lerp(hot, clamped * 0.65));
    const baseLabel = entity.label || entityId;
    setDynamicLabelText(entityId, `${baseLabel}\n${clamped.toFixed(2)}`);
  }

  function resetAllValueNodes() {
    boundValueNodeIds().forEach((entityId) => resetValueNodeVisual(entityId));
    valueStateById.clear();
  }

  function setScene(payload) {
    const incoming = payload && Array.isArray(payload.entities) ? payload.entities : [];
    const seen = new Set();
    incoming.forEach((entity) => {
      if (!entity || !entity.id) return;
      seen.add(entity.id);
      entityDefs.set(entity.id, entity);
      let mesh = byId.get(entity.id);
      if (!mesh || mesh.userData.kind !== entity.kind) {
        if (mesh) {
          if (mesh.userData.label) {
            labelRoot.remove(mesh.userData.label.sprite);
            mesh.userData.label.sprite.material.map.dispose();
            mesh.userData.label.sprite.material.dispose();
          }
          entityRoot.remove(mesh);
          mesh.geometry.dispose();
          mesh.material.dispose();
        }
        mesh = createMesh(entity);
        mesh.userData.kind = entity.kind;
        byId.set(entity.id, mesh);
        entityRoot.add(mesh);
      } else {
        mesh.material.color.set(entity.color || '#9aa0a6');
        mesh.userData.baseColor = mesh.material.color.clone();
        mesh.material.opacity = entity.opacity ?? 1;
        mesh.material.transparent = (entity.opacity ?? 1) < 1;
      }
      updateOpenBinding(mesh, entity);
      applyTransform(mesh, entity);
      syncLabel(mesh, entity);
    });
    for (const id of Array.from(byId.keys())) {
      if (!seen.has(id)) {
        const mesh = byId.get(id);
        if (mesh.userData.label) {
          labelRoot.remove(mesh.userData.label.sprite);
          mesh.userData.label.sprite.material.map.dispose();
          mesh.userData.label.sprite.material.dispose();
        }
        entityRoot.remove(mesh);
        mesh.geometry.dispose();
        mesh.material.dispose();
        byId.delete(id);
        entityDefs.delete(id);
      }
    }
    if (interactionConfig) {
      initializeInteractionControls();
      applyAllControlPoses();
      recomputeInteractiveBindings();
      updateControlPanel();
    }
    rebuildAllLinks();
    refreshHud();
  }

  function refreshHud() {
    const total = byId.size + landscapeCells.size + linkObjects.size;
    hudCount.textContent = String(total);
    emptyState.classList.toggle('hidden', total > 0);
  }

  function easeCamera(endTarget, endRadius, endTheta, endPhi, duration) {
    const myToken = ++cameraAnimationToken;
    const startTarget = target.clone();
    const startRadius = spherical.radius;
    const startTheta = spherical.theta;
    const startPhi = spherical.phi;
    const dur = Math.max(0, duration);
    const startTime = performance.now();

    return new Promise((resolve) => {
      function step(now) {
        if (myToken !== cameraAnimationToken) {
          resolve(false);
          return;
        }
        const progress = dur === 0 ? 1 : Math.min(1, (now - startTime) / dur);
        const eased = 1 - Math.pow(1 - progress, 3);
        target.lerpVectors(startTarget, endTarget, eased);
        spherical.radius = startRadius + (endRadius - startRadius) * eased;
        if (endTheta != null) spherical.theta = startTheta + (endTheta - startTheta) * eased;
        if (endPhi != null) spherical.phi = startPhi + (endPhi - startPhi) * eased;
        updateCamera();
        if (progress < 1) {
          requestAnimationFrame(step);
        } else {
          resolve(true);
        }
      }
      requestAnimationFrame(step);
    });
  }

  function easeImmersiveFocus(endTargetWorld, distanceMeters, duration) {
    const myToken = ++cameraAnimationToken;
    const startRootPosition = xrRoot.position.clone();
    const desiredTargetWorld = desiredImmersiveTargetPosition(endTargetWorld, distanceMeters);
    const endRootPosition = startRootPosition.clone().add(desiredTargetWorld.sub(endTargetWorld));
    const dur = Math.max(0, duration);
    const startTime = performance.now();

    return new Promise((resolve) => {
      function step(now) {
        if (myToken !== cameraAnimationToken) {
          resolve(false);
          return;
        }
        const progress = dur === 0 ? 1 : Math.min(1, (now - startTime) / dur);
        const eased = 1 - Math.pow(1 - progress, 3);
        xrRoot.position.lerpVectors(startRootPosition, endRootPosition, eased);
        if (progress < 1) {
          requestAnimationFrame(step);
        } else {
          resolve(true);
        }
      }
      requestAnimationFrame(step);
    });
  }

  function focus(payload) {
    const t = payload && payload.target ? payload.target : { x: 0, y: 0, z: 0 };
    const distance = payload && payload.distance ? payload.distance : 5;
    const duration = payload && payload.durationMs != null ? payload.durationMs : 600;
    if (isImmersivePresenting()) {
      return easeImmersiveFocus(
        new THREE.Vector3(t.x || 0, t.y || 0, t.z || 0),
        authoredDistanceToImmersive(distance, 5),
        duration,
      );
    }
    easeCamera(new THREE.Vector3(t.x || 0, t.y || 0, t.z || 0), distance, null, null, duration);
  }

  function recenter() {
    if (isImmersivePresenting()) {
      ++cameraAnimationToken;
      resetXrRig();
      return;
    }
    easeCamera(defaultPose.target.clone(), defaultPose.radius, defaultPose.theta, defaultPose.phi, 500);
  }

  function sceneBounds() {
    if (byId.size === 0 && landscapeCells.size === 0 && labelRoot.children.length === 0) {
      return null;
    }
    const box = new THREE.Box3();
    if (byId.size) box.union(new THREE.Box3().setFromObject(entityRoot));
    if (labelRoot.children.length) box.union(new THREE.Box3().setFromObject(labelRoot));
    if (interactionRoot.children.length) box.union(new THREE.Box3().setFromObject(interactionRoot));
    if (landscapeCells.size) box.union(new THREE.Box3().setFromObject(landscapeRoot));
    return box.isEmpty() ? null : box;
  }

  function placeSceneInFrontForImmersiveStart() {
    const box = sceneBounds();
    if (!box) {
      resetXrRig();
      return false;
    }
    const center = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3()).multiplyScalar(worldScale);
    const scaledWidth = Math.max(0.01, size.x);
    const scaledHeight = Math.max(0.01, size.y);
    const scaledDepth = Math.max(0.01, size.z);
    const frontClearance = THREE.MathUtils.clamp(
      0.45 + Math.max(scaledWidth, scaledHeight) * 0.12,
      0.45,
      0.85,
    );
    const depthStandOff = scaledDepth * immersiveEntryDepthMultiplier;
    const entryDistance = Math.max(
      0.7,
      frontClearance + depthStandOff,
      scaledWidth * 0.75,
      scaledHeight * 0.95,
    );
    const desiredTargetWorld = desiredImmersiveTargetPosition(center, entryDistance);
    xrRoot.position.copy(desiredTargetWorld.sub(center));
    return true;
  }

  function fit() {
    const box = sceneBounds();
    if (!box) {
      recenter();
      return;
    }
    const sphere = new THREE.Sphere();
    box.getBoundingSphere(sphere);
    if (isImmersivePresenting()) {
      easeImmersiveFocus(sphere.center.clone(), Math.max(0.5, sphere.radius * 2.6), 500);
      return;
    }
    const fovRad = (camera.fov * Math.PI) / 180;
    const aspect = camera.aspect || 1;
    const fitVert = sphere.radius / Math.sin(fovRad / 2);
    const fitHoriz = sphere.radius / Math.sin(Math.atan(Math.tan(fovRad / 2) * aspect));
    const distance = Math.max(fitVert, fitHoriz) * 1.25;
    easeCamera(sphere.center.clone(), distance, null, null, 500);
  }

  function initializeInteractionControls() {
    const previousStates = new Map(controlStateById);
    controlDefs.clear();
    controlIdByEntityId.clear();
    controlStateById.clear();
    if (!interactionConfig) return;
    (interactionConfig.controls || []).forEach((control) => {
      const entity = entityDefs.get(control.entityId);
      if (!entity) return;
      controlDefs.set(control.id, control);
      controlIdByEntityId.set(control.entityId, control.id);
      const previous = previousStates.get(control.id);
      const basePosition = entity.position || { x: 0, y: 0, z: 0 };
      const baseHeading = THREE.MathUtils.radToDeg((entity.rotation && entity.rotation.y) || 0);
      controlStateById.set(control.id, {
        id: control.id,
        entityId: control.entityId,
        x: previous ? previous.x : (control.initialX != null ? control.initialX : (basePosition.x || 0)),
        z: previous ? previous.z : (control.initialZ != null ? control.initialZ : (basePosition.z || 0)),
        headingDeg: previous ? previous.headingDeg : (control.initialHeadingDeg != null ? control.initialHeadingDeg : baseHeading),
      });
    });
    if (selectedControlId && !controlDefs.has(selectedControlId)) selectedControlId = null;
    if (!selectedControlId) {
      const firstVisibleControl = (interactionConfig.controls || []).find((control) => control.showUi !== false);
      selectedControlId = firstVisibleControl ? firstVisibleControl.id : null;
    }
  }

  function applyAllControlPoses() {
    controlDefs.forEach((_, controlId) => applyControlPose(controlId));
  }

  function clampControlState(control, state) {
    const bounds = control.bounds || {};
    return {
      id: state.id,
      entityId: state.entityId,
      x: Math.min(bounds.maxX ?? state.x, Math.max(bounds.minX ?? state.x, state.x)),
      z: Math.min(bounds.maxZ ?? state.z, Math.max(bounds.minZ ?? state.z, state.z)),
      headingDeg: normalizeAngleDeg(state.headingDeg),
    };
  }

  function applyControlAction(controlId, action) {
    const control = controlDefs.get(controlId);
    const state = controlStateById.get(controlId);
    if (!control || !state) return;
    const headingRad = THREE.MathUtils.degToRad(state.headingDeg);
    const next = { ...state };
    switch (action) {
      case 'turn-left':
        next.headingDeg -= control.rotateStepDeg || 15;
        break;
      case 'turn-right':
        next.headingDeg += control.rotateStepDeg || 15;
        break;
      case 'forward':
        next.x += Math.cos(headingRad) * (control.moveStep || 0.5);
        next.z += Math.sin(headingRad) * (control.moveStep || 0.5);
        break;
      case 'backward':
        next.x -= Math.cos(headingRad) * (control.moveStep || 0.5);
        next.z -= Math.sin(headingRad) * (control.moveStep || 0.5);
        break;
      default:
        return;
    }
    const clamped = clampControlState(control, next);
    controlStateById.set(controlId, clamped);
    applyControlPose(controlId);
    recomputeInteractiveBindings();
    updateControlPanel();
    sendInteractionState();
  }

  function addOrUpdateInteractionLine(id, start, end, color) {
    let line = interactionLines.get(id);
    if (!line) {
      const geometry = new THREE.BufferGeometry().setFromPoints([start, end]);
      const material = new THREE.LineBasicMaterial({ color: new THREE.Color(color || '#8dd3ff'), transparent: true, opacity: 0.95 });
      line = new THREE.Line(geometry, material);
      interactionLines.set(id, line);
      interactionRoot.add(line);
      return;
    }
    line.geometry.dispose();
    line.geometry = new THREE.BufferGeometry().setFromPoints([start, end]);
    line.material.color.set(color || '#8dd3ff');
  }

  function computeRaySensorBindings() {
    (interactionConfig.raySensors || []).forEach((binding) => {
      const control = controlDefs.get(binding.controlId);
      const state = controlStateById.get(binding.controlId);
      if (!control || !state) return;
      const originY = ((entityDefs.get(control.entityId)?.position || {}).y || 0) + 0.18;
      const origin = new THREE.Vector3(state.x, originY, state.z);
      const originWorld = localPointToWorld(origin);
      const wallMeshes = (binding.wallEntityIds || [])
        .map((entityId) => byId.get(entityId))
        .filter(Boolean);
      (binding.anglesDeg || []).forEach((angleDeg, index) => {
        const direction = new THREE.Vector3(
          Math.cos(THREE.MathUtils.degToRad(state.headingDeg + angleDeg)),
          0,
          Math.sin(THREE.MathUtils.degToRad(state.headingDeg + angleDeg)),
        ).normalize();
        const maxDistance = binding.maxDistance || 10;
        const sensorRay = new THREE.Raycaster(originWorld, direction, 0, maxDistance * worldScale);
        const hits = sensorRay.intersectObjects(wallMeshes, true);
        const hit = hits.find((candidate) => candidate.distance >= 0);
        const distance = hit ? worldDistanceToLocal(hit.distance) : maxDistance;
        const valueMode = (binding.valueMode || 'proximity').toLowerCase();
        const normalizedDistance = Math.max(0, Math.min(1, distance / Math.max(maxDistance, 0.001)));
        const value = valueMode === 'distance' ? normalizedDistance : 1 - normalizedDistance;
        const end = hit
          ? worldPointToLocal(hit.point)
          : origin.clone().addScaledVector(direction, maxDistance);
        addOrUpdateInteractionLine(`ray:${binding.id}:${index}`, origin, end, hit ? binding.lineColor : binding.missColor);
        valueStateById.set(`ray:${binding.id}:${index}`, value);
        const nodeId = (binding.valueNodeEntityIds || [])[index];
        if (nodeId) applyValueNodeVisual(nodeId, value);
      });
    });
  }

  function computeBearingSensorBindings() {
    (interactionConfig.bearingSensors || []).forEach((binding) => {
      const control = controlDefs.get(binding.controlId);
      const state = controlStateById.get(binding.controlId);
      const targetEntity = entityDefs.get(binding.targetEntityId);
      if (!control || !state || !targetEntity) return;
      const targetPosition = currentEntityPosition(binding.targetEntityId);
      const dx = (targetPosition.x || 0) - state.x;
      const dz = (targetPosition.z || 0) - state.z;
      const distance = Math.hypot(dx, dz);
      const worldAngle = THREE.MathUtils.radToDeg(Math.atan2(dz, dx));
      const relativeBearing = normalizeAngleDeg(worldAngle - state.headingDeg);
      (binding.sectorCenterAnglesDeg || []).forEach((centerDeg, index) => {
        const delta = Math.abs(normalizeAngleDeg(relativeBearing - centerDeg));
        const falloff = Math.max(binding.falloffDeg || 1, 1);
        const value = Math.max(0, 1 - delta / falloff);
        valueStateById.set(`bearing:${binding.id}:${index}`, value);
        const nodeId = (binding.valueNodeEntityIds || [])[index];
        if (nodeId) applyValueNodeVisual(nodeId, value);
      });
      if (binding.distanceNodeEntityId) {
        const normalizedDistance = Math.max(0, Math.min(1, distance / Math.max(binding.distanceMax || 1, 0.001)));
        const valueMode = (binding.distanceValueMode || 'proximity').toLowerCase();
        const value = valueMode === 'distance' ? normalizedDistance : 1 - normalizedDistance;
        valueStateById.set(`bearing:${binding.id}:distance`, value);
        applyValueNodeVisual(binding.distanceNodeEntityId, value);
      }
    });
  }

  function recomputeInteractiveBindings() {
    clearInteractionLines();
    resetAllValueNodes();
    if (!interactionConfig) return;
    computeRaySensorBindings();
    computeBearingSensorBindings();
  }

  function setInteractions(config) {
    interactionConfig = config || { controls: [], raySensors: [], bearingSensors: [] };
    initializeInteractionControls();
    applyAllControlPoses();
    recomputeInteractiveBindings();
    updateControlPanel();
    sendInteractionState();
  }

  function setInteractionState(payload) {
    if (!payload) return;
    suppressInteractionDispatch = true;
    try {
      const remoteControlStates = Array.isArray(payload.controls) ? payload.controls : [];
      remoteControlStates.forEach((state) => {
        if (!state || !controlDefs.has(state.id)) return;
        const control = controlDefs.get(state.id);
        controlStateById.set(state.id, clampControlState(control, {
          id: state.id,
          entityId: state.entityId || control.entityId,
          x: Number(state.x) || 0,
          z: Number(state.z) || 0,
          headingDeg: Number(state.headingDeg) || 0,
        }));
      });
      applyAllControlPoses();
      recomputeInteractiveBindings();
      selectedControlId = payload.selectedControlId && controlDefs.has(payload.selectedControlId)
        ? payload.selectedControlId
        : null;
      updateControlPanel();
    } finally {
      suppressInteractionDispatch = false;
    }
  }

  function clearInteractions() {
    interactionConfig = null;
    controlDefs.clear();
    controlStateById.clear();
    controlIdByEntityId.clear();
    selectedControlId = null;
    clearInteractionLines();
    resetAllValueNodes();
    updateControlPanel();
    sendInteractionState();
  }

  function setConnectionStatus(text, kind) {
    if (!connectionStatus) return;
    if (!text) {
      connectionStatus.textContent = '';
      connectionStatus.classList.remove('visible');
      connectionStatus.style.color = '';
      return;
    }
    connectionStatus.textContent = text;
    connectionStatus.classList.add('visible');
    connectionStatus.style.color = kind === 'error'
      ? '#ffb4ab'
      : kind === 'warn'
        ? '#f6d28b'
        : '#b7f0c1';
  }

  async function refreshImmersiveButton() {
    if (!immersiveButton) return;
    if (!window.isSecureContext || !navigator.xr) {
      immersiveButton.classList.add('hidden');
      return;
    }
    let supported = false;
    try {
      supported = await navigator.xr.isSessionSupported('immersive-vr');
    } catch (_error) {
      supported = false;
    }
    immersiveButton.classList.toggle('hidden', !supported);
    immersiveButton.textContent = immersiveSession ? 'Exit Immersive' : 'Enter Immersive';
  }

  async function enterImmersive() {
    if (!window.isSecureContext || !navigator.xr) {
      setConnectionStatus('WebXR needs HTTPS or localhost', 'warn');
      return false;
    }
    if (immersiveSession) {
      await immersiveSession.end();
      return true;
    }
    try {
      const session = await navigator.xr.requestSession('immersive-vr', {
        optionalFeatures: ['local-floor', 'bounded-floor', 'hand-tracking'],
      });
      immersiveSession = session;
      session.addEventListener('end', () => {
        immersiveSession = null;
        resetXrRig();
        refreshImmersiveButton();
      });
      await renderer.xr.setSession(session);
      await new Promise((resolve) => requestAnimationFrame(resolve));
      placeSceneInFrontForImmersiveStart();
      await refreshImmersiveButton();
      return true;
    } catch (error) {
      setConnectionStatus(`WebXR failed: ${error && error.message ? error.message : 'unknown error'}`, 'error');
      return false;
    }
  }

  document.getElementById('btn-recenter').addEventListener('click', recenter);
  document.getElementById('btn-fit').addEventListener('click', fit);
  controlsTurnLeft.addEventListener('click', () => selectedControlId && applyControlAction(selectedControlId, 'turn-left'));
  controlsTurnRight.addEventListener('click', () => selectedControlId && applyControlAction(selectedControlId, 'turn-right'));
  controlsForward.addEventListener('click', () => selectedControlId && applyControlAction(selectedControlId, 'forward'));
  controlsBackward.addEventListener('click', () => selectedControlId && applyControlAction(selectedControlId, 'backward'));
  controlsClose.addEventListener('click', () => selectControl(null));
  if (immersiveButton) immersiveButton.addEventListener('click', () => { enterImmersive(); });
  refreshImmersiveButton();

  let speechTimer = null;
  function showCaption(text) {
    speech.textContent = text || '';
    speech.classList.add('visible');
  }

  function hideCaption() {
    speech.classList.remove('visible');
  }

  function scheduleHideCaption(delayMs) {
    if (speechTimer) clearTimeout(speechTimer);
    speechTimer = setTimeout(hideCaption, delayMs);
  }

  function speak(text, durationMs) {
    showCaption(text);
    scheduleHideCaption(durationMs != null ? durationMs : 4000);
  }

  // Lazy-load voices — Web Speech API fills the list asynchronously.
  const hasSpeechSynthesis = 'speechSynthesis' in window;
  let cachedVoices = hasSpeechSynthesis ? speechSynthesis.getVoices() : [];
  if (hasSpeechSynthesis) {
    speechSynthesis.onvoiceschanged = () => { cachedVoices = speechSynthesis.getVoices(); };
  }

  function estimateSpeechMs(text, rate) {
    const words = String(text || '').trim().split(/\s+/).filter(Boolean).length;
    const normalizedRate = Math.max(0.5, Math.min(2, rate || 1));
    return Math.max(1200, Math.round((Math.max(words, 1) * 360) / normalizedRate + 500));
  }

  function cancelNarration() {
    if (hasSpeechSynthesis) speechSynthesis.cancel();
    if (speechTimer) clearTimeout(speechTimer);
    hideCaption();
  }

  function narrateInternal(payload, options) {
    if (!payload || !payload.text) return Promise.resolve({ durationMs: 0, spoken: false });
    const interrupt = !options || options.interrupt !== false;
    const rate = payload.rate || 1;
    const estimatedDuration = estimateSpeechMs(payload.text, rate);
    if (payload.caption !== false) showCaption(payload.text);
    if (!hasSpeechSynthesis) {
      if (payload.caption !== false) scheduleHideCaption(estimatedDuration);
      return new Promise((resolve) => {
        setTimeout(() => resolve({ durationMs: estimatedDuration, spoken: false }), estimatedDuration);
      });
    }
    const utter = new SpeechSynthesisUtterance(payload.text);
    utter.rate = rate;
    if (payload.voice) {
      const match = cachedVoices.find((v) => v.name === payload.voice)
        || cachedVoices.find((v) => v.name.toLowerCase().includes(String(payload.voice).toLowerCase()));
      if (match) utter.voice = match;
    }
    if (interrupt) speechSynthesis.cancel();
    return new Promise((resolve) => {
      const startedAt = performance.now();
      let done = false;
      const fallbackTimer = setTimeout(() => finish(false), estimatedDuration + 250);
      function finish(spoken) {
        if (done) return;
        done = true;
        clearTimeout(fallbackTimer);
        if (payload.caption !== false) scheduleHideCaption(250);
        resolve({ durationMs: Math.max(estimatedDuration, Math.round(performance.now() - startedAt)), spoken });
      }
      utter.onend = () => finish(true);
      utter.onerror = () => finish(false);
      speechSynthesis.speak(utter);
    });
  }

  function narrate(payload) {
    narrateInternal(payload, { interrupt: true });
  }

  let cameraAnimationToken = 0;
  function focusTargetById(entityId) {
    if (!entityId) return null;
    return byId.get(entityId) || landscapeCells.get(entityId)?.mesh || null;
  }

  function focusEntity(payload) {
    const mesh = focusTargetById(payload && payload.entityId);
    if (!mesh) return Promise.resolve(false);
    const worldPos = new THREE.Vector3();
    mesh.getWorldPosition(worldPos);
    if (isImmersivePresenting()) {
      return easeImmersiveFocus(
        worldPos,
        authoredDistanceToImmersive(payload && payload.distance, 4),
        payload.durationMs != null ? payload.durationMs : 600,
      );
    }
    return easeCamera(worldPos, payload.distance || 4, null, null, payload.durationMs != null ? payload.durationMs : 600);
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms || 0)));
  }

  let activeTourToken = 0;
  function playTour(payload) {
    const stops = payload && Array.isArray(payload.stops) ? payload.stops : [];
    const startIndex = payload && payload.startIndex != null ? payload.startIndex : 0;
    const tourToken = ++activeTourToken;
    cancelNarration();
    if (!stops.length) return;
    (async () => {
      for (let i = Math.max(0, startIndex); i < stops.length; i += 1) {
        if (tourToken !== activeTourToken) return;
        const stop = stops[i] || {};
        if (stop.preDelayMs > 0) await sleep(stop.preDelayMs);
        if (tourToken !== activeTourToken) return;
        const focusOk = await focusEntity({
          entityId: stop.entityId,
          distance: stop.distance || 4,
          durationMs: stop.focusDurationMs != null ? stop.focusDurationMs : 600,
        });
        if (!focusOk) continue;

        const narrationMs = stop.text ? estimateSpeechMs(stop.text, stop.rate || 1) : 0;
        const holdMs = Math.max(0, stop.minHoldMs || 0);
        const postDelayMs = Math.max(0, stop.postDelayMs || 0);
        const highlightDuration = stop.highlightDurationMs != null
          ? Math.max(100, stop.highlightDurationMs)
          : Math.max(1500, narrationMs + holdMs + postDelayMs);
        if (Array.isArray(stop.highlightIds) && stop.highlightIds.length) {
          highlight({
            entityIds: stop.highlightIds,
            durationMs: highlightDuration,
            color: stop.color || '#ffffff',
          });
        }

        if (stop.text) {
          const narrationPromise = narrateInternal({
            text: stop.text,
            voice: stop.voice || null,
            rate: stop.rate || 1,
            caption: stop.caption !== false,
          }, { interrupt: false });
          if (stop.waitForSpeech !== false) {
            await narrationPromise;
          }
        }
        if (tourToken !== activeTourToken) return;
        if (holdMs > 0) await sleep(holdMs);
        if (tourToken !== activeTourToken) return;
        if (postDelayMs > 0) await sleep(postDelayMs);
      }
      if (tourToken === activeTourToken) hideCaption();
    })();
  }

  const highlights = new Map(); // mesh → { rafId, prevEmissive, prevIntensity }
  function highlight(payload) {
    const ids = (payload && payload.entityIds) || [];
    const dur = Math.max(100, (payload && payload.durationMs) || 1500);
    const color = new THREE.Color((payload && payload.color) || '#ffffff');
    const start = performance.now();
    ids.forEach((id) => {
      const mesh = byId.get(id);
      if (!mesh || !mesh.material.emissive) return;
      // Cancel any prior pulse on this mesh, restoring state first.
      const prev = highlights.get(mesh);
      if (prev) {
        cancelAnimationFrame(prev.rafId);
        mesh.material.emissive.copy(prev.prevEmissive);
        mesh.material.emissiveIntensity = prev.prevIntensity;
      }
      const prevEmissive = mesh.material.emissive.clone();
      const prevIntensity = mesh.material.emissiveIntensity;
      mesh.material.emissive.copy(color);
      function step(now) {
        const t = Math.min(1, (now - start) / dur);
        // Sinusoidal pulse: 0 → 1 → 0
        const intensity = Math.sin(t * Math.PI) * 0.9;
        mesh.material.emissiveIntensity = intensity;
        if (t < 1) {
          const rafId = requestAnimationFrame(step);
          highlights.set(mesh, { rafId, prevEmissive, prevIntensity });
        } else {
          mesh.material.emissive.copy(prevEmissive);
          mesh.material.emissiveIntensity = prevIntensity;
          highlights.delete(mesh);
        }
      }
      const rafId = requestAnimationFrame(step);
      highlights.set(mesh, { rafId, prevEmissive, prevIntensity });
    });
  }

  // ---------- Churn landscape (treemap on the floor, scrubbable across time) ----------

  // Squarified treemap (Bruls/Huijsen/van Wijk 2000). Returns {path → {x,y,w,h}}
  // in the rectangle [0..W] × [0..H].
  function squarifyTreemap(items, W, H) {
    const out = {};
    if (!items.length) return out;
    const total = items.reduce((s, it) => s + Math.max(it.size, 1e-6), 0);
    const scale = (W * H) / total;
    const sized = items.map((it) => ({ key: it.key, area: Math.max(it.size, 1e-6) * scale }));
    sized.sort((a, b) => b.area - a.area);

    let x = 0, y = 0, w = W, h = H;
    let row = [];

    function worst(row, side) {
      if (!row.length) return Infinity;
      const s = row.reduce((a, b) => a + b.area, 0);
      const r = row.reduce((a, b) => Math.max(a, b.area), 0);
      const min = row.reduce((a, b) => Math.min(a, b.area), Infinity);
      return Math.max((side * side * r) / (s * s), (s * s) / (side * side * min));
    }

    function layoutRow(row) {
      const side = Math.min(w, h);
      const sum = row.reduce((a, b) => a + b.area, 0);
      const thickness = sum / side;
      let cursor = 0;
      if (w >= h) {
        // Strip on the left, full height
        row.forEach((it) => {
          const segH = it.area / thickness;
          out[it.key] = { x: x, y: y + cursor, w: thickness, h: segH };
          cursor += segH;
        });
        x += thickness; w -= thickness;
      } else {
        row.forEach((it) => {
          const segW = it.area / thickness;
          out[it.key] = { x: x + cursor, y: y, w: segW, h: thickness };
          cursor += segW;
        });
        y += thickness; h -= thickness;
      }
    }

    sized.forEach((it) => {
      const side = Math.min(w, h);
      const candidate = [...row, it];
      if (worst(candidate, side) <= worst(row, side) || row.length === 0) {
        row.push(it);
      } else {
        layoutRow(row);
        row = [it];
      }
    });
    if (row.length) layoutRow(row);
    return out;
  }

  function disposeLandscape() {
    landscapeCells.forEach(({ mesh, labelSprite }) => {
      landscapeRoot.remove(mesh);
      mesh.geometry.dispose();
      mesh.material.dispose();
      if (labelSprite) {
        landscapeRoot.remove(labelSprite);
        labelSprite.material.map.dispose();
        labelSprite.material.dispose();
      }
    });
    landscapeCells.clear();
  }

  function defaultChurnColor(t) {
    // Cool→warm: blue → cyan → green → yellow → red.
    const c = new THREE.Color();
    c.setHSL(0.66 - 0.66 * Math.max(0, Math.min(1, t)), 0.7, 0.5);
    return c;
  }

  function setLandscape(timeline) {
    if (!timeline || !Array.isArray(timeline.frames) || timeline.frames.length === 0) {
      clearLandscape();
      return;
    }
    landscapeTimeline = timeline;
    landscapeFrameIndex = 0;
    landscapeMaxHeight = timeline.maxHeight || 6;
    const floor = timeline.floorSize || 20;

    // Union of paths across all frames; each path's cell footprint uses the max LOC across frames
    // (so cells don't change footprint as you scrub — only height changes).
    const pathInfo = new Map();
    timeline.frames.forEach((frame) => {
      (frame.entries || []).forEach((e) => {
        if (!e || !e.path) return;
        const prev = pathInfo.get(e.path) || { loc: 0 };
        if ((e.loc || 0) > prev.loc) prev.loc = e.loc || 0;
        pathInfo.set(e.path, prev);
      });
    });

    const items = Array.from(pathInfo, ([key, info]) => ({ key, size: info.loc || 1 }));
    const layout = squarifyTreemap(items, floor, floor);

    // Per-frame max churn for the default color ramp.
    const frameMax = timeline.frames.map((f) =>
      (f.entries || []).reduce((m, e) => Math.max(m, e.churn || 0), 0) || 1,
    );

    disposeLandscape();
    pathInfo.forEach((_info, path) => {
      const rect = layout[path];
      if (!rect) return;
      const padding = Math.min(rect.w, rect.h) * 0.06;
      const w = Math.max(0.05, rect.w - padding);
      const d = Math.max(0.05, rect.h - padding);
      const geom = new THREE.BoxGeometry(w, 1, d); // unit-tall; we scale Y to set actual height
      const mat = new THREE.MeshStandardMaterial({
        color: 0x666666, metalness: 0.05, roughness: 0.9,
      });
      const mesh = new THREE.Mesh(geom, mat);
      mesh.position.x = rect.x + rect.w / 2 - floor / 2;
      mesh.position.z = rect.y + rect.h / 2 - floor / 2;
      mesh.position.y = 0;
      mesh.scale.y = 0.001;

      // Per-frame heights and colors so scrubbing is local.
      const heights = timeline.frames.map((f) => {
        const entry = (f.entries || []).find((e) => e.path === path);
        if (!entry) return 0;
        const max = frameMax[timeline.frames.indexOf(f)] || 1;
        return Math.max(0.001, (entry.churn || 0) / max) * landscapeMaxHeight;
      });
      const colors = timeline.frames.map((f) => {
        const entry = (f.entries || []).find((e) => e.path === path);
        if (entry && entry.color) return new THREE.Color(entry.color);
        const max = frameMax[timeline.frames.indexOf(f)] || 1;
        return defaultChurnColor((entry?.churn || 0) / max);
      });

      mesh.userData.spatialPath = path;
      mesh.userData.kind = 'landscape-cell';
      landscapeRoot.add(mesh);

      // Label sits above the cell in world space (not parented to the scaled mesh,
      // so the unit Y-scale doesn't distort it).
      const fileLabel = path.split('/').pop();
      const labelSprite = makeLabelSprite(fileLabel, {
        fontSizePx: 48,
        maxTextWidthPx: 540,
        minWorldWidth: 0.9,
        maxWorldWidth: 2.4,
        baseWorldHeight: 0.28,
        extraLineHeight: 0.1,
        paddingX: 22,
        paddingY: 12,
      });
      labelSprite.position.set(mesh.position.x, 0, mesh.position.z);
      labelSprite.visible = false;
      labelSprite.userData.spatialPath = path;
      labelSprite.userData.spatialFocusObject = mesh;
      landscapeRoot.add(labelSprite);

      landscapeCells.set(path, {
        mesh,
        labelSprite,
        heights,
        colors,
        currentHeight: 0,
      });
    });

    // Slider visibility / range
    if (timeline.frames.length > 1) {
      scrubRange.min = '0';
      scrubRange.max = String(timeline.frames.length - 1);
      scrubRange.value = '0';
      scrubEl.classList.add('visible');
    } else {
      scrubEl.classList.remove('visible');
    }
    scrubLabel.textContent = timeline.frames[0].label || '—';

    applyLandscapeFrame(0, true);
    refreshHud();
    fit();
  }

  function applyLandscapeFrame(idx, jumpInsteadOfEase) {
    if (!landscapeTimeline) return;
    landscapeFrameIndex = idx;
    scrubLabel.textContent = landscapeTimeline.frames[idx]?.label || '—';
    const startTime = performance.now();
    const dur = jumpInsteadOfEase ? 0 : 350;

    landscapeCells.forEach((cell) => {
      cell._startHeight = cell.currentHeight;
      cell._endHeight = cell.heights[idx] || 0;
      cell._startColor = cell.mesh.material.color.clone();
      cell._endColor = cell.colors[idx] || cell.mesh.material.color.clone();
    });

    function step(now) {
      const t = dur === 0 ? 1 : Math.min(1, (now - startTime) / dur);
      const eased = 1 - Math.pow(1 - t, 3);
      landscapeCells.forEach((cell) => {
        const h = cell._startHeight + (cell._endHeight - cell._startHeight) * eased;
        cell.currentHeight = h;
        cell.mesh.scale.y = Math.max(0.001, h);
        cell.mesh.position.y = h / 2;
        cell.mesh.material.color.lerpColors(cell._startColor, cell._endColor, eased);
        if (cell.labelSprite) {
          cell.labelSprite.position.y = h + 0.35;
          cell.labelSprite.visible = h > landscapeMaxHeight * 0.35;
        }
      });
      if (t < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  function clearLandscape() {
    landscapeTimeline = null;
    disposeLandscape();
    scrubEl.classList.remove('visible');
    refreshHud();
  }

  scrubRange.addEventListener('input', (e) => {
    applyLandscapeFrame(parseInt(e.target.value, 10), false);
  });

  // ---------- Links (edges between entities, for SARF / architecture maps) ----------

  function disposeLinkObject(group) {
    group.traverse((c) => {
      if (c.geometry) c.geometry.dispose();
      if (c.material) {
        if (c.material.map) c.material.map.dispose();
        c.material.dispose();
      }
    });
    linkRoot.remove(group);
  }

  function buildLinkObject(def) {
    const fromMesh = byId.get(def.fromId);
    const toMesh = byId.get(def.toId);
    if (!fromMesh || !toMesh) return null;
    const a = worldPointToLocal(fromMesh.getWorldPosition(new THREE.Vector3()));
    const b = worldPointToLocal(toMesh.getWorldPosition(new THREE.Vector3()));
    if (a.distanceTo(b) < 1e-4) return null;

    const group = new THREE.Group();
    const color = new THREE.Color(def.color || '#7d8590');
    const opacity = def.opacity == null ? 1 : def.opacity;
    const transparent = opacity < 1;
    const thickness = Number(def.thickness);
    const hasThickness = Number.isFinite(thickness) && thickness > 0.001;

    if (hasThickness) {
      const rod = new THREE.Mesh(
        new THREE.CylinderGeometry(thickness / 2, thickness / 2, a.distanceTo(b), 12),
        new THREE.MeshStandardMaterial({ color, transparent, opacity, metalness: 0.08, roughness: 0.55 }),
      );
      rod.position.copy(a).lerp(b, 0.5);
      rod.quaternion.setFromUnitVectors(
        new THREE.Vector3(0, 1, 0),
        new THREE.Vector3().subVectors(b, a).normalize(),
      );
      group.add(rod);
    } else {
      const lineGeom = new THREE.BufferGeometry().setFromPoints([a, b]);
      const lineMat = new THREE.LineBasicMaterial({ color, transparent, opacity });
      group.add(new THREE.Line(lineGeom, lineMat));
    }

    if (def.arrow) {
      const dir = new THREE.Vector3().subVectors(b, a).normalize();
      const dist = a.distanceTo(b);
      const arrowLen = Math.min(0.45, Math.max(0.12, dist * 0.08, hasThickness ? thickness * 3.2 : 0));
      const cone = new THREE.Mesh(
        new THREE.ConeGeometry(arrowLen * 0.45, arrowLen, 14),
        new THREE.MeshStandardMaterial({ color, transparent, opacity, metalness: 0.1, roughness: 0.5 }),
      );
      // Cone defaults to +Y; rotate so its tip points along dir, then place tip at b.
      cone.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir);
      cone.position.copy(b).addScaledVector(dir, -arrowLen / 2);
      group.add(cone);
    }

    if (def.label) {
      const sprite = makeLabelSprite(def.label, {
        fontSizePx: 42,
        maxTextWidthPx: 420,
        minWorldWidth: 0.8,
        maxWorldWidth: 2.1,
        baseWorldHeight: 0.24,
        extraLineHeight: 0.08,
        paddingX: 18,
        paddingY: 10,
      });
      sprite.position.copy(a).lerp(b, 0.5).add(new THREE.Vector3(0, 0.35, 0));
      group.add(sprite);
    }

    linkRoot.add(group);
    return group;
  }

  function rebuildAllLinks() {
    linkObjects.forEach(disposeLinkObject);
    linkObjects.clear();
    linkDefs.forEach((def) => {
      const obj = buildLinkObject(def);
      if (obj) linkObjects.set(def.id, obj);
    });
  }

  function setLinks(payload) {
    const incoming = payload && Array.isArray(payload.links) ? payload.links : [];
    linkDefs.clear();
    incoming.forEach((def) => {
      if (def && def.id) linkDefs.set(def.id, def);
    });
    rebuildAllLinks();
    refreshHud();
  }

  function clearLinks() {
    linkDefs.clear();
    rebuildAllLinks();
    refreshHud();
  }

  window.Spatial = {
    setScene, focus, focusEntity, speak, narrate, highlight, playTour, recenter, fit,
    setLandscape, clearLandscape,
    setInteractions, setInteractionState, clearInteractions,
    setLinks, clearLinks,
    setConnectionStatus, enterImmersive, setWorldScale, setImmersiveEntryDepthMultiplier,
  };
})();
