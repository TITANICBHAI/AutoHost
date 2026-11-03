# Privacy Policy for ClosePaw

Effective date: 2026-05-21

ClosePaw (`ai.closepaw`) is an open source Android app for user-directed automation through Android Accessibility Service. ClosePaw is designed to run on your device and to send information only when it is needed to complete a task you explicitly start.

This policy is written for users and for Google Play review. It describes how ClosePaw handles data and how the app is intended to comply with the Google Play User Data policy.

## What data is collected

ClosePaw does not collect user data on ClosePaw-operated servers. The app does not include tracking, telemetry, analytics, advertising identifiers, or background profiling. ClosePaw does not operate a backend service that receives, stores, or sells your activity.

The app can store settings on your device so it can function. This includes your selected LLM provider and the API key or credential you enter for that provider. Provider credentials are stored locally on your Android device. Persistent API keys are stored using AndroidX Security `EncryptedSharedPreferences`, protected by a `MasterKey` using AES256_GCM. If a temporary memory-only credential path is used by the app, that credential is not persisted and disappears when the app process ends.

ClosePaw can read the current screen only through the Android Accessibility Service permission that you enable. It uses that access to understand the visible interface and perform the actions you request. ClosePaw does not use root access and does not record your screen.

## What is sent to third parties

ClosePaw sends screen content and task context to the LLM provider you choose, such as OpenAI, OpenRouter, or Novita, only during active task execution. This allows the provider's model to decide the next step for the task you started.

Examples of sent data may include visible text on the screen, app UI structure, your typed task request, and recent action history needed to continue the task. ClosePaw sends this data only so the chosen provider can process the automation request. ClosePaw does not send this information to advertising, analytics, or tracking services.

Your API key never leaves your device except when it is used as an authentication header or credential for the LLM provider you selected. ClosePaw does not send your API key to ClosePaw developers, ClosePaw-operated servers, analytics services, or unrelated third parties.

The LLM provider you choose may process, retain, or log requests according to that provider's own terms and privacy policy. You are responsible for choosing a provider you trust and for managing any account or API-key settings with that provider.

## Permissions used and why

Accessibility Service: ClosePaw uses Android Accessibility Service to read the visible screen, inspect UI elements, and perform taps, swipes, and text entry on your behalf. This permission is required for automation. The service is intended to be active for user-started tasks and to stop when you stop the task or the task completes.

Display over other apps: ClosePaw uses the overlay permission to show the Smart Capsule while you are in other apps. The Smart Capsule displays task progress and controls such as pause, take over, and stop. This permission does not allow ClosePaw to record the screen.

Battery optimization or background running: ClosePaw may ask you to allow unrestricted battery use so longer tasks are not stopped by aggressive device battery management. This is used for task reliability, not for background monitoring.

## Data retention

ClosePaw does not retain user data on ClosePaw-operated servers because it does not collect user data server-side. Task screen content and task context are not stored by ClosePaw on a developer server.

On your device, ClosePaw may retain local settings and provider credentials until you remove them, clear app data, or uninstall the app. Provider credentials are intended to remain on device except when sent to the selected provider as authentication for requests you initiate.

Third-party LLM providers may have their own retention practices for requests sent to them. Review your selected provider's privacy policy and account settings for details.

## How to delete your data

Because ClosePaw stores everything locally on your device and does not operate a backend, you can delete your data at any time without contacting the developer. All deletion is immediate and final — there is no retention period.

In-app deletion (granular):

- **Provider credentials and API keys** — `Settings → LLM Auth → Sign out` (for OAuth) or clear the API key field (for paste-key providers). Encrypted credentials are removed from local storage.
- **Session history and local traces** — `Settings → Permissions & Advanced → Clear session history` and `Clear debug traces`. Removes recorded task transcripts, on-device debug screenshots, and tool-call logs.
- **Memory files** — `Settings → Memory Files`, select a file, tap delete. Removes any agent memory entries you have saved.

Full deletion (one tap):

- Open Android `Settings → Apps → ClosePaw → Storage → Clear data`. This removes all credentials, history, traces, memory, and settings simultaneously.
- Uninstalling ClosePaw also deletes all local app data.

What is deleted vs. kept:

- **Deleted:** all data created by your use of the app, stored on the device — credentials, history, traces, memory, settings.
- **Not in ClosePaw's control:** copies of prompts and screen content that you sent to your chosen third-party LLM provider during prior tasks. To delete those, use the provider's account dashboard (OpenAI, OpenRouter, Anthropic, etc.).

## Children

ClosePaw is not directed to children under 13. The app is intended for users who can understand that it reads screen content and sends task context to their chosen LLM provider during active task execution.

## Google Play User Data policy compliance

ClosePaw's Google Play disclosures should state that the app does not collect user data on ClosePaw-operated servers, does not track users, and does not use analytics or telemetry. The only third-party data transfer described by this policy is user-directed processing by the LLM provider selected by the user during active task execution. ClosePaw's sensitive Android permissions are used to provide the app's core automation and overlay features.

## Contact

Contact email: guoqithu10@gmail.com

Source code: https://github.com/imoonkey/closepaw

