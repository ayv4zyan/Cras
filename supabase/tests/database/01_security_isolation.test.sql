BEGIN;
SELECT plan(35);

-- 1. Unauthenticated / Anonymous caller checks
SELECT throws_ok(
  $$ SELECT * FROM public.tasks $$,
  'permission denied for table tasks',
  'Anonymous caller cannot select directly from public.tasks'
);

SELECT throws_ok(
  $$ SELECT * FROM public.comments $$,
  'permission denied for table comments',
  'Anonymous caller cannot select directly from public.comments'
);

SELECT throws_ok(
  $$ SELECT * FROM public.labels $$,
  'permission denied for table labels',
  'Anonymous caller cannot select directly from public.labels'
);

SELECT throws_ok(
  $$ SELECT * FROM public.operator_settings $$,
  'permission denied for table operator_settings',
  'Anonymous caller cannot select directly from public.operator_settings'
);

SELECT throws_ok(
  $$ SELECT * FROM public.notification_jobs $$,
  'permission denied for table notification_jobs',
  'Anonymous caller cannot select directly from public.notification_jobs'
);

SELECT throws_ok(
  $$ SELECT * FROM public.device_endpoints $$,
  'permission denied for table device_endpoints',
  'Anonymous caller cannot select directly from public.device_endpoints'
);

SELECT throws_ok(
  $$ SELECT * FROM public.web_push_subscriptions $$,
  'permission denied for table web_push_subscriptions',
  'Anonymous caller cannot select directly from public.web_push_subscriptions'
);

SELECT throws_ok(
  $$ SELECT * FROM public.account_deletions $$,
  'permission denied for table account_deletions',
  'Anonymous caller cannot select directly from public.account_deletions'
);

-- 2. Check API Schema Views exist and have security_invoker
SELECT has_view('api', 'tasks_view', 'api.tasks_view exists');
SELECT has_view('api', 'labels_view', 'api.labels_view exists');
SELECT has_view('api', 'comments_view', 'api.comments_view exists');
SELECT has_view('api', 'settings_view', 'api.settings_view exists');

-- 3. Check RPC security
SELECT has_function('api', 'create_task', 'api.create_task exists');
SELECT has_function('api', 'update_task_details', 'api.update_task_details exists');
SELECT has_function('api', 'complete_task', 'api.complete_task exists');
SELECT has_function('api', 'uncomplete_task', 'api.uncomplete_task exists');
SELECT has_function('api', 'delete_task', 'api.delete_task exists');
SELECT has_function('api', 'create_label', 'api.create_label exists');
SELECT has_function('api', 'update_label', 'api.update_label exists');
SELECT has_function('api', 'delete_label', 'api.delete_label exists');
SELECT has_function('api', 'assign_task_labels', 'api.assign_task_labels exists');
SELECT has_function('api', 'create_comment', 'api.create_comment exists');
SELECT has_function('api', 'delete_comment', 'api.delete_comment exists');
SELECT has_function('api', 'create_subtask', 'api.create_subtask exists');
SELECT has_function('api', 'export_operator_data', 'api.export_operator_data exists');
SELECT has_function('api', 'request_account_deletion', 'api.request_account_deletion exists');
SELECT has_function('api', 'recover_account', 'api.recover_account exists');

-- 4. Check composite foreign key constraints preventing cross-operator relationships
SELECT has_fk('public', 'tasks', 'tasks composite operator and parent foreign key exists');
SELECT has_fk('public', 'comments', 'comments composite operator and task foreign key exists');
SELECT has_fk('public', 'task_labels', 'task_labels composite operator and task/label foreign key exists');

-- 5. Check RLS is enabled on all tables
SELECT row_eq(
  $$ SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'tasks' $$,
  ROW(true, true),
  'RLS and Force RLS are enabled on public.tasks'
);

SELECT row_eq(
  $$ SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'comments' $$,
  ROW(true, true),
  'RLS and Force RLS are enabled on public.comments'
);

SELECT row_eq(
  $$ SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'labels' $$,
  ROW(true, true),
  'RLS and Force RLS are enabled on public.labels'
);

SELECT row_eq(
  $$ SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'task_labels' $$,
  ROW(true, true),
  'RLS and Force RLS are enabled on public.task_labels'
);

SELECT row_eq(
  $$ SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'operator_settings' $$,
  ROW(true, true),
  'RLS and Force RLS are enabled on public.operator_settings'
);

SELECT row_eq(
  $$ SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'notification_jobs' $$,
  ROW(true, true),
  'RLS and Force RLS are enabled on public.notification_jobs'
);

SELECT * FROM finish();
ROLLBACK;
