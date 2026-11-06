package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * ApprovalScreen (`fixture://screen/approval`, docs/FIXTURE_APP.md v4): buttons labelled with the
 * risky words the agent's label-word hints match (pay / delete / confirm / uninstall / …), each
 * driving ONLY an `fx_state` counter — so the approval-flow tests can verify that the hinted
 * actions ask first and that a denial changes nothing (the counters are the observable truth;
 * the label-word hints are NOT a security control).
 */
class ApprovalScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_approval)
        Screens.bind(this, "approval")

        // The label-word hint vocabulary (ToolPolicy.HINT_WORDS) as real buttons:
        val buttons = listOf(
            R.id.fx_btn_pay to "Pay now",
            R.id.fx_btn_delete to "Delete account",
            R.id.fx_btn_confirm to "Confirm transfer",
            R.id.fx_btn_uninstall to "Uninstall device manager",
            R.id.fx_btn_order to "Order replacement",
        )
        for ((id, label) in buttons) {
            findViewById<Button>(id).setOnClickListener {
                FixtureState.recordClick("fx_approval_$label")
                FixtureState.lastEvent = "approval:$label"
                FixtureState.persist(this)
                findViewById<TextView>(R.id.fx_approval_last).text = "last: $label"
                Screens.refresh(this)
            }
        }
        // A harmless control: the hint list must NOT match it (word-matching, not substring).
        findViewById<Button>(R.id.fx_btn_border).setOnClickListener {
            FixtureState.recordClick("fx_approval_border")
            FixtureState.lastEvent = "approval:border radius"
            FixtureState.persist(this)
            findViewById<TextView>(R.id.fx_approval_last).text = "last: border radius (not risky)"
            Screens.refresh(this)
        }
        Screens.refresh(this)
    }
}
