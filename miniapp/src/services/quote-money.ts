import { RequestError } from './request-error'
const invalid = () => new RequestError('VALIDATION_ERROR', '数量须为正整数，单价须为非负金额且最多两位小数，合计不能超出金额范围。')
function digits(value: string) { return value.replace(/^0+(?=\d)/, '') }
export function quantity(value: string) {
  if (!/^\d{1,10}$/.test(value) || Number(value) < 1 || Number(value) > 2147483647) throw invalid()
  return Number(value)
}
export function money(value: string) {
  if (!/^\d{1,36}(?:\.\d{1,2})?$/.test(value)) throw invalid()
  const parts = value.split('.'); return `${digits(parts[0]!)}.${((parts[1] ?? '') + '00').slice(0, 2)}`
}
function add(a: string, b: string) {
  let result = '', carry = 0, i = a.length - 1, j = b.length - 1
  while (i >= 0 || j >= 0 || carry) {
    const digit = (i >= 0 ? Number(a[i--]) : 0) + (j >= 0 ? Number(b[j--]) : 0) + carry
    result = String(digit % 10) + result; carry = Math.floor(digit / 10)
  }
  return digits(result)
}
function multiply(a: string, b: string) {
  let result = '0'
  for (let j = b.length - 1; j >= 0; j--) {
    let part = '', carry = 0
    for (let i = a.length - 1; i >= 0; i--) {
      const digit = Number(a[i]) * Number(b[j]) + carry
      part = String(digit % 10) + part; carry = Math.floor(digit / 10)
    }
    if (carry) part = String(carry) + part
    result = add(result, part + '0'.repeat(b.length - 1 - j))
  }
  return result
}
function yuan(cents: string) {
  let value = digits(cents)
  while (value.length < 3) value = '0' + value
  if (value.length > 38) throw invalid()
  return `${value.slice(0, -2)}.${value.slice(-2)}`
}
// Arithmetic operates on individual decimal digits, never floating-point money or ES2020 BigInt.
export function lineAmount(count: string, price: string) { quantity(count); return yuan(multiply(money(price).replace('.', ''), digits(count))) }
export function quoteTotal(lines: { quantity: string; unitPrice: string }[]) {
  let sum = '0'; for (const line of lines) sum = add(sum, lineAmount(line.quantity, line.unitPrice).replace('.', ''))
  return yuan(sum)
}
export function displayAmount(count: string, price: string) { try { return lineAmount(count, price) } catch { return '待核对' } }
