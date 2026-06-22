<div align="center">

<img src="icon.svg" alt="Stay Hmbl" width="160" />

# Stay Hmbl

*step writer · health connect*

</div>

---

A Health Connect step writer built during the **Svasthya Health Challenge 2026**, Amazon's wellness contest powered by Healthify, where the top step-counters win Amazon Pay gift cards and "only genuine task completions and verified activities will be considered."

The verification rests entirely on whatever Health Connect on your phone says happened today. Type a number, get a number. Stay Hmbl writes any value you want (10,000, 100,000, 67,676,767) directly into Health Connect, where Healthify dutifully reads it back as gospel.

The point isn't to win. The point is that if every contestant can claim a million steps with three taps, the leaderboard becomes the honest reflection of the contest it always was. A humbling experience, delivered as an APK.

## What it does

- Writes any step count to **Health Connect** for today's local date.
- Splits the total into `StepsRecord` chunks of ≤1,000,000 steps across non-overlapping time windows. That cap is the only one Health Connect actually enforces.
- Runs inside a **foreground service** so writes survive phone calls, lock screens, and the app being swiped away.
- Shows a progress bar with live ETA. One-tap **CLEAR TODAY** to undo.

## Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | AppCompat, monochrome theme, sharp edges |
| Health integration | `androidx.health.connect:connect-client` |
| Background work | Foreground `Service` + coroutines |
| Min / target SDK | 28 / 35 |

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant notifications + Health Connect read/write on first launch. For Healthify to count Stay Hmbl's steps: Health Connect → Data sources and priority → Activity → drag **Stay Hmbl** to position 1.

## Why a foreground service

`insertRecords` is a Binder hop with a hard payload ceiling. The app batches 200 records per call, paces them with a 50ms delay, and broadcasts progress to the UI. A foreground notification keeps the process alive while you take a call from your aunt about the contest.

## Things this is not

A pedometer · a fitness app · an endorsement · a long-term plan.

## License

MIT. Copy, fork, humble responsibly.
