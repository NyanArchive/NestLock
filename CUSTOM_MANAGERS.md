# Supporting a Custom KernelSU Manager

NestLock is profile-driven. A profile describes one manager/driver pair: which
manager APK to register, which LKM and `ksud` to load, and how to invoke them.
The app code has no manager-specific branches.

Profiles live in `app/src/main/assets/profiles/` and are listed in
`index.json`.

## Required fields

| Field | Meaning |
|---|---|
| `id` | Stable identifier. Used for the saved selection. |
| `displayName` | Label shown in the profile list. |
| `managerPackage` | Manager APK package, for example `com.resukisu.resukisu`. |
| `managerActivity` | Launcher activity. Leave empty to use the package launch intent. |
| `lkmAsset` | Path inside `assets/`, for example `profiles/bakasu/lkm.ko`. |
| `ksudAsset` | Path inside `assets/` for the `ksud` binary. |
| `profileAsset` | GLK1 profile (`.bin`) for this kernel. |
| `ksudPath` | Destination path, normally `/data/adb/ksud`. |
| `moduleName` | Kernel module name checked in `/proc/modules`, for example `kernelsu`. |
| `insmodArgs` | Argument list for loading the LKM. Placeholders: `{ksud}`, `{lkm}`, `{apk}`. |
| `unameRelease` | Exact release string the GLK1 profile was built for. |
| `uapi` | KernelSU UAPI version implemented by the LKM and `ksud`. |
| `kmi` | KMI of the LKM asset, for example `android12-5.10`. |

## Optional fields

| Field | Meaning |
|---|---|
| `ksudLink` | Extra symlink, for example `/data/adb/ksu/bin/ksud`. |
| `register` | Registration mode. `dynamic-manager` expands to `ksud kernel dynamic-manager set-apk {apk}`. |
| `registerCommand` | Explicit argument list for registration. Overrides `register`. |
| `builtinManager` | `true` when the LKM already recognises the manager APK certificate. |
| `nativeLib` | Native executable name. Default `libghostlock.so`. |

## What a port actually requires

Changing the JSON alone is not enough. A working port needs all of these.

### A sparrow-compatible LKM

The sparrow kernel needs the workarounds in the BakaSU fork: `track_throne`
skipped, `ksu_observer` skipped, direct syscall hooks, and late-load cred
handling. A stock LKM may hang or panic on this vendor kernel.

### A ksud that runs module stages after late load

Late load skips `ksu_ksud_init`, so init never runs `post-fs-data`, `services`,
or `boot-completed`. The BakaSU asset uses `ksud insmod --run-stages`, which
runs those stages in the `u:r:ksu:s0` process that loaded the LKM. A stock
`ksud` does not have that flag. Without an equivalent hook, module scripts will
not start on boot.

### Matching UAPI and KMI

`uapi` must match what the LKM exposes. `kmi` must match the LKM asset. A
mismatch is rejected, or loads an incompatible module.

### A GLK1 profile for the exact release

`unameRelease` must match `uname -r` exactly. The native runtime rejects a
mismatch before running.

### Manager registration

Either the LKM signature list contains the manager APK, or `registerCommand`
succeeds. On this device dynamic registration has been observed to fail with
`register rc=1`. The tested path is the official BakaSU/ReSukiSU manager with
`builtinManager: true`. Verify dynamic registration on-device before relying on
it.

## Minimal profile

```json
{
  "id": "my-manager",
  "displayName": "My Manager",
  "managerPackage": "com.example.manager",
  "managerActivity": "com.example.manager.MainActivity",
  "lkmAsset": "profiles/my-manager/lkm.ko",
  "ksudAsset": "profiles/my-manager/ksud",
  "profileAsset": "profiles/my-manager/sparrow.bin",
  "nativeLib": "libghostlock.so",
  "ksudPath": "/data/adb/ksud",
  "ksudLink": "/data/adb/ksu/bin/ksud",
  "moduleName": "kernelsu",
  "insmodArgs": [
    "{ksud}",
    "insmod",
    "--run-stages",
    "{lkm}",
    "allow_shell=1"
  ],
  "register": "dynamic-manager",
  "registerCommand": [
    "{ksud}",
    "kernel",
    "dynamic-manager",
    "set-apk",
    "{apk}"
  ],
  "unameRelease": "5.10.198-perf-gaaf872b28b70-ab117",
  "uapi": 6,
  "kmi": "android12-5.10",
  "builtinManager": false
}
```

Add it to `index.json`:

```json
{
  "profiles": [
    "profiles/bakasu.json",
    "profiles/my-manager.json"
  ]
}
```

## Assets

Place the files here:

```text
app/src/main/assets/profiles/my-manager/lkm.ko
app/src/main/assets/profiles/my-manager/ksud
app/src/main/assets/profiles/my-manager/sparrow.bin
```

`lkm.ko` and `ksud` are packaged in the APK and copied at runtime. The native
executable must stay in `jniLibs/arm64-v8a/`. It cannot be executed from app
data.

## Verification

Build the LKM against the device kheaders and confirm the vermagic matches
`uname -r`. Confirm `ksud --version` reports the expected UAPI. Install the
profile, select it in NestLock, and cold boot with the LKM not loaded.

On that boot, check:

```sh
su -c id
getenforce
grep '^kernelsu ' /proc/modules
pidof zn-daemon
```

`id` must show `uid=0` in `u:r:ksu:s0`. `getenforce` must be `Enforcing`.
`zn-daemon` must be running without any manual command. The root log must end
with `Root ready`.

Repeat the boot at least twice, and confirm AVB stays `green` and the bootloader
stays locked.

Do not mark a profile supported until it passes this gate on the target device.
A mismatched LKM is a kernel-panic risk, not a cosmetic issue.
