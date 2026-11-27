ALTER TABLE commitments
    ADD COLUMN offer_letter text CHECK (char_length(offer_letter) <= 5000);

ALTER TABLE deal_questions
    ADD COLUMN asked_by_name text CHECK (char_length(asked_by_name) <= 100),
    ADD COLUMN asked_by_email text CHECK (char_length(asked_by_email) <= 254);
