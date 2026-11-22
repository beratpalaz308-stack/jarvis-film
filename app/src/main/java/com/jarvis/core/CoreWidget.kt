package com.jarvis.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class CoreWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    companion object {
        fun views(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_core)
            rv.setImageViewBitmap(R.id.core_img, CoreDraw.bitmap(ctx, 300))
            val i = Intent(ctx, JarvisService::class.java).setAction("listen_now")
            val pi = PendingIntent.getForegroundService(
                ctx, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.core_img, pi)
            return rv
        }
    }
}
