export interface UserProfile{id:string;username:string}
export interface LoginRequest{username:string;password:string;rememberMe:boolean}
export interface RegisterRequest{username:string;password:string}
export interface CsrfResponse{token:string;headerName:'X-XSRF-TOKEN'}
