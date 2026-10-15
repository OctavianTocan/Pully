import type { InboxPayload } from './inbox-tools'
import type { PrPayload } from './pr-tools'
import { readFile } from 'node:fs/promises'
import process from 'node:process'
import { createInterface } from 'node:readline'
import { callInboxTool, inboxTools } from './inbox-tools'
import { callPrTool, prTools } from './pr-tools'

const PROTOCOL_VERSION = '2025-06-18'

interface Request {
  jsonrpc: '2.0'
  id?: number | string
  method: string
  params?: Record<string, unknown>
}

const payloadPath = process.argv[2]
if (!payloadPath) {
  process.stderr.write('pr-server needs the path of a payload file.\n')
  process.exit(2)
}

const payload = JSON.parse(await readFile(payloadPath, 'utf8')) as PrPayload | InboxPayload
// An inbox question gets the inbox tools (its payload is the listed pull requests); anything else is one change.
const inbox = 'pulls' in payload ? payload : undefined
const tools = inbox ? inboxTools() : prTools(payload as PrPayload)
const call = (name: string, args: Record<string, unknown>) => inbox ? callInboxTool(inbox, name, args) : callPrTool(payload as PrPayload, name, args)

function send(message: unknown): void {
  process.stdout.write(`${JSON.stringify(message)}\n`)
}

async function handle(request: Request): Promise<void> {
  const { id, method, params } = request
  const reply = (result: unknown) => id !== undefined && send({ jsonrpc: '2.0', id, result })
  switch (method) {
    case 'initialize':
      return void reply({
        protocolVersion: typeof params?.protocolVersion === 'string' ? params.protocolVersion : PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: inbox ? 'inbox' : 'pr', version: '1.0.0' },
      })
    case 'ping':
      return void reply({})
    case 'tools/list':
      return void reply({ tools })
    case 'tools/call':
      try {
        const text = await call(String(params?.name ?? ''), (params?.arguments ?? {}) as Record<string, unknown>)
        return void reply({ content: [{ type: 'text', text }] })
      }
      catch (error) {
        return void reply({ content: [{ type: 'text', text: error instanceof Error ? error.message : String(error) }], isError: true })
      }
    default:
      // Notifications carry no id and expect no answer.
      if (id !== undefined)
        send({ jsonrpc: '2.0', id, error: { code: -32601, message: `unsupported method ${method}` } })
  }
}

createInterface({ input: process.stdin })
  .on('line', (line) => {
    if (!line.trim())
      return
    try {
      void handle(JSON.parse(line) as Request)
    }
    catch {}
  })
  .on('close', () => process.exit(0))
