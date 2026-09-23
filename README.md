# Watch CSV

[English](README.md) | [Русский](README.ru.md)

[Code structure, component relationships, and libraries](ARCHITECTURE.md)

An offline CSV viewer for Galaxy Watch 4 and other Wear OS watches running Android 11 / API 30 or later, with an Android phone companion for file transfer. Native Java UI, Google Wear OS Data Layer for transfers, and no app Internet permission. Both builds require Android 11 / API 30 or later.

## APK

### Version 1.3: edit the watch view from the phone

Install **version 1.3 on both devices**: `build/watch-csv.apk` on the watch and `build/watch-csv-companion.apk` on the phone. Install over existing versions to keep imported files and presets. Remote controls add no new Android permissions.

#### Setup and use

1. Import your CSV on the watch as usual. The remote editor works with **already imported watch files**, not the phone's local viewer databases or unimported Inbox files.
2. On the watch, open **Receive from phone → Start receiving** and wait for Ready. Then tap **Back to viewer**; the receive/control session continues running. Leave the CSV viewer open (the file library is sufficient).
3. On the phone, tap **Find watch → Control watch view**. The companion loads the watch library, column names, last applied settings, and search text. If the watch has a file open, its editor opens automatically; otherwise select a watch CSV.
4. Use **Columns & order** to choose visible columns and move them with ↑/↓. Hold an arrow to move to first/last. **Column filters** lets you type or paste contains-text filters and regexes. **Row sorting** is also editable.
5. Tap **Apply columns & filters to watch** to send the draft. Wait for **View applied on watch** and the result count. The watch saves it as that file's last applied view.
6. Enter or paste search text and choose one of the search actions below.

| Phone action | Watch behavior |
|---|---|
| Apply columns & filters to watch | Applies the entire phone view draft, including selection, column order, filters/regex flags, and row sort. Does not run a new search. |
| Apply view & search | Applies that draft, then searches from the start of the filtered/sorted view. |
| Send search text only | Keeps the watch's currently applied view and searches from the beginning using the phone text. |
| Previous / Next match on watch | Uses the applied watch view and moves between occurrences of the entered text. |

An empty search string clears the watch search. Search-only actions do **not** apply unsent column/filter edits. Search is literal and case-insensitive; column regex filters remain separate. Matches are highlighted in the watch viewer. A no-match response is explicit and does not claim a match was found.

Changes remain in a phone draft until sent. **Reload from watch (discard phone draft)** reloads the actual applied settings. Drafts, including unfinished regexes or an empty column selection, survive activity recreation/rotation; Apply validates them and requires at least one visible column. Named presets can still be saved on the watch after receiving a view.

#### Confirmation and connection behavior

- The control channel is separate from CSV streaming and uses bounded, versioned JSON messages. It carries metadata/settings/search commands and receipts, not the CSV's data rows.
- The watch returns **accepted** first, then the phone polls for **applied/failed**. Large CSV filtering runs on the watch's background database worker, not on the UI thread. A temporary wake lock keeps that operation running when the watch screen sleeps.
- **Check command status** looks up the receipt if a reply was lost or the phone screen was closed. The phone remembers the outstanding command ID. The watch retains the latest 16 command receipts and does not re-execute a repeated command ID while its receipt is retained.
- A watch-process restart marks unconfirmed commands as interrupted. Reload the watch view before retrying when completion is unknown. Completed settings and search text persist.
- If the watch viewer is already importing/filtering, or has a settings draft open, the command is rejected with an explanation. Finish/cancel that work or leave settings before sending the phone draft, so ongoing watch edits are not silently discarded.
- The viewer must have been opened on the watch. The app does not force-launch an activity from a background service. If it was closed/destroyed, use **Back to viewer** and retry. If another screen covers the viewer, returning to it reveals the updated view.
- The existing 10-minute idle receive-session timeout applies. Restart receiving if you spend longer editing without contacting the watch. Finish a CSV file transfer before opening remote controls on the phone.
- Limits: 512 KiB per control message, 4096 characters of search text, 8192 characters per remote filter, and the existing 256-column limit. Oversized requests are rejected rather than truncated.

