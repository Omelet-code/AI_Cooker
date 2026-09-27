package com.cooker.retrieval.mapper;

import com.cooker.recipe.dto.RecipeSearchEligibility;
import com.cooker.retrieval.dto.RecipeSearchCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecipeSearchMapper {
    List<RecipeSearchCandidate> lexical(@Param("eligible") List<RecipeSearchEligibility> eligible,
            @Param("query") String query, @Param("shortQuery") boolean shortQuery,
            @Param("model") String model, @Param("dimension") int dimension, @Param("templateVersion") int templateVersion,
            @Param("viewerUserId") Long viewerUserId);
    List<RecipeSearchCandidate> vector(@Param("eligible") List<RecipeSearchEligibility> eligible,
            @Param("vector") String vector, @Param("model") String model, @Param("dimension") int dimension,
            @Param("templateVersion") int templateVersion, @Param("viewerUserId") Long viewerUserId);
}
