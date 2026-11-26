-- Company profile of an issuer, and a verification flag only an admin sets.
-- Everything is optional, so existing companies stay valid.
ALTER TABLE issuers
    ADD COLUMN contact_name     text          CHECK (char_length(contact_name) <= 100),
    ADD COLUMN contact_role     text          CHECK (char_length(contact_role) <= 100),
    ADD COLUMN website_url      text          CHECK (char_length(website_url) <= 300 AND website_url ~ '^https?://'),
    ADD COLUMN company_summary  text          CHECK (char_length(company_summary) <= 2000),
    ADD COLUMN founded_year     integer       CHECK (founded_year BETWEEN 1800 AND 2100),
    ADD COLUMN team_size_band   text          CHECK (team_size_band IN ('1-10','11-50','51-200','201-1000','1000+')),
    ADD COLUMN total_raised_usd numeric(20,2) CHECK (total_raised_usd >= 0),
    ADD COLUMN runway_months    integer       CHECK (runway_months BETWEEN 0 AND 600),                 -- never shown to investors
    ADD COLUMN data_room_url    text          CHECK (char_length(data_room_url) <= 500 AND data_room_url ~ '^https?://'), -- nor this
    ADD COLUMN verified         boolean       NOT NULL DEFAULT false;

ALTER TABLE issuers ENABLE ROW LEVEL SECURITY;