### Version 1.2: watch Back button and column reordering

Version 1.2 introduced the following features, also included in 1.3.

**Back navigation:** the viewer and companion now handle Android 13+ system Back explicitly, in addition to legacy hardware Back on Android 11/12. The watch's Back button navigates within the current screen hierarchy, so you do not need to scroll to a footer button:

- Full cell → row details → results → file library.
- Column-position editor → reorder list → the settings/column picker that opened it.
- Filter editor → filter list → settings.
- During an import/filter/search operation, Back requests cancellation.
- At the file library, Back leaves the activity. Back from the transfer screen leaves a running transfer active; use **Stop receiving/Cancel transfer** to stop it.
- Returning from row details restores the results list's previous scroll position.

The system keyboard or an open dialog handles Back first where Android normally does so. If Samsung has assigned the watch's lower button to **Recent apps**, set its short-press action to **Go to previous screen** in **Settings → Advanced features → Customize buttons → Back key** (names vary by firmware). The app cannot receive a Back event when the system maps the button to another action.

**Column order:** open a CSV → **Columns & filters → Reorder columns**. This menu is also available inside **Choose columns**. Tap a column, then use **Move up**, **Move down**, **Move to first**, **Move to last**, or enter a position and tap **Move to position**. Back/Done returns to the menus while retaining the draft moves; select **Apply view** in View settings when finished.

Order affects row previews, details, copied rows, the column/filter menus, and previous/next search. Hidden columns retain their place and remain hidden. Filters and row-sorting choices remain attached to their original source columns. **Restore file order** changes only the column order; **Reset view** resets all view options.

Column order is included in saved presets and the automatically restored last applied view. Presets from older releases load in original file order and retain their existing filters, selection, and row sort. The CSV file and database schema are not rewritten.

### Version 1.1: phone companion and watch receiving

Install **both** updated builds:

| Device | APK | Launcher name |
|---|---|---|
| Galaxy Watch 4 | `build/watch-csv.apk` | Watch CSV |
| Paired Android phone | `build/watch-csv-companion.apk` | Watch CSV Companion |

The two builds intentionally have the **same package name and signing certificate**, as required by Google's Data Layer. The phone companion updates the viewer already installed on the phone; it does not install as a second unrelated app. It retains the viewer through **Open CSV viewer on phone**. Install over existing versions to keep imported databases and presets.

#### Send CSV files from the phone

1. Confirm the watch is paired and connected in **Galaxy Wearable**, and Google Play services is enabled on both devices.
2. On the **watch**, open **Watch CSV → Receive from phone → Start receiving**. Wait for **Ready**.
3. On the **phone**, open **Watch CSV Companion → Choose CSV files**. Android's file picker grants access to the selected files; multiple selection is supported.
4. Tap **Find watch**, select the watch if several are listed, then **Send to watch**.
5. If asked, allow transfer-progress notifications. Denying this optional permission does not prevent transfer; progress remains available inside the app.
6. Wait for the phone to say the watch **confirmed** the file was saved.
7. On the watch, tap **Open Inbox**, choose the received CSV, and select its import options. For your existing files, use **windows-1251** and **Semicolon ;**.

No manual file copy into `Android/data`, IP addresses, or ADB are needed for CSV transfer. Updating/installing the APKs is still done with your preferred installation tool.

The companion transfers original bytes: it does **not** re-encode CSV or change separators. Google Wear OS Data Layer manages the available paired-device transport, such as Bluetooth or Wi-Fi. Both apps must have the same signing key; installing an unrelated CSV viewer on either device will not work as a receiver.

#### Transfer behavior

