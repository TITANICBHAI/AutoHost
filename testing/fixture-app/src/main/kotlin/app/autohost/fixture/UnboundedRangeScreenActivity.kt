package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UnboundedRangeScreen (docs/FIXTURE_APP.md): an endless list — rows append as the user scrolls, so
 * `scroll_until` limits and very long trees have a deterministic target.
 */
class UnboundedRangeScreenActivity : Activity() {

    private var nextRow = 0
    private val container by lazy { findViewById<LinearLayout>(R.id.fx_unbounded_scroll) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unbounded)
        Screens.bind(this, "unbounded")
        addRows(30)
    }

    private fun addRows(count: Int) {
        repeat(count) {
            val row = Button(this).apply {
                id = View.generateViewId() // endless list: rows are not stable ids by design
                text = "Row ${nextRow++}"
                isAllCaps = false
            }
            container.addView(row)
        }
    }
}
