package com.controlpad.app

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 여러 키를 골라 조합(예: Ctrl + Shift + S)을 만든다. */
fun pickKeys(a: android.app.Activity, current: List<Int>, done: (List<Int>) -> Unit) {
    val chosen = LinkedHashSet(current)
    val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
    for ((title, defs) in Keys.groups) {
        box.addView(TextView(a).apply { text = title; setPadding(0, a.dp(10), 0, a.dp(4)) })
        val grid = FlowLayout(a)
        for (k in defs) {
            val cell = TextView(a).apply {
                text = k.name; gravity = Gravity.CENTER; textSize = 15f
                setTextColor(Color.WHITE)
            }
            fun paint() {
                cell.background = GradientDrawable().apply {
                    cornerRadius = a.dp(6).toFloat()
                    setColor(if (k.usage in chosen) 0xFF0288D1.toInt() else 0xFF30343C.toInt())
                }
            }
            paint()
            cell.setOnClickListener {
                if (!chosen.remove(k.usage)) chosen += k.usage
                paint()
            }
            grid.addView(cell, android.view.ViewGroup.LayoutParams(a.dp(68), a.dp(44)))
        }
        box.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }
    AlertDialog.Builder(a).setTitle("키 선택 (여러 개를 고르면 동시에 입력)")
        .setView(ScrollView(a).apply { addView(box) })
        .setPositiveButton("확인") { _, _ -> done(Keys.ordered(chosen)) }
        .setNeutralButton("모두 해제") { _, _ -> done(emptyList()) }
        .setNegativeButton("취소", null)
        .show().wide()
}
