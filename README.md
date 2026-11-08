# BAF_cap for Linux

Ground-call data capture for British Columbia VRI Phase 1 field work, as a standalone
Linux desktop app (GCap rebuild). It reads and writes `.dat` files byte-compatible with
the reference GCap files, so the existing shapefile and spreadsheet tools read them unchanged.

Modules:

| Path | Purpose |
|------|---------|
| `gcap-core/` | Pure Kotlin (JVM) engine: `.dat` parser and writer, VRI calculations, shapefile and spreadsheet export. |
| `desktop/` | Swing desktop app that uses `gcap-core`. |

## Build and run

Requires JDK 21 (a full JDK with `jmods` is needed to build the installer).

```
./gradlew :desktop:run          # run from source
./gradlew :gcap-core:test       # engine tests
./gradlew :desktop:dist         # .deb installer and portable .tar.gz
```

The `.dat` tests read sample files from your own folders (`~/GroundCallsCapture511/Groundcalls`
and `~/VRI PHOTOINTERPRETATION/...`). They fail if those files are missing.

## Status

Prototype used in field work. Test it on copies of your data before relying on it.
The release build is not code-signed.

## Licence

MIT, see `LICENSE`.
