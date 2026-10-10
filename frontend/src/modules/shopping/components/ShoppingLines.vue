<script setup lang="ts">
import { allowedUnits, isDecimalInput } from '../validation'
import type { Availability, PreviewLine } from '../types'
import { formatIngredientSource, hasStructuredIngredientAmount } from '@/modules/recipe/format'
defineProps<{ lines: PreviewLine[]; choices: Record<string, Availability | null>; amounts: Record<string, string>; units: Record<string, string>; disabled?: boolean }>()
const emit = defineEmits<{ choice: [lineKey: string, value: Availability]; amount: [lineKey: string, value: string]; unit: [lineKey: string, value: string] }>()
function updateChoice(event: Event, lineKey: string) { const value = (event.target as HTMLSelectElement).value; if (value === 'HAVE_ENOUGH' || value === 'NEED_BUY') emit('choice', lineKey, value) }
function updateAmount(event: Event, lineKey: string) { emit('amount', lineKey, (event.target as HTMLInputElement).value) }
function updateUnit(event: Event, lineKey: string) { emit('unit', lineKey, (event.target as HTMLSelectElement).value) }
</script>
<template>
  <section class="shopping-lines" aria-label="逐项确认食材">
    <article v-for="line in lines" :key="line.lineKey" class="shopping-line">
      <header><strong>{{ hasStructuredIngredientAmount(line) ? line.name : formatIngredientSource({ rawLine: line.rawLine, name: line.name }) }}</strong><span v-if="line.optional" class="optional">可选</span></header>
      <p v-if="hasStructuredIngredientAmount(line)" class="raw-line">{{ line.rawLine }}</p>
      <template v-if="hasStructuredIngredientAmount(line)">
        <p>原菜谱：{{ line.originalAmountMin ?? '未知' }}<template v-if="line.originalAmountMax && line.originalAmountMax !== line.originalAmountMin">～{{ line.originalAmountMax }}</template> {{ line.unitLabel ?? line.unitCode ?? '单位未知' }}</p>
        <p>本餐参考：{{ line.referenceAmount ?? '未知' }} {{ line.unitLabel ?? line.unitCode ?? '' }}<template v-if="line.reason">；{{ line.reason }}</template></p>
      </template>
      <p v-else class="muted">按原文准备；采购总量待确认<template v-if="line.reason">；{{ line.reason }}</template></p>
      <label>本次处理
        <select :value="choices[line.lineKey] ?? ''" :disabled="disabled" @change="updateChoice($event, line.lineKey)">
          <option value="">请选择</option><option value="HAVE_ENOUGH">我已有足量</option><option value="NEED_BUY">需要购买</option>
        </select>
      </label>
      <template v-if="choices[line.lineKey] === 'NEED_BUY' && (line.needsConfirmation || line.referenceAmount === null)">
        <p class="muted">参考用量不确定，可留下未知；填写时必须同时选择兼容单位。</p>
        <div class="amount-entry"><label>确认用量<input :value="amounts[line.lineKey] ?? ''" inputmode="decimal" placeholder="可留空表示未知" :disabled="disabled" @input="updateAmount($event, line.lineKey)"></label>
          <label>单位<select :value="units[line.lineKey] ?? ''" :disabled="disabled" @change="updateUnit($event, line.lineKey)"><option value="">未确认</option><option v-for="unit in allowedUnits(line)" :key="unit" :value="unit">{{ unit }}</option></select></label></div>
        <p v-if="amounts[line.lineKey] && !isDecimalInput(amounts[line.lineKey]!)" class="error">用量需为大于0、最多8位小数的普通十进制数。</p>
      </template>
    </article>
  </section>
</template>
<style scoped>
.shopping-lines{display:grid;gap:12px}.shopping-line{padding:14px;border:1px solid #e2e8db;border-radius:10px;background:#fff;color:#566151;font-size:12px;line-height:1.6}.shopping-line header,.amount-entry{display:flex;align-items:center;justify-content:space-between;gap:12px}.shopping-line strong{color:#354a36;font-size:14px}.optional{padding:2px 7px;border-radius:12px;background:#f0efe7;color:#77704f;font-size:10px}.shopping-line p{margin:5px 0}.shopping-line label{display:grid;gap:5px;margin-top:9px;color:#516449;font-weight:600}.shopping-line select,.shopping-line input{min-height:37px;padding:7px 9px;border:1px solid #dce3d4;border-radius:7px;background:white;color:#354532;font:inherit}.amount-entry{align-items:flex-end}.amount-entry label{flex:1}.muted{color:#83897e;font-size:11px}.error{color:#a34c42;font-size:11px}
</style>
