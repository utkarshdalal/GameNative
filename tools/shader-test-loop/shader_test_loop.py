"""Automated librashader shader test loop (cybernetics: actuator=setprop, sensors=screencap+logcat).
Black-box: no visual inspection needed; classification from pixel statistics + logs."""
import subprocess, time, json, csv, sys

# Per-preset on-demand cache (spec 2026-08-11-slang-shaders-on-demand.md, decisão do
# usuário 2026-08-12): the APK ships no shaders; choosing a preset downloads ONLY its
# closure into filesDir/retroarch_pack (repo-root-relative layout). The debug preset
# override points AT THE CACHE — presets whose closure was not downloaded via the UI
# (shader browser → pick preset) will report CHAIN_FAIL/NO_FILE: download them first.
BASE = "/data/user/0/app.gamenative/files/retroarch_pack"
PRESETS = [
    ("crt/crt-easymode.slangp", "crt-easymode"),
    ("film/technicolor.slangp", "film-technicolor"),
    ("ntsc/blargg.slangp", "ntsc-blargg"),
    ("ntsc/ntsc-simple.slangp", "ntsc-simple"),
    ("ntsc/shaders/decoupled-guest/decoupled-guest-advanced-ntsc 3.slangp", "ntsc-guest-advanced"),
    ("hdr/crt-sony-megatron-default-hdr.slangp", "hdr-megatron-default"),
    ("hdr/crt-sony-megatron-v2-default.slangp", "hdr-megatron-v2"),
    ("misc/color-mangler.slangp", "misc-color-mangler"),
    ("misc/glass.slangp", "misc-glass"),
    ("cel/MMJ_Cel_Shader.slangp", "cel-shader"),
    ("nearest.slangp", "nearest (control-)"),
]

def reset_props():
    # Stale debug props persist across sessions and hijack the app (2026-08-07): libradiag
    # diverts to TEST MODEs; a stale preset value overrides the UI request.
    subprocess.run(["adb", "shell", "setprop", "debug.gamenative.libradiag", "0"], capture_output=True)
    subprocess.run(["adb", "shell", "setprop", "debug.gamenative.preset", ""], capture_output=True)

def adb(args, timeout=60):
    return subprocess.run(["adb"] + args, capture_output=True, text=True, timeout=timeout)

def screen_stats():
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    if r.returncode != 0 or len(r.stdout) < 1000:
        return None
    import numpy as np
    from PIL import Image
    import io
    arr = np.array(Image.open(io.BytesIO(r.stdout)).convert('RGB'))
    h, w, _ = arr.shape
    mean = arr.mean(axis=(0, 1))
    std = arr.std(axis=(0, 1))
    grid = arr[::max(1,h//8), ::max(1,w//16)].reshape(-1, 3)
    nonblack = int((grid.max(axis=1) > 30).sum())
    bright = int((grid.mean(axis=1) > 120).sum())
    # colorfulness: mean channel spread
    colorfulness = float(np.abs(arr[:,:,0].astype(int)-arr[:,:,1]).mean() + np.abs(arr[:,:,1].astype(int)-arr[:,:,2]).mean() + np.abs(arr[:,:,0].astype(int)-arr[:,:,2]).mean()) / 3.0
    return {"mean": tuple(round(v,1) for v in mean), "std": tuple(round(v,1) for v in std),
            "nonblack": nonblack, "bright": bright, "colorfulness": round(colorfulness,1), "grid": len(grid)}

def classify(stats, chain_ok, app_alive, base, pipeline_ok):
    """Scene-independent verdicts (2026-08-07): a dark static game scene makes absolute
    brightness meaningless. Compare DELTA vs the baseline (nearest/passthrough) and use
    pipeline-health sensors (DEFAULT path logs flowing, no latch/timeout/throw)."""
    if not app_alive:
        return "CRASH"
    if not pipeline_ok:
        return "PIPELINE_DOWN"
    if not chain_ok:
        return "CHAIN_FAIL"
    if stats is None:
        return "NO_SCREEN"
    if base is None:
        return "NO_BASELINE"
    dm = stats["mean"][0] - base["mean"][0]
    dc = stats["colorfulness"] - base["colorfulness"]
    # pure black with no variance = genuine black screen (only when baseline isn't black)
    if stats["mean"][0] < 0.5 and stats["std"][0] < 1.0 and base["mean"][0] > 0.5:
        return "BLACK_SCREEN"
    if abs(dm) > 50 or stats["bright"] >= 60:
        return "STRONG_CHANGE"
    if abs(dm) >= 8 or abs(dc) >= 3:
        return "CHANGED"
    return "PASSTHROUGH/SUBTLE"

def pipeline_health(log):
    """Sensors: any latch/timeout/throw in the window = pipeline degraded."""
    bad = [k for k in ("chain latched", "fence timeout", "renderFrame threw",
                       "applyFrame failed", "ignoring runtime preset override")]
    return not any(k in log for k in bad)

def run_once(rel, name, base):
    # actuator: switch preset at runtime
    path = f"{BASE}/{rel}"
    adb(["shell", "setprop", "debug.gamenative.preset", path])
    time.sleep(6)
    # sensors
    stats = screen_stats()
    log = adb(["logcat", "-d"]).stdout
    idx = log.rfind(f"runtime preset override -> {path}")
    tail = log[idx:] if idx >= 0 else ""
    chain_ok = ("preset chain active=1" in tail) or ("preset chain active" not in tail and idx < 0)
    chain_fail = "filter chain create failed" in tail
    if chain_fail:
        chain_ok = False
    app_alive = adb(["shell", "pidof", "app.gamenative"]).stdout.strip() != ""
    verdict = classify(stats, chain_ok, app_alive, base, pipeline_health(tail))
    print(f"{rel:45s} {verdict:16s} mean={stats['mean'] if stats else None} dmean={round(stats['mean'][0]-base['mean'][0],1) if stats and base else '-'} nblk={stats['nonblack'] if stats else '-'}")
    return [rel, name, verdict, chain_ok, str(stats['mean'] if stats else ''), stats['nonblack'] if stats else '', stats['bright'] if stats else '', stats['colorfulness'] if stats else '']

def main():
    reset_props()
    out = "/tmp/shader_results.csv"
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["preset", "name", "verdict", "chain_ok", "mean_rgb", "nonblack", "bright", "colorfulness"])
        # baseline first: nearest (pure passthrough) at the CURRENT scene
        base = None
        try:
            adb(["shell", "setprop", "debug.gamenative.preset", f"{BASE}/nearest.slangp"])
            time.sleep(6)
            base = screen_stats()
            print(f"{'BASELINE (nearest)':45s} mean={base['mean'] if base else None}")
        except Exception as e:
            print("baseline capture failed:", e)
        for rel, name in PRESETS:
            if rel == "nearest.slangp":
                # baseline already measured; re-measure for the row
                try:
                    row = run_once(rel, name, base)
                except Exception as e:
                    row = [rel, name, f"ERROR:{e}", "", "", "", "", ""]
            else:
                try:
                    row = run_once(rel, name, base)
                except Exception as e:
                    row = [rel, name, f"ERROR:{e}", "", "", "", "", ""]
            w.writerow(row)
            f.flush()
    print(f"\nresults -> {out}")

if __name__ == "__main__":
    main()
