const isNativeAndroid = typeof window.AndroidBridge !== 'undefined';

let currentView = 'unsent';
let currentPkgFilter = 'ALL';
let searchQuery = '';
let allMessages = [];
let sbnLogs = [];
let sourceFilesCache = {};
let activeCodeFile = 'MessengerNotificationService.java';
let activeConversationTitle = null;
let headsupTimer = null;

// Quản lý khóa PIN 4 số & Danh sách VIP Watchlist
let savedPinCode = localStorage.getItem('msgguard_pin') || '';
let savedVipWatchlist = localStorage.getItem('msgguard_vip') || 'Minh Anh, Crush, Sếp';
let enteredPin = '';

document.addEventListener('DOMContentLoaded', () => {
  if (isNativeAndroid) {
    document.body.classList.add('native-android-mode');
  }

  updateClock();
  setInterval(updateClock, 15000);

  bindAppNavigation();
  bindPinKeypad();
  bindSimulatorControls();
  bindSourceCodeViewer();

  refreshPermissionState();
  checkInitialPinLock();
  loadMessages();

  if (isNativeAndroid) {
    // Đã có BroadcastReceiver (window.onNativeMessageEvent) cập nhật tức thì 0ms,
    // nên chỉ cần đồng bộ dự phòng mỗi 15s để tiết kiệm 100% CPU & tránh đọc SQLite liên tục
    setInterval(loadMessages, 15000);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) {
        refreshPermissionState();
        loadMessages();
      }
    });
  } else {
    connectSseStream();
    loadSourceFiles();
  }
});

function updateClock() {
  const el = document.getElementById('android-clock');
  if (!el) return;
  const d = new Date();
  el.textContent = d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function formatTime(ts) {
  if (!ts) return '';
  const d = new Date(Number(ts));
  return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

function getAppBadgeClass(pkg) {
  if (pkg === 'com.zing.zalo') return 'zalo';
  if (pkg === 'com.instagram.android') return 'instagram';
  return '';
}

function getInitials(name) {
  const clean = String(name || 'M').trim();
  return clean.charAt(0).toUpperCase();
}

function formatReasonLabel(reason) {
  if (reason === 'MESSAGE_EDITED') return '✏️ Đã phát hiện đối phương CHỈNH SỬA tin nhắn (Lưu giữ bản gốc)';
  if (reason === 'BUNDLE_DIFF_RECALL') return '📚 Bắt qua so sánh chuỗi tin nhắn (Bundle Diff)';
  if (reason === 'TEXT_RECALL') return '🛡️ Bắt qua thông báo "Đã thu hồi tin nhắn" (Layer 1)';
  if (reason === 'APP_CANCEL_RETRACT') return '👻 Bắt qua lệnh rút thông báo ngầm (Layer 2)';
  if (reason === 'MANUAL_MARK') return '📌 Đánh dấu thủ công';
  return '🔔 Đã phát hiện thu hồi';
}

/* =========================================================
   KHÓA MÃ PIN 4 SỐ BẢO MẬT (NÂNG CẤP 4)
   ========================================================= */
function checkInitialPinLock() {
  if (savedPinCode && savedPinCode.length === 4) {
    showPinLockScreen();
  }
}

function showPinLockScreen() {
  enteredPin = '';
  updatePinDots();
  document.getElementById('pin-lock-overlay').classList.remove('hidden');
}

function updatePinDots(isError = false) {
  const dotsBox = document.getElementById('pin-dots');
  if (!dotsBox) return;
  dotsBox.classList.toggle('error', isError);
  const spans = dotsBox.querySelectorAll('span');
  spans.forEach((sp, idx) => {
    sp.classList.toggle('filled', idx < enteredPin.length);
  });
}

function bindPinKeypad() {
  document.querySelectorAll('.pin-key[data-digit]').forEach(btn => {
    btn.addEventListener('click', () => {
      if (enteredPin.length >= 4) return;
      enteredPin += btn.getAttribute('data-digit');
      updatePinDots(false);

      if (enteredPin.length === 4) {
        if (enteredPin === savedPinCode) {
          setTimeout(() => {
            document.getElementById('pin-lock-overlay').classList.add('hidden');
            enteredPin = '';
            updatePinDots(false);
          }, 120);
        } else {
          updatePinDots(true);
          document.getElementById('pin-prompt-text').textContent = '❌ Sai mã PIN, vui lòng thử lại!';
          setTimeout(() => {
            enteredPin = '';
            updatePinDots(false);
          }, 450);
        }
      }
    });
  });

  const clearBtn = document.getElementById('pin-key-clear');
  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      enteredPin = '';
      updatePinDots(false);
    });
  }

  const backBtn = document.getElementById('pin-key-back');
  if (backBtn) {
    backBtn.addEventListener('click', () => {
      enteredPin = enteredPin.slice(0, -1);
      updatePinDots(false);
    });
  }

  const lockNowBtn = document.getElementById('btn-lock-now');
  if (lockNowBtn) {
    lockNowBtn.addEventListener('click', () => {
      if (!savedPinCode || savedPinCode.length !== 4) {
        currentView = 'settings';
        document.querySelectorAll('.app-bottom-nav .nav-item').forEach(b => {
          b.classList.toggle('active', b.getAttribute('data-view') === 'settings');
        });
        renderPhoneViews();
        document.getElementById('input-pin-code').focus();
        showAndroidHeadsUp({
          icon: '🔐',
          appLabel: 'Bảo mật MsgGuard',
          title: 'Bạn chưa đặt mã PIN 4 số',
          text: 'Hãy nhập 4 chữ số ở mục Khóa Mã PIN rồi bấm Lưu cấu hình!',
          isAlert: false
        });
      } else {
        showPinLockScreen();
      }
    });
  }
}

