package com.cooker.retrieval.dto;

import com.cooker.recipe.dto.*;
import java.util.List;

public record MealCandidateResult(MealCandidateRequest.Mode mode, Long seed, List<Group> groups) {
    public record Candidate(MealRecipeFacts facts, String selectionBasis, RecipeSearchResult searchRank,
            boolean servingsUnknown, boolean cookingTimeUnknown) {}
    public record Group(MealRole role, int requestedCount, List<Candidate> candidates, boolean shortage) {}
}