- Streams use bounded buffers; an entire CSV is never held in RAM.
- Before sending, the phone makes a temporary, checksummed copy in app cache. Allow enough free phone storage for one additional copy of the file. The temporary copy is removed when the exchange finishes or fails normally.
- The watch writes to a temporary `.partial` file, checks the announced byte count and SHA-256 checksum, and only then publishes the file in Inbox and sends a receipt. Partial/corrupted transfers cannot be imported as completed CSV files.
- Existing files are preserved: repeated names receive suffixes such as `data (2).csv`.
- Each file is limited to **256 MiB**. Multiple selected files transfer sequentially. If a later file fails, already confirmed files remain in Inbox.
- Transfers continue in a foreground service when navigating away from the screen. Both apps show a progress notification when notification permission is allowed, with a Stop action. Transfers do not automatically restart after process death or reboot.
- The watch's receive session closes after **10 minutes idle**. Tap Start receiving to reopen it. A transfer is stopped after 3 minutes without progress or 45 minutes for one file. Interrupted transfers restart from the beginning when retried.
- If a receipt is lost after the watch saves a file, check Inbox before retrying; resending may create a numbered duplicate.
- A hard OS/process kill can leave an ignored temporary `.partial` file in cache/Inbox. Normal cancellation and transfer errors remove their partial files.

If **Find watch** returns no receiver, check that the watch says **Ready**, Galaxy Wearable says **Connected**, and both devices have companion-enabled builds (1.1 or newer). Reopen receive mode and repeat discovery. Google Play services availability problems get a system resolution prompt when supported.

New manifest permissions are foreground-service/data-sync and wake-lock permissions (automatically granted normal permissions), plus **POST_NOTIFICATIONS**, requested on Android 13+ before the first transfer session. The app uses Google Play services rather than directly scanning Bluetooth, so it does not request Nearby Devices/Bluetooth permissions. File access still uses the system picker, not broad storage permission.

### Version 1.0.1

Fixes import failing with `Queries can be performed using SQLiteDatabase query or rawQuery methods only`. The journal-mode PRAGMA returns a row and is now executed through `rawQuery`, with its result consumed and checked. This affected database initialization independently of CSV encoding. An Android regression test covers Windows-1251 with an explicit semicolon separator, Cyrillic text, and reopening/filtering the imported database.

Install this APK over version 1.0 to retain completed imports and presets, then retry the failed import with the same encoding and separator.

The package name for both builds is `dev.watchcsv.viewer`.

The APK is development-signed for sideloading. Keep `keys/development.jks` if you want later builds to update this installation without uninstalling it. Removing the app deletes its imported databases and saved presets.

You can transfer and install the APK using your preferred method. No watch installation is performed by the build scripts. To open Termux's Android share sheet for the APK:

```sh
termux-open --send /data/data/com.termux/files/home/opencode1/watch-csv/build/watch-csv.apk
```

Use the same command with `watch-csv-companion.apk` to share the phone build.

Whether a receiving app can install on the watch depends on that transfer app; this command just shares the APK file.

## Using the app

1. In Excel, export the desired worksheet as **CSV UTF-8** (`.csv`). Import each worksheet as a separate CSV if needed. XLS/XLSX workbooks are not read directly.
2. Open **Watch CSV → Import CSV → Choose files**. Select one or several files in Android's picker. CSVs are imported into separate local databases and listed in the library.
3. Choose encoding, separator, and whether the first row contains column names. Auto detection supports commas, semicolons, tabs, and pipes. UTF-8 and UTF-16 byte-order marks are recognized. Windows-1251 and Windows-1252 can be selected explicitly.
4. Open a file. Swipe **right to left** on the results, or tap **Columns & filters**.
5. **Choose columns** controls which values appear in the view. **Reorder columns** changes their display order. **Column filters** filters each column independently using case-insensitive literal text or a regular expression. Filters on multiple columns are combined with **AND**, including filters on hidden columns.
6. **Sort order** selects a column and ascending or descending text order. **Apply view** rebuilds the results; it does not modify the CSV.
7. Tap a row to read its selected columns and copy a cell or the selected row. Long cells have a paged **Read full cell** view.
8. Use **Search text** for literal, case-insensitive search across visible columns in the filtered/sorted results. **Next/Previous occurrence** includes repeated matches within a cell and highlights the match in row details. Search does not wrap automatically; choose **Start at beginning** to restart.
9. In settings, **Saved presets → Save current settings** saves column selection and order, filters, regex flags, and sorting for that CSV. Load a preset and tap **Apply view**. The last applied view also restores when reopening a file.

Results load **12 rows at a time**. Use Previous/Next page or **Go to result row**. Row `#` is the original data-row number, excluding the header; result position reflects filters and sorting. The watch's rotary/bezel scroll events are supported when the device sends them to the app.

