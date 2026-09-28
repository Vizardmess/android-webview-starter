# V60 — Official Google Home SDK required

The V60 Web UI and native bridge are implemented.

The remaining APK build dependency is the official Google Home APIs Android SDK.

Google documents that the Home APIs Android libraries are not published as ordinary public development libraries and must be downloaded while signed in to Google Home Developers, then hosted in a local Maven repository.

Expected sample-compatible artifacts:

- com.google.android.gms:play-services-home:17.1.0
- com.google.android.gms:play-services-home-types:17.1.0

Do not commit these SDK binaries to this public repository.

For this personal beta, download the current official Home APIs Android SDK from the signed-in Google Home Developers page and supply the SDK ZIP to the private build environment. After the Maven artifacts are installed locally, Gradle can compile the native Google Home bridge.

The public 16.0.0 artifact was tested separately. It resolves from Google's public Maven repository but does not contain the modern Structure / Room / Device / Permissions APIs required by V60.
