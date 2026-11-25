# Photo to Talk — release checklist

1. Open the project in Android Studio. Sync Gradle and run `./gradlew assembleDebug`.
2. Install the debug APK on a real Android phone. Verify camera permissions, OCR for each selected writing system, photo import, history persistence, deletion, voice language availability, voice command recognition and Play/Pause/Next/Previous/Stop. Test screen reader and larger font sizes.
3. **Important:** This source bundle has not been compiled or tested in this environment. Fix any build/runtime issues before release.
4. Replace the default Android launcher icon with a dedicated Photo to Talk icon; create screenshots of the real working app. Verify text labels and Play Console listing.
5. Finalize and host the privacy policy. Confirm Play Data safety disclosures and any speech provider data flows.
6. Confirm your existing Play Console application ID. This project uses `com.photototalk.app`. If a listing already exists with a different package name, you cannot update it with this AAB.
7. In Android Studio: Build > Generate Signed Bundle / APK > Android App Bundle > Next. Create or select a secure upload key on your Mac (never send the key or passwords here), select `release`, and generate the signed AAB.
8. The AAB will usually be under `app/release/` or the location selected by Android Studio. Upload it to Play Console internal testing first, then follow the relevant release requirements.
9. Confirm target API and other Google Play requirements at submission time.

The Android Gradle `release` build has no embedded signing key on purpose. Running `bundleRelease` without signing creates an unsigned artifact, not a Play-ready signed AAB.
