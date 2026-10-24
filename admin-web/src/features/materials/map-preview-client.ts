import type { InjectionKey } from 'vue'
import type { AxiosInstance } from 'axios'
// Only the isolated development preview supplies this transport. No real API fallback.
export const mapPreviewClientKey: InjectionKey<Pick<AxiosInstance, 'get' | 'post'>> = Symbol('isolated-map-preview')
