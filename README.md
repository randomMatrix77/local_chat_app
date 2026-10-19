# Local Gemma Chat

One APK for the Galaxy Z Fold6 and Nothing Phone (2). It uses Kotlin, Compose, Room and the official LiteRT-LM Android runtime. There is no backend, account, embedding store, cloud inference, or model weight in the APK.

## Built behavior

- Room/SQLite stores multiple local conversations, messages, titles, and image paths. New, rename, delete, and switch chat controls are in the Chat UI.
- The Models screen offers official LiteRT Community Gemma 4 E4B (~3.65 GB) and E2B (~2.58 GB) downloads. Files are private to the app and retain their required `.litertlm` extension. Failed downloads can be retried; downloaded models can be selected or deleted.
- Loading releases the prior LiteRT engine first, attempts GPU + GPU vision, then visibly retries CPU if GPU initialization fails. Load/generation errors are surfaced; no response is fabricated.
- LiteRT-LM streaming is used for real text and image turns. Generation can be stopped.
- Camera capture and Android Photo Picker copy images to private app storage and pass `Content.ImageFile` to LiteRT-LM. Gemma 4 E2B/E4B are documented mobile multimodal models.
- Each real generation recreates a LiteRT conversation from Room history, uses the runtime token count when available, reserves 768 output tokens, and removes the oldest retained messages until the hard 4096-token window fits. Messages are never deleted by trimming.
- The Compose Material 3 UI uses dynamic light/dark color when available, a compact app bar, conversation drawer, responsive message widths, rounded icon composer, clickable source links, and no bottom navigation. It does not implement a dedicated foldable layout.

## Web search boundary

Gemma 4 officially documents the model/developer/final-response function-calling cycle. LiteRT-LM 0.18.0 exposes native Kotlin `ToolSet`, `@Tool`, `@ToolParam`, `ToolCall`, and manual/automatic conversation tool execution. The app registers `web_search(query)` when its default-on toggle and a provider URL template are both configured.

The tool sends only the model-selected query to the provider, never chat history or an external LLM. It validates query size, permits no more than two calls per user turn, returns structured provider output plus validated destination URLs to the same on-device model, shows **Searching the web…**, and appends actual source URLs to the displayed final response. It never presents the DuckDuckGo HTML endpoint as evidence. Tool failures are returned to Gemma and explicitly appended as a UI-visible search-unavailable notice; they are not fabricated as search results. There is no manual-search fallback or embedded API secret.

For diagnostics, filtered `OfflineChatSearch` log entries record provider host, query length (not query text), HTTP status/body size, parsed-result count, native tool call count, and failure class:

```sh
adb -s RFCX71QB49Z logcat | rg OfflineChatSearch
```

## Build and install

Use JDK 17 and the checked-in Gradle wrapper:

```sh
./gradlew :app:assembleDebug
adb -s RFCX71QB49Z install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Physical-device test order

1. Install on the Fold6 or Nothing Phone (2); grant Camera only if you capture a photo.
2. In **Models**, download E4B first over reliable Wi-Fi, select it, then tap **Load selected model**. Keep substantial free disk space for the model, cache, and OS.
3. Confirm the status card reports real inference ready and its actual backend. Send a text prompt and verify streamed text. This is the only proof text inference works.
4. Attach a camera/gallery image and send a prompt describing it. Verify the answer refers to the image. This is the only proof vision inference works.
5. Download E2B, select and load it, then return to an existing chat to verify Room history persisted across the switch.
6. Exercise GPU failure/low-memory paths by reviewing the status error; do not regard an APK build, model download, or engine-load attempt as successful inference.
7. Enable **Web search** in the drawer, keep the DuckDuckGo template, then ask a clearly current question. During a real tool call, confirm **Searching the web…** appears and the final response exposes destination URLs. Check the filtered log above for `response`, `parsedResults`, and `toolCall completed`. If those entries are absent, the model chose not to search; do not treat its answer as web-grounded.

## Current verification status

- **Built:** debug APK compiles with LiteRT-LM 0.18.0, Room 2.7.2/KSP, and the checked-in Gradle wrapper.
- **Installed/started:** this Material 3 revision was installed over the existing app on the connected Galaxy Z Fold6. The Room database and existing E4B file were retained; the UI status confirms the E4B file is downloaded.
- **UI smoke-tested:** the main chat screen was captured over ADB with no app crash observed.
- **Not independently re-tested in this session:** real E4B generation, image response, CPU fallback, and a fresh model-triggered web call. The phone locked before the model could be loaded again, so these remain physical-device tests rather than claims based on compilation.

References: [LiteRT-LM Android guide](https://developers.google.com/edge/litert-lm/android), [Gemma model guide](https://ai.google.dev/gemma/docs/get_started), and the [official LiteRT-LM repository](https://github.com/google-ai-edge/LiteRT-LM).
