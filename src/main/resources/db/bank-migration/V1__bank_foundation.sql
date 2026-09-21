-- Identity is reserved by the provisioning adapter before this migration runs.
CREATE TABLE bank.installation (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO bank.installation(singleton) VALUES (true);
