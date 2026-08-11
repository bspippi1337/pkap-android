# PKap – Native Android Pcredz

**Native Kotlin + Jetpack Compose port of Pcredz with full auto root mode.**

Extracts NTLMv1/v2, HTTP Basic, FTP, SMTP/IMAP/POP, SNMP, HTTP form fields and credit cards from live traffic or PCAPs.

## Modes

| Mode | Root? | Description |
|------|-------|-------------|
| **VPN** | No | Local VpnService capture (works everywhere) |
| **ROOT AUTO** | Yes | `su` + tcpdump on **all interfaces**, continuous crawl, live CSV |
| **PCAP** | No | Offline classic PCAP analysis |

## Auto Root Crawl

- Detects root + tcpdump (Magisk module / busybox / system)
- Captures on `any` interface
- Continuously parses and deduplicates credentials
- Writes **live CSV** + classic hashcat log files
- Auto-rotates pcap when > 50 MB

## Pretty CSV

Columns:

```
timestamp,type,protocol,username,domain,secret,hashcat_line,source
```

Proper quoting, one file per export + continuous `pkap_live.csv` while in auto mode.

Location: `Android/data/com.bspippi.pkap/files/exports/`

## Build

Open in Android Studio → Build → Generate Signed APK  
or

```bash
./gradlew assembleRelease
```

minSdk 26, targetSdk 35.

## Notes

- Requires `tcpdump` on device for ROOT AUTO (install via Magisk tcpdump module or copy binary to `/data/local/tmp`)
- No full TCP reassembly (same limitation as original Pcredz)
- Kerberos extractor still minimal – structure is there for later ASN.1 work

**blckswan · 1337**
