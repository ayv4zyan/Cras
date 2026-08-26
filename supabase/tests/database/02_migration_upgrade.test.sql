BEGIN;
SELECT plan(25);

-- 1. Verify schema tables present after full migration run
SELECT has_table('public', 'tasks', 'public.tasks table is present');
SELECT has_table('public', 'labels', 'public.labels table is present');
SELECT has_table('public', 'task_labels', 'public.task_labels table is present');
SELECT has_table('public', 'comments', 'public.comments table is present');
SELECT has_table('public', 'settings', 'public.settings table is present');
SELECT has_table('public', 'installations', 'public.installations table is present');
SELECT has_table('public', 'notification_jobs', 'public.notification_jobs table is present');
SELECT has_table('public', 'operator_account_state', 'public.operator_account_state table is present');
SELECT has_table('public', 'voice_reservations', 'public.voice_reservations table is present');
SELECT has_table('public', 'usage_security_records', 'public.usage_security_records table is present');
SELECT has_table('public', 'deployment_config', 'public.deployment_config table is present');
SELECT has_table('public', 'voice_model_catalog', 'public.voice_model_catalog table is present');

-- 2. Verify domain constraints and columns
SELECT col_type_is('public', 'tasks', 'version', 'integer', 'tasks.version is integer');
SELECT col_not_null('public', 'tasks', 'operator_id', 'tasks.operator_id is not null');
SELECT col_not_null('public', 'tasks', 'version', 'tasks.version is not null');

-- 3. Verify Deployment Config and Defaults
SELECT is(
  (SELECT count(*)::integer FROM public.deployment_config),
  1,
  'deployment_config singleton row is present'
);

SELECT is(
  (SELECT default_timed_plan_type FROM public.deployment_config LIMIT 1),
  'instant'::text,
  'deployment_config default_timed_plan_type is instant'
);

SELECT is(
  (SELECT voice_enabled FROM public.deployment_config LIMIT 1),
  true,
  'deployment_config voice_enabled is true by default'
);

-- 4. Test Idempotency and Data Preservation Invariants
INSERT INTO auth.users (id, email)
VALUES ('99999999-9999-9999-9999-999999999999'::uuid, 'upgrade.operator@example.com')
ON CONFLICT (id) DO NOTHING;

SET LOCAL "request.jwt.claims" = '{"sub": "99999999-9999-9999-9999-999999999999", "role": "authenticated"}';
SET LOCAL ROLE authenticated;

-- Test Idempotent Task Creation
SELECT lives_ok(
  $$ SELECT api.create_task('Upgrade Task Idempotent', '88888888-8888-8888-8888-888888888888'::uuid) $$,
  'api.create_task creates task on first call'
);

SELECT lives_ok(
  $$ SELECT api.create_task('Upgrade Task Idempotent', '88888888-8888-8888-8888-888888888888'::uuid) $$,
  'api.create_task is idempotent on replay with same id'
);

SELECT is(
  (SELECT count(*)::integer FROM api.tasks WHERE id = '88888888-8888-8888-8888-888888888888'::uuid),
  1,
  'idempotent replay preserves exactly one row for task id'
);

-- Verify preserved row properties
SELECT is(
  (SELECT version FROM api.tasks WHERE id = '88888888-8888-8888-8888-888888888888'::uuid),
  1,
  'created task has default version 1'
);

SELECT is(
  (SELECT priority FROM api.tasks WHERE id = '88888888-8888-8888-8888-888888888888'::uuid),
  4,
  'created task has default priority 4'
);

-- Complete task and verify version bump
SELECT lives_ok(
  $$ SELECT api.complete_task('88888888-8888-8888-8888-888888888888'::uuid, 1) $$,
  'api.complete_task completes task with matching version'
);

SELECT is(
  (SELECT version FROM api.tasks WHERE id = '88888888-8888-8888-8888-888888888888'::uuid),
  2,
  'completed task version incremented to 2'
);

SELECT * FROM finish();
ROLLBACK;
