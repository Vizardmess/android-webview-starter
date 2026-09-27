# Home Edition V58 — first installable APK

This branch is an Android build lane for the first Home Edition phone installation.

The APK is a small native WebView shell. On first launch it asks the user to select the current Home Edition HTML file. The file is copied to private app storage and automatically opened on subsequent launches.

Why this bootstrap exists:
- the Home Edition prototype is already >1 MB as a standalone HTML file;
- the first milestone is to validate a real APK on the phone immediately;
- the full Capacitor/native project remains the long-term architecture.

V58 native shell features:
- package id `pt.homeedition.app`
- Android TextToSpeech JavaScript bridge
- vibration JavaScript bridge
- open Android alarms bridge
- HTML update/import bridge
- persistent WebView localStorage
- local HTTP/WebSocket access enabled for trusted Home Assistant LAN testing

This is a debug beta. Cleartext traffic is deliberately enabled for local Home Assistant compatibility and must be tightened again before release distribution.
