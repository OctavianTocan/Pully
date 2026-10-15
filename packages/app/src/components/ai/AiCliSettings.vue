<script setup lang="ts">
import type { LlmSettings } from '@pulls.review/core/analyze'
import type { CliModelCatalog, LlmEngine } from '@pulls.review/core/local-rpc'
import type { CliModelsState } from '../../composables/useCliModels'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import ActionToggleGroup from '@antfu/design/components/Action/ActionToggleGroup.vue'
import FormField from '@antfu/design/components/Form/FormField.vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import AiEffortPicker from './AiEffortPicker.vue'
import AiModelPicker from './AiModelPicker.vue'

const props = defineProps<{
  llmSettings: LlmSettings
  /** The models of the engine that analyzes the PR. */
  analysisModels: CliModelsState
  /** The models of the engine that answers questions; ignored while it is the analysis engine. */
  askModels: CliModelsState
}>()

const emit = defineEmits<{
  'update:llmSettings': [value: LlmSettings]
  /** Asks the engine's CLI for its models again. */
  'refresh': [engine: LlmEngine]
}>()

const { t } = useI18n()

const engineOptions = [
  { value: 'claude-code', label: 'Claude Code', icon: 'i-simple-icons-claude' },
  { value: 'codex', label: 'Codex', icon: 'i-simple-icons-openai' },
]
const askOptions = computed(() => [{ value: '', label: t('settings.llm.askSame') }, ...engineOptions])

const engine = computed<LlmEngine>(() => props.llmSettings.provider === 'codex' ? 'codex' : 'claude-code')
const askEngine = computed(() => props.llmSettings.askProvider || undefined)

function update(patch: Partial<LlmSettings>) {
  emit('update:llmSettings', { ...props.llmSettings, ...patch })
}

function findModel(catalog: CliModelCatalog | undefined, id: string) {
  return catalog?.models.find(model => model.id === id)
}

/** An effort survives a model switch only when the new model lists it. */
function keptEffort(catalog: CliModelCatalog | undefined, id: string, effort: string): string {
  return effort && findModel(catalog, id)?.efforts.includes(effort) ? effort : ''
}

function engineModel(target: LlmEngine): string {
  return target === 'codex' ? props.llmSettings.codexModel : props.llmSettings.claudeCodeModel
}

const analysisModel = computed({
  get: () => engineModel(engine.value),
  set: (id) => {
    const catalog = props.analysisModels.catalog
    update(engine.value === 'codex'
      ? { codexModel: id, codexEffort: keptEffort(catalog, id, props.llmSettings.codexEffort) }
      : { claudeCodeModel: id, claudeCodeEffort: keptEffort(catalog, id, props.llmSettings.claudeCodeEffort) })
  },
})

const analysisEffort = computed({
  get: () => engine.value === 'codex' ? props.llmSettings.codexEffort : props.llmSettings.claudeCodeEffort,
  set: effort => update(engine.value === 'codex' ? { codexEffort: effort } : { claudeCodeEffort: effort }),
})

// An unset ask model falls back to the saved model of the ask engine.
const askModel = computed({
  get: () => props.llmSettings.askModel || (askEngine.value ? engineModel(askEngine.value) : ''),
  set: id => update({ askModel: id, askEffort: keptEffort(props.askModels.catalog, id, props.llmSettings.askEffort) }),
})

const askEffort = computed({
  get: () => props.llmSettings.askEffort,
  set: effort => update({ askEffort: effort }),
})

const analysisChoice = computed(() => findModel(props.analysisModels.catalog, analysisModel.value))
const askChoice = computed(() => findModel(props.askModels.catalog, askModel.value))

function setAskEngine(value: string) {
  if (value === props.llmSettings.askProvider)
    return
  update({ askProvider: value as LlmSettings['askProvider'], askModel: '', askEffort: '' })
}

function cliLine(state: CliModelsState): string {
  return [state.catalog?.account, state.catalog?.version].filter(Boolean).join(' · ')
}
</script>

<template>
  <FormField :label="$t('settings.llm.engine')">
    <ActionToggleGroup
      :model-value="engine"
      :options="engineOptions"
      @update:model-value="typeof $event === 'string' && update({ provider: $event as LlmEngine })"
    />
  </FormField>

  <FormField :label="$t('settings.llm.model')">
    <AiModelPicker
      v-model="analysisModel"
      :models="analysisModels.catalog?.models"
      :loading="analysisModels.loading"
      :error="analysisModels.error"
    />
    <template #description>
      <span class="inline-flex items-center gap-1.5">
        <span v-if="cliLine(analysisModels)">{{ cliLine(analysisModels) }}</span>
        <ActionIconButton
          icon="i-lu-redo"
          :label="$t('settings.llm.refreshModels')"
          :tooltip="$t('settings.llm.refreshModels')"
          :disabled="analysisModels.loading"
          compact
          class="text-xs"
          @click="emit('refresh', engine)"
        />
      </span>
    </template>
  </FormField>

  <FormField v-if="analysisChoice?.efforts.length" :label="$t('settings.llm.effort')" :description="$t('settings.llm.effortHint')">
    <AiEffortPicker
      v-model="analysisEffort"
      :efforts="analysisChoice.efforts"
      :default-effort="analysisChoice.defaultEffort"
    />
  </FormField>

  <div class="flex flex-col gap-4 border-t border-base pt-4">
    <div>
      <div class="mb1 flex items-center gap-1 text-sm color-base font-medium">
        <div class="i-ph:chat-circle-dots text-lg" />
        {{ $t('settings.llm.askTitle') }}
      </div>
      <p class="text-sm color-faint">
        {{ $t('settings.llm.askDescription') }}
      </p>
    </div>

    <FormField :label="$t('settings.llm.engine')">
      <ActionToggleGroup
        :model-value="llmSettings.askProvider"
        :options="askOptions"
        @update:model-value="typeof $event === 'string' && setAskEngine($event)"
      />
    </FormField>

    <template v-if="askEngine">
      <FormField :label="$t('settings.llm.model')">
        <AiModelPicker
          v-model="askModel"
          :models="askModels.catalog?.models"
          :loading="askModels.loading"
          :error="askModels.error"
        />
        <template v-if="askEngine !== engine" #description>
          <span class="inline-flex items-center gap-1.5">
            <span v-if="cliLine(askModels)">{{ cliLine(askModels) }}</span>
            <ActionIconButton
              icon="i-lu-redo"
              :label="$t('settings.llm.refreshModels')"
              :tooltip="$t('settings.llm.refreshModels')"
              :disabled="askModels.loading"
              compact
              class="text-xs"
              @click="emit('refresh', askEngine)"
            />
          </span>
        </template>
      </FormField>

      <FormField v-if="askChoice?.efforts.length" :label="$t('settings.llm.effort')">
        <AiEffortPicker
          v-model="askEffort"
          :efforts="askChoice.efforts"
          :default-effort="askChoice.defaultEffort"
        />
      </FormField>
    </template>
  </div>
</template>
