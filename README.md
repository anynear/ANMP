# ANMP – anynear Message Protocol

Message format and client core of [anynear](https://anynear.app), an app that keeps a circle of people connected:
messages, shared files and passwords, end-to-end encrypted, over the internet and optionally by radio. This
repository holds the specification of the format and the library the apps are built on: packets, encryption,
signatures, key derivation, circles, contacts and mailboxes on top of ANDP (anynear Distributed Protocol, repository
`anynear/ANDP`), the content-blind store the packets are kept in.

**Status:** version v1, in use in the anynear apps since November 2026. What v1 protects and where it stops (for
example, it has no forward secrecy) is in the section "Security considerations" of `spec/anmp.md`.

| Path | Content |
|---|---|
| `spec/` | `anmp.cddl` and `anmp.md`, protocol version v1 |
| `vectors/` | test vectors |
| `kotlin/` | Kotlin Multiplatform library (Android, iOS, JVM, JS): `format` (no dependencies) and `core` (uses ANDP) |
| `go/` | Go module `github.com/anynear/ANMP/go`: the radio part of the format, for a radio bridge in a relay |

"ADR nnnn" in comments and in the specification refers to internal decision records of the project; they are not
part of this repository.

Contributing: `CONTRIBUTING.md`. Vulnerabilities: `SECURITY.md`, not a public issue. License: GPL-3.0-or-later
(see `LICENSE` and `NOTICE`); a commercial licence is available (`COMMERCIAL.md`), except for the Meshtastic® layer, which stays GPL-only.

## Modules

| Module | Package | Content |
|---|---|---|
| `format` | `app.anynear.anmp` | packets, CBOR, kinds, AEAD, signatures, key derivation, envelope, blobs; no dependencies |
| `core` | `app.anynear.anmp.core` | logic without UI and platform on ANDP: circles, contacts, personal mailbox with grants, sending and fetching, receipts, reactions, read receipts, want/have, history, key changes, forwarding (ADR 0022), watch and wake fetch, links; JS facade `AnmpCoreWeb`. Package `firmware`: signed radio software list (ADR 0056), recommended radios (`radios.json`) and choice of model, ESP32 ROM loader for setting up a radio over USB (ADR 0094); JS facade `RadioSetupWeb` for WebSerial |

`core` uses the ANDP Kotlin client by composite build from `../../ANDP/kotlin`, i.e. the repository `ANDP` checked
out next to `ANMP` (override with `-Pandp.dir=…`). Entry point is `AnmpCore`; the app plugs in `SecretStore`,
`KeyValueStore`, `BlobStore`, `CoreClock` and optionally a `RelayConnector` (`AnmpPlatform`) and receives
`CoreEvent`s. Protocol description: `spec/anmp.md`, section "Core on ANDP".

## Build

```
cd kotlin
./gradlew :format:jvmTest :format:jsNodeTest :format:testAndroidHostTest   # format tests
./gradlew :core:jvmTest :core:jsNodeTest :core:testAndroidHostTest         # core tests
./gradlew :core:compileKotlinIosSimulatorArm64                             # iOS (needs Xcode)
UPDATE_VECTORS=1 ./gradlew :format:jvmTest                                 # rewrite vectors/anmp-v1.json
UPDATE_VECTORS=1 ./gradlew :core:jvmTest                                   # rewrite vectors/anmp-v1-radio-list.json

cd go
go test ./...                                                              # Go against the same vectors
```

Kotlin writes the vectors, Go checks them (ADR 0054 §3a): a change to the radio way changes both languages and the
vectors in the same PR, starting with the vector.

`:core:jvmTest` also runs the core against a Go test relay (repository ANDP-Peer, not part of this repository; next
to `ANDP` or `ANDP_PEER_DIR`, `go run ./internal/cmd/testrelay`) when it and Go are present; `ANMP_REQUIRE_GO=1`
makes a missing one an error. The other tests run against an in-memory relay network (`core/src/commonTest/.../FakeNet.kt`) that
also checks revocations.

JDK 21, Android SDK via `kotlin/local.properties` (`sdk.dir=…`, not committed).
