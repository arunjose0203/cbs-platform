-- Hot-path query benchmark. Run with psql against a database holding realistic volumes:
--   psql -v tenant="'<bank uuid>'" -v account="'<account uuid>'" -v day="'2026-06-01'" -f ops/perf/hot_queries.sql
-- Each statement is the exact SQL issued by the application; compare plans before and after V44.
\echo '== daily limit consumption (account operations)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT coalesce(sum((payload->'change'->>'amount')::numeric),0) FROM platform.account_operation
 WHERE tenant_id=:tenant::uuid AND payload->'account'->'terms'->>'currency'='KES'
   AND payload->'change'->>'kind'='DEPOSIT' AND payload->'change'->>'businessDate'=:day;
\echo '== daily limit consumption (funding)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT coalesce(sum((payload->>'amount')::numeric),0) FROM platform.funding_delivery
 WHERE tenant_id=:tenant::uuid AND payload->'journal'->>'currency'='KES' AND payload->'journal'->>'businessDate'=:day;
\echo '== closure settlement guard'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT EXISTS(SELECT 1 FROM platform.account_operation WHERE account_id=:account::uuid
   AND payload->'change'->>'kind'='CLOSURE_SETTLEMENT');
\echo '== account operation history'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT request_id FROM platform.account_operation WHERE account_id=:account::uuid ORDER BY created_at DESC LIMIT 50;
