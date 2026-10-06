-- FairSplit Reset Script
TRUNCATE TABLE 
    expense_splits, 
    expenses, 
    settlements, 
    activity_logs, 
    epic_members, 
    epics, 
    idempotency_keys, 
    user_devices, 
    user_preferences, 
    refresh_sessions, 
    phone_otp_challenges, 
    auth_identities, 
    users 
CASCADE;
