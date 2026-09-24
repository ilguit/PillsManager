# Background delivery verification, 2026-09-14

Updated the Pixel 7 Pro using `adb install -r` (data preserved). The debug-only
ColdAlarmProbe receiver requires android.permission.DUMP and creates a single
temporary profile, debug-cold-alarm-probe. It is absent from release builds.

At 16:19:21 the probe scheduled an intake for 16:20:21.689. After the probe
completed, sent Home and used `am kill ru.pillsmanager`, not force-stop.
`pidof ru.pillsmanager` returned no PID. `dumpsys alarm` still contained the
exact RTC_WAKEUP PendingIntent to ReminderReceiver for 16:20:21.689.

Without opening the activity, the system started PID 29250 and posted the
notification tagged debug-cold-alarm-probe/1789384821689 with importance 4.
The alarm wakeup counter increased from one to two. The probe cleanup command
removes its temporary profile, cascaded intake, and notification, and reconciles
the real schedule. This verifies process-death delivery, not prolonged Doze,
reboot, force-stop, or a different manufacturer's battery restrictions.

Code changes protect update completion from UI coroutine cancellation, replace
the existing alarm directly instead of cancelling it first, and schedule a
near-term wakeup for unnotified due intakes during non-delivering updates.
Local unit tests and assembleDebug passed.

## 2026-09-16: separate medication alarm clock

The nearest medication intake now uses setAlarmClock with a separate PendingIntent
(request code 1). Housekeeping retains request code 0 and cannot replace the
medication alarm. Without exact-alarm access, the existing inexact fallback applies.
Android may show the next medication time as the system's next alarm.

Unit tests and assembleDebug passed; installed on Pixel with data preserved.
The shell probe scheduled 12:43:36.710; after am kill, pidof returned no process.
Dumpsys showed the medication AlarmClockInfo and a distinct housekeeping alarm.
Notification debug-cold-alarm-probe/1789544616710 arrived without opening the app.
Forced deep idle failed (QUICK_DOZE_DELAY, then ACTIVE); repeated steps also
remained ACTIVE. This is NOT a successful deep-Doze test. Ran deviceidle unforce
and the probe cleanup afterward. Vivo X300 Pro and overnight delivery remain
unverified. Do not treat this short test as resolving the reported long-idle issue.
