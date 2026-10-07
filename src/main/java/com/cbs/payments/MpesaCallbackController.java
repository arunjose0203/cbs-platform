package com.cbs.payments;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class MpesaCallbackController {
 private final PaymentService service;
 public MpesaCallbackController(PaymentService service){this.service=service;}
 @PostMapping("/api/v1/payments/mpesa/callback/{id}/{token}")
 public Map<String,Object> callback(@PathVariable UUID id,@PathVariable String token,@RequestBody String body){service.callback(id,token,body);return Map.of("ResultCode",0,"ResultDesc","Observation recorded");}
}
