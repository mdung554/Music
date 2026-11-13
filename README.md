# Xiaozhi Music Server (Android)

Chay server nhac ngay tren dien thoai, thay cho music_server/server.py. Cung 3 duong dan:
/music/search, /music/next, /music/stream/{id}, nen firmware khong phai sua.

## Dung
1. Mo thu muc nay bang Android Studio (Hedgehog tro len), cho Gradle sync, nhan Run (cam dien thoai, bat USB debugging).
2. Mo app, dat API key (neu muon), bam "Bat server", bam "Tat tiet kiem pin cho app nay".
3. App hien dia chi `http://IP:8787`. Dien vao firmware (menuconfig -> Music relay server URL)
   hoac goi cong cu self.music.set_server_url. Dien thoai va robot phai cung Wi-Fi.
4. Dat IP co dinh cho dien thoai trong router de dia chi khong doi.
5. Thinh thoang bam "Cap nhat yt-dlp".

## Luu y
- Moi bai phai tai va doi sang Opus truoc (vai giay den ~15 giay), nen /music/search tra loi cham;
  firmware cho toi da 20 giay. Bai dai hon 20 phut bi bo qua.
- Bai da doi duoc luu tam trong bo nho cache cua app (giu 6 bai gan nhat).
