-- Institutional investor profiles. The old "type" column becomes investor_type with the wider list.
ALTER TABLE investors RENAME COLUMN type TO investor_type;
ALTER TABLE investors DROP CONSTRAINT investors_type_check;
UPDATE investors SET investor_type = CASE investor_type WHEN 'BANK' THEN 'PRIVATE_CREDIT' WHEN 'ANGEL' THEN 'ANGEL_SYNDICATE'
                                                        ELSE investor_type END;
ALTER TABLE investors ADD CONSTRAINT investors_type_check CHECK (investor_type IN
    ('VC','PE','PRIVATE_CREDIT','FAMILY_OFFICE','CORPORATE_VC','ANGEL_SYNDICATE'));

ALTER TABLE investors
    ADD COLUMN contact_name            text          CHECK (char_length(contact_name) <= 100),
    ADD COLUMN role_title              text          CHECK (char_length(role_title) <= 100),
    ADD COLUMN company_name            text          CHECK (char_length(company_name) <= 200),
    ADD COLUMN website_url             text          CHECK (char_length(website_url) <= 300 AND website_url ~ '^https?://'),
    ADD COLUMN aum_or_revenue_usd      numeric(20,2) CHECK (aum_or_revenue_usd >= 0),
    ADD COLUMN deployable_capital_usd  numeric(20,2) CHECK (deployable_capital_usd > 0),
    ADD COLUMN currency                text          NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'), -- mandate tickets are in this
    ADD COLUMN long_thesis_description text          CHECK (char_length(long_thesis_description) <= 10000),  -- untrusted text
    ADD COLUMN profile_completed       boolean       NOT NULL DEFAULT false;

ALTER TABLE investors ENABLE ROW LEVEL SECURITY;
