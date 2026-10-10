-- 仅本卡最终库内12项人工语义审定；不建立别名、不改材料量、不接触旧两库。
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'cooker_rebuild_d1r2_20261007' THEN
        RAISE EXCEPTION '本次仅允许指定最终库';
    END IF;
    IF (SELECT count(*) FROM ingredient i JOIN (VALUES
        (138,'鲜虾'),(105,'黄油'),(10,'生抽'),(11,'蚝油'),(12,'老抽'),(63,'食用油'),
        (35,'盐'),(56,'糖'),(432,'芹菜'),(486,'香油'),(614,'西红柿'),(4,'鸡蛋')
    ) e(id,name) ON i.id=e.id AND i.canonical_name=e.name WHERE i.normalization_status='UNREVIEWED') <> 12 THEN
        RAISE EXCEPTION '标准ID或规范名/状态不符合逐项核验结果，停止，不扩大全库审定';
    END IF;
    IF EXISTS (SELECT 1 FROM ingredient WHERE normalization_status='REVIEWED') THEN
        RAISE EXCEPTION '已存在审定状态，本轮现场已变化，停止重复执行';
    END IF;
END $$;
UPDATE ingredient SET normalization_status='REVIEWED'
WHERE id IN (138,105,10,11,12,63,35,56,432,486,614,4)
RETURNING id,canonical_name,normalization_status;
COMMIT;
