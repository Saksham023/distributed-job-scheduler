-- 1. Each template version declares the params a job must supply (JSON Schema)
ALTER TABLE templates ADD COLUMN params_schema JSONB;

-- 2. At most one active template per task type, and unique version numbers
DROP INDEX idx_templates_active_lookup;
CREATE UNIQUE INDEX uq_templates_one_active_per_task_type
    ON templates (task_type_id)
    WHERE is_active;
ALTER TABLE templates ADD CONSTRAINT uq_templates_task_type_version
    UNIQUE (task_type_id, version);

-- 3. welcome_email v1 (dollar-quoting avoids escaping quotes inside the text)
INSERT INTO templates (task_type_id, version, is_active, subject, body, params_schema)
SELECT id,
       1,
       true,
       'Welcome, {{first_name}}!',
       $body$<!DOCTYPE html>
        <html>
        <body style="font-family: Arial, sans-serif; color: #222222;">
        <h1>Welcome, {{first_name}}!</h1>
        <p>Thanks for signing up{{#company_name}} with {{company_name}}{{/company_name}}.</p>
        <p>We're glad to have you on board.</p>
  </body>
</html>$body$,
       $schema${
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["to", "first_name"],
  "properties": {
    "to":           { "type": "string", "format": "email" },
    "first_name":   { "type": "string", "minLength": 1 },
    "company_name": { "type": "string", "minLength": 1 }
  }
}$schema$::jsonb
FROM task_types
WHERE name = 'welcome_email';

-- Every template must declare its params from now on
ALTER TABLE templates ALTER COLUMN params_schema SET NOT NULL;

-- 4. Pin the template version on each job: expand -> backfill -> contract
ALTER TABLE jobs ADD COLUMN template_id INTEGER REFERENCES templates (id);

UPDATE jobs j
SET template_id = t.id
FROM templates t
WHERE t.task_type_id = j.task_type_id
  AND t.is_active;

ALTER TABLE jobs ALTER COLUMN template_id SET NOT NULL;