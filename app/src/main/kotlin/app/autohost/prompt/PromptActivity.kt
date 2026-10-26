package app.autohost.prompt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P5-06: the WAITING_USER prompt dialog (plan §12.3). Launched from the prompt notification
 * (full-screen intent when possible); renders confirm/text/choice prompts, FLAG_SECURE per §14.4.
 * The answer resolves the blocked `ui.ask` broker call through [UiPromptProvider.resolve];
 * "Dismiss" answers answered=false so the script sees an honest refusal instead of a hang.
 */
class PromptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // §14.4: prompts may show sensitive text
        window.setGravity(Gravity.CENTER)

        val graph = (application as app.autohost.AutoHostApp).graph
        val provider = graph.uiPromptProvider
        val promptId = intent.getStringExtra(EXTRA_PROMPT_ID)
        val prompt = promptId?.let { provider.pendingOf(it) }
        if (promptId == null || prompt == null) {
            finish() // stale notification tap: the prompt is already gone
            return
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply {
            text = prompt.title
            textSize = 18f
            setPadding(0, 0, 0, pad / 2)
        })
        root.addView(TextView(this).apply { text = prompt.message })

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(container)

        fun submit(payload: kotlinx.serialization.json.JsonObject) {
            provider.resolve(prompt.promptId, payload)
            finish()
        }

        when (prompt.kind) {
            "confirm" -> {
                container.addView(button("Yes") {
                    submit(buildJsonObject { put("answered", true); put("confirmed", true) })
                })
                container.addView(button("No") {
                    submit(buildJsonObject { put("answered", true); put("confirmed", false) })
                })
            }
            "text" -> {
                val input = EditText(this).apply { hint = "Answer" }
                container.addView(input)
                container.addView(button("OK") {
                    submit(buildJsonObject { put("answered", true); put("text", input.text.toString()) })
                })
            }
            "dialog" -> for (label in prompt.buttons) {
                container.addView(button(label) {
                    submit(buildJsonObject { put("answered", true); put("button", label) })
                })
            }
            "form" -> {
                val inputs = mutableMapOf<String, EditText>()
                val toggles = mutableMapOf<String, android.widget.Switch>()
                val selects = mutableMapOf<String, android.widget.Spinner>()
                for (fieldElement in prompt.fields ?: kotlinx.serialization.json.JsonArray(emptyList())) {
                    val field = fieldElement as? kotlinx.serialization.json.JsonObject ?: continue
                    val name = (field["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                    val label = (field["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: name
                    val type = (field["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "text"
                    container.addView(TextView(this).apply { text = label })
                    when (type) {
                        "switch" -> {
                            val toggle = android.widget.Switch(this).apply {
                                isChecked = (field["default"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                            }
                            toggles[name] = toggle
                            container.addView(toggle)
                        }
                        "select" -> {
                            val options = (field["choices"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                ?: emptyList()
                            val spinner = android.widget.Spinner(this).apply {
                                adapter = android.widget.ArrayAdapter(
                                    this@PromptActivity,
                                    android.R.layout.simple_spinner_dropdown_item,
                                    options,
                                )
                            }
                            selects[name] = spinner
                            container.addView(spinner)
                        }
                        else -> {
                            val input = EditText(this).apply {
                                hint = label
                                setText((field["default"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                                if (type == "number") inputType = android.text.InputType.TYPE_CLASS_NUMBER
                                if ((field["x-secret"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
                                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                                }
                            }
                            inputs[name] = input
                            container.addView(input)
                        }
                    }
                }
                container.addView(button("Submit") {
                    val values = buildJsonObject {
                        for ((name, input) in inputs) put(name, input.text.toString())
                        for ((name, toggle) in toggles) put(name, toggle.isChecked)
                        for ((name, spinner) in selects) {
                            spinner.selectedItem?.let { put(name, it.toString()) }
                        }
                    }
                    submit(buildJsonObject { put("answered", true); put("values", values) })
                })
            }
            else -> for (choice in prompt.choices) {
                container.addView(button(choice) {
                    submit(buildJsonObject { put("answered", true); put("choice", choice) })
                })
            }
        }
        root.addView(Button(this).apply {
            text = "Dismiss"
            setOnClickListener {
                submit(buildJsonObject { put("answered", false); put("reason", "dismissed") })
            }
        })
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    companion object {
        const val EXTRA_PROMPT_ID = "prompt_id"

        fun intent(context: Context, promptId: String): Intent =
            Intent(context, PromptActivity::class.java)
                .putExtra(EXTRA_PROMPT_ID, promptId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
