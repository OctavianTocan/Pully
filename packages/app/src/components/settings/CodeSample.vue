<script setup lang="ts">
import { settings } from '../../state/settings'
</script>

<template>
  <pre class="overflow-hidden rounded-xl bg-code px-3 py-2.5 font-mono" :class="settings.codeWrap ? 'whitespace-pre-wrap break-words' : 'whitespace-pre [mask-image:linear-gradient(to_right,black_calc(100%-28px),transparent)]'" :style="{ fontSize: `${12.5 * settings.codeZoom}px`, lineHeight: `${20 * settings.codeZoom}px` }"><span class="color-active-text">const</span> total = add(a, b)
<span class="color-active-text">return</span> total &gt; 9 ? <span class="color-accent-teal">"big"</span> : <span class="color-accent-teal">"ok"</span>
<span class="color-active-text">const</span> note = <span class="color-accent-teal">"a long line shows what wrapping does"</span></pre>
</template>
