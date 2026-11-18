# Apple Music บนจอ Geely EX5 (zeekr-adapt patch)

Workflow `.github/workflows/apple-music-dhu.yml` รับ Apple Music ตัวจริงจาก Play Store
แล้ว patch ด้วย [zeekr-adapt-morphe-patches](https://github.com/mormislaw/zeekr-adapt-morphe-patches)
(`v1.0.0`, แพตช์ "DHU adapter") ผ่าน [Morphe](https://github.com/MorpheApp/morphe-desktop)
จากนั้นตัด library ที่ไม่จำเป็นออก แล้วเซ็นเป็น APK ไฟล์เดียวที่ลงได้

## สิ่งที่ workflow ทำ

1. ดาวน์โหลดไฟล์จาก URL ที่ระบุ (`.apk`, `.apkm`, `.xapk` หรือ `.apks`) แล้วตรวจ SHA-256 ถ้าใส่ค่าไว้
2. ตรวจว่าเป็น `com.apple.android.music` และเซ็นด้วยใบรับรองของ Apple
   (ถ้าไม่ใช่จะหยุด เพื่อกันไฟล์ที่ถูกดัดแปลงมาก่อน)
3. รวม split APK เป็นไฟล์เดียว และใส่ DHU adapter: ปรับ DPI ให้ตัวหนังสืออ่านได้บนจอ 2560×1600,
   ปลดล็อกแนวจอ, ปุ่มลอย back/ตั้งค่า, และข้ามการตรวจ root/emulator/network/SIM ของจอรถ
4. **ตัด library**: เก็บ native lib (`lib/`) ไว้เฉพาะ `arm64-v8a` (ตั้งได้ที่ `keep_abis`)
   และลบ Pine `armeabi-v7a` ที่แพตช์ใส่มาใน `assets/` ด้วย
   ส่วน MediaCenter bridge ของ Zeekr ปิดไว้เป็นค่าเริ่มต้น เพราะ EX5 ไม่มี `com.zeekr.mediacenter`
5. zipalign (page-align 16 KB) และเซ็น ได้ artifact `apple-music-dhu-apk` (เก็บไว้ 7 วัน)

## วิธีใช้

1. หาไฟล์ Apple Music ตัวจริง เช่น ดึง `base.apk` และ split ทั้งหมดจากมือถือที่ลงจาก Play Store
   (`adb shell pm path com.apple.android.music`) แล้วรวมเป็น `.apks` (zip)
   หรือใช้ `.apkm` ของ Apple จาก APKMirror
2. อัปโหลดไว้ที่ที่มีลิงก์ดาวน์โหลดตรง (เช่น release asset ของ repo ส่วนตัว)
3. ไปที่ Actions → **Apple Music for the EX5 head unit** → Run workflow แล้วใส่ URL (และ SHA-256)
4. ถ้าต้องการให้อัปเดตทับกันได้ ให้สร้าง keystore แล้วเก็บเป็น secret:
   `APPLE_MUSIC_DHU_KEYSTORE_B64` (`base64 -w0 key.p12`) และ `APPLE_MUSIC_DHU_KEYSTORE_PASSWORD`
   (PKCS12, ใช้รหัสเดียวกันทั้ง store และ key) ถ้าไม่มี secret จะเซ็นด้วย key ใช้ครั้งเดียว
   ต้องถอนแอปเก่าออกก่อนลงตัวใหม่

## ข้อควรรู้

- ใช้ส่วนตัวบนจอรถของตัวเองเท่านั้น อย่าแจกจ่าย APK ที่ patch แล้ว (เป็นแอปลิขสิทธิ์ของ Apple)
  ถ้า repo เป็น public ทุกคนที่ล็อกอิน GitHub ดาวน์โหลด artifact ได้ จึงควรรันใน repo แบบ private
- แอปที่เซ็นใหม่ไม่ใช่ลายเซ็นของ Apple: อัปเดตอัตโนมัติจาก Play Store ไม่ได้ ต้อง patch ใหม่เอง
- ต้องมีสมาชิก Apple Music ตามปกติ แพตช์นี้ไม่ได้ปลดล็อกเนื้อหาหรือ DRM ใดๆ
- Lossless เปิดในแอป: Settings → Audio Quality → Lossless Audio
- แพตช์นี้ทำมาสำหรับ Zeekr DHU ยังไม่มีผลทดสอบบน Geely EX5 (Flyme Auto)
  ถ้าตัวหนังสือเล็กหรือใหญ่ไป ให้ปรับ `dpi` หรือกดค้างที่ปุ่มลอยในแอปเพื่อปรับ DPI

## ถ้าแอปช้า

สาเหตุหลักมี 2 อย่าง

1. **แอปที่ลงเองยังไม่ถูก compile ล่วงหน้า (AOT)**: แอปจาก Play Store ได้ profile ไปช่วย compile ตอนติดตั้ง
   แต่ APK ที่ลงเองเริ่มด้วยโหมด interpreter/JIT และจอรถอาจไม่เคยเข้าโหมดว่างพร้อมชาร์จที่ระบบใช้ compile ภายหลัง
   ถ้าเปิด ADB บนจอได้ ให้สั่ง compile เต็มครั้งเดียว (ใช้เวลา 1–3 นาที):
   ```
   adb shell cmd package compile -m speed -f com.apple.android.music
   ```
2. **Hook ของ DHU adapter**: แต่ละ category ใส่ Pine hook ไว้บน method ของระบบที่ถูกเรียกบ่อยมาก
   | Category | Hook บนเส้นทางที่ถูกเรียกบ่อย | จำเป็นบน EX5 ไหม |
   |---|---|---|
   | Display (เปิดเสมอ) | `Resources.getDisplayMetrics`, `Resources.getConfiguration`, `View.onAttachedToWindow`, `View.setPaddingRelative` | จำเป็น (ทำให้ UI อ่านได้) |
   | Root bypass | `File.exists` (ทุกไฟล์ที่แอปเช็ก), `Runtime.exec`, PackageManager | น่าจะไม่จำเป็นถ้าจอไม่ได้ root |
   | Emulator bypass | `SystemProperties.get`, `Build.*` | ไม่แน่ใจ ต้องลอง |
   | Network bypass | `NetworkCapabilities.hasCapability/hasTransport` | ต้องใช้ถ้าแอปขึ้นว่า offline |
   | SIM ready | `SharedPreferences.getBoolean` (ทุก pref ที่อ่าน) | ต้องใช้ถ้าแอปไม่ยอมสตรีมผ่าน "cellular" |
   | Rounded corners | clip ทั้งหน้าจอทุก frame | ไม่จำเป็น |

   เลือก `profile` ตอน Run workflow:
   - `full`: preset เดิมของ Apple Music
   - `lean`: ปิด root bypass และ rounded corners (แนะนำให้ลองก่อน)
   - `minimal`: เหลือแค่ Display ถ้าแอปขึ้น offline หรือเล่นไม่ได้ ให้เปิดกลับทีละตัวด้วย `extra_options`
     เช่น `networkBypass=true` หรือ `networkBypass=true,simReady=true`

ไฟล์ `hook-options.txt` ใน artifact บอกว่าแต่ละ build เปิดอะไรไว้บ้าง
ข้อมูลที่ไม่จำเป็นอื่นๆ เช่น resource ทุก density (ldpi–xxxhdpi) ที่รวมมาจาก split ทำให้ไฟล์ใหญ่ขึ้น แต่ไม่ทำให้ช้าลง
