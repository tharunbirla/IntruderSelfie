# IntruderSelfie

IntruderSelfie captures **one front-camera photo for each failed device credential attempt** (PIN, password, or pattern) after the device owner explicitly activates the app as a Device Administrator.

## Behaviour
- Each failed credential callback queues one camera capture.
- Photos per failed attempt: one still JPEG.
- Successful unlock: no photo is captured.
- Storage: local device storage in `Pictures/IntruderSelfie`.
- No cloud upload or network permission is used by the capture feature.

## Setup
1. Install the app and grant Camera and Notifications permissions.
2. Open the app and select **Enable Device Administrator**. Review and explicitly approve the Android system prompt.
3. Keep Intruder Detection enabled. Its camera foreground-service notification must remain active.
4. Test with a failed device PIN/password/pattern entry, then unlock successfully and confirm no additional image is added.
5. Reopen the app after restarting the phone to resume camera monitoring.

## Android limitations
- Android calls `DeviceAdminReceiver.onPasswordFailed()` for failed device or profile credential attempts when the app has the `watch-login` policy. See the [Android API reference](https://developer.android.com/reference/android/app/admin/DeviceAdminReceiver).
- This callback does not provide a reliable notification for fingerprint or face biometric failures.
- Device Administrator activation is user-controlled and can be revoked at any time.
- Camera permission is subject to Android's while-in-use restrictions. The camera foreground service must be started while the app is visible and remain running. A cold camera-service start from a background callback may be rejected on modern Android versions. Android 14+ also restricts camera foreground-service starts from boot receivers, so the app asks the user to reopen it after reboot. See [Android foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- OEM battery management or process termination may interrupt monitoring. Test on the target phone and OS before relying on the app.

## Privacy
Captured photos are stored locally in the public Pictures folder so the app gallery can display them. Use this feature only on a device you own or are authorized to administer, with the device owner's knowledge and consent.

## Compatibility
Minimum Android 8.0 (API 26), compile/target SDK 35.

## License
This project is licensed under the MIT License - see [LICENSE](LICENSE).
