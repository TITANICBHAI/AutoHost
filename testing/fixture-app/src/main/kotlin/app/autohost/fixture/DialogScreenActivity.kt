package app.autohost.fixture

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * DialogScreen (docs/FIXTURE_APP.md): a framework AlertDialog (OK/Cancel), a PopupWindow and a bottom-sheet
 * style panel — targets for the popup guard and RootResolver multi-window reads. Results land in the state
 * readout (`clicks.fx_dialog_ok` etc.).
 */
class DialogScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialog)
        Screens.bind(this, "dialog")

        findViewById<Button>(R.id.fx_btn_alert).setOnClickListener { showAlertDialog() }
        findViewById<Button>(R.id.fx_btn_popup).setOnClickListener { showPopup(it) }
        findViewById<Button>(R.id.fx_btn_sheet).setOnClickListener { showBottomSheet() }
    }

    private fun showAlertDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_alert, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Fixture dialog")
            .setView(view)
            .create()
        view.findViewById<Button>(R.id.fx_dialog_ok).setOnClickListener {
            FixtureState.recordClick("fx_dialog_ok")
            Screens.refresh(this)
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.fx_dialog_cancel).setOnClickListener {
            FixtureState.recordClick("fx_dialog_cancel")
            Screens.refresh(this)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showPopup(anchor: View) {
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val label = TextView(this).apply { text = "Popup window" }
        val button = Button(this).apply { text = "Popup OK"; id = R.id.fx_dialog_ok }
        view.addView(label)
        view.addView(button)
        val popup = PopupWindow(view, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.LTGRAY))
        button.setOnClickListener {
            FixtureState.recordClick("fx_popup_ok")
            Screens.refresh(this)
            popup.dismiss()
        }
        popup.showAsDropDown(anchor)
    }

    private fun showBottomSheet() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_alert, null) as LinearLayout
        view.setBackgroundColor(Color.WHITE)
        view.gravity = Gravity.CENTER
        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.fx_dialog_ok).setOnClickListener {
            FixtureState.recordClick("fx_sheet_ok")
            Screens.refresh(this)
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.fx_dialog_cancel).setOnClickListener {
            FixtureState.recordClick("fx_sheet_cancel")
            Screens.refresh(this)
            dialog.dismiss()
        }
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.show()
    }
}
