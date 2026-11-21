export class RequestError extends Error {
  constructor(public readonly code: string, message: string, public readonly status = 0, public readonly retryAfter = 0) { super(message); this.name = 'RequestError' }
}
