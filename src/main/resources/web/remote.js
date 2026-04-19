(function () {
  const config = window.__spatialConfig || {};
  if (config.transport !== 'remote') return;

  function parseJson(data) {
    return data == null || data === '' ? null : JSON.parse(data);
  }

  function updateStatus(text, kind) {
    if (window.Spatial && typeof window.Spatial.setConnectionStatus === 'function') {
      window.Spatial.setConnectionStatus(text, kind);
    }
  }

  function applyRemoteView(remoteView) {
    if (!window.Spatial || !remoteView) return;
    if (remoteView.worldScale != null) {
      window.Spatial.setWorldScale(remoteView.worldScale);
    }
    if (remoteView.immersiveEntryDepthMultiplier != null
      && typeof window.Spatial.setImmersiveEntryDepthMultiplier === 'function') {
      window.Spatial.setImmersiveEntryDepthMultiplier(remoteView.immersiveEntryDepthMultiplier);
    }
  }

  function noteWebXrConstraint() {
    if (window.isSecureContext) return;
    const httpsHint = config.recommendedHttpsUrl ? ` Try ${config.recommendedHttpsUrl}` : '';
    const certHint = config.certificateUrl ? ` If Safari blocks the cert, download ${config.certificateUrl} and trust it on the device.` : '';
    updateStatus(`Connected over HTTP; WebXR needs HTTPS or localhost.${httpsHint}.${certHint}`.replace(/\.\./g, '.'), 'warn');
  }

  window.__spatialRemoteSend = function (payload) {
    return fetch(config.bridgeUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
    }).catch((error) => {
      updateStatus(`Bridge failed: ${error && error.message ? error.message : 'network error'}`, 'error');
    });
  };

  function applySnapshot(snapshot) {
    if (!window.Spatial) return;
    applyRemoteView(snapshot.remoteView);
    window.Spatial.setScene(snapshot.scene || { entities: [] });
    if (snapshot.landscape) window.Spatial.setLandscape(snapshot.landscape);
    else window.Spatial.clearLandscape();
    window.Spatial.setLinks({ links: snapshot.links || [] });
    if (snapshot.interactionConfig) {
      window.Spatial.setInteractions(snapshot.interactionConfig);
      if (snapshot.interactionState) window.Spatial.setInteractionState(snapshot.interactionState);
    } else {
      window.Spatial.clearInteractions();
    }
  }

  function handleEvent(type, payload) {
    if (!window.Spatial) return;
    switch (type) {
      case 'scene':
        window.Spatial.setScene(payload || { entities: [] });
        break;
      case 'focus':
        window.Spatial.focus(payload || {});
        break;
      case 'focus-entity':
        window.Spatial.focusEntity(payload || {});
        break;
      case 'speech':
        window.Spatial.speak(payload);
        break;
      case 'narrate':
        window.Spatial.narrate(payload || {});
        break;
      case 'highlight':
        window.Spatial.highlight(payload || {});
        break;
      case 'tour':
        window.Spatial.playTour(payload || {});
        break;
      case 'landscape':
        if (payload) window.Spatial.setLandscape(payload);
        else window.Spatial.clearLandscape();
        break;
      case 'links':
        window.Spatial.setLinks({ links: payload || [] });
        break;
      case 'interaction-config':
        if (payload) window.Spatial.setInteractions(payload);
        else window.Spatial.clearInteractions();
        break;
      case 'interaction-state':
        window.Spatial.setInteractionState(payload || {});
        break;
      case 'remote-view':
        applyRemoteView(payload);
        break;
      case 'hello':
        if (payload && Array.isArray(payload.urls)) config.candidateEntries = payload.urls;
        applyRemoteView(payload && payload.remoteView);
        noteWebXrConstraint();
        break;
      default:
        break;
    }
  }

  async function bootstrap() {
    try {
      const response = await fetch(config.stateUrl, { cache: 'no-store' });
      if (!response.ok) throw new Error(`state ${response.status}`);
      const snapshot = await response.json();
      applySnapshot(snapshot);
    } catch (error) {
      updateStatus(`Initial sync failed: ${error && error.message ? error.message : 'unknown error'}`, 'error');
      return;
    }

    const source = new EventSource(config.eventsUrl);
    source.onopen = function () {
      noteWebXrConstraint();
      if (window.isSecureContext) updateStatus('Remote scene connected', 'ok');
    };
    source.onerror = function () {
      updateStatus('Remote scene disconnected; retrying…', 'warn');
    };
    [
      'scene',
      'focus',
      'focus-entity',
      'speech',
      'narrate',
      'highlight',
      'tour',
      'landscape',
      'links',
      'interaction-config',
      'interaction-state',
      'remote-view',
      'hello',
    ].forEach((eventName) => {
      source.addEventListener(eventName, function (event) {
        handleEvent(eventName, parseJson(event.data));
      });
    });
  }

  bootstrap();
})();
