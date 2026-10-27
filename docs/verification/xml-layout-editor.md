# XML layout source editor

The advanced **Actions → Edit** editor has a Visual/XML source switch. XML version 1 is a text
representation of `LayoutProfile`, not Android view XML. It contains the stable current profile ID,
name, ten-column count, and ordered buttons with stable IDs, title, opaque command, width, and
case-preserved `#RRGGBB`/`#AARRGGBB` color. The accepted structure is shown in the XML source section
of the README.

The native monospace field uses a bounded single-pass presentation lexer and an identity offset
mapping; highlighting does not replace or normalize editor text. Validate and Format do not write
the repository. Format preserves text-node contents and in-layout comments. Comments are source
annotations only and are not profile fields. Apply validates the whole document, checks the target
profile ID, and performs one existing atomic recoverable-draft write. Button changes use one entry
in the existing button-only Undo/Redo history; names remain outside that history. Saving still uses
the ordinary editor Save operation.

Only XML 1.0 and profile-schema version 1 are accepted. DTD/entity declarations, external or custom
entities, processing instructions, namespaces, XInclude, and unknown schema fields are rejected.
Source length, nesting, and button count are bounded. Invalid text remains only in the retained
editor ViewModel until explicitly reset or discarded; it is not placed in a Bundle, preference,
file, log, repository, or command sink. A valid recoverable draft can be reopened after process
death, but unapplied in-memory XML cannot.

## Parser security and XML 1.0 round-trip

The codec rejects an XML 1.1 declaration before SAX parsing, including when preceded by a BOM, and
checks decoded text and attribute values against XML 1.0 scalar ranges. This matters because the
JDK SAX probe accepted an XML 1.1 numeric control reference (`&#x1;`) and normalized a literal NEL;
neither behavior can be serialized losslessly as XML 1.0. XML 1.0 NEL round-trips exactly. JVM and
Android tests cover XML 1.1, BOM + XML 1.1, invalid numeric references in text/attributes,
duplicate attributes, and NEL preservation.

On the first API 35 SAX run, `SAXParserFactory.isXIncludeAware = false` threw
`UnsupportedOperationException` (`This parser does not support specification "Unknown" version
"0.0"`). The implementation now catches that optional switch and leaves the provider's default
disabled state unchanged. Secure-processing and external-entity JAXP flags are attempted but are
best-effort; no claim is made that every Android provider supports each optional flag. The hard
barriers are independent of those flags: DTD/entity declarations are rejected by a bounded source
preflight before parser construction, an `EntityResolver` rejects any external request, the
`LexicalHandler` rejects DTD/entity callbacks and is required (otherwise parser setup fails closed),
and the strict schema rejects namespaces and unknown elements such as `xi:include`. No custom XML
parser is used.

## Verification — 2026-10-27

All commands used the Android SDK at `/home/homoludens/Android/Sdk`. Owned emulators were run one at
a time behind the isolated ADB server on port `5049`; no physical device was used.

### Gradle gate

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Final result: **BUILD SUCCESSFUL**. Parsed JVM JUnit XML reports **514 tests, 0 failures, 0 errors,
and 0 skipped**. The XML-focused JVM classes contribute **18 tests** (8 codec, 6 editor state/history,
4 lexer). Lint reports **258 project-wide warnings and 0 errors**. Debug app and Android-test APKs
assembled successfully.

### API 35 editor and command-safety checks

Owned AVD `opencode_xml_20261027_api35`, emulator serial `emulator-5598`, API 35; final boot used
the isolated ADB server on port `5049`:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
ANDROID_ADB_SERVER_PORT=5049 \
JAVA_TOOL_OPTIONS=-DANDROID_ADB_SERVER_PORT=5049 \
ANDROID_SERIAL=emulator-5598 \
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5598 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.stripe1.xmouse.layout.XmlLayoutEditorAndroidTest,com.stripe1.xmouse.layout.NativeActionEditorAndroidTest,com.stripe1.xmouse.layout.LayoutEditorPointerAndroidTest,com.stripe1.xmouse.layout.LayoutEditorAccessibilityAndroidTest \
  --console=plain
```

Final result: **7 tests, 0 failures, 1 expected skip**. The four XML editor methods include the
Android SAX round-trip/security test with XML 1.1/BOM/invalid-reference/duplicate-attribute cases;
three passed and the opt-in screenshot method was skipped here. Native editor, pointer, and
accessibility tests also passed.

The live recording-transport no-dispatch regression was run separately:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
ANDROID_ADB_SERVER_PORT=5049 \
JAVA_TOOL_OPTIONS=-DANDROID_ADB_SERVER_PORT=5049 \
ANDROID_SERIAL=emulator-5598 \
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5598 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.stripe1.xmouse.session.SessionForegroundServiceAndroidTest#layoutEditorNeverDispatchesAndNormalControlsUseOnlyTheSavedProfile \
  --console=plain
```