/* =========================================================
   QUẢN LÝ QUYỀN ANDROID & CÀI ĐẶT PRO
   ========================================================= */
window.refreshPermissionState = function () {
  const banner = document.getElementById('perm-banner');
  const icon = document.getElementById('perm-icon');
  const title = document.getElementById('perm-title');
  const desc = document.getElementById('perm-desc');

  if (isNativeAndroid) {
    try {
      const status = JSON.parse(window.AndroidBridge.getPermissionStatus());
      if (status.notificationListener) {
        banner.classList.remove('missing');
        banner.classList.add('granted');
        if (status.listenerConnected) {
          icon.textContent = '✅';
          title.textContent = 'Dịch Vụ Bắt Tin Nhắn: ĐANG CHẠY 24/7';
          desc.textContent = 'Đã kết nối NotificationListener • Đang bắt mọi tin nhắn đến';
        } else {
          icon.textContent = '🔄';
          title.textContent = 'Quyền Đã Bật (Đang nối lại dịch vụ ngầm)';
          desc.textContent = 'Bấm "🔄 Kết nối lại" hoặc vào Cài đặt Tắt rồi Bật lại MsgGuard';
        }
      } else {
        banner.classList.remove('granted');
        banner.classList.add('missing');
        icon.textContent = '⚠️';
        title.textContent = 'CHƯA CẤP QUYỀN ĐỌC THÔNG BÁO';
        desc.textContent = 'Bấm "Cài đặt" để bật quyền cho MsgGuard';
      }

      const settings = JSON.parse(window.AndroidBridge.getSettings());
      document.getElementById('chk-notify-unsend').checked = Boolean(settings.notifyOnUnsend);
      const keepAliveEl = document.getElementById('chk-keep-alive');
      if (keepAliveEl) keepAliveEl.checked = settings.keepAliveNotif !== false;
      if (settings.pinCode) {
        savedPinCode = settings.pinCode;
        localStorage.setItem('msgguard_pin', savedPinCode);
        document.getElementById('input-pin-code').value = savedPinCode;
      }
      if (typeof settings.vipWatchlist === 'string' && settings.vipWatchlist) {
        savedVipWatchlist = settings.vipWatchlist;
        localStorage.setItem('msgguard_vip', savedVipWatchlist);
      }
      const vipEl = document.getElementById('input-vip-watchlist');
      if (vipEl) vipEl.value = savedVipWatchlist;
    } catch (_) {}
  } else {
    document.getElementById('input-pin-code').value = savedPinCode;
    const vipEl = document.getElementById('input-vip-watchlist');
    if (vipEl) vipEl.value = savedVipWatchlist;
  }
};

function isMessageVip(m) {
  if (m.isStarred) return true;
  if (!savedVipWatchlist) return false;
  const combined = ((m.sender || '') + ' ' + (m.conversationTitle || '')).toLowerCase();
  return savedVipWatchlist
    .split(',')
    .map(s => s.trim().toLowerCase())
    .filter(Boolean)
    .some(kw => combined.includes(kw));
}

/* =========================================================
   TẢI DỮ LIỆU TIN NHẮN & RENDER GIAO DIỆN ĐIỆN THOẠI
   ========================================================= */
async function loadMessages() {
  if (isNativeAndroid) {
    try {
      const raw = window.AndroidBridge.getMessages(false, '');
      allMessages = JSON.parse(raw || '[]');
      renderPhoneViews();
    } catch (_) {}
    return;
  }

  try {
    const res = await fetch('/api/messages');
    const data = await res.json();
    allMessages = data.messages || [];
    sbnLogs = data.sbnLogs || [];
    renderPhoneViews();
    renderSbnLogs();
  } catch (err) {
    console.error('Lỗi tải tin nhắn:', err);
  }
}
window.loadMessages = loadMessages;

function getFilteredMessages(onlyUnsentFlag) {
  return allMessages.filter(m => {
    if (onlyUnsentFlag && !m.isUnsent) return false;
    if (currentPkgFilter === 'STARRED_VIP') {
      if (!isMessageVip(m)) return false;
    } else if (currentPkgFilter !== 'ALL' && m.packageName !== currentPkgFilter) {
      return false;
    }
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      const match =
        (m.sender || '').toLowerCase().includes(q) ||
        (m.conversationTitle || '').toLowerCase().includes(q) ||
        (m.content || '').toLowerCase().includes(q);
      if (!match) return false;
    }
    return true;
  });
}

function renderAvatarHtml(m) {
  if (m.avatarBase64) {
    return `<div class="sender-avatar"><img src="${m.avatarBase64}" alt="Avatar" /></div>`;
  }
  return `<div class="sender-avatar">${escapeHtml(getInitials(m.sender))}</div>`;
}

