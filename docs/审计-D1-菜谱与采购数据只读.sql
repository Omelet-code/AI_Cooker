-- D1只读审计：仅统计与抽样，不修复、导入或调用模型。
-- 名称规则只标记疑似异常；命中数不等于已人工认定的错误数，各项可重叠。
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '20s';
SET LOCAL lock_timeout = '2s';
SELECT jsonb_build_object('section','environment','database',current_database(),
  'readOnly',current_setting('transaction_read_only'),'capturedAt',current_timestamp,
  'migration', (SELECT max(installed_rank) FROM flyway_schema_history WHERE success));

SELECT jsonb_build_object('section','counts',
  'recipes',(SELECT count(*) FROM recipe),
  'sourceDocuments',(SELECT count(*) FROM recipe_source_document),
  'ingredients',(SELECT count(*) FROM ingredient),
  'ingredientAliases',(SELECT count(*) FROM ingredient_alias),
  'recipeIngredients',(SELECT count(*) FROM recipe_ingredient),
  'searchDocuments',(SELECT count(*) FROM recipe_search_document),
  'shoppingLists',(SELECT count(*) FROM shopping_list),
  'productMatches',(SELECT count(*) FROM shopping_product_match));

SELECT jsonb_build_object('section','ingredientNames','total',count(*),
  'unreviewed',count(*) FILTER (WHERE normalization_status='UNREVIEWED'),
  'equals',count(*) FILTER (WHERE canonical_name ~ '[=＝]'),
  'digits',count(*) FILTER (WHERE canonical_name ~ '[0-9]'),
  'commas',count(*) FILTER (WHERE canonical_name ~ '[,，]'),
  'headingCandidates',count(*) FILTER (WHERE canonical_name ~ '^(必须配料|必需配料|可选配料|原料|调料|工具|主料|辅料)[：: ]*$'),
  'suspectUnion',count(*) FILTER (WHERE canonical_name ~ '[=＝0-9,，]' OR canonical_name ~ '^(必须配料|必需配料|可选配料|原料|调料|工具|主料|辅料)[：: ]*$'))
FROM ingredient;

SELECT jsonb_build_object('section','recipeIngredients','total',count(*),
  'nullIngredientId',count(*) FILTER (WHERE ingredient_id IS NULL),
  'missingAmount',count(*) FILTER (WHERE amount_min IS NULL OR amount_max IS NULL),
  'missingUnit',count(*) FILTER (WHERE unit_code IS NULL),
  'customUnit',count(*) FILTER (WHERE unit_code='CUSTOM'),
  'nameEquals',count(*) FILTER (WHERE raw_name ~ '[=＝]'),
  'nameSuspectUnion',count(*) FILTER (WHERE raw_name ~ '[=＝0-9,，]' OR raw_name ~ '^(必须配料|必需配料|可选配料|原料|调料|工具|主料|辅料)[：: ]*$'),
  'affectedRecipesByName',count(DISTINCT recipe_id) FILTER (WHERE raw_name ~ '[=＝0-9,，]' OR raw_name ~ '^(必须配料|必需配料|可选配料|原料|调料|工具|主料|辅料)[：: ]*$'),
  'missingAmountWithNumericUnitCandidate',count(*) FILTER (WHERE (amount_min IS NULL OR amount_max IS NULL) AND raw_line ~* '[0-9]+[[:space:]]*(kg|g|ml|l|克|公斤|毫升|升|斤|个|只|条|瓣|根|片|块)'),
  'missingAmountWithChineseUnitCandidate',count(*) FILTER (WHERE (amount_min IS NULL OR amount_max IS NULL) AND raw_line ~ '[一二两三四五六七八九十半]+[[:space:]]*(克|公斤|毫升|升|斤|个|只|条|瓣|根|片|块)'),
  'missingAmountWithVagueWords',count(*) FILTER (WHERE (amount_min IS NULL OR amount_max IS NULL) AND raw_line ~ '(适量|少许|若干|按.*口味)'),
  'baseAmountOriginalLargeUnit',count(*) FILTER (WHERE unit_code IN ('G','ML') AND lower(unit_label) IN ('kg','公斤','l','升')),
  'negativeAmount',count(*) FILTER (WHERE amount_min<0 OR amount_max<0))
FROM recipe_ingredient;

SELECT jsonb_build_object('section','units','unit',unit_code,'label',unit_label,'rows',count(*))
FROM recipe_ingredient GROUP BY unit_code,unit_label ORDER BY count(*) DESC,unit_code,unit_label;

SELECT jsonb_build_object('section','servings','recipes',count(*),
  'missingServings',count(*) FILTER (WHERE r.servings IS NULL),
  'missingServingsWithPortionText',count(*) FILTER (WHERE r.servings IS NULL AND d.raw_markdown ~ '(每[[:space:]]*[0-9一二两三四五六七八九十]+[[:space:]]*份|[一二两三四五六七八九十]+[[:space:]]*人)'),
  'recipesWithoutIngredients',count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM recipe_ingredient ri WHERE ri.recipe_id=r.id)))
FROM recipe r JOIN recipe_source_document d ON d.id=r.source_document_id;

SELECT jsonb_build_object('section','parseStatus','status',parse_status,'documents',count(*),
  'withWarnings',count(*) FILTER (WHERE jsonb_array_length(parse_warnings)>0))
FROM recipe_source_document GROUP BY parse_status;

SELECT jsonb_build_object('section','nameSamples','recipeId',r.id,'recipe',r.name,
  'lineId',ri.id,'ingredientId',ri.ingredient_id,'name',ri.raw_name,'rawLine',ri.raw_line,
  'min',ri.amount_min,'max',ri.amount_max,'unit',ri.unit_code,'label',ri.unit_label)
