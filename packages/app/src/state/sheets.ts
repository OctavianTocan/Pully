const stack: symbol[] = []
let scrollLocks = 0
let previousOverflow = ''
let layer = 0

/** Opening order owns modal layering and keyboard focus, including nested teleports. */
export function claimSheet(lockScroll: boolean) {
  const id = Symbol('sheet')
  stack.push(id)
  const currentLayer = ++layer
  if (lockScroll && scrollLocks++ === 0) {
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  let released = false
  return {
    layer: currentLayer,
    topmost: () => stack.at(-1) === id,
    release() {
      if (released) return
      released = true
      const index = stack.indexOf(id)
      if (index >= 0) stack.splice(index, 1)
      if (!stack.length) layer = 0
      if (lockScroll && --scrollLocks === 0) document.body.style.overflow = previousOverflow
    },
  }
}
