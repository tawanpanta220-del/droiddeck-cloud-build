# ผลทดสอบจริง — HUAWEI Mate 20 Pro

วันที่ 5 พฤศจิกายน 2026 (Asia/Bangkok)

**Chrome Edition: BLOCKED บน runtime และอุปกรณ์ชุดนี้**
Baseline APK ติดตั้งและเปิดหน้า Android ได้ แต่การแสดงผล Desktop ยังจอดำ
และ Chrome ไม่สามารถเริ่มเบราว์เซอร์พร้อม sandbox ได้
ไม่มีการใช้ `--no-sandbox` ในการทดสอบ

## อุปกรณ์ที่อ่านผ่าน ADB

| รายการ | ค่าจากเครื่อง |
| --- | --- |
| Model | HUAWEI LYA-L29 / Mate 20 Pro |
| Hardware / ABI | kirin980 / arm64-v8a |
| Android | 10 / API 29 |
| EMUI | EmotionUI_12.0.0 |
| Kernel | 4.14.116 aarch64 |
| SELinux | Enforcing |
| RAM ที่ kernel รายงาน | 5,684,880 kB |
| พื้นที่ว่าง /data ก่อนติดตั้ง | 98,929,628 KiB ประมาณ 94.35 GiB |
| สิทธิ์ USB debugging | ผู้ใช้อนุญาตแล้ว; ADB state=device |

EMUI 12 ในเครื่องนี้ไม่ได้หมายความว่าเป็น Android 12 — อ่าน Android 10 จากเครื่องจริง

## Milestones

| Milestone | ผล |
| --- | --- |
| Cloud build และ baseline tests | PASS; workflow 41967966344 |
| ติดตั้ง baseline APK | PASS; Android ตอบ Success |
| อ่าน checksum APK ที่ติดตั้งกลับจากเครื่อง | PASS; ตรงกับไฟล์ local และ CI |
| เปิด Android UI ของ DroidDeck 0.6.9 | PASS; มี screenshot/UI dump |
| ดาวน์โหลดและติดตั้ง Linux runtime | PASS; ubuntu-r1-rc4 |
| ติดตั้ง Desktop pack / เริ่ม guest agent | PASS; Ubuntu 26.04.1 LTS, UID 10246 |
| คำสั่งอ่านไฟล์เมื่อใช้ PRoot fast path เดิม | FAIL; statx รายงาน ENOENT ทั้งที่ไฟล์มีอยู่ |
| ปิด PRoot fast path แล้วตรวจ stat/ls ซ้ำ | PASS |
| Desktop ส่งภาพที่ใช้งานได้ | FAIL; ภาพ ADB จอดำและผู้ใช้ยืนยันจอดำจริง |
| Google signed metadata และ package hash | PASS |
| รัน Chrome ARM64 --version | PASS; Google Chrome 157.0.8103.38 |
| เริ่ม Chrome พร้อม sandbox | FAIL; SUID sandbox helper ไม่ได้เป็น root จริง |
| User namespace ใน runtime | FAIL; unshare ตอบ Invalid argument |
| HTTPS / Chrome window / input / audio / downloads | NOT REACHED / NOT TESTED |
| Chrome launcher / first-run installer / signed updates | NOT IMPLEMENTED; runtime gate ไม่ผ่าน |

## APK ที่ติดตั้งจริง

Package: com.droiddeck.launcher
Version: 0.6.9 / code 17
ไฟล์: DroidDeck-baseline-55362d6-arm64.apk
Source: Droid-Deck/DroidDeck @ 55362d68efbf5ba6ff8f302b914a1d37d0b73a06

SHA-256:
`5d6fe0f034d09f9b2d2ec0aace64070ec38728cfc5cacd4ec016b4554bb8337d`

เป็น baseline เดิม เซ็น development test key ไม่ใช่ Chrome Edition
Android ตรวจรับ APK แล้ว และตรวจ SHA-256 จาก base.apk บนเครื่องตรงกัน

## การแก้ค่าระหว่างตรวจ runtime

เมื่อเปิด PRoot fast path คำสั่ง stat/ls ของ uutils coreutils 0.10.0
อ่าน /usr/share/droiddeck ไม่พบ ขณะที่ Python อ่านได้ ทดสอบแยกแล้วพบว่า
PROOT_FP_PATCH_SVC=0 เพียงตัวเดียวยังผิด แต่ปิด fast path แล้วคำสั่งกลับมาผ่าน

ตั้งค่า session.prootFastPath=false บนอุปกรณ์นี้ผ่านเครื่องมือที่ upstream รองรับ
และเปลี่ยน marker /usr/share/droiddeck/kwin-compositor จาก opengl เป็น qpainter
เพื่อใช้ branch software ที่มีอยู่แล้ว เก็บค่าเดิมไว้ที่
/root/.cache/droiddeck-chrome-proof/kwin-compositor.original

หลังเปลี่ยนค่า guest ผ่านขั้นเริ่มต้นและรายงาน READY/firstFrame=true
แต่ screenshot ยังเป็นสีดำ ผู้ใช้ยืนยันว่าหน้าจอจริงก็ดำ
ดังนั้น READY ไม่ใช่หลักฐานว่า Desktop แสดงผลถูกต้อง
การเปลี่ยนค่านี้อยู่ใน runtime ของเครื่องทดสอบ ไม่ใช่การแก้ APK

