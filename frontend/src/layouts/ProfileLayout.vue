<template>
  <section class="profile-area" aria-label="个人档案区域">
    <header class="profile-area-heading">
      <p class="eyebrow">账户与个人偏好</p>
      <h1>个人档案</h1>
    </header>
    <nav class="profile-tabs" aria-label="个人档案导航">
      <RouterLink :to="{ name: 'profile-settings' }" exact-active-class="is-active">用餐设置</RouterLink>
      <RouterLink :to="{ name: 'favorites' }" exact-active-class="is-active">我的收藏</RouterLink>
      <RouterLink :to="{ name: 'cooked-recipes' }" exact-active-class="is-active">做菜历史</RouterLink>
    </nav>
    <RouterView />
  </section>
</template>

<style scoped>
.profile-area{max-width:1120px;margin:0 auto;padding:38px 0 64px}.profile-area-heading h1{margin:7px 0 20px;color:#304032;font-size:28px;letter-spacing:-.035em}.profile-tabs{display:flex;gap:8px;flex-wrap:wrap;padding:0 0 16px;border-bottom:1px solid #e4e8de}.profile-tabs a{padding:9px 13px;border-radius:8px;color:#657163;text-decoration:none;font-size:13px}.profile-tabs a:hover,.profile-tabs a.is-active{background:#edf2e7;color:#405d37}.profile-tabs a.is-active{font-weight:650}.profile-area :deep(.dining-profile-page){padding:26px 0 0}.profile-area :deep(.profile-heading){display:none}
@media(max-width:600px){.profile-area{padding:28px 0 46px}.profile-area-heading h1{font-size:25px}.profile-tabs{gap:4px}.profile-tabs a{padding:9px 10px}}
</style>
