import type { OutboundHandler } from '@cloudflare/containers'
import { Container, ContainerProxy, getContainer } from '@cloudflare/containers'
import { answerDataMirror, DATA_HOST } from './data'

// Outbound interception (the data mirror) does not work unless this is exported.
export { ContainerProxy }

/** The Worker variables and secrets handed to the server process, when they are set. */
export const SERVER_ENVIRONMENT = [
  'PULLS_REVIEW_GITHUB_TOKEN',
  'PULLY_ACCOUNT_EMAIL',
  'PULLY_ACCOUNT_PASSWORD',
  'PULLY_GOOGLE_CLIENT_ID',
  'PULLY_PUBLIC_URL',
  'TYPESAFE_API_KEY',
  'PULLS_REVIEW_PI_BASE_URL',
  'PULLS_REVIEW_PI_API_KEY',
  'PULLS_REVIEW_PI_MODEL',
  'PULLS_REVIEW_SUGGEST_MODEL',
  'PULLS_REVIEW_ANALYTICS',
] as const

export type Env = Partial<Record<typeof SERVER_ENVIRONMENT[number], string>> & {
  PULLY_SERVER: DurableObjectNamespace<PullyServer>
  DATA: R2Bucket
  KEEP_AWAKE?: string
}

const PORT = 8080
// Restoring the data folder from R2 comes before the server listens, so allow more than the
// library's 20 seconds.
const READY_MS = 120_000

/** The variables from {@link SERVER_ENVIRONMENT} that `env` has. */
export function serverEnvironment(env: Env): Record<string, string> {
  return Object.fromEntries(SERVER_ENVIRONMENT.flatMap(name => env[name] ? [[name, env[name]]] : []))
}

function keepAwake(env: Env): boolean {
  return env.KEEP_AWAKE !== 'false'
}

/**
 * @cc [owner:OctavianTocan,label:architecture;product] hosted-server-stays-awake
 * The hosted server's container MUST stay running between phone visits while `KEEP_AWAKE` is
 * not `false`, so the phone never waits for it to start and its refresh loop keeps running
 * (Tavi, 24 Oct 2026, "Move the server, AI last"; his 14 Oct phone walkthrough, no loading
 * screens).
 */
export class PullyServer extends Container<Env> {
  defaultPort = PORT
  sleepAfter = '10m'
  enableInternet = true
  envVars = serverEnvironment(this.env)

  override async fetch(request: Request): Promise<Response> {
    if (!this.ctx.container?.running) {
      try {
        await this.startAndWaitForPorts({ ports: PORT, cancellationOptions: { portReadyTimeoutMS: READY_MS, abort: request.signal } })
      }
      catch (error) {
        console.error('Pully server did not start', error)
        return new Response('The Pully server is starting. Try again in a moment.', { status: 503, headers: { 'retry-after': '5' } })
      }
    }
    return super.fetch(request)
  }

  override async onActivityExpired(): Promise<void> {
    if (keepAwake(this.env)) {
      this.renewActivityTimeout()
      return
    }
    await super.onActivityExpired()
  }
}

PullyServer.outboundByHost = {
  [DATA_HOST]: ((request, env) => answerDataMirror(request, env.DATA)) satisfies OutboundHandler<Env> as OutboundHandler,
}

function server(env: Env) {
  return getContainer(env.PULLY_SERVER, 'server')
}

export default {
  fetch(request, env) {
    return server(env).fetch(request)
  },
  async scheduled(_controller, env) {
    if (keepAwake(env))
      await server(env).fetch('http://pully/health')
  },
} satisfies ExportedHandler<Env>
