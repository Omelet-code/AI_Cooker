package com.cooker.conversation.controller;

import com.cooker.common.ApiResponse;
import com.cooker.conversation.dto.CreateConversationRequest;
import com.cooker.conversation.service.ConversationService;
import com.cooker.conversation.service.ConversationTurnService;
import com.cooker.conversation.vo.ChatMessageView;
import com.cooker.conversation.vo.ConversationView;
import com.cooker.conversation.vo.PageView;
import com.cooker.conversation.vo.TurnView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final ConversationService conversations;
    private final ConversationTurnService turns;

    public ConversationController(ConversationService conversations, ConversationTurnService turns) {
        this.conversations = conversations;
        this.turns = turns;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationView>> create(@RequestBody(required = false) CreateConversationRequest request) {
        String title = request == null ? null : request.title();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(conversations.create(title)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageView<ConversationView>>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ApiResponse.ok(conversations.list(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ConversationView>> get(@PathVariable String id) {
        return noStore(ApiResponse.ok(conversations.get(id)));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<PageView<ChatMessageView>>> messages(@PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return noStore(ApiResponse.ok(conversations.messages(id, page, size)));
    }

    @GetMapping("/{id}/turns/{turnId}")
    public ResponseEntity<ApiResponse<TurnView>> turn(@PathVariable String id, @PathVariable String turnId) {
        return noStore(ApiResponse.ok(turns.getTurn(id, turnId)));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
