# Family Buzz

Get the kids' attention from your phone or PC. Three apps:

| App | Install on | What it does |
|---|---|---|
| **FamilyBuzz-Parent.apk** | Your phone and your wife's | Send messages, choose how loud, see when they tap "Got it" |
| **FamilyBuzz-Kid.apk** ("Buzz") | Each kid's phone | Listens in the background and alerts |
| **FamilyBuzz-PC.exe** | Any Windows PC | Receives like a kid's phone *and* can send like the parent app |

Three alert levels, chosen per message:
- **Gentle** – a normal notification or pop-up.
- **Loud** – alarm sound at full alarm volume for 10 seconds (phones: even on silent).
- **Take over** – full-screen alarm that keeps ringing until they tap **Got it**.

## Getting the apps

Every push to `main` builds all three on GitHub. They land on the repo's **Releases → latest** page. Open it on each phone and tap the file to install. Allow "install unknown apps" for your browser when Android asks.

## Setting up a kid's phone

1. Install **FamilyBuzz-Kid.apk**, open **Buzz**, and enter the kid's name.
2. Work through the **Setup checklist** until every row has a ✅:
   - **Notifications**
   - **Display over other apps** – lets take-over alerts interrupt games and videos.
   - **Full-screen alerts** (Android 14+) – shows the alarm over the lock screen.
   - **Battery: unrestricted** – keeps it connected while the phone sleeps.
3. Tap **Share code** and send it to yourself.
4. Press **Take over** under *Try it* to check it works.

**Samsung phones:** also go to Settings → Battery → Background usage limits and add Buzz to **Never sleeping apps**. Other brands have similar settings; search "Buzz" in Settings → Battery.

## Setting up a parent phone

Install **FamilyBuzz-Parent.apk** and enter your name (e.g. "Dad"). Then tap **+ Add a kid's phone or PC** for each kid. Copy the code first and the app fills it in. Do the same on your wife's phone using the same codes.

## Setting up a Windows PC

Run **FamilyBuzz-PC.exe**. Windows SmartScreen will warn because the app isn't signed; choose *More info → Run anyway*.
- **Receive tab:** shows this PC's code. Add it in the parent app like a kid's phone. Tick **Start automatically when Windows starts**. Closing the window keeps it running in the system tray.
- **Send tab:** add codes for phones or other PCs and send messages from the PC.

## How it works

Messages go through [ntfy.sh](https://ntfy.sh), a free push relay, so there are no accounts and no server to run. Each receiving device has a random private code, and anyone who knows a code can send to it, so keep codes in the family. Messages are not end-to-end encrypted.

If a phone was off for more than 15 minutes, old messages arrive as gentle notifications with the time they were sent, rather than going off loudly later.

Limits worth knowing:
- A kid can still force-stop or uninstall the app. Pair this with Google Family Link if you need to lock that down.
- On PCs, the take-over window stays on top of normal windows, but some full-screen games can draw over it. The PC can't raise the system volume; it plays at whatever volume Windows is set to.

## Repo notes

- `app/` – Android code. One project, two flavors (`parent`, `kid`) that share `app/src/main`.
- `pc/familybuzz_pc.py` – the Windows app. It runs as plain Python with `pip install pystray pillow`.
- `keystore/family.jks` signs the APKs so new builds install as updates. **Keep this repo private.**
