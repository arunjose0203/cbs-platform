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
    /** Delivery history: filter by status, customer and event. */
    @GetMapping Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(required=false) String status,@RequestParam(required=false) UUID customerId,
            @RequestParam(required=false) NotificationModels.Event event,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){
        var page=notifications.list(actor(jwt),bankId,status,customerId,event,limit,offset);
        return Map.of("providerConfigured",notifications.providerConfigured(),"items",page.items(),"total",page.total(),"limit",limit,"offset",offset);
    }
    @GetMapping("/preferences/{customerId}") Map<String,Object> preference(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID customerId){
        var p=notifications.preference(actor(jwt),bankId,customerId);return p==null?Map.of("configured",false):Map.of("configured",true,"verified",p.verified(),"preference",p);
    }
    @PostMapping("/preferences") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody NotificationModels.Preference input){return approvals.notificationPreference(actor(jwt),key,bankId,input);}
    @PostMapping("/preferences/{customerId}/verify") @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID customerId,@Valid @RequestBody NotificationModels.Verification input){notifications.verify(actor(jwt),bankId,customerId,input.code());}
    @PostMapping("/preferences/{customerId}/resend") @ResponseStatus(HttpStatus.NO_CONTENT)
    void resend(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID customerId){notifications.resend(actor(jwt),bankId,customerId);}
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){notifications.retry(actor(jwt),bankId,id);}

    @GetMapping("/templates") List<NotificationModels.TemplateView> templates(JwtAuthenticationToken jwt,@PathVariable UUID bankId){return notifications.templates(actor(jwt),bankId);}
    @PostMapping("/templates") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request template(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody NotificationModels.Template input){return approvals.notificationTemplate(actor(jwt),key,bankId,input);}

    @GetMapping("/opt-outs") List<NotificationModels.OptOutView> optOuts(JwtAuthenticationToken jwt,@PathVariable UUID bankId){return notifications.optOuts(actor(jwt),bankId);}
    @PostMapping("/opt-outs") @ResponseStatus(HttpStatus.NO_CONTENT) void optOut(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody NotificationModels.OptOut input){notifications.optOut(actor(jwt),bankId,input);}
    @PostMapping("/opt-outs/remove") @ResponseStatus(HttpStatus.NO_CONTENT) void removeOptOut(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody NotificationModels.OptOut input){notifications.removeOptOut(actor(jwt),bankId,input);}
}
