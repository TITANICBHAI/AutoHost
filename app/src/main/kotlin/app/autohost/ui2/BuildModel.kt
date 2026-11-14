package app.autohost.ui2

import app.autohost.Routes

/** One way to make something on the Build tab. [target] is null for a card that only explains. */
data class BuildItem(val id: String, val title: String, val line: String, val target: HubTarget?)

/**
 * The Build tab: everything that creates a task, in one place (owner feedback 2026-11-14). Pure and tested: the text tells
 * the truth about this build (Python or not) and the experimental tools only appear when Labs is on.
 */
object BuildModel {

    fun items(pythonInBuild: Boolean, labs: Boolean): List<BuildItem> = buildList {
        add(
            BuildItem(
                "macro", "Quick macro",
                "When something happens, run a few steps: open an app, tap, wait, send a notice. Pick from lists, no code.",
                HubTarget.Go(Routes.MACRO),
            ),
        )
        add(BuildItem("template", "From a template", "Start from a ready-made task and change what you need.", HubTarget.Go(Routes.TEMPLATES)))
        add(
            BuildItem(
                "file", "Add a task file",
                "Bring in a .zip task from your phone. It can be a macro, a flow or a Python script.",
                HubTarget.AddTask,
            ),
        )
        add(
            BuildItem(
                "python", "Python scripts",
                if (pythonInBuild) "This build runs Python tasks. Write the script anywhere, zip it with its task file and add it above. There is no script editor inside the app yet."
                else "This build has no Python. Macros, flows and JavaScript tasks still work.",
                null,
            ),
        )
        if (labs) {
            add(BuildItem("studio", "Task Studio (experimental)", "Edit the files of a task on this phone.", HubTarget.Go(Routes.STUDIO)))
        }
    }
}
