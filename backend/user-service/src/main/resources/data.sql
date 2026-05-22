-- Seed data for MVP demo tenants.
-- ON CONFLICT DO NOTHING makes this safe to re-run on every startup.
-- DO NOT use these tenantIds as defaults in application code.
-- All new call/event creation must explicitly set telephonyProvider from the tenant record.

INSERT INTO tenants (id, name, telephony_provider) VALUES
    ('tenant-twilio',      'Acme Corp (Twilio)',      'TWILIO'),
    ('tenant-freeswitch',  'Beta Corp (FreeSWITCH)',  'FREESWITCH')
ON CONFLICT (id) DO NOTHING;
