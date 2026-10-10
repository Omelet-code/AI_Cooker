import {apiClient} from '@/shared/api/client'
import type {CookingRecord,CookingRecordInput,CookedRecipePage,FavoriteState,RecipeDetail,RecipePage} from './types'
export const recipeApi={
  list(page:number,size:number,keyword:string,signal?:AbortSignal){
    const query=new URLSearchParams({page:String(page),size:String(size)})
    if(keyword.trim())query.set('keyword',keyword.trim())
    return apiClient.request<RecipePage>('/recipes?'+query.toString(),'GET',undefined,{auth:'required',signal})
  },
  detail(id:string,signal?:AbortSignal){
    return apiClient.request<RecipeDetail>('/recipes/'+encodeURIComponent(id),'GET',undefined,{auth:'required',signal})
  },
  favorites(page:number,size:number,signal?:AbortSignal){
    const query=new URLSearchParams({page:String(page),size:String(size)})
    return apiClient.request<RecipePage>('/favorites/recipes?'+query.toString(),'GET',undefined,{auth:'required',signal})
  },
  addFavorite(id:string){return apiClient.request<FavoriteState>('/recipes/'+encodeURIComponent(id)+'/favorites','POST',{}, {auth:'required'})},
  removeFavorite(id:string){return apiClient.request<FavoriteState>('/recipes/'+encodeURIComponent(id)+'/favorites','DELETE',undefined,{auth:'required'})},
  completeCooking(input:CookingRecordInput){return apiClient.request<CookingRecord>('/cooking-records','POST',input,{auth:'required'})},
  cookedRecipes(page:number,size:number){
    const query=new URLSearchParams({page:String(page),size:String(size)})
    return apiClient.request<CookedRecipePage>('/users/me/cooked-recipes?'+query.toString(),'GET',undefined,{auth:'required'})
  },
}