function renderPhoneViews() {
  const unsentTotal = allMessages.filter(m => m.isUnsent).length;
  const allTotal = allMessages.length;

  document.getElementById('badge-unsent-count').textContent = unsentTotal;
  document.getElementById('badge-all-count').textContent = allTotal;

  const pcTotal = document.getElementById('pc-stat-total');
  const pcUnsent = document.getElementById('pc-stat-unsent');
  if (pcTotal) pcTotal.textContent = allTotal;
  if (pcUnsent) pcUnsent.textContent = unsentTotal;

  const listView = document.getElementById('message-list-view');
  const convView = document.getElementById('conversation-view');
  const leaderboardView = document.getElementById('leaderboard-view');
  const settingsView = document.getElementById('settings-view');

  listView.classList.add('hidden');
  convView.classList.add('hidden');
  if (leaderboardView) leaderboardView.classList.add('hidden');
  settingsView.classList.add('hidden');

  if (currentView === 'unsent' || currentView === 'all') {
    listView.classList.remove('hidden');
    let items = getFilteredMessages(currentView === 'unsent');
    let fallbackBannerHtml = '';

    if (currentView === 'unsent' && items.length === 0) {
      const normalItems = getFilteredMessages(false);
      if (normalItems.length > 0) {
        items = normalItems;
        fallbackBannerHtml = `
          <div style="background:rgba(16,185,129,0.12);border:1px solid rgba(16,185,129,0.35);border-radius:10px;padding:8px 10px;margin-bottom:8px;font-size:11.5px;color:#a7f3d0;">
            🟢 <strong>Đang bắt tin nhắn bình thường (${normalItems.length} tin):</strong> Chưa có ai thu hồi, dưới đây là các tin nhắn mới nhất vừa bắt được:
          </div>
        `;
      }
    }

    if (items.length === 0) {
      listView.innerHTML = `
        <div class="empty-state">
          <div style="font-size:32px;margin-bottom:8px;">${currentView === 'unsent' ? '🕵️‍♂️' : '📭'}</div>
          <strong>${currentView === 'unsent' ? 'Chưa có tin nhắn nào bị gỡ' : 'Chưa có tin nhắn nào'}</strong>
          <p style="margin-top:4px;">Khi đối phương gửi tin nhắn trên Messenger/Zalo, nội dung gốc và ảnh sẽ tự động hiện ngay tại đây!</p>
        </div>
      `;
      return;
    }

    listView.innerHTML = fallbackBannerHtml + items.map(m => {
      const vipFlag = isMessageVip(m);
      const isEdited = m.unsentReason === 'MESSAGE_EDITED' && m.originalContent;
      return `
      <div class="msg-card ${m.isUnsent ? 'unsent' : ''}" data-id="${m.id}">
        <div class="msg-top">
          <div class="msg-sender-group">
            ${renderAvatarHtml(m)}
            <span class="app-pill ${getAppBadgeClass(m.packageName)}">${escapeHtml(m.appName)}</span>
            <span class="msg-sender" title="${escapeHtml(m.sender)}">${escapeHtml(m.sender)}</span>
            ${vipFlag ? `<span style="background:rgba(245,158,11,0.22);color:#fcd34d;border:1px solid rgba(245,158,11,0.45);font-size:9.5px;font-weight:800;padding:1px 6px;border-radius:99px;flex-shrink:0;">👑 VIP</span>` : ''}
          </div>
          ${isEdited
            ? `<span class="badge-unsent" style="background:rgba(245,158,11,0.22);color:#fde68a;border-color:rgba(245,158,11,0.5);">✏️ ĐÃ CHỈNH SỬA</span>`
            : m.isUnsent
            ? `<span class="badge-unsent">🔥 ĐÃ THU HỒI</span>`
            : `<span class="badge-normal">Đã lưu ngầm</span>`
          }
        </div>
        ${isEdited ? `
          <div style="background:rgba(239,68,68,0.14);border-left:3px solid #f87171;padding:6px 9px;border-radius:6px;margin-bottom:6px;font-size:12px;color:#fecaca;">
            <strong>📜 Câu gốc trước khi sửa:</strong> "${escapeHtml(m.originalContent)}"
          </div>
          <div class="msg-body"><strong>✏️ Đã sửa thành:</strong> ${escapeHtml(m.content)}</div>
        ` : `
          <div class="msg-body">${escapeHtml(m.content)}</div>
        `}
        ${m.imageBase64 ? `
          <div class="msg-photo">
            <img src="${m.imageBase64}" alt="Ảnh đính kèm đã lưu" />
          </div>
        ` : ''}
        <div class="msg-footer">
          <span>Gửi lúc: ${formatTime(m.timestamp)}</span>
          ${m.isUnsent
            ? `<span class="unsent-reason-tag">${formatReasonLabel(m.unsentReason)}</span>`
            : `<span>Chưa hiện "Đã xem"</span>`
          }
        </div>
        <div class="msg-actions-row">
          <button class="btn-msg-act act-copy" data-id="${m.id}">📋 Copy</button>
          <button class="btn-msg-act act-star" data-id="${m.id}">
            ${m.isStarred ? '⭐ Đã Ghim' : '☆ Ghim'}
          </button>
          ${m.imageBase64 ? `<button class="btn-msg-act act-save-img" data-id="${m.id}">💾 Tải ảnh</button>` : ''}
          <button class="btn-msg-act act-toggle-unsend" data-id="${m.id}">
            ${m.isUnsent ? '↩️ Bỏ gỡ' : '🔥 Báo gỡ'}
          </button>
          <button class="btn-msg-act danger act-delete" data-id="${m.id}">🗑️</button>
        </div>
      </div>
    `}).join('');

    bindMessageCardActions(listView);
  } else if (currentView === 'conversations') {
    convView.classList.remove('hidden');
    renderConversationsView();
  } else if (currentView === 'leaderboard') {
    if (leaderboardView) leaderboardView.classList.remove('hidden');
    renderLeaderboardView();
  } else if (currentView === 'settings') {
    settingsView.classList.remove('hidden');
  }
}

