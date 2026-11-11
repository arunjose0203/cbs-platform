package com.cbs.foundation.security;

import com.cbs.tenancy.BankDatabaseProvisioner;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Reject development authentication and unverified database transport in production. */
@Component
@Profile("production")
public class ProductionGuard {
 public ProductionGuard(Environment env,BankDatabaseProvisioner.Settings banks){
  validate(env,banks);
 }
 static void validate(Environment env,BankDatabaseProvisioner.Settings banks){
  var problems=new ArrayList<String>();
  if(!env.getProperty("cbs.security.enabled",Boolean.class,false))problems.add("identity authentication must be enabled");
  if(env.getProperty("cbs.security.allow-local-http",Boolean.class,false))problems.add("local HTTP identity is forbidden");
  if(!env.getProperty("cbs.access.local-mfa-bypass-until","").isBlank())problems.add("MFA exceptions are forbidden");
  if(!env.getProperty("cbs.access.bootstrap-admin-subject","").isBlank())problems.add("automatic bootstrap administrator is forbidden");
  for(String key:List.of("cbs.security.issuer","cbs.security.jwk-set-uri","cbs.portal.redirect-uri"))if(!https(env.getProperty(key,"")))problems.add(key+" must use HTTPS without embedded credentials");
  if(env.getProperty("cbs.security.audience","").isBlank()||env.getProperty("cbs.portal.client-id","").isBlank())problems.add("identity audience and portal client ID are required");
  if(!verifiedDatabase(env.getProperty("spring.datasource.url","")))problems.add("control database requires sslmode=verify-full");
  String migration=env.getProperty("spring.flyway.url","");if(!migration.isBlank()&&!verifiedDatabase(migration))problems.add("migration database requires sslmode=verify-full");
  if(env.getProperty("spring.datasource.password","").isBlank())problems.add("database credential is required");
  if(!env.getProperty("spring.flyway.clean-disabled",Boolean.class,false))problems.add("Flyway clean must be disabled");
  if(!env.getProperty("spring.flyway.validate-on-migrate",Boolean.class,false))problems.add("migration validation must be enabled");
  for(var bank:banks.databases().entrySet())if(!verifiedDatabase(bank.getValue().url())||bank.getValue().password()==null||bank.getValue().password().isBlank())problems.add("bank database transport or credential is not production configured");
  if(!env.getProperty("cbs.rate-limit.enabled",Boolean.class,true))problems.add("request rate limiting must stay enabled");
  boolean loopback=Set.of("127.0.0.1","localhost","::1").contains(env.getProperty("server.address","127.0.0.1"));
  if(!loopback&&!"native".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy","")))problems.add("server.forward-headers-strategy=native is required behind the edge proxy so limits apply per client");
  if("test".equalsIgnoreCase(env.getProperty("cbs.notifications.sender","")))problems.add("the test notification sender is forbidden");
  if(env.getProperty("spring.flyway.enabled",Boolean.class,true))problems.add("runtime Flyway migrations must be disabled; use the migration job");
  if(env.getProperty("cbs.banking.migrations-enabled",Boolean.class,true))problems.add("runtime bank migrations must be disabled");
  if(!migration.isBlank()||!env.getProperty("spring.flyway.user","").isBlank()||!env.getProperty("spring.flyway.password","").isBlank())problems.add("migration credentials must not be mounted in the runtime");
  if(!problems.isEmpty())throw new IllegalStateException("Production configuration rejected: "+String.join("; ",problems));
 }
 private static boolean https(String value){try{var uri=URI.create(value);return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null&&!Set.of("localhost","127.0.0.1","::1").contains(uri.getHost());}catch(RuntimeException e){return false;}}
 private static boolean verifiedDatabase(String value){
  return value!=null&&value.matches("jdbc:postgresql://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/[a-zA-Z0-9_]+\\?sslmode=verify-full(?:&channelBinding=require)?");
 }
}
