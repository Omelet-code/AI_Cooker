package com.cooker.agent.controller;

import com.cooker.agent.dto.MealHttpRequest;
import com.cooker.agent.service.MealPlanningService;
import com.cooker.common.ApiResponse;
import com.cooker.policy.MealPolicy;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/conversations/{id}/meals")
public class MealPlanningController {
    private final MealPlanningService service;
    private final MealPolicy policy;
    public MealPlanningController(MealPlanningService service,MealPolicy policy){this.service=service;this.policy=policy;}
    // 原始字符串交由局部严格JSON解析，拒绝重复字段和尾随内容，不修改旧接口Jackson配置。
    private MealHttpRequest request(String body,String type){try{return MealHttpRequest.parse(policy.parse(body),type);}catch(com.cooker.common.ApiException e){throw new com.cooker.common.ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","餐单请求参数不合法");}}
    @PostMapping public ResponseEntity<?> create(@PathVariable String id,@RequestBody String body){return submitted(service.submit(id,null,"CREATE",request(body,"CREATE")));}
    @PostMapping("/{mealId}/revisions") public ResponseEntity<?> revise(@PathVariable String id,@PathVariable String mealId,@RequestBody String body){return submitted(service.submit(id,mealId,"REVISE",request(body,"REVISE")));}
    @PostMapping("/{mealId}/selection") public ResponseEntity<?> select(@PathVariable String id,@PathVariable String mealId,@RequestBody String body){return submitted(service.submit(id,mealId,"SELECT",request(body,"SELECT")));}
    @GetMapping public ResponseEntity<?> list(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ok(service.list(id,page,size));}
    @GetMapping("/{mealId}") public ResponseEntity<?> get(@PathVariable String id,@PathVariable String mealId){return ok(service.get(id,mealId));}
    @GetMapping("/{mealId}/actions/{actionId}") public ResponseEntity<?> action(@PathVariable String id,@PathVariable String mealId,@PathVariable String actionId){return ok(service.action(id,mealId,actionId));}
    private ResponseEntity<?> submitted(MealPlanningService.Submission submission){var builder=ResponseEntity.status(submission.status()).cacheControl(CacheControl.noStore());if(submission.location()!=null)builder.header(HttpHeaders.LOCATION,submission.location());return builder.body(ApiResponse.ok(submission.body()));}
    private ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));}
}