Hold a library file to delete its imported copy and its presets. The original CSV remains untouched. Hold a preset to delete it.

### Filter examples

| Mode | Text | Meaning |
|---|---|---|
| Contains | `moscow` | Contains that text, ignoring case |
| Regex | `^ABC` | Starts with ABC |
| Regex | `red\|blue` | Contains either red or blue |
| Regex | `^\d{6}$` | Exactly six digits |
| Regex | `^$` | Empty cell |

Regex is a **filter**, not a sorting algorithm. Sorting is one column at a time, textual rather than numeric or locale-aware: `10` comes before `2`. SQLite's case-insensitive sort folds ASCII letters; non-ASCII sorting follows SQLite's text ordering. Regex filters and search support Unicode case-insensitive matching.

## File access and permission prompts

- **Choose files** launches Android's document picker. Your selection grants read access to the selected file(s). The app imports them immediately and does not need continued access afterward.
- No broad storage permission, “all files access,” media permission, sensor permission, or app network permission is required. CSV is not a photo/video/audio file, so requesting a media permission would not help. The companion's foreground transfers use the additional permissions described above.
- A denied or expired file grant displays **File access needed**, with a **Choose file** action to get access through the picker again.
- Some Wear OS installations have **no document picker**. The app tries both Android's document and content pickers. If neither exists, it explains the issue and offers the Inbox fallback below. A permission dialog cannot supply a missing file-picker app.
- **Copy field**, **Paste**, and row/cell copy buttons use the watch's local Android clipboard, only on explicit taps. Normal text selection is also enabled. Android has no clipboard runtime permission dialog. The phone's clipboard is not automatically shared with the watch.

### Manual Inbox fallback

The phone companion is the recommended way to populate Inbox. The following manual method remains available without the companion.

Open **Import CSV → Import from Inbox** once to create the directory. The app shows its exact path, normally:

```text
/sdcard/Android/data/dev.watchcsv.viewer/files/Inbox/
```

Transfer CSV files into that directory using a tool with access to it, then tap **Refresh** and choose a file or **Import all**. Ordinary file managers may be restricted from writing another app's `Android/data` directory.

For example, if you already have an ADB connection to the watch:

```sh
adb push "/path/to/data.csv" /sdcard/Android/data/dev.watchcsv.viewer/files/Inbox/
```

