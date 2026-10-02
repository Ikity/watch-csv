# Watch Notes

Independent native Android/Wear OS notes application with a phone companion, inspired by the working watch-csv architecture. All application code and build files are in this directory. `watch-csv/` is a reference project and is not a build dependency.

## Applications

| Device | APK | Package |
|---|---|---|
| Wear OS watch | `build/watch-notes.apk` | `dev.watchnotes` |
| Android phone | `build/watch-notes-companion.apk` | `dev.watchnotes` |

The watch and phone builds share a package ID and signing key so Google Wear OS Data Layer can connect them. They are separate from Watch CSV (`dev.watchcsv.viewer`) and can be installed alongside it. Minimum Android API 30; target API 35.

## Features

- Create and edit text/Markdown notes on either device, including offline.
- Title, body, notebook/category, comma-separated tags, to-do and completion status.
- Search note titles, bodies and tags; category filtering; 20-note library pages.
- Swipe **right** on a note to set its category; swipe **left** for actions. Long-press offers the same actions without swiping. Use the middle of a watch row so the system's edge-back gesture does not take priority.
- Trash and restore; complete revision history, including concurrent offline edits.
- Local editor drafts persisted when leaving the app, opening a picker or rotating.
- Two-way phone/watch synchronization through Google Wear OS Data Layer.
- Export one Markdown note, all active notes as a Markdown ZIP, or the SQLite database with history and trash.
- Send a complete watch database snapshot to the phone and save it through the phone's file picker.
- Import Markdown/plain text, zipped Markdown directories, and Watch Notes SQLite backups.
- Joplin interchange using **Markdown + Front Matter**.
- Receive Joplin Android **Share → Watch Notes Companion** notes directly; share notes back through the Android chooser.
- CommonMark preview with Joplin-style tables, nested lists, task lists, strikethrough, footnotes, links, fenced code, sanitized inline HTML, and images. Long-press a note or its preview to show/hide images on that device.

The editor works with Markdown source and offers a rendered preview. KaTeX math, Mermaid/ABC diagrams, interactive task completion in the preview, arbitrary Joplin desktop plugins, large/binary attachment syncing, reminders, encryption, Joplin server synchronization, and JEX are not implemented. Their original Markdown source remains editable; fenced plugin blocks display as code.

## Build

Use the same Termux-compatible Android tools as watch-csv: Python, JDK, `aapt2`, `d8`, `zipalign`, `apksigner`, and an Android platform JAR. The build downloads Google Wearable 19.0.0 and its transitive libraries, CommonMark Java 0.27.1 with table/task/strikethrough/link/footnote extensions, autolink 0.12.0, and jsoup 1.18.3 into this project's own `deps/` directory.

```sh
bash build.sh          # Both independent Notes applications
bash build.sh watch
bash build.sh phone
```

`ANDROID_HOME` defaults to `~/android-sdk`; `ANDROID_JAR` can override `platforms/android-36/android.jar`. Optionally set `MAVEN_SEED_CACHE` to an existing resolver cache to copy dependency artifacts instead of downloading them. No reference-project cache is required.

The builder creates `keys/development.jks` on first use. Keep that key to retain update compatibility and sign both device builds with the same certificate. Build outputs, dependencies and keys are ignored by Git.

```sh
adb -s WATCH_SERIAL install -r build/watch-notes.apk
adb -s PHONE_SERIAL install -r build/watch-notes-companion.apk
```

## Notes and categories

Choose **+ New note**, enter a title/body and optional category/tags, then **Save**. Use the installed keyboard or its voice-input function on the watch. Saving writes SQLite first and attempts synchronization afterward; a connectivity error does not undo the local save.

Swipe right to type a category or choose an existing one. Categories are note fields, so they synchronize with the note. An empty category means uncategorized. Categories with no notes disappear from the category filter. Tags are comma separated; individual tag names containing commas are not supported.

