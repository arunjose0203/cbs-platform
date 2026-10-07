package com.cbs.payments;

import java.util.UUID;
import static com.cbs.payments.PaymentModels.*;

/** No live endpoint is exposed by this release. Callbacks remain unverified observations. */
public interface MpesaGateway {
 boolean configured(UUID bank);
 boolean binding(UUID bank,UUID ledger);
 String profile(UUID bank);
 Acceptance transfer(UUID bank,UUID payment,Order order,String callbackToken);
 Acceptance query(UUID bank,UUID payment,String originator,String callbackToken);
}
