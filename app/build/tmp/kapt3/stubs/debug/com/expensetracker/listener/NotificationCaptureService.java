package com.expensetracker.listener;

/**
 * Primary transaction-capture path (see REQUIREMENTS.md Architecture ยง1).
 *
 * Requires the user to grant "Notification access" — a special permission
 * separate from READ_SMS, requested via a dedicated onboarding screen that
 * deep-links to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.
 *
 * IMPORTANT: This receives ALL notifications from ALL apps, not just banks.
 * Filtering happens downstream in TransactionParser — this service does the
 * absolute minimum work needed to hand off to WorkManager, so it stays fast
 * and doesn't block the notification pipeline.
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\"\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010 \n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\u0018\u00002\u00020\u0001B\u0005\u00a2\u0006\u0002\u0010\u0002J\u0010\u0010\u0006\u001a\u00020\u00072\u0006\u0010\b\u001a\u00020\tH\u0016R\u0014\u0010\u0003\u001a\b\u0012\u0004\u0012\u00020\u00050\u0004X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006\n"}, d2 = {"Lcom/expensetracker/listener/NotificationCaptureService;", "Landroid/service/notification/NotificationListenerService;", "()V", "relevantPackagePrefixes", "", "", "onNotificationPosted", "", "sbn", "Landroid/service/notification/StatusBarNotification;", "app_debug"})
public final class NotificationCaptureService extends android.service.notification.NotificationListenerService {
    @org.jetbrains.annotations.NotNull()
    private final java.util.List<java.lang.String> relevantPackagePrefixes = null;
    
    public NotificationCaptureService() {
        super();
    }
    
    @java.lang.Override()
    public void onNotificationPosted(@org.jetbrains.annotations.NotNull()
    android.service.notification.StatusBarNotification sbn) {
    }
}