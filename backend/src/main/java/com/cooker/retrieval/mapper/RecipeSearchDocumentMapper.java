package com.cooker.retrieval.mapper;

import com.cooker.retrieval.entity.RecipeSearchDocumentEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 检索投影的显式 SQL 入口。
 *
 * <p>向量列是 pgvector 类型，MyBatis-Plus 无法安全映射，因此所有写入都使用显式 SQL，
 * 并统一采用“按 (recipe_id, chunk_type, chunk_no) 插入或更新”的幂等语义。
 */
@Mapper
public interface RecipeSearchDocumentMapper {
    /** 回查某道菜谱现有的全部投影块，用于判断哪些块仍需重新向量化。 */
    @Select("""
            SELECT id, recipe_id, source_document_id, source_type, owner_user_id, visibility,
                   chunk_type, chunk_no, content, content_hash, document_template_version,
                   embedding_model, embedding_dimension, embedding_status, failure_message, indexed_at
            FROM recipe_search_document
            WHERE recipe_id = #{recipeId}
            """)
    List<RecipeSearchDocumentEntity> findByRecipeId(@Param("recipeId") Long recipeId);

    @Insert("""
            INSERT INTO recipe_search_document (
                recipe_id, source_document_id, source_type, owner_user_id, visibility, chunk_type, chunk_no,
                content, content_hash, document_template_version, embedding_model, embedding_dimension,
                embedding, embedding_status, failure_message, indexed_at
            ) VALUES (
                #{document.recipeId}, #{document.sourceDocumentId}, #{document.sourceType}, #{document.ownerUserId},
                #{document.visibility}, #{document.chunkType}, #{document.chunkNo}, #{document.content},
                #{document.contentHash}, #{document.documentTemplateVersion}, #{document.embeddingModel},
                #{document.embeddingDimension}, CAST(#{embeddingLiteral} AS vector), 'READY', NULL, CURRENT_TIMESTAMP
            )
            ON CONFLICT (recipe_id, chunk_type, chunk_no) DO UPDATE SET
                source_document_id = EXCLUDED.source_document_id,
                source_type = EXCLUDED.source_type,
                owner_user_id = EXCLUDED.owner_user_id,
                visibility = EXCLUDED.visibility,
                content = EXCLUDED.content,
                content_hash = EXCLUDED.content_hash,
                document_template_version = EXCLUDED.document_template_version,
                embedding_model = EXCLUDED.embedding_model,
                embedding_dimension = EXCLUDED.embedding_dimension,
                embedding = EXCLUDED.embedding,
                embedding_status = 'READY',
                failure_message = NULL,
                indexed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            """)
    /**
     * 写入成功生成的向量；同一块重复执行会覆盖旧值并把状态恢复为 READY。
     * 向量以 pgvector 文本字面量传入，由数据库 CAST 成 vector 类型。
     */
    int upsertReady(@Param("document") RecipeSearchDocumentEntity document,
            @Param("embeddingLiteral") String embeddingLiteral);

    @Insert("""
            INSERT INTO recipe_search_document (
                recipe_id, source_document_id, source_type, owner_user_id, visibility, chunk_type, chunk_no,
                content, content_hash, document_template_version, embedding_model, embedding_dimension,
                embedding_status, failure_message
            ) VALUES (
                #{document.recipeId}, #{document.sourceDocumentId}, #{document.sourceType}, #{document.ownerUserId},
                #{document.visibility}, #{document.chunkType}, #{document.chunkNo}, #{document.content},
                #{document.contentHash}, #{document.documentTemplateVersion}, #{document.embeddingModel},
                #{document.embeddingDimension}, 'FAILED', #{failureMessage}
            )
            ON CONFLICT (recipe_id, chunk_type, chunk_no) DO UPDATE SET
                source_document_id = EXCLUDED.source_document_id,
                source_type = EXCLUDED.source_type,
                owner_user_id = EXCLUDED.owner_user_id,
                visibility = EXCLUDED.visibility,
                content = EXCLUDED.content,
                content_hash = EXCLUDED.content_hash,
                document_template_version = EXCLUDED.document_template_version,
                embedding_model = EXCLUDED.embedding_model,
                embedding_dimension = EXCLUDED.embedding_dimension,
                embedding = NULL,
                embedding_status = 'FAILED',
                failure_message = EXCLUDED.failure_message,
                indexed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            """)
    /**
     * 记录向量化失败；冲突时保留文本元数据、清空向量并标记 FAILED，便于下次重试覆盖。
     */
    int upsertFailed(@Param("document") RecipeSearchDocumentEntity document,
            @Param("failureMessage") String failureMessage);

    @Delete("""
            <script>
            DELETE FROM recipe_search_document
            WHERE recipe_id = #{recipeId}
              AND (chunk_type || ':' || chunk_no) NOT IN
              <foreach collection="keepKeys" item="key" open="(" separator="," close=")">
                #{key}
              </foreach>
            </script>
            """)
    /** 删除不再属于当前模板的过期块；调用方必须确认本次重建全部成功后再执行。 */
    int deleteChunksNotIn(@Param("recipeId") Long recipeId, @Param("keepKeys") List<String> keepKeys);
}
