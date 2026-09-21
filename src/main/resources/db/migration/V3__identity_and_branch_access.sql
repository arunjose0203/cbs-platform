CREATE TABLE platform.staff_identity (
    id UUID PRIMARY KEY,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    email VARCHAR(254),
    active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    revoked_before TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(issuer,subject)
);
-- Preserve identities already used by Module 01. Existing bank grants remain valid.
INSERT INTO platform.staff_identity(id,issuer,subject,display_name)
SELECT gen_random_uuid(),issuer,subject,subject FROM (
    SELECT actor_issuer AS issuer,actor_subject AS subject FROM platform.tenant_membership
    UNION SELECT actor_issuer,actor_subject FROM platform.tenant_audit
) known;
CREATE TABLE platform.access_session (
    id UUID PRIMARY KEY,
    staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),
    sid_hash CHAR(64) NOT NULL,
    authenticated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    UNIQUE(staff_id,sid_hash)
);
CREATE INDEX access_session_staff_idx ON platform.access_session(staff_id,last_seen_at);
CREATE TABLE platform.identity_audit (
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),
    action VARCHAR(40) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    details JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER identity_audit_immutable BEFORE UPDATE OR DELETE ON platform.identity_audit
FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_change();
CREATE INDEX identity_audit_time_idx ON platform.identity_audit(occurred_at,id);
ALTER TABLE platform.legal_entity ADD CONSTRAINT legal_entity_tenant_id_unique UNIQUE(tenant_id,id);
CREATE TABLE platform.branch (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES platform.tenant(id),
    legal_entity_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL CHECK(code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    display_name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(tenant_id,id),
    UNIQUE(tenant_id,code),
    FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES platform.legal_entity(tenant_id,id)
);
CREATE TABLE platform.branch_membership (
    tenant_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    staff_id UUID NOT NULL REFERENCES platform.staff_identity(id),
    role VARCHAR(24) NOT NULL CHECK(role IN ('TELLER','BRANCH_MANAGER','AUDITOR')),
    active BOOLEAN NOT NULL,
    PRIMARY KEY(branch_id,staff_id),
    FOREIGN KEY(tenant_id,branch_id) REFERENCES platform.branch(tenant_id,id)
);
