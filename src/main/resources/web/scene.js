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
  const stage = document.getElementById('stage');
  const hudCount = document.getElementById('hud-count');
  const emptyState = document.getElementById('empty');
  const speech = document.getElementById('speech');

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x1e1f22);

  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 5000);
  camera.position.set(6, 6, 10);
  camera.lookAt(0, 0, 0);

  const renderer = new THREE.WebGLRenderer({ antialias: true });
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
  scene.add(grid);

  const entityRoot = new THREE.Group();
  scene.add(entityRoot);
  const byId = new Map();

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
  const panRight = new THREE.Vector3();
  const panUp = new THREE.Vector3();
  renderer.domElement.addEventListener('contextmenu', (e) => e.preventDefault());
  renderer.domElement.addEventListener('mousedown', (e) => {
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
  window.addEventListener('mouseup', () => { dragMode = null; });
  window.addEventListener('mousemove', (e) => {
    if (!dragMode) return;
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
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

  function animate() {
    requestAnimationFrame(animate);
    renderer.render(scene, camera);
  }
  animate();

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
    return mesh;
  }

  function applyTransform(mesh, entity) {
    const p = entity.position || { x: 0, y: 0, z: 0 };
    const r = entity.rotation || { x: 0, y: 0, z: 0 };
    const s = entity.scale || { x: 1, y: 1, z: 1 };
    mesh.position.set(p.x || 0, p.y || 0, p.z || 0);
    mesh.rotation.set(r.x || 0, r.y || 0, r.z || 0);
    mesh.scale.set(s.x || 1, s.y || 1, s.z || 1);
  }

  function setScene(payload) {
    const incoming = payload && Array.isArray(payload.entities) ? payload.entities : [];
    const seen = new Set();
    incoming.forEach((entity) => {
      if (!entity || !entity.id) return;
      seen.add(entity.id);
      let mesh = byId.get(entity.id);
      if (!mesh || mesh.userData.kind !== entity.kind) {
        if (mesh) {
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
        mesh.material.opacity = entity.opacity ?? 1;
        mesh.material.transparent = (entity.opacity ?? 1) < 1;
      }
      applyTransform(mesh, entity);
    });
    for (const id of Array.from(byId.keys())) {
      if (!seen.has(id)) {
        const mesh = byId.get(id);
        entityRoot.remove(mesh);
        mesh.geometry.dispose();
        mesh.material.dispose();
        byId.delete(id);
      }
    }
    hudCount.textContent = String(byId.size);
    emptyState.classList.toggle('hidden', byId.size > 0);
  }

  function easeCamera(endTarget, endRadius, endTheta, endPhi, duration) {
    const startTarget = target.clone();
    const startRadius = spherical.radius;
    const startTheta = spherical.theta;
    const startPhi = spherical.phi;
    const dur = Math.max(0, duration);
    const startTime = performance.now();

    function step(now) {
      const progress = dur === 0 ? 1 : Math.min(1, (now - startTime) / dur);
      const eased = 1 - Math.pow(1 - progress, 3);
      target.lerpVectors(startTarget, endTarget, eased);
      spherical.radius = startRadius + (endRadius - startRadius) * eased;
      if (endTheta != null) spherical.theta = startTheta + (endTheta - startTheta) * eased;
      if (endPhi != null) spherical.phi = startPhi + (endPhi - startPhi) * eased;
      updateCamera();
      if (progress < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  function focus(payload) {
    const t = payload && payload.target ? payload.target : { x: 0, y: 0, z: 0 };
    const distance = payload && payload.distance ? payload.distance : 5;
    const duration = payload && payload.durationMs != null ? payload.durationMs : 600;
    easeCamera(new THREE.Vector3(t.x || 0, t.y || 0, t.z || 0), distance, null, null, duration);
  }

  function recenter() {
    easeCamera(defaultPose.target.clone(), defaultPose.radius, defaultPose.theta, defaultPose.phi, 500);
  }

  function fit() {
    if (byId.size === 0) {
      recenter();
      return;
    }
    const box = new THREE.Box3().setFromObject(entityRoot);
    if (box.isEmpty()) { recenter(); return; }
    const sphere = new THREE.Sphere();
    box.getBoundingSphere(sphere);
    const fovRad = (camera.fov * Math.PI) / 180;
    const aspect = camera.aspect || 1;
    const fitVert = sphere.radius / Math.sin(fovRad / 2);
    const fitHoriz = sphere.radius / Math.sin(Math.atan(Math.tan(fovRad / 2) * aspect));
    const distance = Math.max(fitVert, fitHoriz) * 1.25;
    easeCamera(sphere.center.clone(), distance, null, null, 500);
  }

  document.getElementById('btn-recenter').addEventListener('click', recenter);
  document.getElementById('btn-fit').addEventListener('click', fit);

  let speechTimer = null;
  function speak(text) {
    speech.textContent = text || '';
    speech.classList.add('visible');
    if (speechTimer) clearTimeout(speechTimer);
    speechTimer = setTimeout(() => speech.classList.remove('visible'), 4000);
  }

  window.Spatial = { setScene, focus, speak, recenter, fit };
})();
