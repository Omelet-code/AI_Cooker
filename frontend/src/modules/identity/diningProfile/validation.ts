import { ApiError } from '@/shared/api/types'
import type { DiningProfile } from './types'

export const MAX_DINING_COUNT = 2_147_483_647

/** 服务端返回值必须符合档案接口的唯一字段及 nullable 正整数契约。 */
export function parseDiningProfile(value: unknown): DiningProfile {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiError(200, 'INVALID_RESPONSE', '服务器响应格式不正确')
  }

  const record = value as Record<string, unknown>
  if (Object.keys(record).length !== 1 || !Object.prototype.hasOwnProperty.call(record, 'defaultDiningCount')) {
    throw new ApiError(200, 'INVALID_RESPONSE', '服务器响应格式不正确')
  }

  const count = record.defaultDiningCount
  if (count !== null && (!Number.isInteger(count) || (count as number) < 1 || (count as number) > MAX_DINING_COUNT)) {
    throw new ApiError(200, 'INVALID_RESPONSE', '服务器响应格式不正确')
  }

  return { defaultDiningCount: count as number | null }
}

/** 只接受完整十进制正整数文本，避免 parseInt 将小数截断。 */
export function parseDiningCountInput(value: string): number | null {
  if (!/^[1-9][0-9]*$/.test(value)) return null
  const count = Number(value)
  return Number.isSafeInteger(count) && count <= MAX_DINING_COUNT ? count : null
}