Final result: **1 test passed**; the recording transport observed no editor command dispatch.

### API 35 screenshots

The final opt-in capture command was:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
ANDROID_ADB_SERVER_PORT=5049 \
JAVA_TOOL_OPTIONS=-DANDROID_ADB_SERVER_PORT=5049 \
ANDROID_SERIAL=emulator-5598 \
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5598 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.stripe1.xmouse.layout.XmlLayoutEditorAndroidTest#captureOptInXmlEditorScreensInBothThemesAtNormalAndNarrowLargeFont \
  -Pandroid.testInstrumentationRunnerArguments.captureXmlLayoutEditor=true \
  --console=plain
```

Result: **1 capture test passed**. Four fresh screenshots use only a synthetic layout and inert
commands; they are checked into the durable evidence directory:

- [Normal, light](evidence/xml-layout-editor-20261027/XMouseNGXmlLayoutEditor20261027-final-review5/xml-editor-normal-light.png)
- [Normal, dark](evidence/xml-layout-editor-20261027/XMouseNGXmlLayoutEditor20261027-final-review5/xml-editor-normal-dark.png)
- [320dp / 1.5 font scale, light](evidence/xml-layout-editor-20261027/XMouseNGXmlLayoutEditor20261027-final-review5/xml-editor-320dp-font150-light.png)
- [320dp / 1.5 font scale, dark](evidence/xml-layout-editor-20261027/XMouseNGXmlLayoutEditor20261027-final-review5/xml-editor-320dp-font150-dark.png)

An earlier runtime capture recorded eight states, including narrow/landscape real-IME states, in
`/tmp/opencode/xmouse-xml-editor-20261027/screenshots/XMouseNGXmlLayoutEditor20261027-runtime-final4/`.
That prior record is temporary. A fresh attempt to regenerate all eight did not pass: the API 35
IME show request failed at `PHASE_CLIENT_REQUEST_IME_SHOW`, and the capture test timed out waiting
for visible IME insets. The final clean capture therefore records the four requested normal and
narrow/large-font states; the real-IME states were not reverified in this final pass.

### API 26 platform SAX check

Owned AVD `opencode_xml_20261027_api26`, emulator serial `emulator-5600`, API 26; run after stopping
API 35:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
ANDROID_ADB_SERVER_PORT=5049 \
JAVA_TOOL_OPTIONS=-DANDROID_ADB_SERVER_PORT=5049 \
ANDROID_SERIAL=emulator-5600 \
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5600 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.stripe1.xmouse.layout.XmlLayoutEditorAndroidTest#androidPlatformSaxRoundTripsOpaqueTextAndRejectsUnsafeOrForeignXml \
  --console=plain
```

Final result: **1 test passed** on Android's API 26 SAX provider.

### Failed attempts and recovery notes

- Initial API 35 Android tests had three failures: the unsupported `isXIncludeAware` switch above,
  plus Compose text-equality/timeouts in the editing and recreation tests. Iterations also exposed
  an Android-test helper compile error (`fetchSemanticsNode` unresolved), a destroyed
  `ActivityScenario` assertion, and a brittle validation-message matcher. The helper/waits and
  assertions were corrected; the final API 35 suite passed. These failed attempts are retained in
  `/tmp/opencode/xmouse-xml-editor-20261027/xml-android-api35-*.log`.
- The first final four-screen capture invocation passed while an Android launcher ANR dialog was
  visible; visual inspection caught and discarded those images. A cold API 35 start also surfaced
  a SystemUI startup ANR (`Process ... failed to complete startup`). After selecting **Wait** and
  restoring normal launcher focus, the four-screen capture passed again; the repository images
  above are from that clean run.
- Intermediate no-dispatch attempts failed while Android reported a launcher focus-event ANR; one
  failure showed the saved profile restored but `MainActivity` had no window focus, and another
  cold-start attempt failed before the synthetic connection became ready. After the SystemUI
  startup alert was allowed to recover, the final no-dispatch invocation passed. The failures are
  preserved in `final-review-no-dispatch-api35*.log`; they were not filtered or counted as passes.

### Earlier checkpoint and limits

The pre-runtime checkpoint recorded **511 JVM tests** and marked Android instrumentation as not
run. That is chronological only and is superseded by the final results above. No physical-device
testing was performed. Activity recreation was tested; process-death survival of unapplied XML is
not promised or tested. No commit or push was made, and the design handoff archives were left
untouched by this review.
