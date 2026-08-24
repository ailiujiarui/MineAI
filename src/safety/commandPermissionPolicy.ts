export type CommandRole = 'guest' | 'player' | 'trusted' | 'operator'
export type CommandOrigin = 'user' | 'voice' | 'model' | 'internal' | 'system'

export interface CommandPermissionConfig {
  enabled?: boolean
  default_role?: CommandRole
  trusted_players?: string[]
  operators?: string[]
  command_roles?: Record<string, CommandRole>
  high_risk_actions?: string[]
  require_confirmation?: boolean
  confirmation_ttl_ms?: number
}

export interface CommandRequest {
  actor?: string
  commandName: string
  commandText: string
  origin?: CommandOrigin
  confirmed?: boolean
}

export type PermissionDecision =
  | { allowed: true; reason: 'allowed' | 'disabled' | 'trusted-origin' | 'confirmed' }
  | { allowed: false; reason: 'permission-denied'; message: string }
  | { allowed: false; reason: 'confirmation-required'; message: string; expiresAt: number }

interface PendingConfirmation {
  request: CommandRequest
  expiresAt: number
}

const ROLE_LEVEL: Record<CommandRole, number> = {
  guest: 0,
  player: 1,
  trusted: 2,
  operator: 3
}

const DEFAULT_OPERATOR_ACTIONS = ['!newAction', '!restart', '!clearChat', '!setMode']
const DEFAULT_HIGH_RISK_ACTIONS = ['!newAction', '!restart', '!clearChat', '!attackPlayer', '!discard', '!givePlayer']

function normalizeCommandName(name: string): string {
  const trimmed = (name || '').trim()
  return trimmed.startsWith('!') ? trimmed : `!${trimmed}`
}

function normalizeActor(actor?: string): string {
  return (actor || '').trim().toLocaleLowerCase()
}

function includesActor(list: string[] | undefined, actor: string): boolean {
  return (list || []).some(entry => normalizeActor(entry) === actor)
}

export class CommandPermissionPolicy {
  private readonly config: Required<Pick<CommandPermissionConfig,
    'enabled' | 'default_role' | 'require_confirmation' | 'confirmation_ttl_ms'>> & CommandPermissionConfig
  private readonly now: () => number
  private readonly pending = new Map<string, PendingConfirmation>()

  constructor(config: CommandPermissionConfig = {}, now: () => number = Date.now) {
    this.config = {
      ...config,
      enabled: config.enabled !== false,
      default_role: config.default_role || 'player',
      require_confirmation: config.require_confirmation !== false,
      confirmation_ttl_ms: Math.max(1, config.confirmation_ttl_ms || 60_000)
    }
    this.now = now
  }

  getRole(actor?: string): CommandRole {
    const normalized = normalizeActor(actor)
    if (includesActor(this.config.operators, normalized)) return 'operator'
    if (includesActor(this.config.trusted_players, normalized)) return 'trusted'
    return this.config.default_role
  }

  evaluate(request: CommandRequest): PermissionDecision {
    if (!this.config.enabled) return { allowed: true, reason: 'disabled' }
    if (request.origin === 'internal' || !request.origin) {
      return { allowed: true, reason: 'trusted-origin' }
    }

    const commandName = normalizeCommandName(request.commandName)
    const actor = normalizeActor(request.actor)
    const requiredRole = this.requiredRole(commandName)
    const actualRole = this.getRole(actor)
    if (ROLE_LEVEL[actualRole] < ROLE_LEVEL[requiredRole]) {
      return {
        allowed: false,
        reason: 'permission-denied',
        message: `权限不足：${request.actor || '未知玩家'} 需要 ${requiredRole} 权限才能执行 ${commandName}。`
      }
    }

    if (request.confirmed) return { allowed: true, reason: 'confirmed' }
    if (!this.config.require_confirmation || !this.isHighRisk(commandName)) {
      return { allowed: true, reason: 'allowed' }
    }

    const expiresAt = this.now() + this.config.confirmation_ttl_ms
    this.pending.set(actor, {
      request: { ...request, commandName },
      expiresAt
    })
    return {
      allowed: false,
      reason: 'confirmation-required',
      expiresAt,
      message: `高风险操作 ${commandName} 等待确认。请在 ${Math.ceil(this.config.confirmation_ttl_ms / 1000)} 秒内输入 !confirm，或输入 !cancelConfirm 取消。`
    }
  }

  consumeConfirmation(actor?: string): CommandRequest | null {
    const key = normalizeActor(actor)
    const pending = this.pending.get(key)
    this.pending.delete(key)
    if (!pending || pending.expiresAt < this.now()) return null
    return { ...pending.request, confirmed: true }
  }

  cancelConfirmation(actor?: string): boolean {
    return this.pending.delete(normalizeActor(actor))
  }

  hasPendingConfirmation(actor?: string): boolean {
    const key = normalizeActor(actor)
    const pending = this.pending.get(key)
    if (!pending) return false
    if (pending.expiresAt < this.now()) {
      this.pending.delete(key)
      return false
    }
    return true
  }

  private requiredRole(commandName: string): CommandRole {
    const configured = this.config.command_roles?.[commandName]
      || this.config.command_roles?.[commandName.substring(1)]
    if (configured && configured in ROLE_LEVEL) return configured
    return DEFAULT_OPERATOR_ACTIONS.includes(commandName) ? 'operator' : 'player'
  }

  private isHighRisk(commandName: string): boolean {
    const configured = this.config.high_risk_actions || DEFAULT_HIGH_RISK_ACTIONS
    return configured.map(normalizeCommandName).includes(commandName)
  }
}

export function createCommandPermissionPolicy(config: CommandPermissionConfig = {}): CommandPermissionPolicy {
  return new CommandPermissionPolicy(config)
}
