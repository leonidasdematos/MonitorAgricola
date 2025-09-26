package com.example.monitoragricola.jobs.routes

import android.content.Context

object RouteDisplayPrefs {
    private const val PREFS_NAME = "route_display"

    private fun key(jobId: Long) = "job_${jobId}_visible"

    fun isVisible(context: Context, jobId: Long): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(key(jobId), false)
    }

    fun setVisible(context: Context, jobId: Long, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(jobId), visible)
            .apply()
    }

    fun clear(context: Context, jobId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(jobId))
            .apply()
    }
}