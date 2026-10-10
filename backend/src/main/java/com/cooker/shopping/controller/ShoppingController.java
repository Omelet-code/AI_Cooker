package com.cooker.shopping.controller;

import com.cooker.common.ApiResponse;
import com.cooker.shopping.dto.ShoppingRequest;
import com.cooker.shopping.service.ShoppingService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShoppingController {
    private final ShoppingService service;
    public ShoppingController(ShoppingService service){this.service=service;}
    @GetMapping("/conversations/{conversationId}/meals/{mealId}/shopping-preview")
    public ResponseEntity<?> preview(@PathVariable String conversationId,@PathVariable String mealId,@RequestParam int expectedVersion,@RequestParam String optionId){return ok(service.preview(conversationId,mealId,expectedVersion,optionId));}
    @PostMapping("/shopping-lists")
    public ResponseEntity<?> create(@RequestBody String body){var result=service.create(ShoppingRequest.parse(body));return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result.view()));}
    @GetMapping("/shopping-lists/{id}") public ResponseEntity<?> get(@PathVariable String id){return ok(service.get(id));}
    private ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));}
}