**Back** in the editor offers Save, Discard draft, or Keep editing. **Trash** retains deleted notes, and **Revision history** lets you open any previous version and save it as a new current revision. There is no permanent-delete or history-compaction operation in this release.

## Synchronization and concurrent edits

1. Pair the phone/watch using the normal Wear OS setup, with Google Play services available on both.
2. Install both Notes APKs signed with the same key.
3. Save a note. The app attempts to publish unsent local revisions automatically.
4. Choose **Sync now** on either device to import available Data Layer records and re-publish its complete revision history. This also repairs interrupted transfers and supplies a newly installed peer.
5. Choose **Refresh**, or reopen the library, to display changes received while the library was already open.

Data Layer can queue accepted records while the peer is offline. “Queued” means accepted by local Google Play services, not a remote-device delivery acknowledgement. If Play services is unavailable, revisions remain in the local database; retry with **Sync now** after connectivity/service recovery. Background listener reception is supported, but automatic periodic upload retries are not scheduled.

Each save creates an immutable revision with a UUID and a logical counter. Current versions are selected by counter, then revision UUID for deterministic ties, independently of device wall-clock skew. Both concurrent edits remain in history; this is **whole-note conflict resolution**, not automatic text merging. To select the other edit, open it in history and Save. A deletion is another revision, so stale records cannot simply resurrect a deleted note. A genuinely concurrent edit may win the same deterministic tie; its deletion remains recoverable in history.

Data Layer records and local revisions are retained indefinitely to avoid losing offline edits. Sync work and storage therefore grow with history; this version is intended for personal text-note collections, not large attachment libraries. Each note body is limited to 200,000 characters.

## Files and database backups

**Files & backups** offers:

- **Export notes for Joplin (.zip)**: active notes only, one UTF-8 Markdown file per note, in category directories. Filenames include a UUID to avoid collisions.
- **Export local database**: a standalone, transaction-consistent SQLite snapshot, including all history, trash and pending-sync state.
- Watch: **Send database to phone** queues a database asset.
- Phone: **Sync now**, then **Files & backups → Save watch database** writes the most recently received watch snapshot to your chosen location. This snapshot is kept separately from the phone's live notes database.
- **Import Markdown / ZIP / database**: Markdown import creates new notes. Importing a Watch Notes `.db` merges revision history by UUID; it does not erase notes already on this device. The merged results can then sync to the peer.

A document provider is needed for direct file export/import. Many watches lack one: use the phone companion, or send the watch database to the phone instead. Database reception is limited to 256 MiB. Database import is limited to 10,000 revisions / 16 MiB of serialized text. ZIP imports allow up to 3,000 entries, 1.5 MB per entry and 16 MiB total uncompressed content. Import is validated before a single database transaction; a validation failure imports nothing.

## Joplin → Watch Notes

### Direct share from Joplin Android

Open a note in Joplin Android, tap **Share**, and choose **Watch Notes Companion**. Joplin may send a 32-character hexadecimal note ID on the first line, followed by the actual **title** and then the Markdown body. Watch Notes discards that leading ID, fills **Title** and **Note (Markdown)**, and opens an editable draft. Tap **Save** to store and sync it. A subject is used as the title unless it is the Joplin ID. Markdown with front matter also fills category, tags, creation/update dates and to-do fields. Joplin's plain-text share (like the `jnote`, `ex3`, `ex4`, `ex5`, and `exBashWordSel` examples) does **not** contain the image bytes behind private `:/resource-id` links. References are kept, with unavailable placeholders when no public alternative exists.

When shared HTML `<img src=":/…">` also has a public HTTPS `srcset`, preview uses its first HTTPS URL (requires connectivity). To include offline image data, share PNG/JPEG/GIF/WebP images through Android alongside the text (or choose **Add image** in the editor). Small images are embedded as Markdown `data:image/...;base64,...` links and synchronize with the note. Each image is limited to 120 KB, and the whole note to 200,000 characters. Images are hidden when rendering is off. ZIP imports resolve `:/resource-id` image references when the ZIP contains matching resource-ID image files (such as `_resources/<id>.png`). Image files absent from a text share or archive cannot be reconstructed.