function bindMessageCardActions(container) {
  // Copy chữ
  container.querySelectorAll('.act-copy').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = Number(btn.getAttribute('data-id'));
      const msg = allMessages.find(x => x.id === id);
      if (!msg) return;
      if (isNativeAndroid && window.AndroidBridge.copyToClipboard) {
        window.AndroidBridge.copyToClipboard(msg.content);
      } else if (navigator.clipboard) {
        navigator.clipboard.writeText(msg.content);
      }
      btn.textContent = '✅ Đã Copy!';
      setTimeout(() => { btn.textContent = '📋 Copy chữ'; }, 1200);
    });
  });

  // Tải ảnh đính kèm
  container.querySelectorAll('.act-save-img').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = Number(btn.getAttribute('data-id'));
      const msg = allMessages.find(x => x.id === id);
      if (!msg || !msg.imageBase64) return;
      const a = document.createElement('a');
      a.href = msg.imageBase64;
      a.download = `anh-da-go-${id}.jpg`;
      a.click();
    });
  });

  // Đánh dấu / bỏ đánh dấu Đã gỡ
  container.querySelectorAll('.act-toggle-unsend').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = Number(btn.getAttribute('data-id'));
      if (isNativeAndroid && window.AndroidBridge.toggleMessageUnsent) {
        window.AndroidBridge.toggleMessageUnsent(id);
        loadMessages();
      } else {
        const msg = allMessages.find(x => x.id === id);
        if (msg) {
          msg.isUnsent = !msg.isUnsent;
          msg.unsentReason = msg.isUnsent ? 'MANUAL_MARK' : '';
          msg.unsentAt = msg.isUnsent ? Date.now() : 0;
          renderPhoneViews();
        }
      }
    });
  });

  // Ghim Sao quan trọng (Không bao giờ bị xóa khi Auto-Clean)
  container.querySelectorAll('.act-star').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = Number(btn.getAttribute('data-id'));
      if (isNativeAndroid && window.AndroidBridge.toggleMessageStar) {
        window.AndroidBridge.toggleMessageStar(id);
        loadMessages();
      } else {
        const msg = allMessages.find(x => x.id === id);
        if (msg) {
          msg.isStarred = !msg.isStarred;
          renderPhoneViews();
        }
      }
    });
  });

  // Xóa lẻ từng tin nhắn
  container.querySelectorAll('.act-delete').forEach(btn => {
    btn.addEventListener('click', () => {
      const id = Number(btn.getAttribute('data-id'));
      if (isNativeAndroid && window.AndroidBridge.deleteMessage) {
        window.AndroidBridge.deleteMessage(id);
        loadMessages();
      } else {
        allMessages = allMessages.filter(x => x.id !== id);
        renderPhoneViews();
      }
    });
  });
}

function renderLeaderboardView() {
  const listEl = document.getElementById('leaderboard-list');
  if (!listEl) return;

  const statsMap = new Map();
  for (const m of allMessages) {
    const key = m.conversationTitle || m.sender || 'Không rõ';
    if (!statsMap.has(key)) {
      statsMap.set(key, {
        title: key,
        appName: m.appName,
        packageName: m.packageName,
        sampleMsg: m,
        total: 0,
        unsent: 0
      });
    }
    const st = statsMap.get(key);
    st.total++;
    if (m.isUnsent) st.unsent++;
  }

  const ranked = Array.from(statsMap.values())
    .sort((a, b) => (b.unsent - a.unsent) || (b.total - a.total));

  if (ranked.length === 0) {
    listEl.innerHTML = `<div class="empty-state">Chưa có dữ liệu thống kê.</div>`;
    return;
  }

  const medals = ['🥇 Hạng 1', '🥈 Hạng 2', '🥉 Hạng 3'];
  listEl.innerHTML = ranked.map((r, idx) => {
    const ratio = r.total > 0 ? Math.round((r.unsent / r.total) * 100) : 0;
    const rankLabel = medals[idx] || `#${idx + 1}`;
    return `
      <div class="conv-item" data-leader="${escapeHtml(r.title)}" style="flex-direction:column;align-items:stretch;gap:8px;">
        <div style="display:flex;align-items:center;justify-content:space-between;gap:8px;">
          <div style="display:flex;align-items:center;gap:8px;min-width:0;">
            ${renderAvatarHtml(r.sampleMsg)}
            <div style="min-width:0;">
              <div style="display:flex;align-items:center;gap:6px;">
                <span style="font-size:11px;font-weight:800;color:#fbbf24;">${rankLabel}</span>
                <strong style="font-size:13.5px;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(r.title)}</strong>
              </div>
              <span style="font-size:11px;color:#94a3b8;">${escapeHtml(r.appName)} • Tổng ${r.total} tin</span>
            </div>
          </div>
          <span class="badge-unsent">🔥 Gỡ ${r.unsent} lần (${ratio}%)</span>
        </div>
        <div style="width:100%;height:6px;background:rgba(15,23,42,0.8);border-radius:99px;overflow:hidden;">
          <div style="width:${Math.max(ratio, r.unsent > 0 ? 12 : 0)}%;height:100%;background:linear-gradient(90deg,#f59e0b,#ef4444);"></div>
        </div>
      </div>
    `;
  }).join('');

  listEl.querySelectorAll('.conv-item').forEach(item => {
    item.addEventListener('click', () => {
      activeConversationTitle = item.getAttribute('data-leader');
      currentView = 'conversations';
      document.querySelectorAll('.app-bottom-nav .nav-item').forEach(b => {
        b.classList.toggle('active', b.getAttribute('data-view') === 'conversations');
      });
      renderPhoneViews();
    });
  });
}

