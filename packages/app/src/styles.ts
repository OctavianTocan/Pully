import '@unocss/reset/tailwind.css'
// `@antfu/design/styles.css` minus `splitpanes.css`: the app has no split panes, and that
// file's ungated `.splitpanes__splitter:hover` would be the one hover rule left on phones.
import '@antfu/design/styles/base.css'
import '@antfu/design/styles/scrollbar.css'
import '@antfu/design/styles/animations.css'
import '@antfu/design/styles/reka-ui.css'
import '@antfu/design/styles/vue-afloat.css'
import 'vue-afloat/style.css'
// Geist, Geist Mono and Mona Sans: the very files the APK ships (`PullsFonts`).
import './design/fonts.generated.css'
import './main.css'
import 'virtual:uno.css'
