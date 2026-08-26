BEGIN;
SELECT plan(39);

-- 1. Check Schemas
SELECT has_schema('api', 'api schema exists');
SELECT has_schema('public', 'public schema exists');

-- 2. Check RLS is enabled on all tables
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'tasks'), 'RLS is enabled on public.tasks');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'labels'), 'RLS is enabled on public.labels');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'task_labels'), 'RLS is enabled on public.task_labels');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'comments'), 'RLS is enabled on public.comments');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'settings'), 'RLS is enabled on public.settings');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'installations'), 'RLS is enabled on public.installations');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'notification_jobs'), 'RLS is enabled on public.notification_jobs');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'operator_account_state'), 'RLS is enabled on public.operator_account_state');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'voice_reservations'), 'RLS is enabled on public.voice_reservations');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'deployment_config'), 'RLS is enabled on public.deployment_config');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE relname = 'voice_model_catalog'), 'RLS is enabled on public.voice_model_catalog');

-- 3. Check foreign key constraints
SELECT has_fk('public', 'tasks', 'tasks foreign key exists');
SELECT has_fk('public', 'labels', 'labels foreign key exists');
SELECT has_fk('public', 'comments', 'comments foreign key exists');
SELECT has_fk('public', 'task_labels', 'task_labels foreign key exists');
SELECT has_fk('public', 'settings', 'settings foreign key exists');
SELECT has_fk('public', 'installations', 'installations foreign key exists');

-- 4. Check RPC security functions exist in api schema
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

SELECT * FROM finish();
ROLLBACK;
