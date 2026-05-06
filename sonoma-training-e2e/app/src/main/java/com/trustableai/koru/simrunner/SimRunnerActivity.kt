package com.trustableai.koru.simrunner

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class SimRunnerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Koru Sonoma E2E Runner"
                textSize = 18f
                setPadding(32, 32, 32, 32)
            },
        )
    }
}
