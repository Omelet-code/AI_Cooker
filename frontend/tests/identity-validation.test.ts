import {describe,expect,it} from 'vitest'
import {validateCredentials,validatePassword,validateUsername} from '../src/modules/identity/validation'
describe('身份表单校验',()=>{
 it('接受契约允许的用户名并在提交前规范首尾空白',()=>{expect(validateUsername('  Chef_007 ')).toBeNull()})
 it('拒绝非法用户名和不符合长度的密码',()=>{expect(validateUsername('中午好')).not.toBeNull();expect(validatePassword('short')).not.toBeNull();expect(validatePassword('a'.repeat(129))).not.toBeNull()})
 it('收集登录与注册共用的字段错误',()=>{expect(validateCredentials('x','short')).toHaveLength(2);expect(validateCredentials('cook_lover','long-enough-pass')).toEqual([])})
})
