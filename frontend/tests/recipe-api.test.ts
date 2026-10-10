import {afterEach,describe,expect,it,vi} from 'vitest'
import {apiClient} from '@/shared/api/client'
import {recipeApi} from '@/modules/recipe/api'
import {envelope} from './helpers'
const card={id:'90071992547409930',name:'炖菜',categoryCode:'MAIN',summary:null,difficulty:null,estimatedMinutes:null,isFavorite:false}
describe('菜谱 API 契约',()=>{
 afterEach(()=>vi.restoreAllMocks())
 it('分页和搜索参数使用契约默认页大小并保留十进制字符串 ID',async()=>{
  const request=vi.spyOn(apiClient,'request').mockResolvedValue({items:[card],page:2,size:20,total:21} as never)
  const result=await recipeApi.list(2,20,' 红烧 鱼 ')
  expect(request).toHaveBeenCalledWith('/recipes?page=2&size=20&keyword=%E7%BA%A2%E7%83%A7+%E9%B1%BC','GET',undefined,{auth:'required',signal:undefined})
  expect(result.items[0].id).toBe('90071992547409930')
 })
 it('收藏增删分别使用 POST 空对象和 DELETE 无请求体',async()=>{
  const request=vi.spyOn(apiClient,'request').mockResolvedValue({recipeId:'7',isFavorite:true} as never)
  await recipeApi.addFavorite('7');await recipeApi.removeFavorite('7')
  expect(request).toHaveBeenNthCalledWith(1,'/recipes/7/favorites','POST',{}, {auth:'required'})
  expect(request).toHaveBeenNthCalledWith(2,'/recipes/7/favorites','DELETE',undefined,{auth:'required'})
 })
})

