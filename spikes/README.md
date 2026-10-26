# spikes/ — Phase 0 throwaway code
Purpose: measure the riskiest assumptions on real hardware (plan §18 Phase 0). Nothing here ships.
Deleted at Gate 1 (task P1-xx / gate G1 step "spikes/ deleted").
Results go to `docs/SPIKES.md` with numbers, device, API level and commit.

| Folder | Spike | Needs device? |
|---|---|---|
| s1-python-runtime/ | Chaquopy in two worker processes, AIDL round trip, kill, vendored import | Yes (build runs in CI; run on phone) |
| s2-accessibility/ | Read/click/set_text/scroll, MediaProjection + auto-accept, launch chain | Yes (phone steps) |
| s3-background-survival/ | Exact alarm to foreground service, keep-awake, Doze, reboot, 6 h run | Yes (long run) |
| s4-shell-tier/ | adb tcpip loopback client, secure-settings grant, UiAutomation exclusivity | Yes (PC + USB) |
| s5-text-input-ime/ | IME commit of Unicode/password text, background clipboard | Yes |

Status of every spike: NOT STARTED — waiting for device facts (see docs/PROGRESS.md, Open questions).

## Phone session script (owner)
Do these when asked, one at a time. Report back each result in one line.
1. Settings > About phone: write down model, Android version, build number. Tap Build number 7 times, then Developer options > enable USB debugging.
2. Connect USB to the PC, accept the RSA prompt on the phone, then run `adb shell getprop ro.product.cpu.abilist` and `adb shell getprop ro.product.model`. Send the output.
3. Install a debug APK when the agent gives you a link (`adb install -r <file>`), open it and tap Run.
4. Settings > Accessibility > AutoHost: enable it when asked. If the toggle is greyed out, tell me; that is a known Android 13+ restriction and not expected on Android 10.
5. For the screenshot spike, approve the screen-capture dialog when it appears, and say if the dialog looks different from what you expect.
Never paste credentials or personal screenshots into the chat.
