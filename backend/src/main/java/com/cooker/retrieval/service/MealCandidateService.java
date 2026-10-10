package com.cooker.retrieval.service;

import com.cooker.retrieval.dto.*;

public interface MealCandidateService {
    MealCandidateResult candidates(MealCandidateRequest request);
}