function renderConversationsView() {
  const convListEl = document.getElementById('conv-list');
  const convDetailEl = document.getElementById('conv-detail');

  if (activeConversationTitle) {
    convListEl.classList.add('hidden');
    convDetailEl.classList.remove('hidden');
    document.getElementById('conv-detail-title').textContent = activeConversationTitle;

    const threadMsgs = allMessages
      .filter(m => m.conversationTitle === activeConversationTitle)
      .sort((a, b) => a.timestamp - b.timestamp);

    document.getElementById('chat-bubbles').innerHTML = threadMsgs.map(m => `
      <div class="bubble-item ${m.isUnsent ? 'unsent' : ''}">
        <div style="display:flex;justify-content:space-between;font-size:11px;margin-bottom:4px;color:#94a3b8;">
          <strong>${escapeHtml(m.sender)}</strong>
          <span>${formatTime(m.timestamp)}</span>
        </div>
        <div style="color:#f8fafc;line-height:1.4;">${escapeHtml(m.content)}</div>
        ${m.imageBase64 ? `<div class="msg-photo"><img src="${m.imageBase64}" /></div>` : ''}
        ${m.isUnsent ? `
          <div style="margin-top:6px;font-size:10.5px;color:#fca5a5;font-weight:700;">
            ${formatReasonLabel(m.unsentReason)}
          </div>
        ` : ''}
      </div>
    `).join('');
    return;
  }

  convDetailEl.classList.add('hidden');
  convListEl.classList.remove('hidden');

  const groups = new Map();
  for (const m of getFilteredMessages(false)) {
    const key = m.conversationTitle || m.sender;
    if (!groups.has(key)) {
      groups.set(key, {
        title: key,
        appName: m.appName,
        packageName: m.packageName,
        lastMsg: m,
        total: 0,
        unsent: 0
      });
    }
    const g = groups.get(key);
    g.total++;
    if (m.isUnsent) g.unsent++;
  }

  const arr = Array.from(groups.values());
  if (arr.length === 0) {
    convListEl.innerHTML = `<div class="empty-state">Chưa có cuộc trò chuyện nào.</div>`;
    return;
  }

  convListEl.innerHTML = arr.map(g => `
    <div class="conv-item" data-conv="${escapeHtml(g.title)}">
      <div style="display:flex;align-items:center;gap:10px;min-width:0;flex:1;padding-right:8px;">
        ${renderAvatarHtml(g.lastMsg)}
        <div style="min-width:0;flex:1;">
          <div style="display:flex;align-items:center;gap:6px;margin-bottom:3px;">
            <span class="app-pill ${getAppBadgeClass(g.packageName)}">${escapeHtml(g.appName)}</span>
            <strong style="font-size:13.5px;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(g.title)}</strong>
          </div>
          <div style="font-size:12px;color:#94a3b8;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">
            ${escapeHtml(g.lastMsg.content)}
          </div>
        </div>
      </div>
      <div style="text-align:right;flex-shrink:0;">
        ${g.unsent > 0 ? `<span class="badge-unsent">🔥 ${g.unsent} tin gỡ</span>` : `<span class="badge-normal">${g.total} tin</span>`}
      </div>
    </div>
  `).join('');

  convListEl.querySelectorAll('.conv-item').forEach(item => {
    item.addEventListener('click', () => {
      activeConversationTitle = item.getAttribute('data-conv');
      renderConversationsView();
    });
  });
}

function showAndroidHeadsUp({ icon, appLabel, title, text, isAlert }) {
  const box = document.getElementById('android-headsup');
  if (!box) return;

  document.getElementById('headsup-icon').textContent = icon || '💬';
  document.getElementById('headsup-app').textContent = appLabel || 'Messenger • bây giờ';
  document.getElementById('headsup-title').textContent = title || '';
  document.getElementById('headsup-text').textContent = text || '';

  if (isAlert) {
    box.classList.add('alert-mode');
  } else {
    box.classList.remove('alert-mode');
  }

  box.classList.remove('hidden');
  if (headsupTimer) clearTimeout(headsupTimer);
  headsupTimer = setTimeout(() => {
    box.classList.add('hidden');
  }, 4200);
}

