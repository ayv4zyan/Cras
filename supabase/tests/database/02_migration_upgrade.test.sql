BEGIN;
SELECT plan(15);

-- 1. Verify schema tables present after full migration run
SELECT has_table('public', 'tasks', 'public.tasks table is present');
SELECT has_table('public', 'labels', 'public.labels table is present');
SELECT has_table('public', 'task_labels', 'public.task_labels table is present');
SELECT has_table('public', 'comments', 'public.comments table is present');
SELECT has_table('public', 'operator_settings', 'public.operator_settings table is present');
SELECT has_table('public', 'device_endpoints', 'public.device_endpoints table is present');
SELECT has_table('public', 'web_push_subscriptions', 'public.web_push_subscriptions table is present');
SELECT has_table('public', 'notification_jobs', 'public.notification_jobs table is present');
SELECT has_table('public', 'account_deletions', 'public.account_deletions table is present');
SELECT has_table('public', 'usage_security_records', 'public.usage_security_records table is present');

-- 2. Verify domain constraints and columns
SELECT col_type_is('public', 'tasks', 'version', 'integer', 'tasks.version is integer');
SELECT col_not_null('public', 'tasks', 'operator_id', 'tasks.operator_id is not null');
SELECT col_not_null('public', 'tasks', 'version', 'tasks.version is not null');

-- 3. Verify Deployment Config and Defaults
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

SELECT * FROM finish();
ROLLBACK;
