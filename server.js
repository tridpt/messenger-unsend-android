const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = process.env.PORT || 3456;
const PUBLIC_DIR = path.join(__dirname, 'public');
const DATA_FILE = path.join(__dirname, 'messages_store.json');

const SUPPORTED_PACKAGES = {
  'com.facebook.orca': 'Messenger',
  'com.facebook.mlite': 'Messenger Lite',
  'com.zing.zalo': 'Zalo',
  'com.instagram.android': 'Instagram',
  'org.telegram.messenger': 'Telegram'
};

const UNSEND_PATTERNS = [
  'đã thu hồi một tin nhắn',
  'đã thu hồi tin nhắn',
  'đã gỡ một tin nhắn',
  'tin nhắn đã bị thu hồi',
  'tin nhắn đã được thu hồi',
  'vừa thu hồi một tin nhắn',
  'unsent a message',
  'removed a message',
  'this message was removed',
  'message unsent'
];

// Tạo ảnh SVG Base64 mẫu cho tin nhắn có đính kèm hình ảnh bị thu hồi
function makeSamplePhotoSvg(title, subtitle, bg1 = '#1e293b', bg2 = '#334155') {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="280" viewBox="0 0 480 280">
    <defs>
      <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="${bg1}"/>
        <stop offset="100%" stop-color="${bg2}"/>
      </linearGradient>
    </defs>
    <rect width="480" height="280" rx="16" fill="url(#g)"/>
    <circle cx="390" cy="70" r="90" fill="rgba(255,255,255,0.06)"/>
    <rect x="28" y="28" width="424" height="224" rx="12" fill="rgba(15,23,42,0.45)" stroke="rgba(255,255,255,0.14)"/>
    <text x="50" y="95" fill="#38bdf8" font-family="sans-serif" font-size="15" font-weight="bold">📸 ẢNH ĐÍNH KÈM TRONG NOTIFICATION</text>
    <text x="50" y="140" fill="#f8fafc" font-family="sans-serif" font-size="22" font-weight="bold">${title}</text>
    <text x="50" y="175" fill="#94a3b8" font-family="sans-serif" font-size="15">${subtitle}</text>
    <rect x="50" y="200" width="235" height="28" rx="6" fill="rgba(239,68,68,0.25)"/>
    <text x="62" y="219" fill="#fca5a5" font-family="sans-serif" font-size="12" font-weight="bold">Đã lưu tự động qua EXTRA_PICTURE</text>
  </svg>`;
  return 'data:image/svg+xml;base64,' + Buffer.from(svg, 'utf8').toString('base64');
}

const now = Date.now();
let messages = [];
let sbnLogs = [];
let nextId = 1;

function seedInitialData() {
  messages = [
    {
      id: nextId++,
      sbnKey: '0|com.facebook.orca|1001|null|10234',
      packageName: 'com.facebook.orca',
      appName: 'Messenger',
      conversationTitle: 'Trần Minh Anh 💕',
      sender: 'Trần Minh Anh 💕',
      content: 'Tối nay 8h anh rảnh không? Em có chuyện này muốn nói thật lòng...',
      imageBase64: '',
      avatarBase64: '',
      timestamp: now - 14 * 60 * 1000,
      isUnsent: false,
      unsentReason: '',
      unsentAt: 0
    },
    {
      id: nextId++,
      sbnKey: '0|com.facebook.orca|1001|null|10234',
      packageName: 'com.facebook.orca',
      appName: 'Messenger',
      conversationTitle: 'Trần Minh Anh 💕',
      sender: 'Trần Minh Anh 💕',
      content: 'Thật ra em thích anh từ đợt đi Đà Lạt chung nhóm rồi đó 😳 Đừng cười em nha!',
      imageBase64: '',
      avatarBase64: '',
      timestamp: now - 12 * 60 * 1000,
      isUnsent: true,
      unsentReason: 'TEXT_RECALL',
      unsentAt: now - 11 * 60 * 1000
    },
    {
      id: nextId++,
      sbnKey: '0|com.facebook.orca|1002|null|10234',
      packageName: 'com.facebook.orca',
      appName: 'Messenger',
      conversationTitle: 'Hội Anh Em Cây Khế',
      sender: 'Hoàng Bảo (Hội Anh Em Cây Khế)',
      content: 'Gửi nhẹ tấm ảnh hôm qua đi nhậu ngủ gật của thằng Nam nè ㅋㅋㅋ',
      imageBase64: makeSamplePhotoSvg('Ảnh chụp lúc 23:45 hôm qua', 'Đã bắt từ Bundle Notification.EXTRA_PICTURE', '#31102f', '#0f172a'),
      avatarBase64: '',
      timestamp: now - 8 * 60 * 1000,
      isUnsent: true,
      unsentReason: 'APP_CANCEL_RETRACT',
      unsentAt: now - 7 * 60 * 1000
    },
    {
      id: nextId++,
      sbnKey: '0|com.zing.zalo|2001|null|10235',
      packageName: 'com.zing.zalo',
      appName: 'Zalo',
      conversationTitle: 'Sếp Lê Tuấn',
      sender: 'Sếp Lê Tuấn',
      content: 'Quý này thưởng nóng team em 25 triệu nhé, khoan báo cho phòng bên cạnh biết!',
      imageBase64: '',
      avatarBase64: '',
      timestamp: now - 4 * 60 * 1000,
      isUnsent: true,
      unsentReason: 'TEXT_RECALL',
      unsentAt: now - 3 * 60 * 1000
    }
  ];

  sbnLogs = [
    {
      time: now - 12 * 60 * 1000,
      hook: 'onNotificationPosted',
      pkg: 'com.facebook.orca',
      sbnKey: '0|com.facebook.orca|1001|null|10234',
      extras: {
        'android.title': 'Trần Minh Anh 💕',
        'android.text': 'Thật ra em thích anh từ đợt đi Đà Lạt chung nhóm rồi đó 😳 Đừng cười em nha!'
      },
      action: 'SAVED_TO_SQLITE'
    },
    {
      time: now - 11 * 60 * 1000,
      hook: 'onNotificationPosted',
      pkg: 'com.facebook.orca',
      sbnKey: '0|com.facebook.orca|1001|null|10234',
      extras: {
        'android.title': 'Trần Minh Anh 💕',
        'android.text': 'Trần Minh Anh đã thu hồi một tin nhắn'
      },
      action: 'UNSENT_DETECTED (Layer 1: TEXT_RECALL)'
    },
    {
      time: now - 7 * 60 * 1000,
      hook: 'onNotificationRemoved',
      pkg: 'com.facebook.orca',
      sbnKey: '0|com.facebook.orca|1002|null|10234',
      extras: {
        reason: '8 (REASON_APP_CANCEL)',
        'android.conversationTitle': 'Hội Anh Em Cây Khế'
      },
      action: 'UNSENT_DETECTED (Layer 2: APP_CANCEL_RETRACT)'
    }
  ];
}

try {
  if (fs.existsSync(DATA_FILE)) {
    const loaded = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    if (Array.isArray(loaded.messages) && loaded.messages.length > 0) {
      messages = loaded.messages;
      nextId = loaded.nextId || (messages.length + 1);
      sbnLogs = loaded.sbnLogs || [];
    } else {
      seedInitialData();
    }
  } else {
    seedInitialData();
  }
} catch (_) {
  seedInitialData();
}

function saveStore() {
  try {
    fs.writeFileSync(DATA_FILE, JSON.stringify({ messages, nextId, sbnLogs: sbnLogs.slice(0, 60) }, null, 2), 'utf8');
  } catch (_) {}
}

// Danh sách client SSE đang kết nối
const sseClients = new Set();

function broadcastSse(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) {
    try {
      res.write(payload);
    } catch (_) {
      sseClients.delete(res);
    }
  }
}

function isUnsendText(text) {
  const lower = String(text || '').toLowerCase();
  return UNSEND_PATTERNS.some(p => lower.includes(p));
}

function getLocalIp() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        return net.address;
      }
    }
  }
  return '127.0.0.1';
}

function parseJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => {
      body += chunk;
      if (body.length > 10 * 1024 * 1024) req.destroy();
    });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  });
  res.end(JSON.stringify(obj));
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    return sendJson(res, 200, { ok: true });
  }

  const urlObj = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = urlObj.pathname;

  // SSE stream
  if (pathname === '/api/events' && req.method === 'GET') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*'
    });
    res.write(`event: connected\ndata: {"ok":true}\n\n`);
    sseClients.add(res);
    req.on('close', () => sseClients.delete(res));
    return;
  }

  // Lấy danh sách tin nhắn + SBN logs + thông tin IP LAN
  if (pathname === '/api/messages' && req.method === 'GET') {
    const onlyUnsent = urlObj.searchParams.get('onlyUnsent') === 'true';
    const search = (urlObj.searchParams.get('search') || '').trim().toLowerCase();
    const pkgFilter = (urlObj.searchParams.get('pkg') || '').trim();

    let list = [...messages].sort((a, b) => b.timestamp - a.timestamp);
    if (onlyUnsent) {
      list = list.filter(m => m.isUnsent);
    }
    if (pkgFilter && pkgFilter !== 'ALL') {
      list = list.filter(m => m.packageName === pkgFilter);
    }
    if (search) {
      list = list.filter(m =>
        m.sender.toLowerCase().includes(search) ||
        m.conversationTitle.toLowerCase().includes(search) ||
        m.content.toLowerCase().includes(search)
      );
    }

    return sendJson(res, 200, {
      messages: list,
      totalCount: messages.length,
      unsentCount: messages.filter(m => m.isUnsent).length,
      sbnLogs: sbnLogs.slice(0, 40),
      lanSyncUrl: `http://${getLocalIp()}:${PORT}/api/ingest`
    });
  }

  // Mô phỏng NotificationListenerService.onNotificationPosted(StatusBarNotification)
  if (pathname === '/api/simulate/post' && req.method === 'POST') {
    try {
      const body = await parseJsonBody(req);
      const pkg = body.packageName || 'com.facebook.orca';
      const appName = SUPPORTED_PACKAGES[pkg] || 'Messenger';
      const convTitle = (body.conversationTitle || body.sender || 'Người gửi').trim();
      const sender = (body.sender || convTitle).trim();
      const text = (body.content || '').trim();
      const imageBase64 = body.hasSamplePhoto
        ? makeSamplePhotoSvg(body.photoCaption || `Ảnh từ ${sender}`, `Bắt qua Notification.EXTRA_PICTURE (${appName})`)
        : (body.imageBase64 || '');
      const sbnKey = `0|${pkg}|${ Math.abs(convTitle.split('').reduce((a, c) => a + c.charCodeAt(0), 0)) }|null|10234`;
      const ts = Date.now();

      // Nếu là thông báo thu hồi tin nhắn (Layer 1)
      if (isUnsendText(text)) {
        const target = [...messages]
          .sort((a, b) => b.timestamp - a.timestamp)
          .find(m => m.packageName === pkg && (m.conversationTitle === convTitle || m.sender === sender) && !m.isUnsent);

        const logEntry = {
          time: ts,
          hook: 'onNotificationPosted',
          pkg,
          sbnKey,
          extras: {
            'android.title': sender,
            'android.conversationTitle': convTitle,
            'android.text': text
          },
          action: target ? 'UNSENT_DETECTED (Layer 1: TEXT_RECALL)' : 'IGNORED (Không có tin nhắn gốc trước đó)'
        };
        sbnLogs.unshift(logEntry);

        if (target) {
          target.isUnsent = true;
          target.unsentReason = 'TEXT_RECALL';
          target.unsentAt = ts;
          saveStore();
          broadcastSse('unsent', { message: target, log: logEntry });
          return sendJson(res, 200, { status: 'UNSENT_DETECTED', message: target, log: logEntry });
        }
        return sendJson(res, 200, { status: 'NO_TARGET_FOUND', log: logEntry });
      }

      // Chống trùng lặp trong 60s giống hệt MessageDatabase.kt
      const duplicate = messages.find(m =>
        m.packageName === pkg &&
        m.conversationTitle === convTitle &&
        m.sender === sender &&
        m.content === text &&
        (ts - m.timestamp) < 60000
      );
      if (duplicate) {
        return sendJson(res, 200, { status: 'DUPLICATE_SKIPPED', message: duplicate });
      }

      const newMsg = {
        id: nextId++,
        sbnKey,
        packageName: pkg,
        appName,
        conversationTitle: convTitle,
        sender,
        content: text || (imageBase64 ? '📷 [Hình ảnh đính kèm]' : ''),
        imageBase64,
        avatarBase64: '',
        timestamp: ts,
        isUnsent: false,
        unsentReason: '',
        unsentAt: 0
      };
      messages.push(newMsg);

      const logEntry = {
        time: ts,
        hook: 'onNotificationPosted',
        pkg,
        sbnKey,
        extras: {
          'android.title': sender,
          'android.conversationTitle': convTitle,
          'android.bigText': newMsg.content,
          ...(imageBase64 ? { 'android.picture': 'Bitmap (Saved as Base64)' } : {})
        },
        action: 'SAVED_TO_SQLITE'
      };
      sbnLogs.unshift(logEntry);
      saveStore();

      broadcastSse('message', { message: newMsg, log: logEntry });
      return sendJson(res, 200, { status: 'SAVED', message: newMsg, log: logEntry });
    } catch (err) {
      return sendJson(res, 400, { error: err.message });
    }
  }

  // Mô phỏng NotificationListenerService.onNotificationRemoved(sbn, ranking, reason)
  if (pathname === '/api/simulate/remove' && req.method === 'POST') {
    try {
      const body = await parseJsonBody(req);
      const pkg = body.packageName || 'com.facebook.orca';
      const convTitle = (body.conversationTitle || body.sender || '').trim();
      const reason = Number(body.reason || 8); // 8 = REASON_APP_CANCEL
      const ts = Date.now();

      const target = [...messages]
        .sort((a, b) => b.timestamp - a.timestamp)
        .find(m => {
          if (m.isUnsent) return false;
          if (convTitle) {
            return m.packageName === pkg && (m.conversationTitle === convTitle || m.sender === convTitle);
          }
          return m.packageName === pkg;
        });

      const sbnKey = target ? target.sbnKey : `0|${pkg}|1001|null|10234`;
      const logEntry = {
        time: ts,
        hook: 'onNotificationRemoved',
        pkg,
        sbnKey,
        extras: {
          reason: `${reason} (REASON_APP_CANCEL - App tự rút thông báo)`,
          'android.conversationTitle': target ? target.conversationTitle : convTitle
        },
        action: (reason === 8 && target)
          ? 'UNSENT_DETECTED (Layer 2: APP_CANCEL_RETRACT)'
          : 'IGNORED_DISMISSAL'
      };
      sbnLogs.unshift(logEntry);

      if (reason === 8 && target) {
        target.isUnsent = true;
        target.unsentReason = 'APP_CANCEL_RETRACT';
        target.unsentAt = ts;
        saveStore();
        broadcastSse('unsent', { message: target, log: logEntry });
        return sendJson(res, 200, { status: 'UNSENT_DETECTED', message: target, log: logEntry });
      }

      return sendJson(res, 200, { status: 'NO_TARGET_FOUND', log: logEntry });
    } catch (err) {
      return sendJson(res, 400, { error: err.message });
    }
  }

  // Mô phỏng Đối phương Chỉnh sửa tin nhắn (Bundle Diff Edit Detection v4.0)
  if (pathname === '/api/simulate/edit' && req.method === 'POST') {
    try {
      const body = await parseJsonBody(req);
      const pkg = body.packageName || 'com.facebook.orca';
      const convTitle = (body.conversationTitle || body.sender || '').trim();
      const newContent = (body.newContent || body.content || 'Nội dung đã chỉnh sửa').trim();
      const ts = Date.now();

      const sorted = [...messages].sort((a, b) => b.timestamp - a.timestamp);
      let target = sorted.find(m => {
        if (convTitle) {
          return m.packageName === pkg && (m.conversationTitle === convTitle || m.sender === convTitle);
        }
        return m.packageName === pkg;
      });
      if (!target) {
        target = sorted.find(m => m.packageName === pkg) || sorted[0];
      }

      const sbnKey = target ? target.sbnKey : `0|${pkg}|1001|null|10234`;
      if (target) {
        if (!target.originalContent) {
          target.originalContent = target.content;
        }
        target.content = newContent;
        target.isUnsent = true;
        target.unsentReason = 'MESSAGE_EDITED';
        target.unsentAt = ts;
      }

      const logEntry = {
        time: ts,
        hook: 'onNotificationPosted (BundleDiff)',
        pkg,
        sbnKey,
        extras: {
          'android.conversationTitle': target ? target.conversationTitle : convTitle,
          oldText: target ? target.originalContent : '(Không tìm thấy)',
          newText: newContent
        },
        action: target
          ? 'EDITED_DETECTED (v4.0: MESSAGE_EDITED)'
          : 'IGNORED_EDIT'
      };
      sbnLogs.unshift(logEntry);

      if (target) {
        saveStore();
        broadcastSse('unsent', { message: target, log: logEntry });
        return sendJson(res, 200, { status: 'EDITED_DETECTED', message: target, log: logEntry });
      }
      return sendJson(res, 200, { status: 'NO_TARGET_FOUND', log: logEntry });
    } catch (err) {
      return sendJson(res, 400, { error: err.message });
    }
  }

  // Endpoint nhận dữ liệu đồng bộ thật từ điện thoại Android qua Wi-Fi LAN
  if (pathname === '/api/ingest' && req.method === 'POST') {
    try {
      const body = await parseJsonBody(req);
      const incoming = body.message || body;
      const eventType = body.eventType || (incoming.isUnsent ? 'unsent' : 'message');
      const ts = Date.now();

      const existing = messages.find(m =>
        m.packageName === incoming.packageName &&
        m.sender === incoming.sender &&
        m.content === incoming.content &&
        Math.abs(m.timestamp - (incoming.timestamp || ts)) < 60000
      );

      let record;
      if (existing) {
        existing.isUnsent = Boolean(incoming.isUnsent);
        existing.unsentReason = incoming.unsentReason || 'ANDROID_LAN_SYNC';
        existing.unsentAt = incoming.unsentAt || ts;
        record = existing;
      } else {
        record = {
          id: nextId++,
          sbnKey: incoming.sbnKey || 'lan|android',
          packageName: incoming.packageName || 'com.facebook.orca',
          appName: incoming.appName || 'Messenger',
          conversationTitle: incoming.conversationTitle || incoming.sender || 'Messenger',
          sender: incoming.sender || 'Messenger',
          content: incoming.content || '',
          imageBase64: incoming.imageBase64 || '',
          avatarBase64: incoming.avatarBase64 || '',
          timestamp: incoming.timestamp || ts,
          isUnsent: Boolean(incoming.isUnsent),
          unsentReason: incoming.unsentReason || '',
          unsentAt: incoming.unsentAt || 0
        };
        messages.push(record);
      }

      const logEntry = {
        time: ts,
        hook: 'AndroidLAN_Sync',
        pkg: record.packageName,
        sbnKey: record.sbnKey,
        extras: {
          'android.title': record.sender,
          'android.text': record.content
        },
        action: eventType === 'unsent' ? 'REAL_DEVICE_UNSENT' : 'REAL_DEVICE_MESSAGE'
      };
      sbnLogs.unshift(logEntry);
      saveStore();
      broadcastSse(eventType === 'unsent' ? 'unsent' : 'message', { message: record, log: logEntry });
      return sendJson(res, 200, { ok: true, record });
    } catch (err) {
      return sendJson(res, 400, { error: err.message });
    }
  }

  // Xóa lịch sử hoặc Reset dữ liệu mẫu
  if (pathname === '/api/messages' && req.method === 'DELETE') {
    const mode = urlObj.searchParams.get('mode') || 'clear';
    if (mode === 'seed') {
      seedInitialData();
    } else {
      messages = [];
      sbnLogs = [];
    }
    saveStore();
    broadcastSse('reload', {});
    return sendJson(res, 200, { ok: true });
  }

  // Xem mã nguồn Java Android thực tế ngay trên giao diện
  if (pathname === '/api/source' && req.method === 'GET') {
    const files = {
      'MessengerNotificationService.java': path.join(__dirname, 'android-app/java-src/com/msgguard/app/MessengerNotificationService.java'),
      'MessageDatabase.java': path.join(__dirname, 'android-app/java-src/com/msgguard/app/MessageDatabase.java'),
      'MainActivity.java': path.join(__dirname, 'android-app/java-src/com/msgguard/app/MainActivity.java'),
      'AndroidManifest.xml': path.join(__dirname, 'android-app/app/src/main/AndroidManifest.xml'),
      'build-apk.ps1': path.join(__dirname, 'build-apk.ps1')
    };
    const result = {};
    for (const [name, filePath] of Object.entries(files)) {
      try {
        result[name] = fs.readFileSync(filePath, 'utf8');
      } catch (e) {
        result[name] = '// Không đọc được file: ' + e.message;
      }
    }
    return sendJson(res, 200, { files: result });
  }

  // Phục vụ file tĩnh trong thư mục public/
  let safePath = path.normalize(pathname === '/' ? '/index.html' : pathname).replace(/^(\.\.[/\\])+/, '');
  let filePath = path.join(PUBLIC_DIR, safePath);

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const ext = path.extname(filePath).toLowerCase();
    const mimeTypes = {
      '.html': 'text/html; charset=utf-8',
      '.css': 'text/css; charset=utf-8',
      '.js': 'application/javascript; charset=utf-8',
      '.json': 'application/json; charset=utf-8',
      '.svg': 'image/svg+xml',
      '.apk': 'application/vnd.android.package-archive'
    };
    const stat = fs.statSync(filePath);
    const headers = {
      'Content-Type': mimeTypes[ext] || 'application/octet-stream',
      'Content-Length': stat.size,
      'Cache-Control': 'no-store, no-cache, must-revalidate, max-age=0'
    };
    if (ext === '.apk') {
      headers['Content-Disposition'] = `attachment; filename="${path.basename(filePath)}"`;
    }
    res.writeHead(200, headers);
    fs.createReadStream(filePath).pipe(res);
  } else {
    sendJson(res, 404, { error: 'Not Found' });
  }
});

server.listen(PORT, () => {
  console.log(`============================================================`);
  console.log(`🛡️  MsgGuard Android Simulator & Sync Server đang chạy tại:`);
  console.log(`👉 Local:   http://localhost:${PORT}`);
  console.log(`👉 LAN IP:  http://${getLocalIp()}:${PORT}`);
  console.log(`============================================================`);
});
