package com.cbs.notifications;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/notifications")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class NotificationController {
    private final NotificationService notifications;private final ApprovalService approvals;
    public NotificationController(NotificationService notifications,ApprovalService approvals){this.notifications=notifications;this.approvals=approvals;}
    @GetMapping Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(required=false) String status,@RequestParam(required=false) UUID customerId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){
        var page=notifications.list(actor(jwt),bankId,status,customerId,limit,offset);
        return Map.of("providerConfigured",notifications.providerConfigured(),"items",page.items(),"total",page.total(),"limit",limit,"offset",offset);
    }
    @GetMapping("/preferences/{customerId}") Map<String,Object> preference(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID customerId){
        var p=notifications.preference(actor(jwt),bankId,customerId);return p==null?Map.of("configured",false):Map.of("configured",true,"preference",p);
    }
    @PostMapping("/preferences") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody NotificationModels.Preference input){return approvals.notificationPreference(actor(jwt),key,bankId,input);}
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){notifications.retry(actor(jwt),bankId,id);}
}
