import FormTextInput from '@antfu/design/components/Form/FormTextInput.vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, withDirectives } from 'vue'
import { FIELD, vField } from './field'

describe('v-field', () => {
  it('reaches the input inside FormTextInput, not its label', () => {
    const Host = defineComponent(() => () => withDirectives(h(FormTextInput), [[vField, FIELD.search]]))
    const input = mount(Host).find('input')

    expect(input.attributes()).toMatchObject({
      enterkeyhint: 'search',
      autocapitalize: 'none',
      autocorrect: 'off',
      spellcheck: 'false',
    })
  })

  it('sets hints on a bare textarea', () => {
    const Host = defineComponent(() => () => withDirectives(h('textarea'), [[vField, FIELD.message]]))
    expect(mount(Host).find('textarea').attributes('enterkeyhint')).toBe('send')
  })
})
