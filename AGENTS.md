# Project agent instructions

## Remote Quest ADB through Tailminal

- A Quest 3 is attached by ADB to the tailnet host `lily`. Local `adb devices`
  may be empty; check the remote ADB server before concluding that the headset
  is unavailable.
- Tailminal is already installed on this workstation and running on `lily`.
  It connects directly over Tailscale. Its operating guide is
  <https://tailminal.justfeli.dev/llms.txt>.
- From PowerShell on this workstation, invoke the npm CMD launcher explicitly.
  The `.ps1` wrapper has mishandled the `--` command boundary here:

  ```powershell
  & "$env:APPDATA\npm\tailminal.cmd" exec lily --timeout-ms 30000 -- "adb devices -l"
  ```

- Pass the entire remote command after `--` as one quoted argument. Prefer
  one-shot `exec` calls with a finite `--timeout-ms` for unattended work:

  ```powershell
  & "$env:APPDATA\npm\tailminal.cmd" exec lily --timeout-ms 30000 -- "adb shell getprop ro.product.model"
  & "$env:APPDATA\npm\tailminal.cmd" exec lily --timeout-ms 30000 -- "adb shell tail -n 300 /sdcard/Android/data/app.gamenative/files/gamenativevr/launch.log"
  & "$env:APPDATA\npm\tailminal.cmd" exec lily --timeout-ms 30000 -- "adb logcat -d -v threadtime -t 1000"
  ```

- `tailminal hosts` depends on a local Tailminal server and may return no peers
  when that server is stopped. Directly addressing the MagicDNS host `lily`
  still works. If a direct command fails, check `tailscale status`, then retry
  the Tailminal health/command path before treating the Quest as disconnected.
- Tailminal grants a full shell on the remote host. Keep commands scoped to the
  requested task, prefer read-only diagnostics first, and do not install,
  delete, reboot, or otherwise mutate the headset unless the user's request
  authorizes it.
