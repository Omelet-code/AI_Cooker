import {createRouter,createWebHistory} from 'vue-router'
import {useIdentityStore} from '@/stores/identity'
const router=createRouter({
 history:createWebHistory(import.meta.env.BASE_URL),
 routes:[
  {path:'/',redirect:{name:'recipes'}},
  {path:'/login',name:'login',component:()=>import('@/views/LoginView.vue'),meta:{guestOnly:true}},
  {path:'/register',name:'register',component:()=>import('@/views/RegisterView.vue'),meta:{guestOnly:true}},
  {path:'/',component:()=>import('@/layouts/AppLayout.vue'),meta:{requiresAuth:true},children:[
   {path:'recipes',name:'recipes',component:()=>import('@/views/RecipesView.vue')},
   {path:'recipes/:id',name:'recipe-detail',component:()=>import('@/views/RecipeDetailView.vue')},
   {path:'settings/model',name:'model-settings',component:()=>import('@/views/ModelSettingsView.vue')},
   {path:'settings/profile',component:()=>import('@/layouts/ProfileLayout.vue'),children:[
    {path:'',name:'profile-settings',component:()=>import('@/views/DiningProfileView.vue')},
    {path:'favorites',name:'favorites',alias:'/favorites',component:()=>import('@/views/FavoritesView.vue')},
    {path:'cooked-recipes',name:'cooked-recipes',alias:'/cooked-recipes',component:()=>import('@/views/CookedRecipesView.vue')},
   ]},
   {path:'kitchen/:conversationId/meals/:mealId/materials',name:'kitchen-materials',component:()=>import('@/views/KitchenMaterialsView.vue')},
   {path:'chat',name:'chat',component:()=>import('@/views/ChatView.vue')},
   {path:'chat/:conversationId',name:'chat-conversation',component:()=>import('@/views/ChatView.vue')},
   {path:'kitchen',name:'kitchen',component:()=>import('@/views/KitchenAssistantView.vue')},
   {path:'kitchen/:conversationId',name:'kitchen-conversation',component:()=>import('@/views/KitchenAssistantView.vue')},
   {path:'shopping',name:'shopping-home',component:()=>import('@/views/ShoppingView.vue')},
   {path:'shopping/new',name:'shopping-new',component:()=>import('@/views/ShoppingView.vue')},
   {path:'shopping/:listId',name:'shopping-list',component:()=>import('@/views/ShoppingView.vue')},
  ]},
  {path:'/:pathMatch(.*)*',redirect:{name:'recipes'}},
 ],
})
router.beforeEach(async to=>{
 if(to.meta.requiresAuth||to.meta.guestOnly){
  const identity=useIdentityStore()
  await identity.initialize()
  if(to.meta.requiresAuth&&identity.status!=='authenticated')return {name:'login',query:{redirect:to.fullPath}}
  if(to.meta.guestOnly&&identity.status==='authenticated')return {name:'recipes'}
 }
 return true
})
export default router