FROM recipe_ingredient ri JOIN recipe r ON r.id=ri.recipe_id
JOIN recipe_source s ON s.id=r.source_id
WHERE s.source_type='SYSTEM_HOWTOCOOK' AND s.visibility='PUBLIC'
AND (ri.raw_name ~ '[=＝0-9,，]' OR ri.raw_name ~ '^必须配料')
ORDER BY ri.id LIMIT 18;

WITH keys AS (SELECT id, canonical_name,
  btrim(regexp_replace(canonical_name,'[[:space:]]*[=＝][[:space:]]*$','')) AS proposed
  FROM ingredient)
SELECT jsonb_build_object('section','trailingEqualsCollisionCandidates',
  'groups',count(*),'ingredientRows',coalesce(sum(n),0))
FROM (SELECT proposed,count(*) n FROM keys GROUP BY proposed HAVING count(*)>1) q;

SELECT jsonb_build_object('section','searchDocuments','status',embedding_status,
  'chunkType',chunk_type,'rows',count(*),
  'withEquals',count(*) FILTER (WHERE content ~ '[=＝]'),
  'vectorPresent',count(*) FILTER (WHERE embedding IS NOT NULL))
FROM recipe_search_document GROUP BY embedding_status,chunk_type ORDER BY embedding_status,chunk_type;

SELECT jsonb_build_object('section','shoppingDemandStatus','status',status,'rows',count(*))
FROM shopping_list_item GROUP BY status ORDER BY status;
SELECT jsonb_build_object('section','productMatchStatus','status',status,'rows',count(*))
FROM shopping_product_match_item GROUP BY status ORDER BY status;
SELECT jsonb_build_object('section','matchReasons','status',line->>'status','reason',line->>'reason','rows',count(*))
FROM shopping_product_match m CROSS JOIN LATERAL jsonb_array_elements(m.view_json->'lines') line
GROUP BY line->>'status',line->>'reason' ORDER BY count(*) DESC;
SELECT jsonb_build_object('section','demandReasons','scaleStatus',line->>'scaleStatus',
  'needsConfirmation',line->>'needsConfirmation','reason',line->>'reason','rows',count(*))
FROM shopping_list l CROSS JOIN LATERAL jsonb_array_elements(l.view_json->'lines') line
GROUP BY line->>'scaleStatus',line->>'needsConfirmation',line->>'reason' ORDER BY count(*) DESC;

-- 下列ID来自已部署的显式Mock目录，只统计覆盖，不更新绑定或推断同义词。
SELECT jsonb_build_object('section','mockCoverage','catalogIds',12,
  'existingCatalogIds',count(*) FILTER (WHERE id IN (194,195,10,11,12,70,59,60,578,645,830,4)),
  'totalIngredientIds',count(*),
  'recipeLinesWithCatalogId',(SELECT count(*) FROM recipe_ingredient WHERE ingredient_id IN (194,195,10,11,12,70,59,60,578,645,830,4)))
FROM ingredient;

-- 仅输出摘要，不输出用户账户、密钥、Cookie或完整私有快照。
SELECT jsonb_build_object('section','baselineDigests',
 'sourceDocuments',(SELECT md5(string_agg(md5(row_to_json(d)::text),'' ORDER BY id)) FROM recipe_source_document d),
 'recipeIngredients',(SELECT md5(string_agg(md5(row_to_json(ri)::text),'' ORDER BY id)) FROM recipe_ingredient ri),
 'shoppingLists',(SELECT md5(string_agg(md5(row_to_json(l)::text),'' ORDER BY id)) FROM shopping_list l),
 'productMatches',(SELECT md5(string_agg(md5(row_to_json(m)::text),'' ORDER BY id)) FROM shopping_product_match m));

SELECT jsonb_build_object('section','emptyIngredientRecipes','recipeId',r.id,'name',r.name,
  'sourceKey',s.source_key,'hasCalculationHeading',d.raw_markdown ~ '##[[:space:]]*计算',
  'calculationExcerpt',left(btrim(split_part(split_part(d.raw_markdown,'## 计算',2),E'\n## ',1)),200))
FROM recipe r JOIN recipe_source s ON s.id=r.source_id JOIN recipe_source_document d ON d.id=r.source_document_id
WHERE s.source_type='SYSTEM_HOWTOCOOK' AND NOT EXISTS (SELECT 1 FROM recipe_ingredient ri WHERE ri.recipe_id=r.id)
ORDER BY r.id;

SELECT jsonb_build_object('section','unitContractCandidates',
 'normalizedAmountOriginalLargeUnit',count(*) FILTER (WHERE unit_code IN ('G','ML') AND lower(unit_label) IN ('kg','公斤','l','升')),
 'mlMixedCaseLabel',count(*) FILTER (WHERE unit_code='ML' AND unit_label='mL'),
 'affectedRowsUnion',count(*) FILTER (WHERE (unit_code IN ('G','ML') AND lower(unit_label) IN ('kg','公斤','l','升')) OR (unit_code='ML' AND unit_label='mL')))
FROM recipe_ingredient;

SELECT jsonb_build_object('section','missingAmountSamples','recipeId',r.id,'name',ri.raw_name,'rawLine',ri.raw_line)
FROM recipe_ingredient ri JOIN recipe r ON r.id=ri.recipe_id JOIN recipe_source s ON s.id=r.source_id
WHERE s.source_type='SYSTEM_HOWTOCOOK' AND ri.amount_min IS NULL AND ri.raw_name !~ '[=＝0-9,，]'
ORDER BY ri.id LIMIT 10;
ROLLBACK;
