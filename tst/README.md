# Tests

Android unit and instrumentation tests live inside the Gradle module:

- **Unit tests:** `src/app/src/test/java/com/custom/minimizer/`
- **Instrumented tests:** `src/app/src/androidTest/java/com/custom/minimizer/`

Run unit tests:
```bash
cd src
./gradlew test
```

Run instrumented tests (requires connected device or emulator):
```bash
cd src
./gradlew connectedAndroidTest
```
