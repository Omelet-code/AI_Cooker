export interface DiningProfile {
  defaultDiningCount: number | null
}

export interface DiningProfileApi {
  getProfile(signal?: AbortSignal): Promise<DiningProfile>
  saveProfile(defaultDiningCount: number | null, signal?: AbortSignal): Promise<DiningProfile>
}
