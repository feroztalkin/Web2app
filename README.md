# Web2App Android — personal-use build

Kotlin Android app using CameraX, on-device Google ML Kit OCR, lightweight grayscale/contrast enhancement, and your existing Cloudflare Worker.

## Included features
- Camera capture on Android.
- On-device OCR; the image is not uploaded to an OCR provider.
- Basic grayscale + contrast enhancement before OCR.
- Editable question and answer-choice fields.
- Multiple named Worker profiles: save, load/switch, and delete.
- Editable Worker URL and bearer token.
- AES/GCM encryption of saved profiles using an AES key generated in Android Keystore.
- "Test Worker" sends a sample MCQ to the selected Worker.
- Answer display and Android text-to-speech.
- Codemagic build configuration in `codemagic.yaml`.

## 1. Open and build locally (optional)
1. Install Android Studio: https://developer.android.com/studio
2. Extract this ZIP and open the `Web2AppAndroid` directory.
3. Let Android Studio sync Gradle dependencies.
4. Connect an Android phone with USB debugging enabled and press Run.
5. To create an APK: Build → Build Bundle(s) / APK(s) → Build APK(s).

## 2. Build online with Codemagic
1. Create a private GitHub repository, e.g. `Web2AppAndroid`.
2. Extract this ZIP and push the *contents* of the `Web2AppAndroid` folder to the repository root. Ensure `codemagic.yaml` is at the repository root.
3. Sign up at https://codemagic.io/ and connect GitHub.
4. Add the repository as an application. Select `codemagic.yaml` workflow configuration.
5. Start the `web2app-android` workflow.
6. Download `app-debug.apk` from the build's Artifacts section.
7. Transfer the APK to your phone and install it. Android may ask you to allow installation from that source.

The YAML creates a Gradle 8.9 wrapper on the Codemagic build machine, then runs `assembleDebug`. A debug APK is appropriate for private testing. For a distributable signed release, configure a private Android keystore in Codemagic and add a release workflow. Never upload your keystore or passwords to a public repository.

## 3. Configure multiple API profiles inside the app
On first launch, the app shows a default profile with the previous Worker URL:
`https://web2app-ai.feroz-talkin.workers.dev/`

1. Enter a profile name, e.g. `Personal Worker`.
2. Enter your Cloudflare Worker HTTPS URL.
3. Enter the bearer token expected by that Worker.
4. Tap **Save profile**.
5. To add another endpoint, change the name, URL and token, then save again.
6. Use the profile dropdown to switch between saved profiles.
7. Use **Delete** to remove the selected profile.
8. **Test Worker** sends a sample MCQ to the currently entered URL/token and displays the returned answer or error.

You do not need to rebuild the APK when changing profile names, URLs or tokens.

## 4. Existing Worker API contract
This Android client sends:
```http
POST /
Authorization: Bearer <token>
Content-Type: application/json
```
Body:
```json
{
  "question": "Which planet is known as the Red Planet?",
  "options": ["Earth", "Mars", "Venus", "Jupiter"]
}
```
It expects a JSON response with a non-empty `answer` field, for example:
```json
{"success": true, "answer": "B. Mars"}
```
If your Worker uses a different request schema, authorization header, or response field, adjust `sendRequest()` in `MainActivity.kt` to match it. The exact current deployed Worker implementation has not been fetched or verified as part of this project generation.

## 5. Credential security — personal-use scope
- Profiles are encrypted at rest using AES/GCM; the encryption key is held by Android Keystore.
- This protects saved data at rest better than plain SharedPreferences.
- It does **not** make a bearer token impossible to extract from a rooted/compromised phone or while the app is using it.
- This project is configured for your personal use on your own devices. Do not distribute an APK containing a shared master token to other people.
- Prefer a separate, limited-scope token for this app. Apply Cloudflare-side rate limits and rotate the token if the device is lost.
- The app never stores a hard-coded token in source. The initial URL is prefilled; token is entered on-device.

## 6. Notes and limitations
- Image processing is currently grayscale + contrast adjustment, not full OpenCV perspective correction or advanced denoising.
- The initial OCR parser splits at the first line containing `?`. For unusual layouts or questions without `?`, manually correct the question and options before solving.
- The test button sends an actual sample question to your Worker and may consume AI usage.
- Camera/OCR, API and TTS are implemented. Voice-triggered commands are not included yet.
- The app expects HTTPS Worker endpoints.
