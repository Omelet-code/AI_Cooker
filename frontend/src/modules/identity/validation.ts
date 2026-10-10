export interface FormIssue{field:'username'|'password';message:string}
export function validateUsername(value:string):FormIssue|null{return /^[A-Za-z0-9_]{3,32}$/.test(value.trim())?null:{field:'username',message:'用户名需为 3～32 位英文字母、数字或下划线'}}
export function validatePassword(value:string):FormIssue|null{return value.length>=12&&value.length<=128?null:{field:'password',message:'密码长度需为 12～128 个字符'}}
export function validateCredentials(username:string,password:string):FormIssue[]{return [validateUsername(username),validatePassword(password)].filter((x):x is FormIssue=>x!==null)}
