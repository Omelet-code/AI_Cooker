package com.cooker.agent.controller;

import com.cooker.agent.dto.MealHttpRequest;
import com.cooker.agent.service.MealGuidanceService;
import com.cooker.common.*;
import com.cooker.policy.MealPolicy;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 不接收事实、Key、模型目的地址或资源档位。 */
@RestController
@RequestMapping("/conversations/{conversation}/meals/{meal}/guides")
public class MealGuidanceController {
    private final MealGuidanceService service;
    private final MealPolicy policy;
    public MealGuidanceController(MealGuidanceService service,MealPolicy policy){this.service=service;this.policy=policy;}
    @PostMapping public ResponseEntity<?> generate(@PathVariable String conversation,@PathVariable String meal,@RequestBody String raw) {
        MealHttpRequest request;
        try{request=MealHttpRequest.parse(policy.parse(raw),"SELECT");}
        catch(ApiException e){throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","指导请求参数不合法");}
        var response=service.generate(conversation,meal,request);
        var builder=ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore());
        if(response.location()!=null)builder.header(HttpHeaders.LOCATION,response.location());
        return builder.body(ApiResponse.ok(response.body()));
    }
    @GetMapping("/{guide}") public ResponseEntity<?> get(@PathVariable String conversation,@PathVariable String meal,@PathVariable String guide) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.get(conversation,meal,guide)));
    }
}
