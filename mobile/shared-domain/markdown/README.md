The HTML5 entity data is copied byte-for-byte from CommonMark Java 0.24.0,
org/commonmark/internal/util/entities.txt. Its existing NewLine override is applied
in the generated common lookup. License: BSD 2-clause, preserved in
android/app/src/main/assets/licenses/markdown-parsers.txt.

renderer-fixtures.json captures semantic trees directly from the unchanged Android
CommonMark 0.24.0 parser configured with its existing table, strike, autolink and
task-list extensions. These are the expected renderer inputs, including attributes
and child order. Reference definition nodes are omitted because they are inert;
their resolved link destinations remain in the golden trees.

The common golden tests run against each host parser. The JVM also compares the
portable JetBrains GFM adapter directly with these independent Android goldens.
No renderer-specific HTML string is used as an alternate screen implementation.

Android retains the exact CommonMark parser and four extensions. Apple adapts the
published JetBrains Markdown 0.7.3 GFM syntax tree to the same semantic node types.
The flavour keeps Android's single-tilde strike and literal math behavior. The
existing autolink scanners, HTML5 entities and reference-label folding retain
Android semantics; no HTML renderer or regular-expression Markdown parser is used.
The shared Compose renderer remains the existing Android source.

Run the portable parser and original-parser comparisons with
`cd android && ./gradlew :shared-domain:jvmTest :shared-ui:testAndroidHostTest`.
The iOS verification workflow runs the same common goldens against the Apple
adapter with `:shared-domain:iosSimulatorArm64Test` and the shared mention tests
with `:shared-ui:iosSimulatorArm64Test` on its pinned macOS simulator.
