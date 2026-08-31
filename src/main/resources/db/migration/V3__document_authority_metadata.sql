ALTER TABLE documents ADD COLUMN IF NOT EXISTS organization TEXT;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS authority TEXT;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS document_type TEXT;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS is_current BOOLEAN;

CREATE INDEX IF NOT EXISTS idx_documents_authority_scope
    ON documents (organization, authority, is_current);

UPDATE documents
SET organization = CASE
        WHEN file_name LIKE 'TechCorp%' THEN 'TechCorp'
        WHEN file_name LIKE 'Acme%' THEN 'Acme'
        ELSE COALESCE(organization, 'Unknown')
    END,
    authority = CASE
        WHEN file_name = 'TechCorp_Handbook.docx' THEN 'AUTHORITATIVE'
        WHEN file_name LIKE '%Release_Announcement_2023%' THEN 'HISTORICAL'
        WHEN file_name LIKE '%Support_FAQ%' OR file_name LIKE '%Training_Notes%' THEN 'REFERENCE'
        WHEN file_name LIKE 'Acme%' THEN 'EXTERNAL'
        ELSE COALESCE(authority, 'REFERENCE')
    END,
    document_type = CASE
        WHEN file_name = 'TechCorp_Handbook.docx' THEN 'POLICY'
        WHEN file_name LIKE '%Support_FAQ%' THEN 'FAQ'
        WHEN file_name LIKE '%Release_Announcement%' THEN 'ANNOUNCEMENT'
        WHEN file_name LIKE '%Training_Notes%' THEN 'TRAINING'
        WHEN file_name LIKE 'Acme%' THEN 'POLICY'
        ELSE COALESCE(document_type, 'REFERENCE')
    END,
    is_current = CASE
        WHEN file_name = 'TechCorp_Handbook.docx' THEN TRUE
        ELSE COALESCE(is_current, FALSE)
    END;
