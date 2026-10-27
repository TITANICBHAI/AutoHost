package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

/** DynamicScreen (docs/FIXTURE_APP.md): content changes every `delay_ms`; settle and window-updating tests. */
class DynamicScreenActivity : Activity() {

    private var tick = 0
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick += 1
            FixtureState.lastEvent = "tick:$tick"
            findViewById<TextView>(R.id.fx_dynamic_tick).text = tick.toString()
            Screens.refresh(this@DynamicScreenActivity)
            handler.postDelayed(this, delayMs)
        }
    }

    private var delayMs: Long = 500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dynamic)
        Screens.bind(this, "dynamic")
        delayMs = Screens.delay(this, 500).toLong().coerceAtLeast(50L)
        findViewById<Button>(R.id.fx_dynamic_stop).setOnClickListener {
            handler.removeCallbacks(ticker)
            FixtureState.lastEvent = "dynamic:stopped"
            Screens.refresh(this)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, delayMs)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