window.onNativeMessageEvent = function (eventType, msgJsonStr) {
  try {
    const msg = typeof msgJsonStr === 'string' ? JSON.parse(msgJsonStr) : msgJsonStr;
    loadMessages();
    if (eventType === 'UNSENT_DETECTED') {
      showAndroidHeadsUp({
        icon: '🚨',
        appLabel: 'MsgGuard PRO • vừa xong',
        title: `${msg.sender} vừa gỡ 1 tin nhắn!`,
        text: `Nội dung gốc: "${msg.content}"`,
        isAlert: true
      });
    } else {
      showAndroidHeadsUp({
        icon: '💬',
        appLabel: `${msg.appName} • vừa xong`,
        title: msg.sender,
        text: msg.content,
        isAlert: false
      });
    }
  } catch (_) {}
};

function connectSseStream() {
  const es = new EventSource('/api/events');
  es.addEventListener('message', e => {
    const data = JSON.parse(e.data);
    loadMessages();
    if (data.message) {
      showAndroidHeadsUp({
        icon: '💬',
        appLabel: `${data.message.appName} • bây giờ`,
        title: data.message.sender,
        text: data.message.content,
        isAlert: false
      });
    }
  });

  es.addEventListener('unsent', e => {
    const data = JSON.parse(e.data);
    loadMessages();
    if (data.message) {
      showAndroidHeadsUp({
        icon: '🚨',
        appLabel: 'MsgGuard PRO • vừa xong',
        title: `🚨 ${data.message.sender} vừa thu hồi tin nhắn!`,
        text: `Nội dung gốc đã cứu: "${data.message.content}"`,
        isAlert: true
      });
    }
  });

  es.addEventListener('reload', () => {
    loadMessages();
  });
}

function renderSbnLogs() {
  const container = document.getElementById('sbn-log-list');
  if (!container) return;

  if (!sbnLogs || sbnLogs.length === 0) {
    container.innerHTML = `<div style="color:#64748b;font-size:12px;">Chưa có gói StatusBarNotification nào...</div>`;
    return;
  }

  container.innerHTML = sbnLogs.map(log => {
    const isUnsent = String(log.action || '').includes('UNSENT');
    return `
      <div class="sbn-item ${isUnsent ? 'unsent-hook' : ''}">
        <div class="sbn-item-top">
          <span>[${formatTime(log.time)}] ${escapeHtml(log.hook)}() → ${escapeHtml(log.pkg)}</span>
          <span style="color:${isUnsent ? '#fca5a5' : '#34d399'};">${escapeHtml(log.action)}</span>
        </div>
        <div class="sbn-item-extras">
          Bundle Extras: ${escapeHtml(JSON.stringify(log.extras))}
        </div>
      </div>
    `;
  }).join('');
}

