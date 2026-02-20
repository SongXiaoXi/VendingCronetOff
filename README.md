# VendingCronetOff

Work around a buggy ROM-provided APEX Cronet (not bundled in Google Play Store) by disabling Play Store's Cronet path and forcing a safe fallback network stack.

## What it does
- Hooks Play Store’s Cronet entry points and blocks `android.net.http.HttpEngine` build/creation.
- Disables `NativeCronetProvider` so Play Store falls back to a non-Cronet HTTP stack.
- Targets the crash: `fdsan: attempted to close file descriptor 0` in `/apex/com.android.tethering/lib64/libcronet.*.so`.
- Root cause is the system ROM/APEX Cronet library; Play Store is only a frequent trigger path.

## Before fix (symptoms)
- Play Store may randomly crash while browsing or updating.
- App downloads may stall at a certain progress.
- In adb shell, `com.android.vending` CPU usage may exceed 100%.

## Requirements
- Android 14+ (recommended; APEX Cronet path). May still help on older versions.
- LSPosed / Xposed framework.
- Scope: `com.android.vending` only.

## Installation
1. Build and install the module APK.
2. Enable the module in LSPosed.
3. Scope it to `com.android.vending`.
4. Force-stop Play Store and reopen it.

## Usage
No UI. The module works in the background once enabled.

## Notes
- On some devices/ROMs (for example HyperOS 1.0.15.0.UKKCNXM on Redmi K40 Pro), the buggy `libcronet.so` comes from the ROM APEX package, not from `com.android.vending`.
- HyperOS 1.0.15.0.UKKCNXM on Redmi K40 Pro may be in late maintenance (near end-of-support), so APEX Cronet fixes may not arrive quickly.
- This module does not ship any Google Play code; it only hooks runtime behavior.

## Troubleshooting
- Check LSPosed logs for `VendingCronetOff:` lines to confirm hooks are active.

## Crash Evidence (before module)
Key lines only (trimmed for readability).

- Device/ROM sample: `Redmi K40 Pro`, `HyperOS 1.0.15.0.UKKCNXM`
- Process: `com.android.vending` (`ChromiumNet` thread)
- Fault location: `/apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so`

<details>
<summary>Crash sample A — fdsan close(fd=0) abort (excerpt)</summary>

```text
Build fingerprint: Redmi/haydn/haydn:14/.../V816.0.15.0.UKKCNXM:user/release-keys
Cmdline: com.android.vending
signal 6 (SIGABRT)
Abort message: 'fdsan: attempted to close file descriptor 0, expected to be unowned, actually owned by unique_fd ...'

backtrace:
	#00 /apex/com.android.runtime/lib64/bionic/libc.so (fdsan_error)
	#01 /apex/com.android.runtime/lib64/bionic/libc.so (android_fdsan_close_with_tag)
	#02 /apex/com.android.runtime/lib64/bionic/libc.so (close)
	#03 /apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so (net::SocketPosix::StopWatchingAndCleanUp)
	#04 /apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so (net::SocketPosix::~SocketPosix)
	#05 /apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so (net::TCPSocketPosix::Close)
```

</details>

<details>
<summary>Crash sample B — udp_socket_posix fatal (excerpt)</summary>

```text
Build fingerprint: Redmi/haydn/haydn:14/.../V816.0.15.0.UKKCNXM:user/release-keys
Cmdline: com.android.vending
signal 5 (SIGTRAP)
Abort message: '[....:FATAL:udp_socket_posix.cc(315)] Check failed: . : Bad file descriptor (9)'

backtrace:
	#00 /apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so (logging::LogMessage::~LogMessage)
	#01 /apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so (logging::ErrnoLogMessage::~ErrnoLogMessage)
	#02 /apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so (net::UDPSocketPosix::Close)
	#03 /apex/com.android.tethering/lib64/libcronet.114.0.5735.84.so (net::UDPClientSocket::~UDPClientSocket)
```

</details>

