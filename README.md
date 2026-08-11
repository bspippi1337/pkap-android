# PKap // BLCKSWAN

**Native Kotlin + Jetpack Compose packet-analysis toolkit for Android.**

PKap is designed for **local, authorized analysis** of traffic you own or are permitted to inspect. It supports offline PCAP analysis, a rooted local sensor using `tcpdump`, and an experimental Android VPN lab mode.

## BLCKSWAN Edition

- Dark terminal-style Compose UI
- Privacy mode enabled by default
- Automatic logs and live CSV are redacted
- Explicit reveal toggle for local inspection
- Manual raw CSV export only while reveal mode is enabled
- ROOT capture kills only its own `tcpdump` PID
- Correct PID tracking after PCAP rotation
- CI builds debug APKs for master, PRs, and `agent/**` branches

## Modes

| Mode | Root | Status |
|---|---:|---|
| **PCAP** | No | Offline classic-PCAP analysis |
| **ROOT SENSOR** | Yes | Local `su` + `tcpdump` capture on owned/authorized interfaces |
| **VPN LAB** | No | Experimental packet observation via `VpnService`; not a transparent forwarding VPN |

## Privacy model

PKap keeps detected material in memory for the active session, but automatic persistence is sanitized:

- `PKap-Session.log` stores metadata + redacted markers
- `pkap_live_redacted.csv` never stores plaintext secrets
- normal CSV export is redacted
- enabling **RAW VIEW** allows an explicit local raw export

Treat any raw export as sensitive data.

## Build

```bash
gradle --no-daemon :app:assembleDebug
```

GitHub Actions publishes the APK as `PKap-BLCKSWAN-debug-apk`.

- minSdk 26
- targetSdk 35
- Java 17
- Gradle 8.10.2

## ROOT SENSOR requirements

A trusted `tcpdump` must be available through Magisk, BusyBox, system paths, or `/data/local/tmp`.

PKap intentionally tracks and terminates **only the capture PID it started**; it does not use a global `pkill tcpdump` shutdown.

## Notes

- TCP stream reassembly is still limited.
- IPv6 parsing remains partial/future work.
- VPN LAB currently observes TUN packets but does not implement a full user-space TCP/IP forwarder.

**BLCKSWAN · RESTLESS · NODE42**
