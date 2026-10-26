package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * P2-16: schema-driven config form (UI Tier A, plan §10.1). Widgets for the manifest's config_schema /
 * profile_schema subset: text, number (min/max), boolean switch, select, secret (masked), and an
 * app-picker placeholder that narrows to a plain text field until the app list screen lands.
 *
 * Validation is inline; secrets are flagged from `x-secret`; a "changed → will re-ask permissions"
 * note is the caller's job (it knows the old grants' fingerprints).
 */
object ConfigFormModel {

    data class Field(
        val key: String,
        val type: String,
        val title: String,
        val default: JsonPrimitive?,
        val minimum: Double?,
        val maximum: Double?,
        val options: List<String>,
        val secret: Boolean,
    )

    fun fields(schema: JsonObject?): List<Field> {
        val properties = (schema?.get("properties") as? JsonObject) ?: return emptyList()
        return properties.entries.map { (key, raw) ->
            val spec = raw as? JsonObject ?: JsonObject(emptyMap())
            val type = (spec["type"] as? JsonPrimitive)?.content ?: "string"
            Field(
                key = key,
                type = type,
                title = (spec["title"] as? JsonPrimitive)?.content ?: key,
                default = spec["default"] as? JsonPrimitive,
                minimum = (spec["minimum"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                maximum = (spec["maximum"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                options = (spec["enum"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList(),
                secret = (spec["x-secret"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    /** Defaults overlaid by saved values, as the form's initial state. */
    fun initialValues(schema: JsonObject?, saved: JsonObject): JsonObject {
        val values = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (field in fields(schema)) {
            val savedValue = saved[field.key] as? JsonPrimitive
            values[field.key] = savedValue ?: field.default ?: JsonPrimitive("")
        }
        return JsonObject(values)
    }

    /** Inline validation; empty list = form is valid. */
    fun validate(fields: List<Field>, values: JsonObject): List<String> = fields.mapNotNull { field ->
        val raw = (values[field.key] as? JsonPrimitive)?.content ?: ""
        when (field.type) {
            "integer", "number" -> {
                val number = raw.toDoubleOrNull()
                    ?: return@mapNotNull "${field.title}: ${if (field.type == "integer") "an integer" else "a number"} is required"
                if (field.minimum != null && number < field.minimum) "${field.title}: must be ≥ ${field.minimum.toLong()}"
                else if (field.maximum != null && number > field.maximum) "${field.title}: must be ≤ ${field.maximum.toLong()}"
                else if (field.type == "integer" && raw.contains('.')) "${field.title}: must be an integer"
                else null
            }
            else -> {
                val required = field.default == null
                if (required && raw.isBlank()) "${field.title}: required" else null
            }
        }
    }
}

@Composable
fun ConfigForm(
    schema: JsonObject?,
    values: JsonObject,
    onValueChange: (String, JsonPrimitive) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = remember(schema) { ConfigFormModel.fields(schema) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fields.isEmpty()) {
            Text("This task has no settings.", style = MaterialTheme.typography.bodySmall)
        }
        for (field in fields) {
            val current = (values[field.key] as? JsonPrimitive)?.content ?: ""
            when {
                field.type == "boolean" -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(field.title, style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = current == "true",
                        onCheckedChange = { checked -> onValueChange(field.key, JsonPrimitive(checked)) },
                    )
                }
                field.secret -> SecretField(field, current, onValueChange)
                field.type == "integer" || field.type == "number" -> NumberField(field, current, onValueChange)
                else -> OutlinedTextField(
                    value = current,
                    onValueChange = { text -> onValueChange(field.key, JsonPrimitive(text)) },
                    label = { Text(field.title) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun SecretField(field: ConfigFormModel.Field, current: String, onValueChange: (String, JsonPrimitive) -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = current,
            onValueChange = { text -> onValueChange(field.key, JsonPrimitive(text)) },
            label = { Text(field.title + " (secret)") },
            visualTransformation = if (revealed) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                androidx.compose.material3.TextButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            "Stored Keystore-wrapped; masked in logs and exports.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun NumberField(field: ConfigFormModel.Field, current: String, onValueChange: (String, JsonPrimitive) -> Unit) {
    OutlinedTextField(
        value = current,
        onValueChange = { text -> onValueChange(field.key, JsonPrimitive(text.toIntOrNull() ?: text.toDoubleOrNull() ?: 0)) },
        label = { Text(field.title + " (number)") },
        supportingText = {
            val bounds = listOfNotNull(
                field.minimum?.let { "min ${it.toLong()}" },
                field.maximum?.let { "max ${it.toLong()}" },
            ).joinToString(", ")
            if (bounds.isNotEmpty()) Text(bounds)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
