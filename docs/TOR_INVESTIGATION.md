# Tor Investigation

## Goal
Measure feasibility and impact of adding `arti-client` (Tor) to Cove.

## Blocking issue
Arti and existing BDK dependencies pulled incompatible SQLite link stacks into one Cargo graph.

- Arti path: `arti-client -> tor-dirmgr -> rusqlite 0.38 -> libsqlite3-sys 0.36`
- Previous BDK path: `bdk_wallet -> bdk_chain -> rusqlite 0.31 -> libsqlite3-sys 0.28`

Cargo allows only one crate with `links = "sqlite3"`, so resolution failed.

## Implemented fix (local, in-repo)
We avoided an external maintained fork and used a local vendored patch.

1. Vendored BDK crates under:
   - `rust/external/bdk/bdk_wallet`
   - `rust/external/bdk/bdk_chain`
   - `rust/external/bdk/bdk_core`
2. Added `[patch.crates-io]` in `rust/Cargo.toml` to point to those local paths.
3. Aligned SQLite dependencies to Arti-compatible line:
   - workspace `rusqlite` -> `0.38`
   - vendored `bdk_chain` `rusqlite` -> `0.38.0`
4. Applied minimal compatibility changes in vendored `bdk_chain` SQLite code for `rusqlite 0.38` integer conversions.

## Why this approach
- No per-machine Cargo registry hacks.
- No separate external fork repo to maintain.
- Fully auditable and reproducible in this repository.
- Easy rollback by removing `[patch.crates-io]` overrides.

## Current status
Dependency graph resolves with a single SQLite stack:

- `rusqlite 0.38.0`
- `libsqlite3-sys 0.36.0`

Checks passing:
- `cargo check -p cove-http`
- `cargo check -p xtask`
- `cargo check -p cove`
- `cargo check --workspace`

## Dependency increase estimate
Isolated probe (minimal baseline vs baseline + Arti) shows:

- baseline graph: `111` packages
- with `arti-client 0.40` (`tokio,rustls,compression`): `535` packages
- estimated increase: **`+424` transitive packages**

## Arti integration scope right now
Current change links Arti into the binary for size/dependency measurement, but does **not** route Cove network traffic through Tor yet.

- `arti-client::TorClientConfig::default()` is touched to force linkage
- no runtime Tor proxying/connection logic is enabled yet

## Android size measurements
### Baseline (before successful Arti-linked release build)
- `android/app/build/outputs/apk/dev/release/app-dev-release.apk`: `88,297,003` bytes
- `android/app/src/main/jniLibs/arm64-v8a/libcoveffi.so`: `28,154,448` bytes
- `android/app/src/main/jniLibs/x86_64/libcoveffi.so`: `29,296,712` bytes

### Arti-linked JNI libs (after successful release-speed build)
- `android/app/src/main/jniLibs/arm64-v8a/libcoveffi.so`: `29,062,024` bytes (**+907,576**)
- `android/app/src/main/jniLibs/x86_64/libcoveffi.so`: `30,120,896` bytes (**+824,184**)

Note: APK number has not yet been refreshed from a post-Arti Gradle assemble in this document; measure again after `./gradlew assembleDevRelease`.
