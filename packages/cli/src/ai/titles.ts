/** A work-log sentence while the step runs and once it is over, e.g. `Reading a.ts` / `Read a.ts`. */
export interface StepTitle {
  running: string
  done: string
}

function shorten(text: string, max: number): string {
  const line = text.replace(/\s+/g, ' ').trim()
  return line.length > max ? `${line.slice(0, max - 1)}…` : line
}

function code(text: string): string {
  return `\`${shorten(text, 48)}\``
}

function step(running: string, done: string, object: string): StepTitle {
  return { running: `${running} ${object}`, done: `${done} ${object}` }
}

function files(paths: string[]): string {
  if (paths.length <= 1)
    return paths[0] ?? 'files'
  return `${paths[0]} and ${paths.length - 1} more file${paths.length > 2 ? 's' : ''}`
}

function strings(value: unknown): string[] {
  if (typeof value === 'string')
    return [value]
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function count(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/**
 * A tool's own name, without the `mcp__<server>__` prefix Claude gives MCP tools.
 * @param name - The name the CLI reported.
 * @returns The name the tool server declared.
 */
export function bareToolName(name: string): string {
  return name.replace(/^mcp__.+?__/, '')
}

/**
 * The work-log sentence for a tool call.
 * @param name - The tool's name as the CLI reported it.
 * @param input - Its arguments; may be empty while they are still streaming.
 * @returns The sentence while it runs and once it finished.
 */
export function describeTool(name: string, input: Record<string, unknown> = {}): StepTitle {
  const text = (key: string) => typeof input[key] === 'string' ? input[key] as string : undefined
  switch (bareToolName(name)) {
    case 'StructuredOutput':
      return { running: 'Writing the answer', done: 'Wrote the answer' }
    case 'read_file_diff':
      return step('Reading', 'Read', `the diff of ${files(strings(input.paths))}`)
    case 'read_source': {
      const start = count(input.startLine)
      const end = count(input.endLine)
      const range = start !== undefined || end !== undefined ? ` (lines ${start ?? 1}–${end ?? (start ?? 1) + 399})` : ''
      return step('Reading', 'Read', `${text('path') ?? 'a file'}${range}`)
    }
    case 'find_files': {
      const pattern = text('pattern')
      return pattern ? step('Finding', 'Found', `files matching ${code(pattern)}`) : step('Listing', 'Listed', 'the repository\'s files')
    }
    case 'search_diff':
      return step('Searching', 'Searched', `the diff for ${code(text('pattern') ?? '')}`)
    case 'search_source': {
      const path = text('path')
      return step('Searching', 'Searched', `the code for ${code(text('pattern') ?? '')}${path ? ` in ${path}` : ''}`)
    }
    case 'read_discussion':
      return step('Reading', 'Read', 'the PR description and comments')
    case 'read_pull_request':
      return step('Reading', 'Read', text('pull') ?? 'a pull request')
    case 'read_pull_request_diff': {
      const paths = strings(input.paths)
      return step('Reading', 'Read', `the diff of ${text('pull') ?? 'a pull request'}${paths.length ? ` (${files(paths)})` : ''}`)
    }
    case 'web_search':
      return step('Searching', 'Searched', `the web for ${code(text('query') ?? '')}`)
    default:
      return step('Running', 'Ran', bareToolName(name))
  }
}

/** Splits a command line into words, honouring simple quotes. */
function words(command: string): string[] {
  return [...command.matchAll(/'([^']*)'|"((?:[^"\\]|\\.)*)"|(\S+)/g)].map(match => match[1] ?? match[2] ?? match[3] ?? '')
}

/** The command a `bash -lc '…'` wrapper runs, or the line itself. */
function unwrapShell(command: string): string {
  const wrapped = /^(?:\S*\/)?(?:ba|z)?sh\s+-l?c\s+(['"])([\s\S]*)\1\s*$/.exec(command.trim())
  return (wrapped?.[2] ?? command).trim()
}

const GIT_STEPS: Record<string, [string, string, string]> = {
  status: ['Checking', 'Checked', '`git status`'],
  diff: ['Comparing', 'Compared', 'changes'],
  show: ['Inspecting', 'Inspected', 'a commit'],
  log: ['Reviewing', 'Reviewed', 'the git history'],
  blame: ['Reading', 'Read', 'the blame'],
  grep: ['Searching', 'Searched', 'the repository'],
  ls_files: ['Listing', 'Listed', 'tracked files'],
}

/**
 * The work-log sentence for a shell command a CLI ran.
 * @param command - The command line, possibly wrapped in `bash -lc`.
 * @returns The sentence while it runs and once it finished, plus the program it ran.
 */
export function describeCommand(command: string): StepTitle & { program: string } {
  const line = unwrapShell(command)
  const first = line.split(/\s*(?:\|\||&&|\||;)\s*/)[0] ?? line
  const [bin = '', ...rest] = words(first)
  const program = bin.split('/').at(-1) ?? bin
  const operands = rest.filter(word => !word.startsWith('-'))
  const raw = { ...step('Running', 'Ran', code(line)), program }

  switch (program) {
    case 'cat':
    case 'nl':
    case 'head':
    case 'tail':
    case 'sed':
    case 'less':
    case 'more':
    case 'bat': {
      // sed's first operand is its script; head and tail take bare line counts.
      const targets = (program === 'sed' ? operands.slice(1) : operands).filter(word => !/^\d+$/.test(word))
      return targets.length ? { ...step('Reading', 'Read', files(targets)), program } : raw
    }
    case 'rg':
    case 'grep':
    case 'ag':
    case 'ack': {
      const [pattern, path] = operands
      if (!pattern)
        return { ...step('Searching', 'Searched', 'files'), program }
      return { ...step('Searching', 'Searched', `for ${code(pattern)}${path ? ` in ${path}` : ''}`), program }
    }
    case 'ls':
      return { ...step('Listing', 'Listed', operands[0] ?? 'files'), program }
    case 'find':
    case 'fd':
      return { ...step('Finding', 'Found', `files${operands[0] && operands[0] !== '.' ? ` in ${operands[0]}` : ''}`), program }
    case 'git': {
      const known = GIT_STEPS[(operands[0] ?? '').replace('-', '_')]
      return known ? { ...step(known[0], known[1], known[2]), program } : raw
    }
    default:
      return raw
  }
}