## Chrome provenance

ดาวน์โหลดจาก Google ผ่าน HTTPS:
https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_157.0.8103.38-1_arm64.deb

ขนาด: 139,364,644 bytes
SHA-256:
`ea1b98eda5ff948be07e52237e07bbcc8f7dedc3460b693f6bf29e62e89c3bd6`

ใช้ GnuPG 2.4.8 ภายใน guest ตรวจ InRelease กับ primary key fingerprint ที่
Google เผยแพร่: EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796
ได้รับ VALIDSIG จาก subkey 0E225917414670F4442C250DFD533C07C264648F
ตรวจ Packages.gz กับ SHA-256/size ใน signed Release และตรวจ .deb กับ Packages
ทั้งบน Windows และหลังส่งไฟล์เข้าอุปกรณ์แล้ว

เอกสาร key: https://www.google.com/linuxrepositories/

แตก official .deb ใน prefix สำหรับการตรวจโดยเฉพาะ:
 /root/.cache/droiddeck-chrome-proof/package
ไม่ได้รัน maintainer scripts หรือเปลี่ยนเบราว์เซอร์เริ่มต้น
เป็น diagnostic extraction ไม่ใช่ first-run installer ของผลิตภัณฑ์
ยังไม่มี UI บันทึกการยอมรับ Chrome Terms ใน Chrome Edition

## การทดสอบ sandbox

ทดสอบด้วย UID/EUID 10246 ผ่านตัวเรียกแบบ static ที่คอมไพล์ด้วย Android NDK
ตรวจว่า ELF ไม่มี PT_INTERP/DT_NEEDED และ smoke test ยืนยัน UID ได้จริง
เพื่อป้องกัน native preload ของ upstream แทรก --no-sandbox ระหว่าง exec

สำรองและทำ /etc/ld.so.preload ให้ว่างชั่วคราวใน private runtime เฉพาะช่วง probe
และคืน bytes เดิมใน finally ตรวจ SHA-256 หลังคืนค่าตรงกัน:
3500baf0d2b81f9ad18e7b21827c59b43ea88c6b8eaeddbccf5d9cc542e3b8ea

- Chrome --version: exit 0; Google Chrome 157.0.8103.38
- Headless HTTPS พร้อม sandbox: exit -6 / SIGABRT ก่อนเปิดเว็บ
- ข้อผิดพลาด: SUID sandbox helper พบแล้ว แต่ตั้งค่าไม่ถูกต้อง ต้องเป็นเจ้าของ root และ mode 4755
- helper มี mode 4755 แต่เจ้าของจริงเป็น UID 10246
- user namespace probe: exit 1 / Invalid argument
- /proc/self/ns ใน guest แสดงเพียง mnt และ cgroup

ไม่ได้ทำให้ root ownership ดูเหมือนผ่านด้วย preload และไม่ได้เพิ่ม flags ปิด sandbox
ผลนี้ยืนยันความล้มเหลวของ Chrome ในสภาพแวดล้อมที่ทดสอบ
ไม่ได้สรุปว่าทุกอุปกรณ์หรือ runtime อื่นจะมีผลเหมือนกัน

## สภาพเครื่องหลังทดสอบ

หยุด Linux session แล้ว (IDLE, running=false)
ปิด Debugging tools กลับเป็นค่าเดิม ล้าง agent environment/overrides
และคืน stay_on_while_plugged_in เป็น 0
คืน /etc/ld.so.preload แล้ว
คง baseline APK, runtime และไฟล์ diagnostic สำหรับตรวจต่อไว้ใน app-private storage
คง prootFastPath=false และ software compositor marker เป็นค่าทดสอบความเข้ากันได้
ADB server ที่ผู้ใช้เริ่มไว้ยังคงทำงาน ไม่ได้เปลี่ยนค่า bootloader/root/SELinux

## หลักฐาน

DEVICE_INSPECTION.json, DEVICE_APK_INSTALL.json, DEVICE_INSTALLED_APK_VERIFICATION.json,
DEVICE_RUNTIME_STATE.json, DEVICE_FASTPATH_COMPARISON.json,
DEVICE_DESKTOP_ATTEMPT4_STATE.json, DEVICE_SOFTWARE_DESKTOP_LOGS.json,
CHROME_PROVENANCE_VERIFICATION.json, CHROME_PACKAGE_VERIFICATION.json,
CHROME_DEVICE_PACKAGE_EXTRACTION.json, CHROME_STATIC_LAUNCHER_VERIFICATION.json,
CHROME_SANDBOX_PROBE.json, DEVICE_FINAL_GUEST_FACTS.json,
DEVICE_TEMP_SETTINGS.json และ DEVICE_FINAL_STATE.json

ดู DEVICE_EVIDENCE_SHA256SUMS.txt และ DroidDeck-Mate20Pro-test-evidence.zip

## งานที่ยังขวาง Chrome Edition

ต้องได้แนวทาง runtime ที่ผ่าน sandbox และภาพจริงบนอุปกรณ์ก่อน จึงจะเดินต่อไปยัง
launcher/installer ตาม Master Prompt ได้ การเปลี่ยนแค่ชื่อแอปหรือใช้
--no-sandbox จะไม่ทำให้ milestone นี้ผ่าน
