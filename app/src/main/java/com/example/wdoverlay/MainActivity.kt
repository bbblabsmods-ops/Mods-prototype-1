package com.example.wdoverlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView

    // The keep-alive reports reconnects here.
    private val messageListener: (String) -> Unit = { msg -> runOnUiThread { show(msg) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Mono.BLACK
        window.navigationBarColor = Mono.BLACK

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val gap = (3 * d).toInt()
        val prefs = getSharedPreferences("wd", MODE_PRIVATE)

        // ---------- small helpers ----------
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(Mono.WHITE); setPadding(0, pad / 2, 0, pad / 4)
        }
        fun
