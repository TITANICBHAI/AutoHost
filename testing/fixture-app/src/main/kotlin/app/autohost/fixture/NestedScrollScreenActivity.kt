package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView

/**
 * NestedScrollScreen (docs/FIXTURE_APP.md v4, A4): a vertical list (`fx_list`, 60 items) living
 * INSIDE a horizontal pager (`fx_pager`) on the second page. A scroll must go to the LIST — inside
 * its bounds, accessibility-first — not to the screen centre or the pager.
 */
class NestedScrollScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nested_scroll)
        Screens.bind(this, "nested")

        val list = findViewById<ListView>(R.id.fx_list)
        val items = (1..60).map { if (it == 60) "TARGET-60" else "fx_item_$it" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        list.setOnItemClickListener { _, _, position, _ ->
            FixtureState.recordClick("fx_row_${position + 1}")
            FixtureState.lastEvent = "click:fx_row_${position + 1}"
            FixtureState.persist(this)
            Screens.refresh(this)
        }
        (findViewById<TextView>(R.id.fx_pager_status)).text = "page 2: the list lives here"
        Screens.refresh(this)
    }
}
