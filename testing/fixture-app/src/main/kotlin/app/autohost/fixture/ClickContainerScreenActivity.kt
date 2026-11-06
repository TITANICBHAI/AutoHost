package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout

/**
 * ClickContainerScreen (docs/FIXTURE_APP.md v4, A5): a large clickable container (`fx_container`)
 * whose actionable child (`fx_btn_child`) sits OFF-CENTRE. The container's centre is a dead zone;
 * the counters prove which target each tap really hit — a coordinate click on the container
 * centre must NOT increment `fx_btn_child`, and the hardened hotspot promotion must hit the child.
 */
class ClickContainerScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_click_container)
        Screens.bind(this, "container")

        findViewById<FrameLayout>(R.id.fx_container).setOnClickListener {
            FixtureState.recordClick("fx_container")
            FixtureState.lastEvent = "click:fx_container"
            FixtureState.persist(this)
            Screens.refresh(this)
        }
        findViewById<Button>(R.id.fx_btn_child).setOnClickListener {
            FixtureState.recordClick("fx_btn_child")
            FixtureState.lastEvent = "click:fx_btn_child"
            FixtureState.persist(this)
            Screens.refresh(this)
        }
        Screens.refresh(this)
    }
}