function bindAppNavigation() {
  document.querySelectorAll('.app-bottom-nav .nav-item').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.app-bottom-nav .nav-item').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentView = btn.getAttribute('data-view');
      activeConversationTitle = null;
      renderPhoneViews();
    });
  });

  document.getElementById('btn-back-conv').addEventListener('click', () => {
    activeConversationTitle = null;
    renderConversationsView();
  });

  document.querySelectorAll('#app-filter-chips .chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('#app-filter-chips .chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      currentPkgFilter = chip.getAttribute('data-pkg');
      renderPhoneViews();
    });
  });

  document.getElementById('search-input').addEventListener('input', e => {
    searchQuery = e.target.value;
    renderPhoneViews();
  });

  const rebindBtn = document.getElementById('btn-rebind-listener');
  if (rebindBtn) {
    rebindBtn.addEventListener('click', () => {
      if (isNativeAndroid && window.AndroidBridge.forceRebindListener) {
        window.AndroidBridge.forceRebindListener();
        setTimeout(() => {
          refreshPermissionState();
          loadMessages();
        }, 400);
      } else {
        loadMessages();
      }
      showAndroidHeadsUp({
        icon: '🔄',
        appLabel: 'MsgGuard Keep-Alive',
        title: 'Đã làm mới kết nối bắt thông báo ngầm!',
        text: 'Dịch vụ NotificationListenerService đã được kích hoạt lại và đồng bộ tin nhắn mới nhất.',
        isAlert: false
      });
    });
  }

  document.getElementById('btn-open-perm').addEventListener('click', () => {
    if (isNativeAndroid) {
      window.AndroidBridge.openNotificationListenerSettings();
    } else {
      showAndroidHeadsUp({
        icon: '⚙️',
        appLabel: 'Android Settings',
        title: 'Mở màn hình Quyền Đọc Thông Báo',
        text: 'Trên điện thoại thật, nút này mở thẳng trang bật công tắc MsgGuard.',
        isAlert: false
      });
    }
  });

  const appInfoBtn = document.getElementById('btn-open-app-info');
  if (appInfoBtn) {
    appInfoBtn.addEventListener('click', () => {
      if (isNativeAndroid && window.AndroidBridge.openAppDetailsSettings) {
        window.AndroidBridge.openAppDetailsSettings();
      } else {
        showAndroidHeadsUp({
          icon: '📲',
          appLabel: 'Xiaomi HyperOS / MIUI Settings',
          title: 'Mở trang Thông tin Ứng dụng MsgGuard',
          text: 'Dùng để bật "Tự khởi chạy" và "Không hạn chế pin" trên máy Xiaomi.',
          isAlert: false
        });
      }
    });
  }

  document.getElementById('btn-battery-opt').addEventListener('click', () => {
    if (isNativeAndroid) {
      window.AndroidBridge.requestIgnoreBatteryOptimization();
    } else {
      showAndroidHeadsUp({
        icon: '🔋',
        appLabel: 'Android PowerManager',
        title: 'Mở cài đặt Không tối ưu hóa Pin',
        text: 'Giúp dịch vụ chạy ngầm 24/7 không bị tắt khi khóa màn hình.',
        isAlert: false
      });
    }
  });

  const clean7dBtn = document.getElementById('btn-smart-clean-7d');
  if (clean7dBtn) {
    clean7dBtn.addEventListener('click', () => {
      let deleted = 0;
      if (isNativeAndroid && window.AndroidBridge.cleanNormalMessages) {
        deleted = window.AndroidBridge.cleanNormalMessages(7);
        loadMessages();
      } else {
        const before = allMessages.length;
        const cutoff = Date.now() - 7 * 86400000;
        allMessages = allMessages.filter(m => m.isUnsent || isMessageVip(m) || m.timestamp >= cutoff);
        deleted = before - allMessages.length;
        renderPhoneViews();
      }
      showAndroidHeadsUp({
        icon: '🧹',
        appLabel: 'Dọn bộ nhớ thông minh',
        title: `Đã dọn ${deleted} tin nhắn thường cũ (> 7 ngày)`,
        text: '100% tin nhắn đã gỡ (🔥) và tin VIP/Ghim (⭐) được giữ nguyên!',
        isAlert: false
      });
    });
  }

  const cleanAllNormalBtn = document.getElementById('btn-smart-clean-all');
  if (cleanAllNormalBtn) {
    cleanAllNormalBtn.addEventListener('click', () => {
      let deleted = 0;
      if (isNativeAndroid && window.AndroidBridge.cleanNormalMessages) {
        deleted = window.AndroidBridge.cleanNormalMessages(0);
        loadMessages();
      } else {
        const before = allMessages.length;
        allMessages = allMessages.filter(m => m.isUnsent || isMessageVip(m));
        deleted = before - allMessages.length;
        renderPhoneViews();
      }
      showAndroidHeadsUp({
        icon: '✨',
        appLabel: 'Dọn bộ nhớ thông minh',
        title: `Đã dọn ${deleted} tin nhắn thường`,
        text: 'Chỉ giữ lại các tin nhắn ĐÃ GỠ (🔥) và tin VIP/Ghim (⭐)!',
        isAlert: false
      });
    });
  }

  document.getElementById('btn-save-settings').addEventListener('click', () => {
    const notifyFlag = document.getElementById('chk-notify-unsend').checked;
    const keepAliveFlag = document.getElementById('chk-keep-alive').checked;
    const rawPin = document.getElementById('input-pin-code').value.trim();
    const rawVip = (document.getElementById('input-vip-watchlist')?.value || '').trim();

    if (rawPin && !/^\d{4}$/.test(rawPin)) {
      showAndroidHeadsUp({
        icon: '⚠️',
        appLabel: 'Lỗi Mã PIN',
        title: 'Mã PIN phải gồm đúng 4 chữ số!',
        text: 'Ví dụ: 1234 (hoặc để trống nếu không muốn khóa mã PIN).',
        isAlert: true
      });
      return;
    }

    savedPinCode = rawPin;
    savedVipWatchlist = rawVip;
    localStorage.setItem('msgguard_pin', savedPinCode);
    localStorage.setItem('msgguard_vip', savedVipWatchlist);

    if (isNativeAndroid && window.AndroidBridge.saveSettingsV3) {
      window.AndroidBridge.saveSettingsV3(notifyFlag, keepAliveFlag, savedPinCode, savedVipWatchlist);
    } else if (isNativeAndroid && window.AndroidBridge.saveSettings) {
      window.AndroidBridge.saveSettings(notifyFlag, keepAliveFlag, savedPinCode);
    }

    renderPhoneViews();
    showAndroidHeadsUp({
      icon: '✅',
      appLabel: 'MsgGuard ULTIMATE v3.0',
      title: 'Đã lưu cấu hình v3.0 thành công!',
      text: savedVipWatchlist
        ? `Đang theo dõi VIP: ${savedVipWatchlist}`
        : 'Đã cập nhật chế độ bảo vệ ngầm 24/7.',
      isAlert: false
    });
  });

  document.getElementById('btn-export-json').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify(allMessages, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `msgguard-pro-history-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  });

  document.getElementById('btn-clear-all').addEventListener('click', async () => {
    if (isNativeAndroid) {
      window.AndroidBridge.clearAllMessages();
      loadMessages();
    } else {
      await fetch('/api/messages?mode=clear', { method: 'DELETE' });
      loadMessages();
    }
  });
}

function bindSimulatorControls() {
  document.querySelectorAll('.panel-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.panel-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      const target = tab.getAttribute('data-ptab');
      document.getElementById('ptab-simulator').classList.toggle('hidden', target !== 'simulator');
      document.getElementById('ptab-source').classList.toggle('hidden', target !== 'source');
    });
  });

  const sendBtn = document.getElementById('btn-sim-send');
  if (!sendBtn) return;

  sendBtn.addEventListener('click', async () => {
    const packageName = document.getElementById('sim-pkg').value;
    const sender = document.getElementById('sim-sender').value.trim() || 'Người gửi';
    const content = document.getElementById('sim-content').value.trim() || 'Tin nhắn thử nghiệm';
    const hasSamplePhoto = document.getElementById('sim-has-photo').checked;

    await fetch('/api/simulate/post', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ packageName, sender, conversationTitle: sender, content, hasSamplePhoto })
    });
  });

  document.getElementById('btn-sim-unsend-text').addEventListener('click', async () => {
    const packageName = document.getElementById('sim-pkg').value;
    const sender = document.getElementById('sim-sender').value.trim() || 'Người gửi';

    await fetch('/api/simulate/post', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ packageName, sender, conversationTitle: sender, content: `${sender} đã thu hồi một tin nhắn` })
    });
  });

  document.getElementById('btn-sim-unsend-cancel').addEventListener('click', async () => {
    const packageName = document.getElementById('sim-pkg').value;
    const sender = document.getElementById('sim-sender').value.trim() || 'Người gửi';

    await fetch('/api/simulate/remove', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ packageName, sender, conversationTitle: sender, reason: 8 })
    });
  });

  const editBtn = document.getElementById('btn-sim-edit');
  if (editBtn) {
    editBtn.addEventListener('click', async () => {
      const packageName = document.getElementById('sim-pkg').value;
      const sender = document.getElementById('sim-sender').value.trim() || 'Người gửi';
      const newContent = document.getElementById('sim-content').value.trim() || 'Nội dung đã chỉnh sửa lại';

      await fetch('/api/simulate/edit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ packageName, sender, conversationTitle: sender, newContent })
      });
    });
  }

  document.getElementById('btn-reset-seed').addEventListener('click', async () => {
    await fetch('/api/messages?mode=seed', { method: 'DELETE' });
  });

  document.getElementById('btn-scen-crush').addEventListener('click', async () => {
    const sender = 'Crush Nguyễn Bảo Ngọc 💕';
    const content = 'Nếu chủ nhật này trời không mưa thì mình đi xem phim riêng 2 đứa nha anh 😳👉👈';
    document.getElementById('sim-pkg').value = 'com.facebook.orca';
    document.getElementById('sim-sender').value = sender;
    document.getElementById('sim-content').value = content;
    document.getElementById('sim-has-photo').checked = false;

    await fetch('/api/simulate/post', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ packageName: 'com.facebook.orca', sender, conversationTitle: sender, content })
    });

    setTimeout(async () => {
      await fetch('/api/simulate/post', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ packageName: 'com.facebook.orca', sender, conversationTitle: sender, content: `${sender} đã thu hồi một tin nhắn` })
      });
    }, 1800);
  });

  document.getElementById('btn-scen-photo').addEventListener('click', async () => {
    const sender = 'Đức Phúc (Nhóm Bạn Thân)';
    const content = 'Tấm ảnh bí mật tối qua nè, xem nhanh kẻo tao xóa =)))';
    document.getElementById('sim-pkg').value = 'com.facebook.orca';
    document.getElementById('sim-sender').value = sender;
    document.getElementById('sim-content').value = content;
    document.getElementById('sim-has-photo').checked = true;

    await fetch('/api/simulate/post', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ packageName: 'com.facebook.orca', sender, conversationTitle: sender, content, hasSamplePhoto: true, photoCaption: 'Ảnh chụp trộm lúc nửa đêm' })
    });

    setTimeout(async () => {
      await fetch('/api/simulate/remove', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ packageName: 'com.facebook.orca', sender, conversationTitle: sender, reason: 8 })
      });
    }, 1800);
  });

  document.getElementById('btn-scen-boss').addEventListener('click', async () => {
    const sender = 'Giám Đốc Hoàng Minh';
    const content = 'File bảng thưởng Tết năm nay của phòng Kỹ thuật: mỗi bạn được 3 tháng lương nhé!';
    document.getElementById('sim-pkg').value = 'com.zing.zalo';
    document.getElementById('sim-sender').value = sender;
    document.getElementById('sim-content').value = content;
    document.getElementById('sim-has-photo').checked = false;

    await fetch('/api/simulate/post', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ packageName: 'com.zing.zalo', sender, conversationTitle: sender, content })
    });

    setTimeout(async () => {
      await fetch('/api/simulate/post', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ packageName: 'com.zing.zalo', sender, conversationTitle: sender, content: 'Tin nhắn đã được thu hồi' })
      });
    }, 1800);
  });
}

async function loadSourceFiles() {
  try {
    const res = await fetch('/api/source');
    const data = await res.json();
    sourceFilesCache = data.files || {};
    renderActiveCodeFile();
  } catch (_) {}
}

function renderActiveCodeFile() {
  const codeEl = document.getElementById('code-content');
  const pathEl = document.getElementById('current-code-filename');
  if (!codeEl) return;
  pathEl.textContent = activeCodeFile;
  codeEl.textContent = sourceFilesCache[activeCodeFile] || '// Đang tải...';
}

function bindSourceCodeViewer() {
  document.querySelectorAll('#code-file-tabs .cfile-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('#code-file-tabs .cfile-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activeCodeFile = btn.getAttribute('data-file');
      renderActiveCodeFile();
    });
  });

  const copyBtn = document.getElementById('btn-copy-code');
  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      const text = sourceFilesCache[activeCodeFile] || '';
      navigator.clipboard.writeText(text);
      copyBtn.textContent = '✅ Đã Copy!';
      setTimeout(() => { copyBtn.textContent = '📋 Copy mã nguồn'; }, 1500);
    });
  }
}
