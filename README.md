# IntruderSelfie

IntruderSelfie captures **one front-camera photo for each failed device credential attempt** (PIN, password, or pattern) after the user explicitly activates the app as a Device Administrator.

## Behaviour
- Failed credential callback: queue one camera capture.
- Photos per failed attempt: exactly one still JPEG.
- Successful unlock: does not trigger a photo.
- Storage: local device storage in `Pictures/IntruderSelfie`.
- No cloud upload or network permission is used by the capture feature.

## Setup
1. Install the app and grant Camera and Notifications permissions.
2. Open Android Settings' device administrator activation prompt from the app's setup flow, then explicitly activate **Intruder Selfie**.
3. Keep Intruder Detection enabled in the app.
4. Test using a failed device PIN/password/pattern entry, then unlock successfully and confirm no additional image is added.

## Android limitations
- Android calls `DeviceAdminReceiver.onPasswordFailed()` for failed lock-screen credentials. This does not provide a reliable callback for biometric failures (fingerprint/face) on ordinary third-party apps.
- Device Administrator activation is user-controlled and can be revoked at any time.
- Android versions and OEM policies may restrict camera access after a failed-attempt callback, especially when a camera foreground service is started from the background. Test on the target device/OS before relying on capture.
- The implementation queues callbacks while the service process is alive. Android may terminate a process or deny camera startup; therefore no third-party app can promise forensic-grade capture of every attempt on every Android build.

## Privacy
Captured photos are stored locally in the public Pictures folder so the app gallery can display them. Do not install or enable this feature on a device without the device owner's informed consent.

## Compatibility
Minimum Android 8.0 (API 26), compile/target SDK 35.

## License
This project is licensed under the MIT License - see [LICENSE](LICENSE).
