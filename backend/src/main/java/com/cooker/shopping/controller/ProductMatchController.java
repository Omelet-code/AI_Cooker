package com.cooker.shopping.controller;

import com.cooker.common.ApiResponse;
import com.cooker.shopping.dto.ProductMatchRequest;
import com.cooker.shopping.service.ProductMatchService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shopping-lists/{listId}/product-matches")
public class ProductMatchController {
    private final ProductMatchService service;
    public ProductMatchController(ProductMatchService service){this.service=service;}
    @PostMapping public ResponseEntity<?> create(@PathVariable String listId,@RequestBody String raw){var result=service.create(listId,ProductMatchRequest.parse(raw));return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result.view()));}
    @GetMapping("/{matchId}") public ResponseEntity<?> get(@PathVariable String listId,@PathVariable String matchId){return ok(service.get(listId,matchId));}
    @GetMapping("/latest") public ResponseEntity<?> latest(@PathVariable String listId){return ok(service.latest(listId));}
    private ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));}
}