Preview follows Joplin's [Markdown guide](https://joplinapp.org/help/apps/markdown/) and [CommonMark](https://spec.commonmark.org/) for headings, paragraphs/hard line breaks, emphasis, nested ordered/unordered lists, checked/unchecked task items, quotes, links/autolinks, indented/fenced code, horizontal rules and tables; it also supports strikethrough, footnotes, `==highlight==`, and `[[toc]]`/`[toc]` contents links. Shared inline HTML is sanitized before WebView display. Links to other Joplin notes (`:/ID`) are retained in source but cannot open inside Watch Notes unless the target is separately imported.

### Exported Markdown

1. In Joplin desktop, export notes/notebooks as **MD - Markdown + Front Matter**.
2. ZIP the **contents** of the exported directory, preserving notebook subdirectories, or select an individual `.md` file.
3. Move the ZIP/Markdown file to your phone.
4. In Watch Notes, choose **Files & backups → Import Markdown / ZIP / database**.
5. Choose **Sync now** to send the imported notes to the watch.

Supported Joplin metadata: title, creation/update dates, tags and to-do completion (`completed?: yes/no`). Notebook categories are inferred from directory paths; a Watch Notes `notebook` front-matter field preserves categories on its own round trips. Dates without a zone are interpreted as UTC. Extra Joplin metadata (author, location, source URL and due date) is ignored. Other linked attachments are not imported or rewritten, so references can remain unresolved. Importing the same Markdown twice creates duplicates; Markdown is an interchange format, not an identity-preserving synchronization protocol.

## Watch Notes → Joplin

On Android, open a note and tap **Share to Joplin / other apps**, then select Joplin from the system chooser. The share sends the note title as subject and the title plus Markdown body as plain text; Joplin's Android share flow creates a new note. Plain-text sharing cannot preserve Watch Notes categories, tags, timestamps, revisions or trash in Joplin. For those fields use the desktop **Markdown + Front Matter** export below. Embedded data-URI images are included as text, but Joplin Android may not import them as Joplin-managed resource attachments.

### Desktop import

1. Export all notes with **Export notes for Joplin (.zip)**, or use **Export this note (.md)** in the editor.
2. Transfer the export to the computer and unzip it if necessary.
3. In Joplin desktop, select **Import → MD - Markdown + Front Matter (Directory)** for the unzipped directory, or the file option for one note.

The export uses Joplin's supported `title`, `created`, `updated`, `tags` and `completed?` fields. Joplin imports the directory structure as notebooks; it ignores the additional `notebook` field. Category directory names are sanitized for filesystem compatibility. Note IDs, revision history and trash are preserved only in Watch Notes database backups, not Joplin Markdown interchange.

Format reference: <https://joplinapp.org/help/dev/spec/interop_with_frontmatter/>

## Verification

```sh
bash test.sh
bash build.sh
bash build-tests.sh
adb install -r build/watch-notes-companion.apk
adb install -r -t build/watch-notes-tests.apk
adb shell am instrument -w dev.watchnotes.tests/dev.watchnotes.StoreInstrumentation
```

The JVM suite covers wire validation, revision ordering, Joplin front-matter and Android shared-text examples (including ID + title), CommonMark/extensions and HTML sanitization, image `srcset` fallback/visibility, Markdown/ZIP round trips, ZIP image resource resolution and import bounds. Instrumentation covers actual SQLite saves, outbox state, duplicate delivery, concurrent-edit convergence, deletion/restoration, transactional rollback, database snapshots and backup merging. It uses isolated test databases.

Both application APKs have been built, signature-verified and alignment-checked in this environment. JVM tests pass. Device instrumentation and paired-device UI/transport checks require working ADB and real devices; these have not been run here. Check typing/back/rotation, both swipe directions, offline concurrent edits, reconnect/retry, Joplin desktop import, and watch-database reception on your pair before relying on the app for important notes.
