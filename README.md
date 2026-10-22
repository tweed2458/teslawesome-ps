# TeslAwesome PS (PS Bridge)

PlayStation Remote Play (your own PS5, over PSN) and PS Plus Premium cloud
streaming as an Android service with no screen of its own. A client app signed
with the same key starts it, sends it requests over a local socket and receives
the picture, sound and session state back (see `PROTOCOL.md`).

Built on [chiaki-ng](https://github.com/streetpea/chiaki-ng) and
[CloudPad](https://github.com/Chazq2023/CloudPad-Android).

## License

GNU Affero General Public License v3.0 with the OpenSSL exception, as used by
the upstream projects (`COPYING`, `src/main/cpp/third_party/chiaki/LICENSES`).
Bundled third-party libraries keep their own licenses (nanopb, jerasure,
gf-complete in `src/main/cpp/third_party`; mbedTLS, curl, Opus, json-c and
miniupnpc are fetched at build time, see `src/main/cpp/CMakeLists.txt`).

## What is where

- `src/main/cpp/third_party/chiaki/` — chiaki's protocol library as shipped by
  CloudPad, unmodified; `generated/` holds the nanopb sources generated from
  `takion.proto`, so the build needs neither Python nor protoc.
- `src/main/cpp/ps-jni.c` — the JNI bridge.
- `src/main/java/com/tals/psbridge/cloud/` — CloudPad's PS Plus cloud
  code (Gaikai allocation, catalog, library), package renamed, PS Now path removed.
- `PsnAccount.kt` — PSN sign-in (npsso → OAuth tokens), after CloudPad.
- `RemoteSession.kt`, `ModuleService.kt`, `Protocol.kt` — the session and the
  local socket clients talk to.

## Building

Android SDK 36, NDK 28.2.13676358 and CMake 3.30.4, Gradle 8.9 with JDK 17+:

    gradle assembleRelease

The first build downloads the pinned third-party archives (network needed).
A release is signed from an optional `signing.properties` (not in the source):
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`. The bridge only serves
apps signed with the same key.
