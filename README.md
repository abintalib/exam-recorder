# Exam Recorder

Records each student's face (front camera + mic) on Android tablets during an exam, controlled fully by the admin or the exam system, and uploads each video to `{student_id}/{session_id}/`.

Students never touch the app. They use the exam app as usual while recording runs in the background.

## How it works

```
Admin dashboard or exam system  --Start/Stop-->  Control server  <--every 2s--  Tablets
                                                                                   |
                                     Storage: {student_id}/{session_id}/  <--upload after Stop
```

1. **One-time setup (IT):** install the APK, grant permissions, and link each tablet's 6-letter code to a student in the dashboard.
2. **Start:** the tablet shows an invisible screen for about 1 second (Android only allows the camera to start while the app is visible), starts recording, and returns to the exam app.
3. **Stop:** the tablet finalizes the video and uploads it in the background. Failed uploads retry with backoff, and the queue survives reboots.
4. **Status:** the dashboard shows each tablet as Ready, Recording, Uploading, Uploaded, Offline or Error.

Tested on a Lenovo TB336ZU, Android 16.

## Project layout

| Path | What |
|---|---|
| `android/` | Tablet app (native Kotlin, CameraX) |
| `server/server.js` | Test control server, no dependencies |
| `server/dashboard.html` | Admin dashboard |

### Tablet app (`android/app/src/main/java/com/examrecorder/`)

| File | Role |
|---|---|
| `ControlService.kt` | Always-on foreground service. Polls the server every 2s, reports status, starts or stops recording. |
| `TriggerActivity.kt` | Invisible screen that briefly comes to the front so Android allows the camera to start. |
| `RecordingService.kt` | Records front camera + mic in the background (640x480, about 1.2 Mbps, about 0.65 GB per hour). |
| `Uploader.kt` | Upload queue on disk, one file at a time, retries with backoff. |
| `Api.kt` | All server calls. Swap this to change the backend. |
| `MainActivity.kt` | Setup screen for IT: pairing code and permission checklist. |
| `BootReceiver.kt` | Starts the control service after a reboot. |

## Run it

**Server** (Node.js 18+):

```bash
cd server
EXAM_TOKEN=your-secret node server.js
```

Dashboard: http://localhost:8080. The tablets must reach this server over the network.

**Tablet app** (JDK 17 + Android SDK 34):

```bash
cd android
./gradlew assembleDebug -PserverUrl=http://<server-ip>:8080
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Permissions per tablet

Camera, microphone, notifications, "Display over other apps" and "No battery optimization". They can be granted on the setup screen, by MDM, or by adb:

```bash
P=com.examrecorder
adb shell pm grant $P android.permission.CAMERA
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +$P
```

## API

### Exam system webhook

Called when the supervisor starts or ends the exam. `examId` becomes the session folder.

```
POST /api/exam/start   Header: X-Exam-Token: <secret>   Body: {"examId": "EX-777", "examName": "Math Final"}
POST /api/exam/stop    Header: X-Exam-Token: <secret>   Body: {"examId": "EX-777"}
```

Returns `401` for a bad token, `400` without `examId`, `200` on success.

### Tablet

```
POST /api/devices/:id/heartbeat    {code, status, detail, pendingUploads, lastUpload}  ->  {action: "record" | "idle", sessionId, studentId, examPackage}
POST /api/devices/:id/upload-url   {studentId, sessionId, fileName}                     ->  {url, key}
```

### Admin

```
GET  /api/state
POST /api/assign           {deviceId, studentId}
POST /api/session/start    {name, examPackage}
POST /api/session/stop
```

## Current limits and next steps

- **Storage:** uploads currently go to the test server (`server/uploads/`). For S3, change `makeUploadUrl()` in `server.js` to return a presigned PUT URL. The app needs no change.
- **Server:** it runs locally for testing. It needs a cloud host (AWS or Firebase) so tablets and the exam system can reach it from anywhere.
- **Security:** the test server has no admin login and uses plain HTTP. Add both before production.
- **State:** the server keeps state in memory, so a restart forgets tablet links. Production needs a database.
- **Long exams:** each recording is one file. Splitting into chunks would limit loss if a tablet dies mid-exam.
- Android always shows a notification and a green camera dot while recording. They can't be hidden.
- The tablet should stand upright facing the student.
