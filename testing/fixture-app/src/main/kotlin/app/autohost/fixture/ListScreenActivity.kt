package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

/** ListScreen: 200 rows, target "Item 150", `fx_list` — scroll_until and long-tree tests. */
class ListScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        FixtureState.load(this)
        FixtureState.screen = "list"

        val list = findViewById<ListView>(R.id.fx_list)
        list.adapter = ItemsAdapter()
        list.setOnItemClickListener { _, view, _, _ ->
            FixtureState.lastEvent = "item:" + (view as TextView).text
            updateState()
        }
        updateState()
    }

    private inner class ItemsAdapter : BaseAdapter() {
        override fun getCount(): Int = 200
        override fun getItem(position: Int): Any = "Item $position"
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_1, parent, false)
            (view as TextView).text = "Item $position"
            view.id = R.id.fx_item_text
            return view
        }
    }

    private fun updateState() {
        findViewById<TextView>(R.id.fx_state).text = FixtureState.toJson().toString()
    }
}
