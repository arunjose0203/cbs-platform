CREATE TABLE platform.tenant (
    id UUID PRIMARY KEY,
    bank_code VARCHAR(32) NOT NULL UNIQUE CHECK (bank_code ~ '^[A-Z][A-Z0-9_]{2,31}$'),
    display_name VARCHAR(200) NOT NULL,
    default_language VARCHAR(35) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('REQUESTED','PROVISIONING','ACTIVE','SUSPENDED','PROVISIONING_FAILED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE platform.legal_entity (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    legal_name VARCHAR(200) NOT NULL,
    country CHAR(2) NOT NULL,
    functional_currency CHAR(3) NOT NULL,
    business_timezone VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX legal_entity_tenant_idx ON platform.legal_entity(tenant_id);
CREATE TABLE platform.database_binding (
    tenant_id UUID PRIMARY KEY REFERENCES platform.tenant(id),
    database_reference VARCHAR(64) NOT NULL UNIQUE,
    schema_version VARCHAR(32),
    failure_code VARCHAR(64),
    attempt_id UUID,
    lease_until TIMESTAMPTZ
);
CREATE TABLE platform.registration_request (
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id) DEFERRABLE INITIALLY DEFERRED,
    PRIMARY KEY (actor_issuer, actor_subject, idempotency_key)
);
CREATE TABLE platform.tenant_membership (
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('BANK_OPERATOR','BANK_AUDITOR')),
    active BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, actor_issuer, actor_subject)
);
CREATE TABLE platform.tenant_audit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    action VARCHAR(40) NOT NULL,
    tenant_version BIGINT NOT NULL,
    previous_status VARCHAR(24),
    new_status VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    target_subject VARCHAR(200),
    target_role VARCHAR(24),
    change_details JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX tenant_audit_tenant_idx ON platform.tenant_audit(tenant_id, occurred_at, id);
CREATE FUNCTION platform.reject_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Tenant audit records are append-only';
END;
$$;
CREATE TRIGGER tenant_audit_immutable BEFORE UPDATE OR DELETE ON platform.tenant_audit
FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
