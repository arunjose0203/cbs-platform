ALTER TABLE platform.approval_request DROP CONSTRAINT approval_request_action_check;
ALTER TABLE platform.approval_request ADD CONSTRAINT approval_request_action_check
    CHECK (action IN ('BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS'));
ALTER TABLE platform.staff_identity ADD COLUMN preferred_language VARCHAR(35);

CREATE TABLE platform.bank_settings (
    tenant_id UUID PRIMARY KEY REFERENCES platform.tenant(id),
    currencies JSONB NOT NULL CHECK (jsonb_typeof(currencies)='array'),
    languages JSONB NOT NULL CHECK (jsonb_typeof(languages)='array'),
    business_timezone VARCHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    weekend_days JSONB NOT NULL CHECK (jsonb_typeof(weekend_days)='array'),
    holidays JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(holidays)='array'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Invoker privileges only. Used for migration backfill and new tenant registration.
CREATE FUNCTION platform.initialize_bank_settings(bank UUID) RETURNS void LANGUAGE sql AS $$
    INSERT INTO platform.bank_settings(tenant_id,currencies,languages,business_timezone,business_date,weekend_days)
    SELECT t.id,
        (SELECT jsonb_agg(jsonb_build_object('code',c.code,'roundingMode','HALF_EVEN','enabled',true) ORDER BY c.code)
         FROM (SELECT DISTINCT functional_currency AS code FROM platform.legal_entity WHERE tenant_id=t.id) c),
        jsonb_build_array(t.default_language),e.business_timezone,
        (SELECT min(d::date) FROM generate_series(
            (CURRENT_TIMESTAMP AT TIME ZONE e.business_timezone)::date,
            (CURRENT_TIMESTAMP AT TIME ZONE e.business_timezone)::date+6,interval '1 day') d
         WHERE extract(isodow FROM d)<6),'[6,7]'::jsonb
    FROM platform.tenant t
    JOIN LATERAL (SELECT business_timezone FROM platform.legal_entity WHERE tenant_id=t.id ORDER BY created_at,id LIMIT 1) e ON true
    WHERE t.id=bank ON CONFLICT (tenant_id) DO NOTHING;
$$;
SELECT platform.initialize_bank_settings(id) FROM platform.tenant;
