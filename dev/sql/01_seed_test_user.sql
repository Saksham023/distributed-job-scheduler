-- Local test user. The returned id is the userId to send in POST /jobs.
-- Safe to re-run: skips the insert if the email already exists.
INSERT INTO users (email, name)
VALUES ('test.user@example.com', 'Test User')
ON CONFLICT (email) DO NOTHING;

SELECT id, email, name FROM users WHERE email = 'test.user@example.com';
