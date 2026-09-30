# OCR process lifetime and verification

The private, non-exported `OcrWorkerService` runs in `:ocr`. Only it initializes
ONNX Runtime, OpenCV and PaddleOCR. The main process owns overlay sessions, QR
scanning, geometry mapping, text caching and model installation/preferences.
It sends the selected catalog pack ID explicitly: worker preferences are never
used as cross-process state.

Input travels as read-only anonymous `SharedMemory` containing raw ARGB pixels.
There is no screenshot file, PNG/JPEG encoding or external transfer. There is a
pixel copy into shared memory and a worker-owned bitmap copy; this is not zero-copy.
Results also use shared memory to avoid Binder's transaction-size limit. Text,
floating-point coordinates and UTF-16 CTC spans are preserved. Transport bounds
are 128 MiB per input bitmap and 16 MiB per result; oversized input fails explicitly.
Models, OCR thresholds, detector resolution and thread/batch configuration are unchanged.

Loading starts only after a confirmed capture invocation, in parallel with screen
capture, or lazily when another entry point first requests OCR. It never starts
on accessibility-service connection. Failure/cancellation of capture cancels
its pending warm-up. The service-scoped warm-up contains no screenshot reference.

The parent serializes warm-up, recognition, shutdown and model mutation under the
existing engine mutex. Requests have unique IDs and individually owned reply slots.
Cancellation asks the worker to cancel, then waits at most two seconds for cleanup;
if the worker cannot acknowledge it, the parent shuts it down before another request.
Unexpected process death fails the current operation; the next invocation binds afresh.
There is no automatic duplicate scan or upload.

After the last visible overlay session and in-flight OCR operation end, a 15-second
grace period allows fast reuse. `TRIM_MEMORY_UI_HIDDEN` preserves that grace;
background/critical memory-pressure signals can shorten it. Reopening cancels a
pending timer. Committed shutdown finishes before a new request proceeds.
The parent unbinds before requesting termination so `BIND_AUTO_CREATE` cannot restart
an intentionally stopped worker. The worker kills only its own `:ocr` PID; the parent waits for Binder death before
allowing model mutation or a new worker. Losing the parent binding also terminates
the worker, so Android cannot retain its native allocator after service destruction.
No app task, accessibility setting, user data or unrelated process is stopped.

Platform contracts: [shared-memory ownership](https://developer.android.com/reference/android/os/SharedMemory)
and [binding lifetime](https://developer.android.com/develop/background-work/services/bound-services).

## Checks without a phone

Run `testDebugUnitTest`, `assembleDebug` and `assembleDebugAndroidTest`. JVM tests
cover transport validation, Unicode/spans, late/duplicate replies, cancellation,
the idle grace period and the existing model-mutation boundaries. These checks
do not prove actual Android process lifetime, UI behavior or memory savings.

## Authorized device verification

Only after separate authorization, check `adb devices`, target the intended device
explicitly and update the debug installation without clearing data or changing
permissions/settings. Run only `OcrProcessInstrumentedTest` first. It uses generated
pixels, checks raw transfer, private service metadata, cold/warm/restarted OCR,
separate PIDs and the absence of native OCR libraries in the instrumented main process.
Its only timing log contains numbers, not recognized text. Existing `OcrCorpusInstrumentedTest`
can compare synthetic accuracy and latency against the baseline build.

For a UI comparison, use the same synthetic screen and existing invocation method:

1. Record app-process PIDs and per-PID `dumpsys meminfo` before invocation.
2. Invoke once, note time from invocation to selectable text; sample memory after completion.
3. Close and reopen within 5 seconds: expect worker PID reuse and fast OCR.
4. Close, leave the app in the background for at least 20 seconds after OCR finishes,
   and sample again: expect no `:ocr` process. Reopen: expect a new worker PID.
5. Repeat 5–10 times, then check rapid cancellation/replacement, rotation, translation,
   region OCR, QR, and changing/removing an installed OCR pack through the normal UI.

Record only timings, PIDs, PSS, SwapPss and native heap sizes. Do not use screenrecord,
screencap, UI hierarchy dumps, heap dumps or broad logcat capture. Include the main
process, `:voice`, `:ocr` and attributable WebView renderers in totals; compare the
same browser state and other running apps. A lower main-process PSS alone is not
evidence of lower total memory. Evaluate cold and warm latency distributions and
synthetic recognition accuracy before accepting the UX tradeoff.
