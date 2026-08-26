BEGIN;
SELECT plan(48);

-- 1. Check Schemas
SELECT has_schema('api', 'api schema exists');
SELECT has_schema('public', 'public schema exists');

-- 2. Check RLS is enabled on all tables
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tasks'), 'RLS is enabled on public.tasks');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'labels'), 'RLS is enabled on public.labels');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'task_labels'), 'RLS is enabled on public.task_labels');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'comments'), 'RLS is enabled on public.comments');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'settings'), 'RLS is enabled on public.settings');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'installations'), 'RLS is enabled on public.installations');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'notification_jobs'), 'RLS is enabled on public.notification_jobs');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'operator_account_state'), 'RLS is enabled on public.operator_account_state');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'voice_reservations'), 'RLS is enabled on public.voice_reservations');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'deployment_config'), 'RLS is enabled on public.deployment_config');
SELECT ok((SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'voice_model_catalog'), 'RLS is enabled on public.voice_model_catalog');

-- 3. Check Views
SELECT has_view('api', 'tasks', 'api.tasks view exists');
SELECT has_view('api', 'comments', 'api.comments view exists');

-- 4. Check foreign key constraints
SELECT fk_ok(
    'public', 'tasks', ARRAY['parent_id', 'operator_id'],
    'public', 'tasks', ARRAY['id', 'operator_id'],
    'fk_tasks_parent composite foreign key references public.tasks(id, operator_id)'
);
SELECT has_fk('public', 'labels', 'labels foreign key exists');
SELECT has_fk('public', 'comments', 'comments foreign key exists');
SELECT has_fk('public', 'task_labels', 'task_labels foreign key exists');
SELECT has_fk('public', 'settings', 'settings foreign key exists');
SELECT has_fk('public', 'installations', 'installations foreign key exists');

-- 5. Check RPC security functions exist in api schema
SELECT has_function('api', 'create_task', 'api.create_task exists');
SELECT has_function('api', 'update_task', 'api.update_task exists');
SELECT has_function('api', 'complete_task', 'api.complete_task exists');
SELECT has_function('api', 'uncomplete_task', 'api.uncomplete_task exists');
SELECT has_function('api', 'create_comment', 'api.create_comment exists');
SELECT has_function('api', 'register_or_update_installation', 'api.register_or_update_installation exists');
SELECT has_function('api', 'deactivate_installation', 'api.deactivate_installation exists');
SELECT has_function('api', 'lease_due_notification_jobs', 'api.lease_due_notification_jobs exists');
SELECT has_function('api', 'record_notification_result', 'api.record_notification_result exists');
SELECT has_function('api', 'reserve_voice_allowance', 'api.reserve_voice_allowance exists');
SELECT has_function('api', 'reconcile_voice_usage', 'api.reconcile_voice_usage exists');
SELECT has_function('api', 'enter_pending_deletion', 'api.enter_pending_deletion exists');
SELECT has_function('api', 'recover_account', 'api.recover_account exists');
SELECT has_function('api', 'revoke_operator_sessions', 'api.revoke_operator_sessions exists');
SELECT has_function('api', 'operator_is_pending_deletion', 'api.operator_is_pending_deletion exists');
SELECT has_function('api', 'claim_due_purge_batch', 'api.claim_due_purge_batch exists');
SELECT has_function('api', 'finalize_operator_purge', 'api.finalize_operator_purge exists');
SELECT has_function('api', 'export_operator_data', 'api.export_operator_data exists');
SELECT has_function('api', 'get_lifecycle_status', 'api.get_lifecycle_status exists');
SELECT has_function('api', 'assert_active_session', 'api.assert_active_session exists');

-- 6. Multi-Operator and Unauthenticated Caller Isolation
INSERT INTO auth.users (id, email) VALUES 
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'operator_a@example.com'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'operator_b@example.com')
ON CONFLICT (id) DO NOTHING;

-- Act as Operator A
SET LOCAL "request.jwt.claims" = '{"sub": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "role": "authenticated"}';
SET LOCAL ROLE authenticated;

SELECT lives_ok(
  $$ SELECT api.create_task('Operator A Task', '11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid) $$,
  'Operator A can create task'
);

SELECT results_eq(
  $$ SELECT title FROM api.tasks WHERE id = '11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid $$,
  $$ VALUES ('Operator A Task'::text) $$,
  'Operator A can read own task via api.tasks'
);

-- Act as Operator B
SET LOCAL "request.jwt.claims" = '{"sub": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "role": "authenticated"}';
SET LOCAL ROLE authenticated;

SELECT lives_ok(
  $$ SELECT api.create_task('Operator B Task', '22222222-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid) $$,
  'Operator B can create task'
);

SELECT is_empty(
  $$ SELECT * FROM api.tasks WHERE id = '11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid $$,
  'Operator B cannot read Operator A task via api.tasks view'
);

SELECT is_empty(
  $$ SELECT * FROM public.tasks WHERE id = '11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid $$,
  'Operator B cannot read Operator A task via public.tasks table'
);

SELECT throws_ok(
  $$ SELECT api.create_comment('11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'Unauthorized cross-operator comment') $$,
  'Task not found or unauthorized',
  'Operator B cannot comment on Operator A task'
);

-- Act as Anonymous
SET LOCAL "request.jwt.claims" = '{"role": "anon"}';
SET LOCAL ROLE anon;

SELECT is_empty(
  $$ SELECT * FROM public.tasks $$,
  'Anonymous caller cannot read any tasks from public.tasks'
);

SELECT * FROM finish();
ROLLBACK;
