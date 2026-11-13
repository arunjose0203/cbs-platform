package com.cbs.fx;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/fx")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class FxController {
 private final FxTradeService trades;private final FxService service;private final ApprovalService approvals;
 public FxController(FxService service,ApprovalService approvals,FxTradeService trades){this.trades=trades;this.service=service;this.approvals=approvals;}
 @GetMapping("/trades") public Map<String,Object> trades(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return trades.list(actor(jwt),bank,limit,offset);}
 @PostMapping("/prepare") public void prepare(JwtAuthenticationToken jwt,@PathVariable UUID bank){trades.prepare(actor(jwt),bank);}
 @PostMapping("/trades/preview") public FxTradeModels.Batch preview(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody FxTradeModels.Trade input){return trades.preview(actor(jwt),bank,input);}
 @PostMapping("/trades") public ApprovalModels.Request trade(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody FxTradeModels.Trade input){return approvals.fxTrade(actor(jwt),key,bank,input);}
 @PostMapping("/reversals") public ApprovalModels.Request reversal(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody FxTradeModels.Reversal input){return approvals.fxReversal(actor(jwt),key,bank,input);}
 @PostMapping("/trades/{id}/retry") public void retry(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){trades.retry(actor(jwt),bank,id);}
 @GetMapping public Map<String,Object> dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.dashboard(actor(jwt),bank,limit,offset);}
 @PostMapping("/rates") public ApprovalModels.Request rate(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody FxModels.Rate input){return approvals.fxRate(actor(jwt),key,bank,input);}
 @PostMapping("/withdrawals") public ApprovalModels.Request withdraw(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody FxModels.Withdraw input){return approvals.fxWithdraw(actor(jwt),key,bank,input);}
 @PostMapping("/quote") public FxModels.Quote quote(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody FxModels.QuoteInput input){return service.quote(actor(jwt),bank,input);}

 @GetMapping("/cross-rate")
 public FxModels.CrossRate crossRate(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam String base, @RequestParam String quote) {
  return service.deriveCrossRate(actor(jwt), bank, base, quote);
 }

 @PostMapping("/feeds")
 public FxRateFeedModels.FeedRecord importFeed(JwtAuthenticationToken jwt, @PathVariable UUID bank, @Valid @RequestBody FxRateFeedModels.FeedImport input) {
  return service.importFeed(actor(jwt), bank, input);
 }

 @GetMapping("/feeds")
 public List<FxRateFeedModels.FeedRecord> listFeeds(JwtAuthenticationToken jwt, @PathVariable UUID bank) {
  return service.listFeeds(actor(jwt), bank);
 }

 @PostMapping("/feeds/{id}/approve")
 public void approveFeed(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id) {
  service.approveFeed(actor(jwt), bank, id);
 }
}
