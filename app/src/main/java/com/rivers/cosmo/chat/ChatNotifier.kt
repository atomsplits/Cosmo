package com.rivers.cosmo.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rivers.cosmo.Main
import com.rivers.cosmo.R

/**
 * Shows a notification for new chat messages, one per conversation. Tapping it opens that
 * conversation. Messages only arrive while the app is running (in the foreground or background).
 */
class ChatNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Chat messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in Cosmo chat"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /** Whether notifications can be shown at all (Android 13+ asks the user first). */
    fun permitted(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Shows (or updates) [message]'s conversation notification. [unread] counts its unread messages. */
    fun show(message: ChatService.ChatMessage, unread: Int, showPreview: Boolean) {
        if (!permitted()) return
        val contactId = message.from
        val open = PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            openChatIntent(context, contactId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = if (unread > 1) "$unread new messages" else "New message"
        val text = if (showPreview) message.text else summary
        // What the lock screen shows when it hides private content.
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Cosmo chat")
            .setContentText(summary)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contactId)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(if (showPreview && unread > 1) summary else null)
            .setNumber(unread)
            .setWhen(message.sentAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(TAG, contactId.hashCode(), notification)
        } catch (e: SecurityException) {
            // The permission was revoked between the check and now.
        }
    }

    fun cancel(contactId: String) = manager.cancel(TAG, contactId.hashCode())

    /** Removes every chat notification, e.g. when logging out. */
    fun cancelAll() = manager.cancelAll()

    companion object {
        private const val CHANNEL_ID = "chat_messages"
        private const val TAG = "chat"
        const val ACTION_OPEN_CHAT = "com.rivers.cosmo.OPEN_CHAT"
        const val EXTRA_CONTACT_ID = "contact_id"

        fun openChatIntent(context: Context, contactId: String): Intent =
            Intent(context, Main::class.java)
                .setAction(ACTION_OPEN_CHAT)
                .putExtra(EXTRA_CONTACT_ID, contactId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
