-- D1-A专用只读快照；仅公开系统原文/事实和公共词典建议，不读取账户或私有快照。
SELECT json_build_object('kind','environment','database',current_database(),'readOnly',current_setting('transaction_read_only'),'isolation',current_setting('transaction_isolation'),'capturedAt',current_timestamp);
SELECT json_build_object('kind','recipe','sourceId',s.id,'sourceKey',s.source_key,'sourceType',s.source_type,'sourceVersion',d.source_version,'documentId',d.id,'contentHash',d.content_hash,'rawMarkdown',d.raw_markdown,'recipeId',r.id,'servings',r.servings,'name',r.name,
'ingredients',coalesce((SELECT json_agg(row_to_json(ri) ORDER BY ri.sort_order,ri.id) FROM recipe_ingredient ri WHERE ri.recipe_id=r.id),'[]'::json),
'chunks',coalesce((SELECT json_agg(json_build_object('id',sd.id,'type',sd.chunk_type,'hash',sd.content_hash) ORDER BY sd.id) FROM recipe_search_document sd WHERE sd.recipe_id=r.id),'[]'::json))
FROM recipe r JOIN recipe_source s ON s.id=r.source_id JOIN recipe_source_document d ON d.id=r.source_document_id WHERE s.source_type='SYSTEM_HOWTOCOOK' AND s.visibility='PUBLIC' AND s.owner_user_id IS NULL ORDER BY r.id;
SELECT json_build_object('kind','dictionary','items',(SELECT json_agg(json_build_object('id',i.id,'name',i.canonical_name,'status',i.normalization_status) ORDER BY i.id) FROM ingredient i WHERE EXISTS (SELECT 1 FROM recipe_ingredient ri JOIN recipe r ON r.id=ri.recipe_id JOIN recipe_source s ON s.id=r.source_id WHERE ri.ingredient_id=i.id AND s.source_type='SYSTEM_HOWTOCOOK' AND s.visibility='PUBLIC' AND s.owner_user_id IS NULL)));
SELECT json_build_object('kind','digests',
'documents',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM recipe_source_document t),
'ingredients',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM recipe_ingredient t),
'dictionary',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM ingredient t),
'meals',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM meal_session t),
'options',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM meal_option t),
'guides',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM meal_guide t),
'lists',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM shopping_list t),
'matches',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM shopping_product_match t),
'chunks',(SELECT md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM recipe_search_document t));
