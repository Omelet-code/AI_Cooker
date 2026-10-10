export interface ModelCredentialStatus {
 configured:boolean
}

export interface ModelCredentialSettingsApi {
 getStatus():Promise<ModelCredentialStatus>
 save(apiKey:string):Promise<ModelCredentialStatus>
 remove():Promise<ModelCredentialStatus>
}
