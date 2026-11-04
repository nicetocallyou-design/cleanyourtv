#!/usr/bin/env python3
"""Integrate the V19 SUMBAJANG Home widget into the current CleanYourTV launcher.

The widget source/assets are committed as ordinary project files. This installer only
patches launcher wiring (MainActivity, manifest permission, and an Android resource ID).
It is deliberately compatible with the current upstream-derived launcher, which has no
weather-widget focus hooks.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import shutil
import sys

MASTER_SHA = "031882616a7a9f000e87fb005e2cc9554026fbbe5009263a45db63b8626e32ff"
RUNTIME_SHA = "9bbe1978a9aad1dbf088b22128914404e893448dc977739eec89dc9517819b87"
MARKER = "SUMBAJANG_WIDGET_DOCK_FOCUS_V19"


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def merge_permission(manifest: str, permission: str) -> str:
    if permission in manifest:
        return manifest
    app_anchor = "    <application"
    if manifest.count(app_anchor) != 1:
        raise RuntimeError(f"Manifest application anchor not unique while adding {permission}")
    return manifest.replace(app_anchor, f'    <uses-permission android:name="{permission}" />\n{app_anchor}', 1)


def merge_ids(ids_text: str | None) -> str:
    item = '<item name="sumbajang_widget_tag" type="id" />'
    if ids_text is None:
        return '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    ' + item + '\n</resources>\n'
    if "sumbajang_widget_tag" in ids_text:
        return ids_text
    if ids_text.count("</resources>") != 1:
        raise RuntimeError("ids.xml has no unique </resources> anchor")
    return ids_text.replace("</resources>", f"    {item}\n</resources>", 1)


def patch_activity(activity: str) -> str:
    # If this marker exists, the whole focus integration must already be present.
    if MARKER in activity:
        required = (
            "private boolean sumbajangWidgetHiddenForGridNavigation;",
            "private int sumbajangFocusZone(View candidate)",
            "SumbajangWidgetController.install(this, root);",
            "showSumbajangWidgetWhenDockFocused(newF);",
        )
        missing = [token for token in required if token not in activity]
        if missing:
            raise RuntimeError("Partial V19 integration detected; missing: " + ", ".join(missing))
        return activity

    # State field: anchor to the already existing WallpaperPlayer field.
    field_anchor = "    private WallpaperPlayer wallpaperPlayer;"
    activity = replace_once(
        activity,
        field_anchor,
        field_anchor + "\n    // " + MARKER + ": visibility state for the dock-focus prayer card.\n"
        "    private boolean sumbajangWidgetHiddenForGridNavigation;",
        "V19 visibility state field",
    )

    # Install the native overlay as a non-focusable child of the existing launcher root.
    install_anchor = "        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);"
    install_call = "        SumbajangWidgetController.install(this, root);"
    if install_call not in activity:
        activity = replace_once(activity, install_anchor, install_anchor + "\n" + install_call,
                                "V19 controller installation")

    # Insert helpers beside the existing focused-tile helper; the dock layout is defined by
    # CleanYourTV's DockSlots helper, not by assumptions about weather widget code.
    helper_anchor = "    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */\n    private int focusedTilePosition() {"
    helpers = '''    // SUMBAJANG_WIDGET_DOCK_FOCUS_V19: -1 outside grid, 0 in scrollable app rows, 1 in dock.
    private int sumbajangFocusZone(View candidate) {
        if (candidate == null || grid == null) return -1;
        View item = candidate;
        while (item != null) {
            android.view.ViewParent parent = item.getParent();
            if (parent == grid) {
                int position = grid.getChildAdapterPosition(item);
                if (position == RecyclerView.NO_POSITION) return -1;
                return DockSlots.inDockRow(dockRow, singleRow, position, COLUMNS) ? 1 : 0;
            }
            if (!(parent instanceof View)) return -1;
            item = (View) parent;
        }
        return -1;
    }

    private void hideSumbajangWidgetForGridNavigation() {
        if (sumbajangWidgetHiddenForGridNavigation) return;
        sumbajangWidgetHiddenForGridNavigation = true;
        SumbajangWidgetController.setWidgetVisible(root, false);
    }

    private void showSumbajangWidgetWhenDockFocused(View focused) {
        if (sumbajangFocusZone(focused) != 1 || !sumbajangWidgetHiddenForGridNavigation) return;
        sumbajangWidgetHiddenForGridNavigation = false;
        SumbajangWidgetController.setWidgetVisible(root, true);
    }

'''
    activity = replace_once(activity, helper_anchor, helpers + helper_anchor, "V19 focus helper insertion")

    # Replace the existing global focus listener without depending on any weather-widget symbols.
    listener_old = '''        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });'''
    listener_new = f'''        // {MARKER}: keep SUMBAJANG visible on the dock; hide it in app rows and restore on dock focus.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {{
            int focusZone = sumbajangFocusZone(newF);
            if (focusZone == 0) hideSumbajangWidgetForGridNavigation();
            else if (focusZone == 1) showSumbajangWidgetWhenDockFocused(newF);
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        }});'''
    activity = replace_once(activity, listener_old, listener_new, "V19 global focus listener")
    return activity


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher", default="launcher")
    args = parser.parse_args()
    launcher = Path(args.launcher).resolve()
    project = launcher / "app/src/main"
    java_dir = project / "java/com/gathin/tvhome"
    res_values = project / "res/values"
    asset_dir = project / "assets"
    drawable_dir = project / "res/drawable-nodpi"

    activity_path = java_dir / "MainActivity.java"
    manifest_path = project / "AndroidManifest.xml"
    view_path = java_dir / "SumbajangWidgetView.java"
    controller_path = java_dir / "SumbajangWidgetController.java"
    logo_path = asset_dir / "sumbajang_logo.png"
    master_path = asset_dir / "sumbajang_logo_master.png"
    logo_lock_path = asset_dir / "SUMBAJANG_LOGO_LOCK.txt"
    mosque_path = drawable_dir / "sumbajang_mosque.webp"
    ids_path = res_values / "ids.xml"
    layout_path = project / "res/layout/activity_main.xml"

    for path in (activity_path, manifest_path, view_path, controller_path,
                 logo_path, master_path, mosque_path, layout_path):
        if not path.is_file():
            raise RuntimeError(f"Required project file missing: {path}")

    if sha(master_path) != MASTER_SHA:
        raise RuntimeError("Master logo SHA-256 mismatch; refusing to change launcher.")
    if sha(logo_path) != RUNTIME_SHA:
        raise RuntimeError("Runtime logo SHA-256 mismatch; refusing to change launcher.")

    # Confirm that the existing layout has every anchor consumed by the widget controller.
    layout_text = layout_path.read_text(encoding="utf-8")
    for ident in ("root", "leftBlock", "heroName", "hero", "date"):
        if not re.search(r"@(?:\+)?id/" + re.escape(ident) + r"\b", layout_text):
            raise RuntimeError(f"Required activity_main.xml ID missing: {ident}")

    activity = patch_activity(activity_path.read_text(encoding="utf-8"))
    manifest = manifest_path.read_text(encoding="utf-8")
    manifest = merge_permission(manifest, "android.permission.INTERNET")
    manifest = merge_permission(manifest, "android.permission.ACCESS_COARSE_LOCATION")
    ids_text = ids_path.read_text(encoding="utf-8") if ids_path.exists() else None
    ids_text = merge_ids(ids_text)

    # All validation is complete. Back up text files before changing them.
    backup = launcher / ".sumbajang-v19-backup"
    backup.mkdir(exist_ok=True)
    for path in (activity_path, manifest_path, ids_path):
        if path.exists():
            shutil.copy2(path, backup / (path.name + ".before-v19"))

    activity_path.write_text(activity, encoding="utf-8")
    manifest_path.write_text(manifest, encoding="utf-8")
    res_values.mkdir(parents=True, exist_ok=True)
    ids_path.write_text(ids_text, encoding="utf-8")

    # Post-write assertions prevent a silent partial patch.
    if activity.count("SumbajangWidgetController.install(this, root);") != 1:
        raise RuntimeError("Controller install call did not resolve to exactly one call.")
    if "sumbajang_widget_tag" not in ids_path.read_text(encoding="utf-8"):
        raise RuntimeError("Android ID resource was not written.")
    print("SUMBAJANG V19 integration completed for current CleanYourTV launcher.")
    print("- Native Home overlay + prayer schedule source/assets verified")
    print("- Logo master and runtime PNG SHA-256 verified")
    print("- Dock focus shows widget; app-row focus auto-hides it")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
