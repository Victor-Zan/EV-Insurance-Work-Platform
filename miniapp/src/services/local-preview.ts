// Normal builds expose no sample identities or sample transport.
export const isLocalDemo = false
export const isNativePreview = false
export type DemoRole = 'OWNER' | 'REPAIR_SHOP'
export type DemoResponse = { statusCode: number; data: { code: string; data?: unknown } }
export type DemoRequest = { method?: string; data?: Record<string, unknown>; idempotencyKey?: string }
export type DemoUpload = { caseId?: string; category?: string; file: { path: string; mimeType?: string }; idempotencyKey: string }
const disabled = () => new Error('本机演示未启用。')
export function demoSession(_role: DemoRole): unknown { throw disabled() }
export async function demoLogin(_role: DemoRole): Promise<unknown> { throw disabled() }
export async function demoRequest(_path: string, _options: DemoRequest): Promise<DemoResponse> { throw disabled() }
export async function demoUpload(_command: DemoUpload, _active: () => boolean): Promise<string> { throw disabled() }
export async function demoFile(_path: string): Promise<string> { throw disabled() }
