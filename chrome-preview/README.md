# DroidDeck Chrome Preview

The preview adds an Android launcher and an embedded RFB display to the pinned
DroidDeck source at 55362d68efbf5ba6ff8f302b914a1d37d0b73a06.
Build with -PchromeEdition=true after the upstream native/runtime preparation.
The default build continues to use the upstream launcher.

This build requires the verified diagnostic Linux/QEMU/Chrome runtime already
prepared in app-private storage on the test Mate 20 Pro. It does not download
Chrome, accept terms, or install a runtime. No Chrome binaries or VM images are
included in this source overlay or APK. First-run installation, signed updates,
a separate persistent profile disk, reliable shutdown and feature validation
remain unfinished. This is a development preview, not a release.

The display uses an app-private Unix socket and verifies the server Android UID.
VncAuth remains required. X11 uses the private filesystem socket, a loopback relay
and a private MIT cookie; the supervisor verifies its relay started successfully.
Chrome runs as UID1000 under the guest Linux kernel. No global --no-sandbox,
TLS verification bypass or remote PC runtime is used.

Protocol unit tests cover authentication policy, framebuffer/text bounds, RGB
ordering and LastRect handling. The same Java client authenticated to the actual
phone display and decoded a 1280x720 Chrome frame. The new Android APK still
requires on-device validation; these results do not establish full compatibility.

The carrier workflow verifies overlay-manifest.json, applies the listed files to
that exact upstream revision after preparing native components, builds the APK,
runs Android/native tests and verifies the APK signature. The overlay commit and
checksums are included in build evidence. The AOSP development test key is used.