On watch versions supporting wireless pairing, enable developer options and wireless debugging, then use the watch's displayed pairing and connection addresses. These use **different ports**:

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
```

Enter the pairing code when ADB asks, and approve debugging access on the watch if prompted. Older Wear OS versions may offer an ADB-over-Wi-Fi connection without the pairing step. The app can also receive CSV content URIs through Android's **Open with** action from a compatible file provider.

## Large-file behavior

- CSV is read as a stream and written in one SQLite transaction; it is not loaded entirely into memory.
- On import, quoted delimiters, quoted multiline fields, escaped double quotes, CRLF/LF line endings, Unicode BOMs, and trailing empty cells are supported.
- Filtering and sorting build an on-disk table of matching row IDs. Indexed positions allow small result pages. Database work runs on a background executor, with import/filter progress and cancellation. The screen stays awake during operations.
- Search reads through the active view; it does not pre-load all matches. Complex regexes and broad searches can still take time on watch hardware. A pathological Java regex can delay cancellation until its current match finishes.
- The imported database, result index, source CSV (if kept in Inbox), and SQLite temporary files need free space in addition to the original file size. Allow several times the CSV's size rather than exactly 11 MB.
- Limits: **256 columns**, **500,000 characters per data row**, and **1,000,000 characters per field at the parser level**. Very wide/large rows are rejected with an explanation rather than silently truncated.
- Short rows are padded with empty values. Extra cells beyond the header count are rejected. Physically blank lines in multi-column CSVs are skipped. Duplicate/empty column names receive distinct display names.
- A cancelled or failed individual import is rolled back. During a multi-file import, files already completed remain available if a later file fails or is cancelled.
- Closing/killing the app during work can interrupt the operation. Presets and completed imports persist. An OS kill can leave an ignored `.partial` database in the app's private datasets directory; it is not shown as a completed import.

## Building in Termux

The current environment has the required tools: Python, Java, AAPT2, D8, zipalign, apksigner, and Android SDK API 36. Gradle and Android Studio are not required for this project.

From this directory:

```sh
bash build.sh
bash test.sh
```

`build.sh` now builds both APKs. Use `bash build.sh watch` or `bash build.sh phone` for one variant. The Python builder downloads pinned Google Maven AAR/JAR dependencies on the first build, then caches them in `deps/`. The entry dependency is `com.google.android.gms:play-services-wearable:19.0.0`; transitive libraries, resolved versions, and downloaded artifact SHA-256 values are listed in `deps/resolved.json`. Google/AndroidX resources and generated R classes are packaged into both APKs. Their dependency manifests require the Google Play services version metadata and GoogleApiActivity, both included in the app manifests.

Set `ANDROID_JAR` to use another suitable platform JAR location, or `ANDROID_HOME` to change the SDK root. The scripts use the ARM-compatible Termux tools on `PATH`.

## Verification

- Both apps compile, package, and pass APK signature and alignment verification.
- The JVM test suite checks CSV quoting/newlines, separator detection and overrides, UTF-8/UTF-16 BOMs, Cyrillic encodings, invalid input, forward/backward occurrence navigation, Unicode highlight offsets, and cancellation.
- The large-file parser test streams **14,640,000 bytes / 240,000 rows under a 32 MB Java heap**. This measures the parser, not watch import/rendering speed.
- The transfer suite has **53 assertions**: full bidirectional transfer and receipt, byte-for-byte Windows-1251 preservation, empty files, filename handling, duplicate preservation, corruption/truncation/invalid-header rejection, cancellation cleanup, and a verified **14,640,000-byte stream under a 32 MB Java heap**.
- The parser/search suite has **55 assertions**. These and the transfer tests run on the local JVM, not on a watch.
- The column-order/preset suite has **59 assertions**, covering moves, hidden columns, immutable source-column identities, copy isolation, JSON round trips, old-preset migration, invalid orders, and bidirectional occurrence navigation through reordered columns. This suite uses a pinned Android-compatible `org.json` JAR from Maven Central (`com.vaadin.external.google:android-json:0.0.20131108.vaadin1`) only for JVM tests; it is not packaged in either APK.
- The remote-control suite has **64 assertions** for duplex command framing, applied-view payloads, Unicode/regex/search preservation, accepted/completed/error receipts, search directions, draft restoration, invalid setting rejection, message-size limits, malformed input, and mismatched replies. **231 local assertions pass in total.**
- System Back registration and the manifest opt-in are built for both APKs. Physical Samsung Back-key behavior, keyboard/dialog handling, reorder controls, and scroll restoration still require an on-watch UI check.
- The new phone-control editor, watch UI updates, Google Data Layer RPC, and lifecycle behavior have not been exercised on a paired watch/phone here. Protocol tests use local streams, not Google's transport. Additional Android instrumentation coverage for metadata, persistent command receipts, duplicate request IDs, busy-command rejection, and restart recovery is built but requires an on-device run.
- The user reported the version 1.0.1 CSV viewer working on a phone. **The new companion's Google Data Layer connection and Galaxy Watch runtime have not been tested on a paired device set here.** Foreground service/notification behavior, discovery, background transfers, and watch performance still need an on-device check. Running Android SQLite directly through this Termux host's `app_process` was unavailable; this is not counted as a passed test.

Optional Android instrumentation tests are included for real database import, paging, duplicate headers, combined/hidden-column filters, sorting, presets, search, cancellation rollback, multiple imports, malformed rows, and remote-backend state. To build and run them on a connected compatible Android device/watch after installing the main APK:

```sh
bash build-tests.sh
adb install -r -t build/watch-csv-tests.apk
adb shell am instrument -w dev.watchcsv.viewer.tests/dev.watchcsv.viewer.StoreInstrumentation
```

The test APK is separate from the normal app, has no launcher icon, and is not needed for using the viewer. Tests create and clean up their own random-named database directory in app cache.
