import type { Meta, StoryObj } from '@storybook/vue3-vite'
import type { LocalRpc } from '../../local/connection'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { provide, reactive } from 'vue'
import { createMemoryHistory, createRouter, routeLocationKey, routerKey } from 'vue-router'
import { MOCK_MY_PULLS } from '../../../test/fixtures/mock-my-pulls'
import { appContextKey } from '../../app-context'
import { createBrowserCache } from '../../cache/browser-cache'
import { localRpcKey } from '../../local/local-rpc-key'
import HomePage from '../../pages-local/index.vue'

/** The `pulls.review` server's answers, from memory: the inbox the phone's design map shows. */
function mockRpc(pulls = MOCK_MY_PULLS): LocalRpc {
  const call = async (name: string) => {
    if (name === LOCAL_RPC.myPullsCached)
      return { pulls, fetchedAt: Date.now() - 120_000, refreshing: false }
    if (name === LOCAL_RPC.myPulls)
      return pulls
    return null
  }
  return { call } as LocalRpc
}

const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:all(.*)*', component: { render: () => null } }] })

const meta: Meta<typeof HomePage> = {
  title: 'Pages/Home',
  component: HomePage,
  parameters: { layout: 'fullscreen' },
  decorators: [
    () => ({
      setup() {
        provide(routerKey, router)
        provide(routeLocationKey, reactive(router.currentRoute.value))
        provide(appContextKey, { cache: createBrowserCache(), credentials: { githubToken: async () => undefined } })
        provide(localRpcKey, mockRpc())
      },
      template: '<story />',
    }),
  ],
}
export default meta

type Story = StoryObj<typeof HomePage>

export const Inbox: Story = {}
