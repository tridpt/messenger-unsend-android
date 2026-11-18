# 🛡️ MsgGuard — App Android Xem Tin Nhắn Đã Gỡ Trên Messenger (Notification Listener)

Ứng dụng Android bắt và lưu trữ tự động mọi tin nhắn đến từ **Facebook Messenger, Messenger Lite, Zalo, Instagram, Telegram** ngay khi vừa có thông báo đẩy — **không cần mở khung chat (không hiện "Đã xem / Seen")** và **giữ nguyên nội dung gốc + ảnh đính kèm khi đối phương bấm thu hồi**.

---

## ⚙️ Cơ chế hoạt động (2 Lớp Bắt Tin Nhắn Bị Gỡ)

1. **Bắt & Lưu ngầm tức thì (`onNotificationPosted`)**:
   - Ngay khi có tin nhắn tới, [`MessengerNotificationService`](android-app/app/src/main/java/com/msgguard/app/MessengerNotificationService.kt) đọc gói `StatusBarNotification` (`EXTRA_TITLE`, `EXTRA_BIG_TEXT`, `EXTRA_PICTURE` cho hình ảnh đính kèm) và lưu vào SQLite ([`MessageDatabase`](android-app/app/src/main/java/com/msgguard/app/MessageDatabase.kt)).
2. **Lớp 1 — Phát hiện thông báo thu hồi (`TEXT_RECALL`)**:
   - Khi đối phương bấm gỡ tin nhắn, Messenger đẩy thông báo cập nhật kiểu *"A đã thu hồi một tin nhắn"* / *"unsent a message"*.
   - Thay vì ghi đè làm mất tin nhắn cũ, hệ thống giữ nguyên bản ghi gốc, đánh dấu `is_unsent = 1` và bắn thông báo cảnh báo riêng: **"🚨 [Tên] vừa gỡ 1 tin nhắn: [Nội dung gốc]"**.
3. **Lớp 2 — Phát hiện lệnh rút thông báo ngầm (`REASON_APP_CANCEL = 8`)**:
   - Trong nhiều trường hợp đối phương gỡ tin nhắn ngay khi bạn chưa mở máy, ứng dụng Messenger sẽ tự gọi lệnh hủy thông báo trên thanh trạng thái.
   - Hàm `onNotificationRemoved(sbn, rankingMap, reason)` kiểm tra nếu `reason == REASON_APP_CANCEL` (chính app Messenger tự rút thông báo chứ không phải người dùng vuốt xóa) thì lập tức khóa và đánh dấu tin nhắn đó là **Đã bị thu hồi ngầm**.

---

## 🚀 Chạy Trình Giả Lập Android & Dashboard Đồng Bộ Trên Máy Tính

Đã khởi chạy sẵn tại:
- **Trình duyệt máy tính**: http://localhost:3456
- **Lệnh chạy thủ công**:
  ```bash
  cd d:\AI_App\messenger-unsend-android
  node server.js
  ```

---

## 📲 Cách xuất file `.APK` cài vào điện thoại Android

### Cách 1: Tự động Build `.APK` miễn phí trên GitHub (Không cần cài Android Studio)
1. Đẩy thư mục `d:\AI_App\messenger-unsend-android` lên một kho chứa (Repository) trên GitHub.
2. File cấu hình [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) sẽ tự động chạy máy ảo đám mây để biên dịch mã nguồn Kotlin.
3. Vào mục **Actions** trên GitHub -> Chọn bản build mới nhất -> Tải file **`MsgGuard-AntiUnsend-APK`** về điện thoại và cài đặt.

### Cách 2: Build bằng Android Studio trên máy tính
1. Mở thư mục `d:\AI_App\messenger-unsend-android\android-app` bằng **Android Studio**.
2. Chọn menu **Build > Build Bundle(s) / APK(s) > Build APK(s)** để xuất file `app-debug.apk`.
