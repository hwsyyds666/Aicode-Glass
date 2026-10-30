# File Browser & Code Editor

The **Files** tab in the workspace sidebar browses the active local or remote SSH workspace. Tap a text file to open the full-screen editor; images open in the image viewer and APK/APKS/XAPK files open the Android installer.

## Browse a workspace

- The workspace root is the first row. Tap a directory to expand it in place; directories appear before files, with each group sorted by name.
- Each directory loads its first 500 entries initially. Use **Load more** to fetch the next page; collapsed descendants are not scanned eagerly.
- Expanded/collapsed folders are remembered per workspace.
- Hidden files are deemphasized and entries matched by the workspace-root `.gitignore` are highlighted. Only the root `.gitignore` is applied.
- Local workspaces refresh when watched directories change. Remote SSH workspaces cannot watch remote changes automatically; use the refresh button or return to the Files tab to reload.

## Create, rename, delete, and move

Long-press a directory (including the root) to create a file or folder. Long-press a file or child directory to rename, delete, copy, or cut it. Paste into a target directory by long-pressing that directory.

Names must be a single entry name, not a path. Folder deletion is recursive and irreversible. Replacing an existing destination is also irreversible; review the confirmation prompt before proceeding.

## Code editor

Text files up to **2 MB** can be edited. Binary files and larger files are rejected with an explanation. The editor supports undo/redo, save, syntax highlighting, Markdown preview, configurable wrapping/font size/indent guides/whitespace, and a save confirmation when leaving with unsaved changes.

Saving replaces the file contents. There is no external-change merge prompt, so avoid editing the same file in the editor and AI/terminal at the same time.

For details about Android file-system access and file links in chat, see the in-app file browser and the workspace's configured execution mode.
